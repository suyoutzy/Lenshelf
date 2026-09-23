package com.example.photocategorycamera.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.photocategorycamera.AppContainer
import com.example.photocategorycamera.domain.CaptureStatus
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.StorageRoot
import com.example.photocategorycamera.domain.VideoStabilizationStatus
import com.example.photocategorycamera.diagnostics.CaptureEventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

enum class VideoRecordingPhase { IDLE, PREPARING, RECORDING, PAUSED, FINALIZING }

data class VideoRecordingState(
    val phase: VideoRecordingPhase = VideoRecordingPhase.IDLE,
    val elapsedNanos: Long = 0L,
    val qualityLabel: String? = null,
    val warningShown: Boolean = false,
    val stabilizationStatus: VideoStabilizationStatus = VideoStabilizationStatus.DISABLED_BY_USER,
) {
    val isActive: Boolean get() = phase != VideoRecordingPhase.IDLE
    val isRunning: Boolean get() = phase == VideoRecordingPhase.RECORDING
}

data class CameraUiState(
    val category: Category? = null,
    val activeCategories: List<Category> = emptyList(),
    val storageRoot: StorageRoot? = null,
    val isSaving: Boolean = false,
    val message: String? = null,
    val canRetry: Boolean = false,
    val pendingCaptureCount: Int = 0,
    val failedCaptureCount: Int = 0,
    val failedCaptureError: String? = null,
    val video: VideoRecordingState = VideoRecordingState(),
) {
    val captureQueueFull: Boolean get() = pendingCaptureCount >= com.example.photocategorycamera.data.CaptureTaskRepository.MAX_OUTSTANDING
    val controlsLocked: Boolean get() = video.isActive
}

data class VideoPreparation(
    val taskId: String,
    val tempFile: File,
    val fileSizeLimitBytes: Long,
    val categoryName: String,
)

