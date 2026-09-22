package com.example.photocategorycamera.data

import android.net.Uri
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.photocategorycamera.diagnostics.CaptureEventLog
import com.example.photocategorycamera.domain.CaptureStatus
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.StorageRoot
import com.example.photocategorycamera.storage.MediaStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID

data class CaptureReservation(val id: String, val tempFile: File)

class CaptureTaskRepository(
    private val context: Context,
    private val dao: CaptureTaskDao,
    private val settings: SettingsRepository,
    private val storage: MediaStorage,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reservationMutex = Mutex()
    private val savingMutex = Mutex()
    val outstanding: Flow<List<CaptureTaskEntity>> = dao.observeOutstanding()

    init {
        scope.launch {
            migrateLegacyPending()
            resumeInterruptedTasks()
            processReady()
        }
    }

    suspend fun reserve(
        category: Category,
        root: StorageRoot,
        kind: MediaKind,
        createTempFile: () -> File,
    ): Result<CaptureReservation> = reservationMutex.withLock {
        runCatching {
            check(dao.countOutstanding() < MAX_OUTSTANDING) { "待处理任务已满，请等待保存完成" }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val file = createTempFile()
            val inserted = dao.insert(
                CaptureTaskEntity(
                    id = id,
                    tempPath = file.absolutePath,
                    categoryId = category.id,
                    categoryName = category.name,
                    rootTreeUri = root.treeUri,
                    mediaKind = kind.name,
                    stage = CaptureTaskStage.CAPTURING.name,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            check(inserted != -1L) { "无法建立拍摄任务" }
            CaptureEventLog.record(
                context,
                "task_reserved",
                id,
                values = mapOf("categoryId" to category.id, "categoryName" to category.name, "mediaKind" to kind.name),
            )
            CaptureReservation(id, file)
        }
    }

    fun markReady(id: String, kind: MediaKind) {
        scope.launch {
            dao.markReady(id, kind.name, now = System.currentTimeMillis())
            CaptureEventLog.record(context, "task_ready", id, values = mapOf("mediaKind" to kind.name))
            processReady()
        }
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
            dao.retryAll(System.currentTimeMillis())
            processReady()
        }
    }

    suspend fun discardFailed(): Int = reservationMutex.withLock {
        val failed = dao.failed()
        failed.forEach { task ->
            File(task.tempPath).delete()
            dao.delete(task.id)
            CaptureEventLog.record(context, "failed_task_discarded", task.id)
        }
        failed.size
    }

    suspend fun hasOutstandingForCategory(categoryId: String): Boolean =
        dao.countOutstandingForCategory(categoryId) > 0

    private suspend fun processReady() = savingMutex.withLock {
        while (true) {
            var task = dao.nextSavable() ?: break
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
                    MediaKind.PHOTO, MediaKind.MOTION_PHOTO -> if (isRecoverableJpeg(file)) {
                        if (containsMotionPhotoMetadata(file)) MediaKind.MOTION_PHOTO else MediaKind.PHOTO
                    } else null
                }
                if (recoveredKind != null) {
                    dao.markReady(task.id, recoveredKind.name, now = System.currentTimeMillis())
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

    private fun containsMotionPhotoMetadata(file: File): Boolean = runCatching {
        val prefixSize = minOf(file.length(), 256L * 1024L).toInt()
        val prefix = ByteArray(prefixSize)
        RandomAccessFile(file, "r").use { it.readFully(prefix) }
        val xmp = String(prefix, StandardCharsets.ISO_8859_1)
        xmp.contains("GCamera:MotionPhoto=\"1\"") && xmp.contains("GCamera:MicroVideoOffset=\"")
    }.getOrDefault(false)

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
