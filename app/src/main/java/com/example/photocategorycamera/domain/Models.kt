package com.example.photocategorycamera.domain

data class CategoryGroup(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
) {
    val isHome: Boolean get() = id == HOME_ID

    companion object {
        const val HOME_ID = "home"
        const val HOME_NAME = "常用"
    }
}

data class Category(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val isActive: Boolean,
    val createdAt: Long,
    val groupId: String = CategoryGroup.HOME_ID,
    val groupName: String? = if (groupId == CategoryGroup.HOME_ID) CategoryGroup.HOME_NAME else null,
) {
    val needsHomeMigration: Boolean get() = groupId == CategoryGroup.HOME_ID && groupName == null
    val directoryLabel: String
        get() = "${if (groupId == CategoryGroup.HOME_ID) CategoryGroup.HOME_NAME else groupName ?: groupId}/$name"
}

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
    val groupId: String = CategoryGroup.HOME_ID,
    val groupName: String? = null,
)

typealias PendingCapture = PendingMedia
