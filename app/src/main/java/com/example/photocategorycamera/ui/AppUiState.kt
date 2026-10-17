package com.example.photocategorycamera.ui

import com.example.photocategorycamera.data.AppSettings
import com.example.photocategorycamera.data.CaptureTaskEntity
import com.example.photocategorycamera.data.CaptureTaskStage
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.data.DirectoryOperationEntity
import com.example.photocategorycamera.data.DocumentDraftEntity
import com.example.photocategorycamera.data.blocksCategory
import com.example.photocategorycamera.data.blocksGroup
import com.example.photocategorycamera.domain.FolderOpenMode
import com.example.photocategorycamera.domain.StorageRoot

data class AppUiState(
    val categories: List<Category> = emptyList(),
    val allCategories: List<Category> = emptyList(),
    val groups: List<CategoryGroup> = listOf(HOME_GROUP),
    val selectedGroup: CategoryGroup = HOME_GROUP,
    val directoryOperations: List<DirectoryOperationUiState> = emptyList(),
    val directoryLockedCategoryIds: Set<String> = emptySet(),
    val mutationLockedCategoryIds: Set<String> = emptySet(),
    val lockedGroupIds: Set<String> = emptySet(),
    val directoryOperationGroupIds: Set<String> = emptySet(),
    val rootSwitchLocked: Boolean = false,
    val homeMigrationLabels: List<String> = emptyList(),
    val homeMigrationIssue: String? = null,
    val pendingCategoryLabels: List<String> = emptyList(),
    val documentDraftLabel: String? = null,
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
    val videoStabilizationEnabled: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val documentDraft: com.example.photocategorycamera.data.DocumentDraftEntity? = null,
) {
    fun isCategoryMutationLocked(category: Category): Boolean = category.id in mutationLockedCategoryIds
}

internal val HOME_GROUP = CategoryGroup(CategoryGroup.HOME_ID, CategoryGroup.HOME_NAME, 0, 0)

internal fun directoryRootPath(root: StorageRoot): String {
    val uri = runCatching { java.net.URI(root.treeUri) }.getOrNull()
    if (uri?.authority == "com.android.externalstorage.documents") {
        val treeId = uri.rawPath?.substringAfter("/tree/", "")?.substringBefore("/document/")
        val decoded = runCatching { java.net.URLDecoder.decode(treeId, "UTF-8") }.getOrNull()
        if (decoded?.startsWith("primary:") == true) return "/storage/emulated/0/" + decoded.substringAfter(':').trim('/')
    }
    return root.displayName
}

internal fun categoryDirectoryPath(root: StorageRoot?, category: Category): String =
    listOfNotNull(root?.let(::directoryRootPath), category.groupName, category.name)
        .joinToString("/")

data class DirectoryOperationUiState(
    val id: String,
    val kind: String,
    val sourcePath: String,
    val targetPath: String,
    val stage: String,
    val error: String?,
    val categoryId: String? = null,
    val sourceGroupId: String? = null,
    val targetGroupId: String? = null,
    val targetBeforeCategoryId: String? = null,
) {
    val status: String get() = when {
        error != null -> "目录操作失败，等待重试"
        kind == "TRANSFER_CATEGORY" -> "正在移动分类"
        kind == "MIGRATE_HOME_CATEGORY" -> "正在迁移常用分类"
        else -> "正在重命名"
    }
}

internal data class CategoryFileCounts(
    val rootTreeUri: String? = null,
    val byCategoryId: Map<String, Int> = emptyMap(),
    val groupId: String = CategoryGroup.HOME_ID,
)

