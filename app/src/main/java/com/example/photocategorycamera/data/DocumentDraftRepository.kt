package com.example.photocategorycamera.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.example.photocategorycamera.document.DocumentCapturePreview
import com.example.photocategorycamera.document.DocumentProcessor
import com.example.photocategorycamera.diagnostics.CaptureEventLog
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentQuad
import com.example.photocategorycamera.domain.StorageRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class DocumentReservation(val id: String, val file: File)

/** Durable automatic processing. The editable slot is only for unconfirmed 0.7.0 material. */
class DocumentDraftRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val processor: DocumentProcessor,
    private val captureTasks: CaptureTaskRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dao = database.documentDraftDao()
    private val tasks = database.captureTaskDao()
    private val directory = File(context.filesDir, "document-drafts").apply { mkdirs() }
    // Serialize large bitmap/native operations, while CameraX can accept the next source.
    private val processingMutex = Mutex()
    val editable = dao.observeEditable()
    private val initialization = scope.async { recoverInterrupted() }

    suspend fun reserve(category: Category, root: StorageRoot, effect: DocumentEffect = DocumentEffect.ORIGINAL): Result<DocumentReservation> =
        withContext(Dispatchers.IO) {
            runCatching {
                initialization.await()
                val source = File.createTempFile("document_", ".jpg", directory)
                try {
                    val id = UUID.randomUUID().toString()
                    captureTasks.reserveDocument(DocumentDraftEntity(
                        id, source.absolutePath, category.id, category.name, root.treeUri,
                        effectValue = effect.name, stage = "AUTO_CAPTURING", editingSlot = null,
                        createdAt = System.currentTimeMillis(),
                        groupId = category.groupId, groupName = category.groupName,
                    )).getOrThrow()
                    DocumentReservation(id, source)
                } catch (error: Exception) { source.delete(); throw error }
            }
        }

    /** Application scope: leaving the viewfinder never cancels an accepted JPEG. */
    fun completeCapture(id: String, boundary: DocumentQuad? = null, onComplete: (Result<DocumentCapturePreview?>) -> Unit = {}) {
        scope.launch {
            val result = runCatching {
                initialization.await()
                // Persist before entering the processing queue, so restart/retry retains this shot's box.
                boundary?.takeIf { it.isValid && it != DocumentQuad.FULL_IMAGE }?.let { dao.setCaptureBoundary(id, it.encode()) }
                processAutomatic(id, withPreview = true).getOrThrow()
            }
            withContext(Dispatchers.Main) { onComplete(result) }
        }
    }

    fun captureFailed(id: String) {
        // A CameraX callback error may still leave a complete JPEG; recover it if possible.
        scope.launch { initialization.await(); processAutomatic(id, withPreview = false) }
    }

    fun retryProcessing() {
        scope.launch {
            initialization.await()
            dao.all().filter { it.stage == "AUTO_RETRY" }.forEach { processAutomatic(it.id, withPreview = false) }
        }
    }

    private suspend fun processAutomatic(id: String, withPreview: Boolean): Result<DocumentCapturePreview?> = processingMutex.withLock {
        var preview: DocumentCapturePreview? = null
        runCatching {
            var draft = dao.find(id) ?: return@runCatching null // Already saved or explicitly discarded.
            if (draft.stage == "QUEUED" || draft.stage == "AUTO_FAILED") return@runCatching null
            check(draft.editingSlot == null && draft.stage.startsWith("AUTO_")) { "旧版草稿需要明确选择保存或丢弃" }
            val source = File(draft.sourcePath)
            if (!readable(source)) {
                markFailure(draft, recoverable = false, "文档照片未拍摄完整，材料已保留，请检查失败任务")
                error("文档照片未拍摄完整")
            }
            RandomAccessFile(source, "rw").use { it.fd.sync() }
            val quad = if (draft.stage == "AUTO_CAPTURING") {
                // Validate the frozen page against the exposure, without guessing a different page.
                processor.resolveCaptureQuad(source, draft.quad.takeUnless { it == DocumentQuad.FULL_IMAGE })
            } else draft.quad
            CaptureEventLog.record(context, "document_crop_selected", id, values = mapOf(
                "boundarySource" to when {
                    draft.stage != "AUTO_CAPTURING" -> "RECOVERED"
                    quad == DocumentQuad.FULL_IMAGE -> "FULL_IMAGE"
                    draft.quad != DocumentQuad.FULL_IMAGE -> "PREVIEW_VERIFIED"
                    else -> "PHOTO"
                },
                "area" to quad.area,
            ))
            draft = draft.copy(stage = "AUTO_PROCESSING", quadValue = quad.encode())
            database.withTransaction {
                dao.update(draft)
                tasks.updateProgress(id, CaptureTaskStage.DOCUMENT_PROCESSING.name, null, null, System.currentTimeMillis())
            }
            val output = File(directory, id + "_processed.jpg")
            processor.writeResult(source, draft.quad, draft.effect, draft.quarterTurns, output)
            // Read display copies before the saving queue can clean up files.
            // A preview failure must never block saving a valid output.
            if (withPreview) preview = runCatching { processor.capturePreview(id, source, output, draft.quad) }.getOrNull()
            captureTasks.enqueueDocument(id, output).getOrThrow()
            preview
        }.onFailure { error ->
            preview?.recycleUnpublished()
            dao.find(id)?.takeIf { it.stage != "QUEUED" && it.stage != "AUTO_FAILED" && it.editingSlot == null }?.let {
                markFailure(it, recoverable = true, error.message ?: "文档处理失败，可重试")
            }
        }
    }

    private suspend fun markFailure(draft: DocumentDraftEntity, recoverable: Boolean, message: String) {
        database.withTransaction {
            dao.update(draft.copy(stage = if (recoverable) "AUTO_RETRY" else "AUTO_FAILED"))
            tasks.updateProgress(draft.id,
                if (recoverable) CaptureTaskStage.DOCUMENT_RETRY.name else CaptureTaskStage.FAILED.name,
                null, message, System.currentTimeMillis())
        }
    }

    /** Explicit consent for an old unconfirmed draft; preserve its recipe and frozen target. */
    suspend fun submit(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        initialization.await()
        processingMutex.withLock {
            runCatching {
                val draft = dao.find(id) ?: return@runCatching
                if (draft.stage == "QUEUED") return@runCatching
                check(draft.stage == "EDITING" && draft.replacementPath == null) { "请等待旧版文档恢复完成" }
                val output = File(directory, id + "_processed.jpg")
                processor.writeResult(File(draft.sourcePath), draft.quad, draft.effect, draft.quarterTurns, output)
                captureTasks.enqueueDocument(id, output).getOrThrow()
            }
        }
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) {
        initialization.await()
        processingMutex.withLock {
            val draft = dao.find(id) ?: return@withLock
            check(draft.editingSlot == 1) { "自动保存的文档请在保存队列中处理" }
            dao.delete(id)
            deleteMaterials(draft)
        }
    }

    private suspend fun recoverInterrupted() {
        for (draft in dao.all()) {
            when {
                draft.stage == "QUEUED" && tasks.find(draft.id) == null -> {
                    deleteMaterials(draft)
                    dao.delete(draft.id)
                }
                draft.stage == "AUTO_CAPTURING" || draft.stage == "AUTO_PROCESSING" -> processAutomatic(draft.id, withPreview = false)
                draft.editingSlot == 1 && (draft.stage == "CAPTURING" || draft.replacementPath != null) -> {
                    // One-time 0.7.0 compatibility: never silently archive an unconfirmed image.
                    val source = File(draft.replacementPath ?: draft.sourcePath)
                    if (readable(source)) {
                        val quad = runCatching { processor.detect(source) }.getOrNull() ?: DocumentQuad.FULL_IMAGE
                        dao.update(draft.copy(sourcePath = source.absolutePath, replacementPath = null, stage = "EDITING",
                            quadValue = quad.encode(), effectValue = DocumentEffect.ORIGINAL.name, quarterTurns = 0))
                        if (draft.sourcePath != source.absolutePath) File(draft.sourcePath).delete()
                    } else {
                        dao.update(draft.copy(replacementPath = null, stage = "EDITING"))
                        if (draft.replacementPath != null) source.delete()
                    }
                }
            }
        }
        val referenced = dao.all().flatMap { listOfNotNull(it.sourcePath, it.replacementPath) }.toSet() +
            tasks.observeOutstanding().first().map { it.tempPath }
        directory.listFiles().orEmpty().filter { it.isFile && it.absolutePath !in referenced }.forEach { it.delete() }
    }

    private fun deleteMaterials(draft: DocumentDraftEntity) {
        File(draft.sourcePath).delete()
        draft.replacementPath?.let { File(it).delete() }
        File(directory, draft.id + "_processed.jpg").delete()
        File(directory, draft.id + "_processed.jpg.part").delete()
    }

    private fun readable(source: File): Boolean {
        if (!source.isFile || source.length() < 3) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != "image/jpeg") return false
        return runCatching {
            RandomAccessFile(source, "r").use {
                it.seek(it.length() - 2)
                it.readUnsignedByte() == 0xFF && it.readUnsignedByte() == 0xD9
            }
        }.getOrDefault(false)
    }
}
