package com.example.photocategorycamera.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.photocategorycamera.AppContainer
import com.example.photocategorycamera.data.AppSettings
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.CaptureStatus
import com.example.photocategorycamera.domain.FolderOpenMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppViewModel(private val container: AppContainer) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val fileCounts = MutableStateFlow(CategoryFileCounts())
    private val countRefreshMutex = Mutex()

    val state: StateFlow<AppUiState> = combine(
        container.categories.categories, container.settings.settings,
        container.captureTasks.outstanding, container.groups.groups, container.directoryOperations.pending,
    ) { categories, settings, tasks, groups, operations ->
        AppUiStateMapper.create(categories, settings, tasks, false, null, groups, operations)
    }.combine(container.documents.editable) { ui, draft ->
        AppUiStateMapper.withDocumentDraft(ui, draft)
    }.combine(container.directoryOperations.migrationIssue) { ui, issue ->
        ui.copy(homeMigrationIssue = issue)
    }.combine(busy) { ui, isBusy -> ui.copy(busy = isBusy) }
        .combine(message) { ui, currentMessage -> ui.copy(message = currentMessage) }
        .combine(fileCounts) { ui, counts -> AppUiStateMapper.withFileCounts(ui, counts) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    init {
        viewModelScope.launch {
            container.initialization.await()
            combine(container.groups.groups, container.settings.settings) { groups, settings -> groups to settings }
                .collect { (groups, settings) ->
                    if (groups.any { it.isHome } && container.groups.findById(settings.selectedGroupId) == null) {
                        container.settings.setSelectedGroupId(CategoryGroup.HOME_ID)
                    }
                }
        }
    }

    suspend fun refreshCategoryFileCounts() = countRefreshMutex.withLock {
        container.initialization.await()
        val settings = currentSettings()
        val root = settings.storageRoot ?: return@withLock
        val group = container.groups.findById(settings.selectedGroupId)
            ?: container.groups.findById(CategoryGroup.HOME_ID) ?: return@withLock
        val categories = container.categories.getAll().filter { it.groupId == group.id && it.isActive }
        val counts = categories.groupBy { it.groupName }.flatMap { (directory, entries) ->
            val byName = container.mediaStorage.countCategoryMedia(Uri.parse(root.treeUri), entries.map { it.name }, directory)
                .getOrNull().orEmpty()
            entries.mapNotNull { category -> byName[category.name]?.let { category.id to it } }
        }.toMap()
        fileCounts.value = CategoryFileCounts(root.treeUri, counts, group.id)
    }

    fun selectGroup(id: String) {
        viewModelScope.launch {
            container.initialization.await()
            if (container.groups.findById(id) != null) container.settings.setSelectedGroupId(id)
        }
    }

    fun migrateHomeCategories() = mutate("常用分类迁移尚未完成",
        action = { container.directoryOperations.migrateHomeCategories() })

    fun configureRoot(uri: Uri) = mutate("根目录设置失败", shortBusy = true,
        action = { container.directoryOperations.configureRoot(uri) },
        onSuccess = { message.value = "根目录已设置：${it.displayName}" })

    fun addGroup(name: String, onComplete: (Boolean) -> Unit = {}) = mutate("新增文件夹失败",
        onComplete = onComplete,
        action = {
            container.directoryOperations.createGroup(name).onSuccess {
                container.settings.setSelectedGroupId(it.id)
            }
        },
        onSuccess = { message.value = "已新增文件夹“${it.name}”" })

    fun renameGroup(group: CategoryGroup, name: String, onComplete: (Boolean) -> Unit = {}) =
        mutate("文件夹重命名失败", onComplete = onComplete,
            action = { container.directoryOperations.renameGroup(group, name) },
            onSuccess = { message.value = "正在重命名“${group.name}”，进度可在目录操作中查看" })

    fun moveGroup(group: CategoryGroup, direction: Int) = mutate("文件夹排序失败",
        action = { container.directoryOperations.reorderGroup(group, direction) })

    fun reorderGroups(expectedIds: List<String>, orderedIds: List<String>, onComplete: (Boolean) -> Unit) =
        mutate("文件夹列表已变化，请重新排序", onComplete = onComplete,
            action = { container.directoryOperations.reorderGroups(expectedIds, orderedIds) })

    fun deleteGroup(group: CategoryGroup, onComplete: (Boolean) -> Unit = {}) = mutate("删除文件夹失败",
        onComplete = onComplete, action = { container.directoryOperations.deleteGroup(group) },
        onSuccess = { message.value = "已删除空文件夹“${group.name}”" })

    fun addCategory(name: String, onComplete: (Boolean) -> Unit = {}) = mutate("新增分类失败",
        onComplete = onComplete,
        action = { container.directoryOperations.createCategory(name, selectedGroupId()) },
        onSuccess = { message.value = "已新增分类“${it.directoryLabel}”" })

    fun importCategoriesFromRoot() = mutate("读取分类文件夹失败", shortBusy = true,
        action = { container.directoryOperations.importCategories(selectedGroupId()) },
        onSuccess = { result ->
            message.value = when {
                result.importedCount > 0 -> buildString {
                    append("已导入 ${result.importedCount} 个新分类")
                    if (result.existingCount > 0) append("，跳过 ${result.existingCount} 个已有分类")
                    if (result.ignoredCount > 0) append("，忽略 ${result.ignoredCount} 个无效目录")
                }
                result.existingCount > 0 -> "当前文件夹没有新分类，已有 ${result.existingCount} 个分类"
                else -> "当前文件夹没有可导入的新分类，已忽略 ${result.ignoredCount} 个目录"
            }
        })

    fun setCategoryActive(category: Category, active: Boolean) = mutate("分类状态更新失败",
        action = { container.directoryOperations.setCategoryActive(category, active) })

    fun moveCategory(category: Category, direction: Int) = mutate("分类排序失败",
        action = { container.directoryOperations.reorderCategory(category, direction) })

    fun reorderCategories(groupId: String, isActive: Boolean, expectedIds: List<String>, orderedIds: List<String>,
        onComplete: (Boolean) -> Unit) = mutate("分类列表已变化，请重新排序", onComplete = onComplete,
        action = { container.directoryOperations.reorderCategories(groupId, isActive, expectedIds, orderedIds) })

    fun dropTransferCategory(category: Category, targetGroupId: String, beforeId: String?, onComplete: (Boolean) -> Unit) {
        var selectionFailed = false
        mutate("分类转移失败", onComplete = onComplete, action = {
            val accepted = container.directoryOperations.transferCategory(category, targetGroupId, beforeId)
            if (accepted.isSuccess) {
                try {
                    container.settings.setSelectedGroupId(targetGroupId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A preference failure cannot undo a directory operation already reserved in Room.
                    selectionFailed = true
                }
            }
            accepted
        }, onSuccess = {
            message.value = "正在移动“${category.directoryLabel}”，进度可在目录操作中查看" +
                if (selectionFailed) "；未能记住目标文件夹，下次打开时请重新选择" else ""
        })
    }

    fun renameCategory(category: Category, input: String, onComplete: (Boolean) -> Unit = {}) =
        mutate("分类重命名失败", onComplete = onComplete,
            action = { container.directoryOperations.renameCategory(category, input) },
            onSuccess = { message.value = "正在重命名“${category.directoryLabel}”，进度可在目录操作中查看" })

    fun transferCategory(category: Category, targetGroupId: String, onComplete: (Boolean) -> Unit = {}) =
        mutate("分类转移失败", onComplete = onComplete,
            action = { container.directoryOperations.transferCategory(category, targetGroupId) },
            onSuccess = { message.value = "正在移动“${category.directoryLabel}”，进度可在目录操作中查看" })

    fun deleteCategory(category: Category, deleteFolderWithContents: Boolean, onComplete: (Boolean) -> Unit = {}) =
        mutate("分类删除失败", shortBusy = true, onComplete = onComplete,
            action = { container.directoryOperations.deleteCategory(category, deleteFolderWithContents) },
            onSuccess = {
                message.value = if (deleteFolderWithContents) "分类、文件夹及其中内容已删除"
                else "分类已删除，文件夹及其中内容已保留"
            })

    fun resolveCategoryDirectory(category: Category, onResolved: (Uri) -> Unit) = mutate("无法打开分类目录",
        action = { container.directoryOperations.resolveCategoryDirectory(category) }, onSuccess = onResolved)

    fun resolveRootDirectory(onResolved: (Uri) -> Unit) = mutate("无法打开根目录", action = {
        val root = currentSettings().storageRoot
        if (root == null) Result.failure(IllegalStateException("请先授权根目录"))
        else container.mediaStorage.groupDirectoryUri(Uri.parse(root.treeUri), null)
    }, onSuccess = onResolved)

    fun retryDirectoryOperation(id: String) = mutate("目录操作重试失败",
        action = { container.directoryOperations.retry(id) },
        onSuccess = { message.value = "已开始重试目录操作" })

    fun saveLegacyDocument(id: String) = mutate("旧版文档保存失败，原图已保留", shortBusy = true,
        action = { container.documents.submit(id) },
        onSuccess = { message.value = "旧版文档已加入保存队列" })

    fun discardDocument(id: String) {
        viewModelScope.launch {
            container.initialization.await()
            runCatching { container.documents.discard(id) }
                .onSuccess { message.value = "已丢弃待确认文档" }
                .onFailure { message.value = it.message ?: "无法丢弃文档草稿" }
        }
    }

    private suspend fun selectedGroupId(): String {
        val id = currentSettings().selectedGroupId
        return container.groups.findById(id)?.id ?: CategoryGroup.HOME_ID
    }

    private fun <T> mutate(
        fallback: String,
        shortBusy: Boolean = false,
        onComplete: (Boolean) -> Unit = {},
        action: suspend () -> Result<T>,
        onSuccess: (T) -> Unit = {},
    ) {
        viewModelScope.launch {
            container.initialization.await()
            if (shortBusy) busy.value = true
            try {
                val result = action()
                result.onSuccess(onSuccess).onFailure { message.value = it.message ?: fallback }
                onComplete(result.isSuccess)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                message.value = error.message ?: fallback
                onComplete(false)
            } finally {
                if (shortBusy) busy.value = false
            }
        }
    }
    fun setFolderOpenMode(mode: FolderOpenMode) {
        viewModelScope.launch { container.initialization.await(); container.settings.setFolderOpenMode(mode) }
    }

    fun setCameraGridEnabled(enabled: Boolean) {
        viewModelScope.launch { container.initialization.await(); container.settings.setCameraGridEnabled(enabled) }
    }

    fun setVideoStabilizationEnabled(enabled: Boolean) {
        viewModelScope.launch { container.initialization.await(); container.settings.setVideoStabilizationEnabled(enabled) }
    }

    fun retryPending() {
        viewModelScope.launch {
            container.initialization.await()
            val settings = currentSettings()
            container.documents.retryProcessing()
            container.captureTasks.retryAll()
            val root = settings.storageRoot ?: run {
                message.value = "请先重新授权根目录"
                return@launch
            }
            val pending = settings.pendingCapture
            if (pending == null) {
                message.value = "已开始重试待保存任务"
                return@launch
            }
            busy.value = true
            val result = container.mediaStorage.retry(Uri.parse(root.treeUri), pending)
            message.value = if (result.status == CaptureStatus.SAVED) {
                "照片已恢复到“${if (pending.groupId == CategoryGroup.HOME_ID) CategoryGroup.HOME_NAME else pending.groupName ?: pending.groupId}/${pending.categoryName}”"
            } else {
                result.error ?: "照片恢复失败"
            }
            busy.value = false
        }
    }

    fun discardFailedCaptures() {
        viewModelScope.launch {
            container.initialization.await()
            val count = container.captureTasks.discardFailed()
            message.value = if (count > 0) "已清除 $count 个失败记录及其无效临时文件" else "没有需要清除的失败记录"
        }
    }

    fun clearMessage() { message.value = null }

    fun reportMessage(value: String) { message.value = value }

    private suspend fun currentSettings(): AppSettings = container.settings.settings.first()

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(container) as T
        }
    }
}
