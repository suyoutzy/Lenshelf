package com.example.photocategorycamera.domain

data class Category(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val isActive: Boolean,
    val createdAt: Long,
)

data class StorageRoot(
    val treeUri: String,
    val displayName: String,
    val authorizedAt: Long,
)

enum class FolderOpenMode {
    SYSTEM,
    XIAOMI,
}

enum class CaptureStatus {
    SAVED,
    RETRY_AVAILABLE,
    FAILED,
}

enum class MediaKind {
    PHOTO,
    MOTION_PHOTO,
    VIDEO,
}

data class CaptureResult(
    val categoryId: String,
    val fileUri: String? = null,
    val status: CaptureStatus,
    val error: String? = null,
    val warning: String? = null,
)

data class PendingMedia(
    val tempPath: String,
    val categoryId: String,
    val categoryName: String,
    val mediaKind: MediaKind = MediaKind.PHOTO,
) {
    val isMotionPhoto: Boolean get() = mediaKind == MediaKind.MOTION_PHOTO
}

typealias PendingCapture = PendingMedia