internal object AppUiStateMapper {
    fun create(
        categories: List<Category>,
        settings: AppSettings,
        tasks: List<CaptureTaskEntity>,
        busy: Boolean,
        message: String?,
        groups: List<CategoryGroup> = emptyList(),
        operations: List<DirectoryOperationEntity> = emptyList(),
    ): AppUiState {
        val retryTask = tasks.firstOrNull { it.taskStage.isRetryable }
        val failedTasks = tasks.filter { it.taskStage == CaptureTaskStage.FAILED }
        val activeTaskCount = tasks.size - failedTasks.size
        val orderedGroups = listOf(groups.firstOrNull { it.isHome } ?: HOME_GROUP) +
            groups.filterNot { it.isHome }.sortedBy { it.sortOrder }
        val selected = orderedGroups.firstOrNull { it.id == settings.selectedGroupId } ?: orderedGroups.first()
        val pendingOperations = operations.filter { it.slot == 1 }
        val directoryLocks = categories.filter { category ->
            pendingOperations.any { it.blocksCategory(category.id, category.groupId) }
        }.map { it.id }.toSet()
        return AppUiState(
            categories = categories.filter { it.groupId == selected.id },
            allCategories = categories,
            groups = orderedGroups,
            selectedGroup = selected,
            directoryOperations = pendingOperations.map { operation ->
                val root = settings.storageRoot?.takeIf { it.treeUri == operation.rootTreeUri }?.let(::directoryRootPath)
                    ?: operation.rootTreeUri
                fun path(groupName: String?, name: String): String =
                    listOfNotNull(root, groupName, name).joinToString("/")
                DirectoryOperationUiState(operation.id, operation.kind,
                    if (operation.kind == "RENAME_GROUP") path(null, operation.sourceName)
                    else path(operation.sourceGroupName, operation.sourceName),
                    if (operation.kind == "RENAME_GROUP") path(null, operation.targetName)
                    else path(operation.targetGroupName, operation.targetName),
                    operation.stage, operation.error, operation.categoryId, operation.sourceGroupId,
                    operation.targetGroupId, operation.targetBeforeCategoryId)
            },
            directoryLockedCategoryIds = directoryLocks + categories.filter { it.needsHomeMigration }.map { it.id },
            mutationLockedCategoryIds = directoryLocks + tasks.map { it.categoryId } +
                listOfNotNull(settings.pendingCapture?.categoryId),
            lockedGroupIds = orderedGroups.filter { group -> pendingOperations.any { it.blocksGroup(group.id) } }
                .map { it.id }.toSet() + tasks.map { it.groupId } +
                listOfNotNull(settings.pendingCapture?.groupId),
            directoryOperationGroupIds = pendingOperations.flatMap { listOf(it.sourceGroupId, it.targetGroupId) }.toSet(),
            rootSwitchLocked = pendingOperations.isNotEmpty() || categories.any { it.needsHomeMigration },
            homeMigrationLabels = categories.filter { it.needsHomeMigration }.map { it.directoryLabel },
            pendingCategoryLabels = (listOfNotNull(settings.pendingCapture?.let {
                "${if (it.groupId == CategoryGroup.HOME_ID) CategoryGroup.HOME_NAME else it.groupName ?: it.groupId}/${it.categoryName}"
            }) + tasks.map { it.category().directoryLabel }).distinct(),
            storageRoot = settings.storageRoot,
            hasPendingCapture = settings.pendingCapture != null || tasks.isNotEmpty(),
            pendingCaptureCount = activeTaskCount + if (settings.pendingCapture != null) 1 else 0,
            pendingCategoryName = settings.pendingCapture?.categoryName ?: retryTask?.categoryName
                ?: failedTasks.firstOrNull()?.categoryName ?: tasks.firstOrNull()?.categoryName,
            failedCaptureCount = failedTasks.size,
            failedCaptureError = failedTasks.firstOrNull()?.error,
            canRetryCapture = settings.pendingCapture != null || retryTask != null,
            folderOpenMode = settings.folderOpenMode,
            cameraGridEnabled = settings.cameraGridEnabled,
            videoStabilizationEnabled = settings.videoStabilizationEnabled,
            busy = busy,
            message = message,
        )
    }

    fun withDocumentDraft(state: AppUiState, draft: DocumentDraftEntity?): AppUiState = state.copy(
        documentDraft = draft,
        documentDraftLabel = draft?.let {
            "${if (it.groupId == CategoryGroup.HOME_ID) CategoryGroup.HOME_NAME else it.groupName ?: it.groupId}/${it.categoryName}"
        },
        mutationLockedCategoryIds = state.mutationLockedCategoryIds + listOfNotNull(draft?.categoryId),
        lockedGroupIds = state.lockedGroupIds + listOfNotNull(draft?.groupId),
    )

    fun withFileCounts(state: AppUiState, counts: CategoryFileCounts): AppUiState = state.copy(
        categoryFileCounts = if (state.storageRoot?.treeUri == counts.rootTreeUri && state.selectedGroup.id == counts.groupId) {
            counts.byCategoryId
        } else {
            emptyMap()
        },
    )
}
