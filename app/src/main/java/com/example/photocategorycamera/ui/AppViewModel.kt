package com.example.photocategorycamera.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.photocategorycamera.AppContainer
import com.example.photocategorycamera.data.AppSettings
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryNameValidator
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
        container.categories.categories,
        container.settings.settings,
        container.captureTasks.outstanding,
        busy,
        message,
    ) { categories, settings, tasks, isBusy, currentMessage ->
        AppUiStateMapper.create(categories, settings, tasks, isBusy, currentMessage)
    }.combine(fileCounts) { ui, counts ->
        AppUiStateMapper.withFileCounts(ui, counts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    suspend fun refreshCategoryFileCounts() = countRefreshMutex.withLock {
        val root = currentSettings().storageRoot ?: return@withLock
        val categories = container.categories.getAll().filter { it.isActive }
        val counts = container.mediaStorage.countCategoryMedia(Uri.parse(root.treeUri), categories.map { it.name })
        fileCounts.value = CategoryFileCounts(
            rootTreeUri = root.treeUri,
            byCategoryId = counts.getOrNull().orEmpty().let { byName ->
                categories.mapNotNull { category -> byName[category.name]?.let { category.id to it } }.toMap()
            },
        )
    }

    init {
        viewModelScope.launch { container.categories.ensureSeeded() }
    }

    fun configureRoot(uri: Uri) {
        viewModelScope.launch {
            busy.value = true
            val categories = container.categories.getAll()
            container.mediaStorage.prepareRoot(uri, categories)
                .onSuccess {
                    container.settings.setStorageRoot(it)
                    message.value = "根目录已设置：${it.displayName}"
                }
                .onFailure { message.value = it.message ?: "根目录设置失败" }
            busy.value = false
        }
    }

    fun addCategory(name: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = container.categories.create(name)
            result.onSuccess { category ->
                state.value.storageRoot?.let { root ->
                    container.mediaStorage.ensureCategory(Uri.parse(root.treeUri), category.name)
                        .onFailure { message.value = "分类已建立，但目录暂未创建：${it.message}" }
                }
                if (message.value == null) message.value = "已新增分类“${category.name}”"
            }.onFailure { message.value = it.message ?: "新增分类失败" }
            onComplete(result.isSuccess)
        }
    }

    fun importCategoriesFromRoot() {
        viewModelScope.launch {
            val root = state.value.storageRoot ?: run {
                message.value = "请先重新授权根目录"
                return@launch
            }
            busy.value = true
            container.mediaStorage.listCategoryDirectories(Uri.parse(root.treeUri))
                .onSuccess { directoryNames ->
                    val result = container.categories.importDirectories(directoryNames)
                    message.value = when {
                        directoryNames.isEmpty() -> "当前根目录中没有可导入的一级文件夹"
                        result.importedCount > 0 -> buildString {
                            append("已导入 ${result.importedCount} 个新分类")
                            if (result.existingCount > 0) append("，跳过 ${result.existingCount} 个已有分类")
                            if (result.ignoredCount > 0) append("，忽略 ${result.ignoredCount} 个无效目录")
                        }
                        result.existingCount > 0 && result.ignoredCount == 0 ->
                            "没有新分类：${result.existingCount} 个文件夹均已存在"
                        else -> "没有可导入的新分类，已忽略 ${result.ignoredCount} 个无效目录"
                    }
                }
                .onFailure { message.value = it.message ?: "读取分类文件夹失败" }
            busy.value = false
        }
    }

    fun setCategoryActive(category: Category, active: Boolean) {
        viewModelScope.launch {
            if (active) {
                state.value.storageRoot?.let { root ->
                    val prepared = container.mediaStorage.ensureCategory(Uri.parse(root.treeUri), category.name)
                    if (prepared.isFailure) {
                        message.value = prepared.exceptionOrNull()?.message ?: "分类目录不可用"
                        return@launch
                    }
                }
            }
            container.categories.setActive(category.id, active)
        }
    }

    fun moveCategory(category: Category, direction: Int) {
        viewModelScope.launch { container.categories.move(category.id, direction) }
    }

    fun renameCategory(category: Category, input: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val validation = CategoryNameValidator.validate(input)
            if (!validation.isValid) {
                message.value = validation.error
                onComplete(false)
                return@launch
            }
            val newName = validation.normalizedName
            if (newName == category.name) {
                onComplete(true)
                return@launch
            }
            val settings = currentSettings()
            if (settings.pendingCapture?.categoryId == category.id || container.captureTasks.hasOutstandingForCategory(category.id)) {
                message.value = "该分类有媒体正在处理或等待恢复，请完成保存后再重命名"
                onComplete(false)
                return@launch
            }
            val root = settings.storageRoot
            if (root == null) {
                message.value = "请先重新授权根目录"
                onComplete(false)
                return@launch
            }
            busy.value = true
            val folderResult = container.mediaStorage.renameCategoryDirectory(
                Uri.parse(root.treeUri),
                category.name,
                newName,
            )
            if (folderResult.isFailure) {
                message.value = folderResult.exceptionOrNull()?.message ?: "分类文件夹重命名失败"
                busy.value = false
                onComplete(false)
                return@launch
            }
            container.categories.rename(category.id, newName)
                .onSuccess {
                    message.value = "分类及文件夹已重命名为“$newName”"
                    onComplete(true)
                }
                .onFailure { error ->
                    val rollback = container.mediaStorage.renameCategoryDirectory(
                        Uri.parse(root.treeUri),
                        newName,
                        category.name,
                    )
                    message.value = if (rollback.isSuccess) {
                        error.message ?: "分类重命名失败"
                    } else {
                        "分类记录未修改，但文件夹已改名为“$newName”，请在文件管理器中改回“${category.name}”"
                    }
                    onComplete(false)
                }
            busy.value = false
        }
    }

    fun deleteCategory(category: Category, deleteFolderWithContents: Boolean, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (state.value.categories.size <= 1) {
                message.value = "至少需要保留一个分类"
                onComplete(false)
                return@launch
            }
            val settings = currentSettings()
            if (settings.pendingCapture?.categoryId == category.id || container.captureTasks.hasOutstandingForCategory(category.id)) {
                message.value = "该分类有媒体正在处理或等待恢复，请先完成保存"
                onComplete(false)
                return@launch
            }
            busy.value = true
            val folderResult = if (deleteFolderWithContents) {
                val root = settings.storageRoot
                if (root == null) Result.failure(IllegalStateException("请先重新授权根目录"))
                else container.mediaStorage.deleteCategoryDirectoryWithContents(Uri.parse(root.treeUri), category.name)
            } else {
                Result.success(Unit)
            }
            if (folderResult.isSuccess) {
                runCatching { container.categories.delete(category.id) }
                    .onSuccess {
                        message.value = if (deleteFolderWithContents) "分类、文件夹及其中内容已删除" else "分类已删除，文件夹及其中内容已保留"
                        onComplete(true)
                    }
                    .onFailure {
                        message.value = it.message ?: "分类删除失败"
                        onComplete(false)
                    }
            } else {
                message.value = folderResult.exceptionOrNull()?.message ?: "分类文件夹及其中内容删除失败"
                onComplete(false)
            }
            busy.value = false
        }
    }

    fun resolveCategoryDirectory(category: Category, onResolved: (Uri) -> Unit) {
        viewModelScope.launch {
            val root = state.value.storageRoot ?: run {
                message.value = "请先重新授权根目录"
                return@launch
            }
            busy.value = true
            container.mediaStorage.categoryDirectoryUri(Uri.parse(root.treeUri), category.name)
                .onSuccess(onResolved)
                .onFailure { message.value = it.message ?: "无法打开分类目录" }
            busy.value = false
        }
    }

    fun setFolderOpenMode(mode: FolderOpenMode) {
        viewModelScope.launch { container.settings.setFolderOpenMode(mode) }
    }

    fun setCameraGridEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setCameraGridEnabled(enabled) }
    }

    fun setMotionPhotoEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setMotionPhotoEnabled(enabled) }
    }

    fun setVideoStabilizationEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setVideoStabilizationEnabled(enabled) }
    }

    fun retryPending() {
        viewModelScope.launch {
            val settings = currentSettings() ?: return@launch
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
                "照片已恢复到“${pending.categoryName}”"
            } else {
                result.error ?: "照片恢复失败"
            }
            busy.value = false
        }
    }

    fun discardFailedCaptures() {
        viewModelScope.launch {
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
