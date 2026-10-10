package com.example.photocategorycamera.data

import android.content.Context
import android.graphics.BitmapFactory
import com.example.photocategorycamera.document.DocumentProcessor
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentQuad
import com.example.photocategorycamera.domain.StorageRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class DocumentReservation(val id: String, val file: File)

/** Unconfirmed material is deliberately separate from the automatic media-saving queue. */
class DocumentDraftRepository(
    context: Context,
    private val database: AppDatabase,
    private val processor: DocumentProcessor,
    private val captureTasks: CaptureTaskRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dao = database.documentDraftDao()
    private val directory = File(context.filesDir, "document-drafts").apply { mkdirs() }
    private val mutex = Mutex()
    val editable = dao.observeEditable()

    private val initialization = scope.async { recoverInterrupted() }

    suspend fun reserve(category: Category, root: StorageRoot, retakeId: String? = null): Result<DocumentReservation> =
        withContext(Dispatchers.IO) {
            runCatching {
                initialization.await()
                mutex.withLock {
                    val old = dao.editable()
                    check(old == null || old.id == retakeId) { "已有待确认文档，请先继续编辑、保存或丢弃" }
                    check(old == null || old.replacementPath == null) { "上一张文档仍在处理中" }
                    check(database.captureTaskDao().countOutstanding() < CaptureTaskRepository.MAX_OUTSTANDING) { "保存队列已满，请稍后拍摄" }
                    val file = File.createTempFile("document_", ".jpg", directory)
                    try {
                        val id = old?.id ?: UUID.randomUUID().toString()
                        if (old != null) {
                            // A retake keeps the previous complete source until its replacement is durable.
                            dao.update(old.copy(replacementPath = file.absolutePath))
                        } else {
                            dao.insert(DocumentDraftEntity(id, file.absolutePath, category.id, category.name, root.treeUri,
                                createdAt = System.currentTimeMillis()))
                        }
                        DocumentReservation(id, file)
                    } catch (error: Exception) { file.delete(); throw error }
                }
            }
        }

    /** Own application scope so leaving the camera cannot cancel persistence of an accepted JPEG. */
    fun completeCapture(id: String, onComplete: (Result<DocumentDraftEntity>) -> Unit) {
        scope.launch {
            val result = runCatching { initialization.await(); finishCapture(id).getOrThrow() }
            withContext(Dispatchers.Main) { onComplete(result) }
        }
    }

    private suspend fun finishCapture(id: String): Result<DocumentDraftEntity> = mutex.withLock {
        runCatching {
            val draft = checkNotNull(dao.find(id)) { "文档草稿已不存在" }
            if (draft.stage == "EDITING" && draft.replacementPath == null) return@runCatching draft
            val source = File(draft.replacementPath ?: draft.sourcePath)
            check(readable(source)) { "文档照片未拍摄完整，可重拍或丢弃草稿" }
            RandomAccessFile(source, "rw").use { it.fd.sync() }
            val quad = runCatching { processor.detect(source) }.getOrNull() ?: DocumentQuad.FULL_IMAGE
            val ready = draft.copy(sourcePath = source.absolutePath, replacementPath = null, stage = "EDITING",
                quadValue = quad.encode(), effectValue = DocumentEffect.ORIGINAL.name, quarterTurns = 0)
            dao.update(ready)
            if (draft.sourcePath != ready.sourcePath) File(draft.sourcePath).delete()
            ready
        }
    }

    fun captureFailed(id: String) {
        scope.launch {
            initialization.await()
            val captured = dao.find(id)
            if (captured != null && readable(File(captured.replacementPath ?: captured.sourcePath))) {
                finishCapture(id)
                return@launch
            }
            mutex.withLock {
                val draft = dao.find(id) ?: return@withLock
                val replacement = draft.replacementPath
                if (replacement != null) {
                    File(replacement).delete()
                    dao.update(draft.copy(replacementPath = null))
                } else {
                    // Keep any complete image for explicit review; only failed empty captures are removed.
                    if (readable(File(draft.sourcePath))) dao.update(draft.copy(stage = "EDITING"))
                    else { dao.delete(id); File(draft.sourcePath).delete() }
                }
            }
        }
    }

    suspend fun edit(id: String, quad: DocumentQuad, effect: DocumentEffect, quarterTurns: Int) = withContext(Dispatchers.IO) {
        initialization.await()
        mutex.withLock {
            val draft = checkNotNull(dao.find(id)) { "文档草稿已不存在" }
            check(draft.editingSlot == 1 && draft.stage == "EDITING" && draft.replacementPath == null) { "文档当前不能编辑" }
            require(quad.isValid)
            dao.update(draft.copy(quadValue = quad.encode(), effectValue = effect.name, quarterTurns = (quarterTurns % 4 + 4) % 4))
        }
    }

    suspend fun submit(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            initialization.await()
            mutex.withLock {
                val draft = checkNotNull(dao.find(id)) { "文档草稿已不存在" }
                if (draft.stage == "QUEUED") return@withLock
                check(draft.stage == "EDITING" && draft.replacementPath == null) { "请等待文档照片完成" }
                val output = File(directory, id + "_processed.jpg")
                processor.writeResult(File(draft.sourcePath), draft.quad, draft.effect, draft.quarterTurns, output)
                captureTasks.enqueueDocument(id, output).getOrThrow()
            }
        }
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) {
        initialization.await()
        mutex.withLock {
            val draft = dao.find(id) ?: return@withLock
            check(draft.editingSlot == 1) { "已确认的文档请在保存队列中处理" }
            dao.delete(id)
            deleteMaterials(draft)
        }
    }

    private suspend fun recoverInterrupted() {
        for (draft in dao.all()) {
            if (draft.stage == "QUEUED") {
                if (database.captureTaskDao().find(draft.id) == null) {
                    // The queue removes its durable row only after a successful read-back or explicit discard.
                    deleteMaterials(draft)
                    dao.delete(draft.id)
                }
            } else if (draft.stage == "CAPTURING" || draft.replacementPath != null) {
                val source = File(draft.replacementPath ?: draft.sourcePath)
                if (readable(source)) finishCapture(draft.id)
                else if (draft.replacementPath != null) {
                    dao.update(draft.copy(replacementPath = null))
                    source.delete()
                } else dao.update(draft.copy(stage = "EDITING"))
            }
        }
        val referenced = dao.all().flatMap { listOfNotNull(it.sourcePath, it.replacementPath) }.toSet() +
            database.captureTaskDao().observeOutstanding().first().map { it.tempPath }
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
        // New CameraX document sources are plain JPEGs. Bounds alone can accept a truncated capture.
        return runCatching {
            RandomAccessFile(source, "r").use {
                it.seek(it.length() - 2)
                it.readUnsignedByte() == 0xFF && it.readUnsignedByte() == 0xD9
            }
        }.getOrDefault(false)
    }
}
