package com.example.photocategorycamera.data

import android.net.Uri
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.room.withTransaction
import com.example.photocategorycamera.diagnostics.CaptureEventLog
import com.example.photocategorycamera.domain.CaptureStatus
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.StorageRoot
import com.example.photocategorycamera.storage.MediaStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.time.ZoneId
import com.example.photocategorycamera.domain.PhotoDateStamp
import com.example.photocategorycamera.photo.PhotoStampWriter
import com.example.photocategorycamera.photo.PhotoDateStampProcessor
import com.example.photocategorycamera.photo.ImageProcessingGate

data class CaptureReservation(val id: String, val tempFile: File, val category: Category, val autoPromptEligible: Boolean = false)

class CaptureTaskRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: SettingsRepository,
    private val storage: MediaStorage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val photoStamper: PhotoStampWriter = PhotoDateStampProcessor(),
    private val captureTimeMillis: () -> Long = System::currentTimeMillis,
    private val captureZone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = database.captureTaskDao()
    private val documentDao = database.documentDraftDao()
    private val reservationMutex = Mutex()
    private val savingMutex = Mutex()
    val outstanding: Flow<List<CaptureTaskEntity>> = dao.observeOutstanding()
    val awaitingNotes: Flow<List<CaptureTaskEntity>> = dao.observeAwaitingNotes()

    private val initialization = scope.async {
        settings.migrateObsoleteCameraPreferences()
        migrateLegacyPending()
        resumeInterruptedTasks()
    }

    init { scope.launch { initialization.await(); processReady() } }

    /** Reserve the source and its queue slot together, before CameraX writes anything. */
    suspend fun reserveDocument(draft: DocumentDraftEntity): Result<Unit> = runCatching {
        initialization.await()
        reservationMutex.withLock {
            database.withTransaction {
                check(settings.settings.first().storageRoot?.treeUri == draft.rootTreeUri) { "保存根目录已变化，请重新拍摄" }
                val latest = database.latestCaptureCategory(draft.categoryId)
                val frozen = draft.copy(categoryName = latest.name, groupId = latest.groupId, groupName = latest.groupName)
                check(dao.countOutstanding() < MAX_OUTSTANDING) { "保存队列已满，请稍后拍摄" }
                val now = System.currentTimeMillis()
                check(dao.insert(CaptureTaskEntity(
                    frozen.id, frozen.sourcePath, frozen.categoryId, frozen.categoryName, frozen.rootTreeUri,
                    MediaKind.PHOTO.name, CaptureTaskStage.DOCUMENT_PROCESSING.name,
                    createdAt = now, updatedAt = now, groupId = frozen.groupId, groupName = frozen.groupName,
                )) != -1L) { "文档任务已存在" }
                documentDao.insert(frozen)
            }
        }
    }

    /** Processing-to-saving is atomic; retries reuse the reserved ID and target. */
    suspend fun enqueueDocument(id: String, processed: File): Result<Unit> = runCatching {
        initialization.await()
        reservationMutex.withLock {
            database.withTransaction {
                val draft = checkNotNull(documentDao.find(id)) { "文档草稿已不存在" }
                if (draft.stage == "QUEUED") return@withTransaction
                check(draft.stage in listOf("EDITING", "AUTO_PROCESSING") && draft.replacementPath == null) { "文档仍在拍摄中" }
                check(isRecoverableJpeg(processed)) { "处理后的文档图片无效" }
                val now = System.currentTimeMillis()
                if (draft.stage == "AUTO_PROCESSING") {
                    check(dao.find(id)?.taskStage == CaptureTaskStage.DOCUMENT_PROCESSING) { "文档任务状态不一致" }
                    dao.markDocumentReady(id, processed.absolutePath, now)
                } else {
                    check(dao.countOutstanding() < MAX_OUTSTANDING) { "保存队列已满，请稍后保存旧草稿" }
                    check(dao.insert(CaptureTaskEntity(
                        id, processed.absolutePath, draft.categoryId, draft.categoryName, draft.rootTreeUri,
                        MediaKind.PHOTO.name, CaptureTaskStage.READY.name, createdAt = now, updatedAt = now,
                        groupId = draft.groupId, groupName = draft.groupName,
                    )) != -1L) { "文档保存任务已存在" }
                }
                documentDao.update(draft.copy(stage = "QUEUED", editingSlot = null))
            }
            scope.launch { processReady() }
            Unit
        }
    }

    suspend fun reserve(
        category: Category,
        root: StorageRoot,
        kind: MediaKind,
        createTempFile: () -> File,
    ): Result<CaptureReservation> = runCatching {
        initialization.await()
        reservationMutex.withLock { database.withTransaction {
            val snapshot = settings.settings.first()
            check(snapshot.storageRoot?.treeUri == root.treeUri) { "保存根目录已变化，请重新进入相机" }
            val latest = database.latestCaptureCategory(category.id)
            check(dao.countOutstanding() < MAX_OUTSTANDING) { "待处理任务已满，请等待保存完成" }
            val now = captureTimeMillis()
            val stamp = if (kind == MediaKind.PHOTO && snapshot.photoDateStampEnabled)
                PhotoDateStamp.format(now, captureZone(), snapshot.photoDateStampFormat) else null
            val autoPrompt = snapshot.postCaptureNoteEnabled && dao.countUnconfirmedNotes() == 0
            val id = UUID.randomUUID().toString()
            val file = createTempFile()
            val inserted = dao.insert(
                CaptureTaskEntity(
                    id = id,
                    tempPath = file.absolutePath,
                    categoryId = latest.id,
                    categoryName = latest.name,
                    rootTreeUri = root.treeUri,
                    mediaKind = kind.name,
                    stage = CaptureTaskStage.CAPTURING.name,
                    createdAt = now,
                    updatedAt = now,
                    groupId = latest.groupId,
                    groupName = latest.groupName,
                    photoDateStampText = stamp,
                    sourcePath = if (snapshot.timeStampEnabled || snapshot.postCaptureNoteEnabled) file.absolutePath else null,
                    timeStampEnabled = snapshot.timeStampEnabled,
                    timeStampFormat = snapshot.timeStampFormat.name,
                    noteRequired = snapshot.postCaptureNoteEnabled,
                    stampVersion = 2,
                    captureZoneId = if (kind == MediaKind.PHOTO) captureZone().id else null,
                ),
            )
            check(inserted != -1L) { "无法建立拍摄任务" }
            CaptureEventLog.record(
                context,
                "task_reserved",
                id,
                values = mapOf("categoryId" to category.id, "categoryName" to category.name, "mediaKind" to kind.name),
            )
            CaptureReservation(id, file, latest, autoPrompt)
        } }
    }

    private fun processingStage(task: CaptureTaskEntity): CaptureTaskStage = when {
        !task.stampRecipe.hasContent -> CaptureTaskStage.READY
        task.kind == MediaKind.VIDEO -> CaptureTaskStage.VIDEO_PROCESSING
        else -> CaptureTaskStage.PHOTO_PROCESSING
    }

    suspend fun markVideoStarted(id: String): Result<Unit> = runCatching {
        initialization.await()
        reservationMutex.withLock { database.withTransaction {
            val task = checkNotNull(dao.find(id)) { "录像任务已不存在" }
            check(task.kind == MediaKind.VIDEO && task.taskStage == CaptureTaskStage.CAPTURING) { "录像任务状态已变化" }
            if (task.recordingStartedAt == null) {
                val now = captureTimeMillis()
                val zone = captureZone()
                val format = com.example.photocategorycamera.domain.TimeStampFormat.valueOf(task.timeStampFormat)
                dao.markVideoStarted(id, if (task.timeStampEnabled) PhotoDateStamp.format(now, zone, format) else null, now, zone.id)
            }
        } }
    }

    suspend fun completeCapture(id: String, kind: MediaKind): Result<CaptureTaskEntity?> = runCatching {
        initialization.await()
        val ready = reservationMutex.withLock { database.withTransaction {
            val task = dao.find(id) ?: return@withTransaction null
            check(task.kind == kind) { "拍摄类型不一致" }
            if (task.taskStage == CaptureTaskStage.CAPTURING) {
                val stage = if (task.noteRequired) CaptureTaskStage.AWAITING_NOTE else processingStage(task)
                dao.completeCapture(id, stage.name, System.currentTimeMillis())
            }
            dao.find(id)
        } }
        scope.launch { processReady() }
        ready
    }

    fun markReady(id: String, kind: MediaKind) { scope.launch { completeCapture(id, kind) } }

    suspend fun updateAnnotationDraft(id: String, text: String): Result<Unit> = runCatching {
        initialization.await()
        com.example.photocategorycamera.domain.PostCaptureNote.validate(text).getOrThrow()
        reservationMutex.withLock {
            check(dao.updateNoteDraft(id, text, System.currentTimeMillis()) == 1) { "该素材已进入保存，请刷新待处理列表" }
        }
    }

    suspend fun confirmAnnotation(id: String, text: String): Result<Unit> = runCatching {
        initialization.await()
        com.example.photocategorycamera.domain.PostCaptureNote.validate(text).getOrThrow()
        // Flush first, even if admission to the bounded processing queue will fail.
        reservationMutex.withLock {
            dao.updateNoteDraft(id, text, System.currentTimeMillis())
            database.withTransaction {
                val task = dao.find(id) ?: return@withTransaction // Already archived: idempotent confirmation.
                check(task.noteRequired) { "该素材没有待填写备注" }
                check(task.taskStage != CaptureTaskStage.CAPTURING && task.taskStage != CaptureTaskStage.FAILED) { "该素材尚未就绪或已无法恢复" }
                if (task.taskStage != CaptureTaskStage.AWAITING_NOTE) return@withTransaction
                check(dao.countOutstanding() < MAX_OUTSTANDING) { "保存队列已满，备注已保留，请稍后保存" }
                val confirmed = task.copy(noteText = text.trim())
                dao.confirmNote(id, confirmed.noteText, processingStage(confirmed).name, System.currentTimeMillis())
            }
        }
        scope.launch { processReady() }
    }

    fun discardCapture(id: String, tempFile: File) {
        scope.launch {
            tempFile.delete()
            dao.delete(id)
            CaptureEventLog.record(context, "task_discarded", id)
        }
    }

    fun retryAll() {
        scope.launch {
            initialization.await()
            dao.retryAll(System.currentTimeMillis())
            processReady()
        }
    }

    suspend fun discardFailed(): Int = reservationMutex.withLock {
        val failed = dao.failed()
        failed.forEach { task ->
            File(task.tempPath).delete()
            task.sourcePath?.let { File(it).delete() }
            stampedOutput(task.id).delete()
            File(stampedOutput(task.id).path + ".part").delete()
            dao.delete(task.id)
            clearDocumentMaterials(task.id)
            CaptureEventLog.record(context, "failed_task_discarded", task.id)
        }
        failed.size
    }

    suspend fun hasOutstandingForCategory(categoryId: String): Boolean =
        dao.countOutstandingForCategory(categoryId) > 0 || documentDao.countForCategory(categoryId) > 0

    private suspend fun clearDocumentMaterials(id: String) {
        documentDao.find(id)?.let { draft ->
            File(draft.sourcePath).delete()
            draft.replacementPath?.let { File(it).delete() }
            val directory = File(context.filesDir, "document-drafts")
            File(directory, id + "_processed.jpg").delete()
            File(directory, id + "_processed.jpg.part").delete()
            documentDao.delete(id)
        }
    }

    private suspend fun processReady() = savingMutex.withLock {
        while (true) {
            var task = dao.nextSavable()
            if (task == null) {
                val photo = dao.nextPhotoProcessing() ?: break
                processPhoto(photo)
                continue
            }
            val now = System.currentTimeMillis()
            if (task.targetUri == null) {
                val target = storage.reserveTarget(Uri.parse(task.rootTreeUri), task.category(), task.kind)
                if (target.isFailure) {
                    dao.updateProgress(task.id, CaptureTaskStage.RETRY.name, null,
                        target.exceptionOrNull()?.message ?: "无法建立目标文件", now)
                    continue
                }
                val uri = target.getOrThrow().toString()
                dao.updateProgress(task.id, CaptureTaskStage.SAVING.name, uri, null, now)
                CaptureEventLog.record(context, "target_reserved", task.id, values = mapOf("targetUri" to uri))
                task = task.copy(stage = CaptureTaskStage.SAVING.name, targetUri = uri)
            } else if (task.taskStage == CaptureTaskStage.READY) {
                dao.updateProgress(task.id, CaptureTaskStage.SAVING.name, task.targetUri, null, now)
            }
            val result = storage.storeTempMediaToTarget(
                Uri.parse(checkNotNull(task.targetUri)), File(task.tempPath), task.category(), task.kind,
            )
            when (result.status) {
                CaptureStatus.SAVED -> {
                    CaptureEventLog.record(
                        context,
                        "saf_saved",
                        task.id,
                        values = mapOf("targetUri" to task.targetUri, "mediaKind" to task.mediaKind),
                    )
                    dao.delete(task.id)
                    File(task.tempPath).delete()
                    task.sourcePath?.let { File(it).delete() }
                    clearDocumentMaterials(task.id)
                }
                CaptureStatus.RETRY_AVAILABLE -> dao.updateProgress(
                    task.id,
                    CaptureTaskStage.RETRY.name,
                    if (result.error == "待写入文件已不存在") null else task.targetUri,
                    result.error,
                    System.currentTimeMillis(),
                )
                CaptureStatus.FAILED -> dao.updateProgress(
                    task.id, CaptureTaskStage.FAILED.name, task.targetUri, result.error, System.currentTimeMillis(),
                )
            }
            if (result.status != CaptureStatus.SAVED) {
                CaptureEventLog.record(
                    context,
                    "saf_failed",
                    task.id,
                    values = mapOf("status" to result.status.name, "error" to result.error),
                )
            }
        }
    }

    private fun stampedOutput(id: String): File = File(
        File(context.filesDir, "capture-recovery").apply { mkdirs() }, id + "_stamped.jpg",
    )

    private suspend fun processPhoto(task: CaptureTaskEntity) {
        try {
            ImageProcessingGate.mutex.withLock {
                val source = File(checkNotNull(task.sourcePath) { "水印原图路径缺失" })
                val text = task.stampRecipe.text.also { check(it.isNotBlank()) { "水印配方缺失" } }
                val output = stampedOutput(task.id)
                photoStamper.writeResult(source, text, output)
                dao.markPhotoProcessed(task.id, output.absolutePath, System.currentTimeMillis())
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: com.example.photocategorycamera.photo.InvalidPhotoSourceException) {
            dao.updateProgress(task.id, CaptureTaskStage.FAILED.name, null,
                "照片原图不完整或无法读取，材料已保留，可清除失败记录：" + error.message, System.currentTimeMillis())
        } catch (error: OutOfMemoryError) {
            dao.updateProgress(task.id, CaptureTaskStage.PHOTO_RETRY.name, null,
                "水印处理内存不足，原图已保留，请稍后重试", System.currentTimeMillis())
        } catch (error: Exception) {
            dao.updateProgress(task.id, CaptureTaskStage.PHOTO_RETRY.name, null,
                "水印处理失败，原图已保留：" + (error.message ?: "可重试"), System.currentTimeMillis())
        }
    }

    private suspend fun migrateLegacyPending() {
        val appSettings = settings.settings.first()
        val pending = appSettings.pendingCapture ?: return
        val root = appSettings.storageRoot ?: return
        val now = System.currentTimeMillis()
        val id = UUID.nameUUIDFromBytes("legacy:${pending.tempPath}".toByteArray()).toString()
        dao.insert(
            CaptureTaskEntity(
                id, pending.tempPath, pending.categoryId, pending.categoryName, root.treeUri,
                pending.mediaKind.name, CaptureTaskStage.READY.name, createdAt = now, updatedAt = now,
                groupId = pending.groupId, groupName = pending.groupName,
            ),
        )
        settings.clearPendingCapture()
    }

    private suspend fun resumeInterruptedTasks() {
        outstanding.first().forEach { task ->
            if (task.taskStage == CaptureTaskStage.CAPTURING) {
                val file = File(task.tempPath)
                val recoveredKind = when (task.kind) {
                    MediaKind.VIDEO -> if (isRecoverableVideo(file)) MediaKind.VIDEO else null
                    MediaKind.PHOTO -> if (isRecoverableJpeg(file)) MediaKind.PHOTO else null
                }
                if (recoveredKind != null) {
                    dao.completeCapture(task.id, if (task.noteRequired) CaptureTaskStage.AWAITING_NOTE.name
                        else processingStage(task).name, System.currentTimeMillis())
                    CaptureEventLog.record(
                        context,
                        "task_recovered",
                        task.id,
                        values = mapOf("mediaKind" to recoveredKind.name),
                    )
                } else {
                    dao.updateProgress(
                        task.id,
                        CaptureTaskStage.FAILED.name,
                        task.targetUri,
                        "进程中断前未生成可恢复的媒体文件",
                        System.currentTimeMillis(),
                    )
                    CaptureEventLog.record(
                        context,
                        "recovery_failed",
                        task.id,
                        values = mapOf("error" to "进程中断前未生成可恢复的媒体文件"),
                    )
                }
            }
        }
    }

    private fun isRecoverableJpeg(file: File): Boolean {
        if (!file.isFile || file.length() <= 2L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun isRecoverableVideo(file: File): Boolean {
        if (!file.isFile || file.length() <= 8L) return false
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var hasVideo = false
                var hasAudio = false
                for (index in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                    hasVideo = hasVideo || mime.startsWith("video/")
                    hasAudio = hasAudio || mime.startsWith("audio/")
                }
                hasVideo && hasAudio
            } finally {
                extractor.release()
            }
        }.getOrDefault(false)
    }

    companion object { const val MAX_OUTSTANDING = 8 }
}
