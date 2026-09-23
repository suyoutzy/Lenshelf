package com.example.photocategorycamera.ui

import com.example.photocategorycamera.data.AppSettings
import com.example.photocategorycamera.data.CaptureTaskEntity
import com.example.photocategorycamera.data.CaptureTaskStage
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.FolderOpenMode
import com.example.photocategorycamera.domain.StorageRoot

data class AppUiState(
    val categories: List<Category> = emptyList(),
    val categoryFileCounts: Map<String, Int> = emptyMap(),
    val storageRoot: StorageRoot? = null,
    val hasPendingCapture: Boolean = false,
    val pendingCaptureCount: Int = 0,
    val pendingCategoryName: String? = null,
    val failedCaptureCount: Int = 0,
    val failedCaptureError: String? = null,
    val canRetryCapture: Boolean = false,
    val folderOpenMode: FolderOpenMode = FolderOpenMode.SYSTEM,
    val cameraGridEnabled: Boolean = false,
    val motionPhotoEnabled: Boolean = false,
    val videoStabilizationEnabled: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
)

internal data class CategoryFileCounts(
    val rootTreeUri: String? = null,
    val byCategoryId: Map<String, Int> = emptyMap(),
)

internal object AppUiStateMapper {
    fun create(
        categories: List<Category>,
        settings: AppSettings,
        tasks: List<CaptureTaskEntity>,
        busy: Boolean,
        message: String?,
    ): AppUiState {
        val retryTask = tasks.firstOrNull { it.taskStage == CaptureTaskStage.RETRY }
        val failedTasks = tasks.filter { it.taskStage == CaptureTaskStage.FAILED }
        val activeTaskCount = tasks.size - failedTasks.size
        return AppUiState(
            categories = categories,
            storageRoot = settings.storageRoot,
            hasPendingCapture = settings.pendingCapture != null || retryTask != null || failedTasks.isNotEmpty(),
            pendingCaptureCount = activeTaskCount + if (settings.pendingCapture != null) 1 else 0,
            pendingCategoryName = settings.pendingCapture?.categoryName ?: retryTask?.categoryName
                ?: failedTasks.firstOrNull()?.categoryName,
            failedCaptureCount = failedTasks.size,
            failedCaptureError = failedTasks.firstOrNull()?.error,
            canRetryCapture = settings.pendingCapture != null || retryTask != null,
            folderOpenMode = settings.folderOpenMode,
            cameraGridEnabled = settings.cameraGridEnabled,
            motionPhotoEnabled = settings.motionPhotoEnabled,
            videoStabilizationEnabled = settings.videoStabilizationEnabled,
            busy = busy,
            message = message,
        )
    }

    fun withFileCounts(state: AppUiState, counts: CategoryFileCounts): AppUiState = state.copy(
        categoryFileCounts = if (state.storageRoot?.treeUri == counts.rootTreeUri) {
            counts.byCategoryId
        } else {
            emptyMap()
        },
    )
}