class CameraViewModel(
    private val appContext: Context,
    private val categoryId: String,
    private val container: AppContainer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = mutableState.asStateFlow()
    private var frozenCategory: Category? = null

    init {
        viewModelScope.launch {
            val category = container.categories.findById(categoryId)
            val settings = container.settings.settings.first()
            mutableState.value = CameraUiState(
                category = category,
                activeCategories = container.categories.getAll().filter { it.isActive },
                storageRoot = settings.storageRoot,
                canRetry = settings.pendingCapture != null,
            )
        }
        viewModelScope.launch {
            container.captureTasks.outstanding.collectLatest { tasks ->
                val failedTasks = tasks.filter {
                    it.taskStage == com.example.photocategorycamera.data.CaptureTaskStage.FAILED
                }
                val activeTaskCount = tasks.size - failedTasks.size
                CaptureEventLog.record(
                    appContext,
                    "queue_state",
                    values = mapOf(
                        "count" to activeTaskCount,
                        "failedCount" to failedTasks.size,
                        "full" to (activeTaskCount >= com.example.photocategorycamera.data.CaptureTaskRepository.MAX_OUTSTANDING),
                    ),
                )
                mutableState.value = mutableState.value.copy(
                    pendingCaptureCount = activeTaskCount,
                    failedCaptureCount = failedTasks.size,
                    failedCaptureError = failedTasks.firstOrNull()?.error,
                    canRetry = tasks.any { it.taskStage == com.example.photocategorycamera.data.CaptureTaskStage.RETRY },
                )
            }
        }
    }

    fun createTempFile(): File = createRecoveryFile("capture_", ".jpg")

    fun beginCapture(onReserved: (com.example.photocategorycamera.data.CaptureReservation) -> Unit): Boolean {
        val snapshot = mutableState.value
        val category = snapshot.category
        val root = snapshot.storageRoot
        if (snapshot.controlsLocked || snapshot.captureQueueFull || category == null || root == null) {
            CaptureEventLog.record(
                appContext,
                "capture_rejected",
                values = mapOf(
                    "controlsLocked" to snapshot.controlsLocked,
                    "queueFull" to snapshot.captureQueueFull,
                    "hasCategory" to (category != null),
                    "hasRoot" to (root != null),
                ),
            )
            return false
        }
        viewModelScope.launch {
            val result = container.captureTasks.reserve(category, root, MediaKind.PHOTO) { createTempFile() }
            result.onSuccess(onReserved).onFailure { error ->
                mutableState.value = mutableState.value.copy(message = error.message ?: "无法建立拍摄任务")
            }
        }
        return true
    }

    fun prepareVideo(onPrepared: (VideoPreparation) -> Unit): Boolean {
        val snapshot = mutableState.value
        val category = snapshot.category
        val root = snapshot.storageRoot
        if (snapshot.controlsLocked || snapshot.captureQueueFull || category == null || root == null) return false
        val usable = appContext.filesDir.usableSpace
        val reserve = max(MIN_CACHE_RESERVE_BYTES, usable / 10L)
        val budget = usable - reserve
        if (budget < MIN_VIDEO_BUDGET_BYTES) {
            mutableState.value = snapshot.copy(message = "可用缓存空间不足，至少需要预留128 MiB用于录像")
            return false
        }
        frozenCategory = category
        mutableState.value = snapshot.copy(
            message = "正在准备录像…",
            video = VideoRecordingState(phase = VideoRecordingPhase.PREPARING),
        )
        viewModelScope.launch {
            container.captureTasks.reserve(category, root, MediaKind.VIDEO) {
                createRecoveryFile("video_", ".mp4")
            }.onSuccess { reservation ->
                onPrepared(VideoPreparation(reservation.id, reservation.tempFile, budget, category.name))
            }.onFailure { error ->
                clearFrozenVideoTarget()
                mutableState.value = mutableState.value.copy(
                    message = error.message ?: "无法建立录像任务",
                    video = VideoRecordingState(),
                )
            }
        }
        return true
    }

    fun onVideoStarted(
        qualityLabel: String,
        stabilizationStatus: VideoStabilizationStatus,
        warning: String?,
    ) {
        mutableState.value = mutableState.value.copy(
            message = warning,
            video = mutableState.value.video.copy(
                phase = VideoRecordingPhase.RECORDING,
                qualityLabel = qualityLabel,
                stabilizationStatus = stabilizationStatus,
            ),
        )
    }

    fun onVideoStatus(elapsedNanos: Long) {
        val current = mutableState.value.video
        val showWarning = elapsedNanos >= VIDEO_WARNING_NANOS && !current.warningShown
        mutableState.value = mutableState.value.copy(
            message = if (showWarning) "录像将在30秒后自动结束" else mutableState.value.message,
            video = current.copy(
                elapsedNanos = elapsedNanos,
                warningShown = current.warningShown || showWarning,
            ),
        )
    }

    fun onVideoPaused() {
        mutableState.value = mutableState.value.copy(video = mutableState.value.video.copy(phase = VideoRecordingPhase.PAUSED))
    }

    fun onVideoResumed() {
        mutableState.value = mutableState.value.copy(video = mutableState.value.video.copy(phase = VideoRecordingPhase.RECORDING))
    }

    fun markVideoFinalizing() {
        val current = mutableState.value
        if (!current.video.isActive) return
        mutableState.value = current.copy(
            message = "正在结束并校验录像…",
            video = current.video.copy(phase = VideoRecordingPhase.FINALIZING),
        )
    }

    fun onVideoReady(taskId: String, tempFile: File, controllerWarning: String? = null) {
        val category = frozenCategory
        if (category == null) {
            onVideoFailed(taskId, tempFile, "录像目标分类不可用")
            return
        }
        mutableState.value = mutableState.value.copy(
            isSaving = false,
            message = buildString {
                append("视频已录制，正在后台保存到“${category.name}”")
                controllerWarning?.let { append("；$it") }
            },
            video = VideoRecordingState(),
        )
        container.captureTasks.markReady(taskId, MediaKind.VIDEO)
        clearFrozenVideoTarget()
    }

    fun onVideoFailed(taskId: String, tempFile: File, error: String) {
        container.captureTasks.discardCapture(taskId, tempFile)
        mutableState.value = mutableState.value.copy(message = error, isSaving = false, video = VideoRecordingState())
        clearFrozenVideoTarget()
    }

    fun switchCategory(newCategoryId: String) {
        val snapshot = mutableState.value
        if (snapshot.controlsLocked) {
            mutableState.value = snapshot.copy(message = "保存或录像结束后才能切换分类")
            return
        }
        val target = snapshot.activeCategories.firstOrNull { it.id == newCategoryId } ?: return
        mutableState.value = snapshot.copy(category = target, message = null)
    }

    fun onCaptureFailed(taskId: String, tempFile: File, error: String) {
        container.captureTasks.discardCapture(taskId, tempFile)
        mutableState.value = mutableState.value.copy(message = error)
    }

    fun saveCapturedPhoto(taskId: String, isMotionPhoto: Boolean = false) {
        container.captureTasks.markReady(taskId, if (isMotionPhoto) MediaKind.MOTION_PHOTO else MediaKind.PHOTO)
        mutableState.value = mutableState.value.copy(message = "已拍摄，正在后台保存")
    }

    fun retry() {
        container.captureTasks.retryAll()
        viewModelScope.launch {
            val settings = container.settings.settings.first()
            val root = settings.storageRoot
            val pending = settings.pendingCapture
            if (root == null || pending == null) {
                mutableState.value = mutableState.value.copy(message = "正在重试待保存任务")
                return@launch
            }
            mutableState.value = mutableState.value.copy(isSaving = true, message = "正在重试…")
            val result = container.mediaStorage.retry(Uri.parse(root.treeUri), pending)
            mutableState.value = mutableState.value.copy(
                isSaving = false,
                message = if (result.status == CaptureStatus.SAVED) "重试保存成功" else result.error,
                canRetry = result.status == CaptureStatus.RETRY_AVAILABLE,
            )
        }
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }
    fun reportMessage(message: String) { mutableState.value = mutableState.value.copy(message = message) }

    private fun createRecoveryFile(prefix: String, suffix: String): File {
        val directory = File(appContext.filesDir, "capture-recovery").apply { mkdirs() }
        return File.createTempFile(prefix, suffix, directory)
    }

    private fun clearFrozenVideoTarget() {
        frozenCategory = null
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val MIN_CACHE_RESERVE_BYTES = 256L * MIB
        private const val MIN_VIDEO_BUDGET_BYTES = 128L * MIB
        private const val VIDEO_WARNING_NANOS = 570L * 1_000_000_000L

        fun factory(context: Context, categoryId: String, container: AppContainer) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CameraViewModel(context.applicationContext, categoryId, container) as T
            }
    }
}
