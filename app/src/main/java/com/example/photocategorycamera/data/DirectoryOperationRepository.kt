package com.example.photocategorycamera.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.photocategorycamera.domain.*
import com.example.photocategorycamera.storage.MediaStorage
import com.example.photocategorycamera.storage.operations.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Reserves directory mutations in the same database used to accept camera captures. */
class DirectoryOperationRepository(
    context: Context,
    private val database: AppDatabase,
    private val settings: SettingsRepository,
    private val categories: CategoryRepository,
    private val groups: CategoryGroupRepository,
    private val storage: MediaStorage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val backend: DirectoryBackend = SafDirectoryBackend(context.contentResolver),
    private val beforeMetadataCommit: suspend () -> Unit = {},
) {
    private val dao = database.directoryOperationDao()
    private val engine = DirectoryMutationEngine(backend)
    private val journals = DirectoryOperationJournal(context.filesDir)
    private val worker = Mutex()
    val pending = dao.observePending().map { listOfNotNull(it) }
    private val homeMigrationIssue = MutableStateFlow<String?>(null)
    val migrationIssue = homeMigrationIssue.asStateFlow()

    suspend fun resumeInterrupted() {
        val row = dao.pending()
        if (row != null) {
            if (row.error == null) launch(row.id)
        } else migrateHomeCategories()
    }

    /** Each category keeps its legacy location until its own durable move has committed. */
    suspend fun migrateHomeCategories(): Result<Unit> {
        val reserved = transaction<String?> {
            if (dao.pending() != null) return@transaction null
            val root = settings.settings.first().storageRoot ?: return@transaction null
            check(storage.hasPersistedAccess(Uri.parse(root.treeUri))) { "请重新授权原根目录后继续迁移常用分类" }
            val legacy = categories.getAll(CategoryGroup.HOME_ID).filter { it.needsHomeMigration }
            ensureCommonDirectory(root)
            homeMigrationIssue.value = null
            for (category in legacy) {
                checkLegacySourceOwnership(category)
                if (hasOutstandingMedia(category)) {
                    homeMigrationIssue.value = "请先保存或处理旧任务、失败记录和文档草稿，然后继续迁移。"
                    continue
                }
                checkPhysicalDestination(root, CategoryGroup.HOME_NAME, category.name)
                val parent = storage.groupDirectoryUri(Uri.parse(root.treeUri), null).getOrThrow()
                val source = backend.children(parent.toString()).firstOrNull { it.name.equals(category.name, ignoreCase = true) }
                if (source == null) {
                    // An inactive category may never have had a physical directory.
                    check(!category.isActive) { "原分类目录“${category.name}”不存在，请先恢复目录后继续迁移" }
                    val entity = checkNotNull(database.categoryDao().findById(category.id))
                    database.categoryDao().update(entity.copy(homeAtRoot = false))
                } else {
                    check(source.directory && source.name == category.name) { "原分类目录“${category.name}”的名称或类型不一致，请先检查" }
                    val row = operation("MIGRATE_HOME_CATEGORY", category.id, CategoryGroup.HOME_ID,
                        CategoryGroup.HOME_ID, root, null, CategoryGroup.HOME_NAME, category.name, category.name)
                    dao.insert(row)
                    return@transaction row.id
                }
            }
            null
        }
        // A worker on another Room executor must only read the reservation after COMMIT.
        return reserved.onSuccess { id -> id?.let(::launch) }
            .onFailure { homeMigrationIssue.value = it.message ?: "常用分类迁移尚未完成" }
            .map { Unit }
    }

    private suspend fun ensureCommonDirectory(root: StorageRoot) {
        check(groups.getAll().none { !it.isHome && it.name.equals(CategoryGroup.HOME_NAME, ignoreCase = true) }) {
            "已有自定义文件夹名为“常用”，请先在管理文件夹中将它改名，再继续迁移"
        }
        check(categories.getAll(CategoryGroup.HOME_ID).none {
            it.needsHomeMigration && it.name.equals(CategoryGroup.HOME_NAME, ignoreCase = true)
        }) { "根目录已有名为“常用”的旧分类，请先在管理分类中将它改名，再继续迁移" }
        if (database.categoryGroupDao().findById(CategoryGroup.HOME_ID)?.name != CategoryGroup.HOME_NAME) {
            database.categoryGroupDao().setName(CategoryGroup.HOME_ID, CategoryGroup.HOME_NAME)
        }
        val uri = Uri.parse(root.treeUri)
        val parent = storage.groupDirectoryUri(uri, null).getOrThrow()
        val existing = backend.children(parent.toString()).firstOrNull { it.name.equals(CategoryGroup.HOME_NAME, ignoreCase = true) }
        if (existing == null) storage.createGroupDirectory(uri, CategoryGroup.HOME_NAME).getOrThrow()
        else check(existing.directory && existing.name == CategoryGroup.HOME_NAME) { "根目录已有同名文件或目录，请先改名后继续迁移" }
    }

    suspend fun configureRoot(uri: Uri): Result<StorageRoot> = transaction {
        dao.pending()?.let { operation ->
            check(operation.rootTreeUri == uri.toString()) { "目录操作尚未完成，只能重新授权原根目录" }
            check(storage.hasPersistedAccess(uri)) { "原根目录授权未持久化，请重新选择" }
            return@transaction checkNotNull(settings.settings.first().storageRoot?.takeIf { it.treeUri == operation.rootTreeUri })
        }
        val previousRoot = settings.settings.first().storageRoot
        if (previousRoot != null && categories.getAll(CategoryGroup.HOME_ID).any { it.needsHomeMigration }) {
            check(previousRoot.treeUri == uri.toString()) { "常用分类尚未迁移完成，暂时不能更换根目录" }
            check(storage.hasPersistedAccess(uri)) { "原根目录授权未持久化，请重新选择" }
            return@transaction previousRoot
        }
        val root = storage.prepareRoot(uri, categories.getAll()).getOrThrow()
        groups.getAll().filterNot { it.isHome }.forEach { group ->
            // Empty groups also need a directory when changing to another authorized root.
            if (storage.groupDirectoryUri(uri, group.name).isFailure) storage.createGroupDirectory(uri, group.name).getOrThrow()
        }
        ensureCommonDirectory(root)
        settings.setStorageRoot(root)
        root
    }.onSuccess { migrateHomeCategories() }

    suspend fun createGroup(name: String): Result<CategoryGroup> = transaction {
        val normalized = validName(name)
        val root = currentRoot()
        rejectLegacyHomeName(normalized)
        check(dao.pending()?.let { it.kind == "RENAME_GROUP" &&
            (it.sourceName.equals(normalized, true) || it.targetName.equals(normalized, true)) } != true) { "该名称已被目录操作预留" }
        val group = groups.create(normalized).getOrThrow()
        storage.createGroupDirectory(Uri.parse(root.treeUri), normalized).getOrThrow()
        group
    }

    suspend fun deleteGroup(group: CategoryGroup): Result<Unit> = transaction {
        val current = requireGroup(group.id)
        check(!current.isHome) { "常用不能删除" }
        rejectLegacyHomeName(current.name)
        check(dao.pending()?.blocksGroup(current.id) != true) { "该一级文件夹涉及未完成的目录操作" }
        check(database.categoryGroupDao().countCategories(current.id) == 0) { "请先转移或删除该文件夹中的全部分类" }
        storage.deleteEmptyGroupDirectory(Uri.parse(currentRoot().treeUri), current.name).getOrThrow()
        groups.delete(current.id).getOrThrow()
        if (settings.settings.first().selectedGroupId == current.id) settings.setSelectedGroupId(CategoryGroup.HOME_ID)
        Unit
    }

    suspend fun reorderGroup(group: CategoryGroup, direction: Int): Result<Unit> = transaction {
        if (direction == 0 || group.isHome) return@transaction Unit
        val expected = groups.getAll().filterNot { it.isHome }.map { it.id }
        val index = expected.indexOf(group.id)
        val target = index + direction
        if (index !in expected.indices || target !in expected.indices) return@transaction Unit
        val ordered = expected.toMutableList().apply { set(index, expected[target]); set(target, expected[index]) }
        reorderGroupsInTransaction(expected, ordered)
    }

    suspend fun reorderGroups(expectedIds: List<String>, orderedIds: List<String>): Result<Unit> = transaction {
        reorderGroupsInTransaction(expectedIds, orderedIds)
    }

    suspend fun reorderCategories(groupId: String, isActive: Boolean, expectedIds: List<String>, orderedIds: List<String>): Result<Unit> = transaction {
        reorderCategoriesInTransaction(groupId, isActive, expectedIds, orderedIds)
    }

    private suspend fun reorderGroupsInTransaction(expectedIds: List<String>, orderedIds: List<String>) {
        val current = groups.getAll().filterNot { it.isHome }
        SectionOrdering.validate(current.map { it.id }, expectedIds, orderedIds)
        current.filterIndexed { index, group -> group.id != orderedIds[index] }.forEach { group ->
            check(dao.pending()?.blocksGroup(group.id) != true) { "该一级文件夹涉及未完成的目录操作" }
        }
        groups.reorder(expectedIds, orderedIds)
    }

    private suspend fun reorderCategoriesInTransaction(groupId: String, active: Boolean, expectedIds: List<String>, orderedIds: List<String>) {
        requireGroup(groupId)
        check(dao.pending()?.blocksGroup(groupId) != true) { "该一级文件夹涉及未完成的目录操作" }
        val section = categories.getAll(groupId).filter { it.isActive == active }
        SectionOrdering.validate(section.map { it.id }, expectedIds, orderedIds)
        section.filterIndexed { index, category -> category.id != orderedIds[index] }.forEach { requireIdle(it) }
        categories.reorder(groupId, active, expectedIds, orderedIds)
    }

    suspend fun createCategory(name: String, groupId: String): Result<Category> = transaction {
        val group = requireGroup(groupId)
        checkGroupNotRenaming(groupId)
        val normalized = validName(name)
        check(reservedNames(groupId).none { it.equals(normalized, ignoreCase = true) }) { "同名目录正在变更，请稍后再试" }
        if (group.isHome) ensureCommonDirectory(currentRoot())
        val category = categories.create(normalized, groupId).getOrThrow()
        storage.ensureCategory(Uri.parse(currentRoot().treeUri), category.name, category.groupName).getOrThrow()
        category
    }

    suspend fun importCategories(groupId: String): Result<CategoryImportResult> = transaction {
        val group = requireGroup(groupId)
        checkGroupNotRenaming(groupId)
        val excluded = reservedNames(groupId).toMutableSet()
        val names = storage.listCategoryDirectories(Uri.parse(currentRoot().treeUri), group.directoryName).getOrThrow()
            .filter { name -> excluded.none { it.equals(name, ignoreCase = true) } }
        categories.importDirectories(names, groupId)
    }

    suspend fun setCategoryActive(category: Category, active: Boolean): Result<Unit> = transaction {
        val current = currentCategory(category.id)
        checkCategoryNotMutating(current)
        if (active) storage.ensureCategory(Uri.parse(currentRoot().treeUri), current.name, current.groupName).getOrThrow()
        categories.setActive(current.id, active)
    }

    suspend fun reorderCategory(category: Category, direction: Int): Result<Unit> = transaction {
        val current = currentCategory(category.id)
        if (direction == 0) return@transaction Unit
        val expected = categories.getAll(current.groupId).filter { it.isActive == current.isActive }.map { it.id }
        val index = expected.indexOf(current.id)
        val target = index + direction
        if (index !in expected.indices || target !in expected.indices) return@transaction Unit
        val ordered = expected.toMutableList().apply { set(index, expected[target]); set(target, expected[index]) }
        reorderCategoriesInTransaction(current.groupId, current.isActive, expected, ordered)
    }

    suspend fun deleteCategory(category: Category, deleteFolderWithContents: Boolean): Result<Unit> = transaction {
        val current = currentCategory(category.id)
        requireIdle(current)
        if (deleteFolderWithContents) checkLegacySourceOwnership(current)
        if (deleteFolderWithContents) storage.deleteCategoryDirectoryWithContents(
            Uri.parse(currentRoot().treeUri), current.name, current.groupName,
        ).getOrThrow()
        categories.delete(current.id)
    }

    suspend fun resolveCategoryDirectory(category: Category): Result<Uri> = transaction {
        val current = currentCategory(category.id)
        checkCategoryNotMutating(current)
        storage.categoryDirectoryUri(Uri.parse(currentRoot().treeUri), current.name, current.groupName).getOrThrow()
    }

    suspend fun transferCategory(category: Category, targetGroupId: String, targetBeforeCategoryId: String? = null): Result<Unit> = reserve {
        val current = currentCategory(category.id)
        targetBeforeCategoryId?.let { anchorId ->
            val anchor = currentCategory(anchorId)
            require(anchor.id != current.id && anchor.groupId == targetGroupId && anchor.isActive == current.isActive) {
                "目标位置已变化，请重新选择同一启用状态的分类位置"
            }
        }
        if (current.groupId == targetGroupId) return@reserve null
        requireIdle(current)
        checkLegacySourceOwnership(current)
        val target = requireGroup(targetGroupId)
        check(database.categoryDao().countByName(current.name, target.id) == 0) { "目标文件夹有同名分类，请先重命名再转移" }
        if (target.isHome) ensureCommonDirectory(currentRoot())
        val root = currentRoot()
        checkPhysicalSource(root, current.groupName, current.name)
        checkPhysicalDestination(root, target.directoryName, current.name)
        operation("TRANSFER_CATEGORY", current.id, current.groupId, target.id, root,
            current.groupName, target.directoryName, current.name, current.name).copy(targetBeforeCategoryId = targetBeforeCategoryId)
    }

    suspend fun renameCategory(category: Category, input: String): Result<Unit> = reserve {
        val current = currentCategory(category.id)
        val name = validName(input)
        if (name == current.name) return@reserve null
        requireIdle(current)
        checkLegacySourceOwnership(current)
        check(database.categoryDao().countByNameExcludingId(name, current.id, current.groupId) == 0) { "分类名称已存在" }
        if (current.needsHomeMigration) check(groups.getAll().none { !it.isHome && it.name.equals(name, true) }) {
            "该名称已用于一级文件夹，请先完成常用迁移后再改名"
        }
        val root = currentRoot()
        checkPhysicalSource(root, current.groupName, current.name)
        checkPhysicalDestination(root, current.groupName, name)
        operation("RENAME_CATEGORY", current.id, current.groupId, current.groupId, root,
            current.groupName, current.groupName, current.name, name)
    }

    suspend fun renameGroup(group: CategoryGroup, input: String): Result<Unit> = reserve {
        val current = requireGroup(group.id)
        check(!current.isHome) { "常用不能重命名" }
        rejectLegacyHomeName(current.name)
        val name = validName(input)
        if (name == current.name) return@reserve null
        check(name != CategoryGroup.HOME_NAME) { "常用是保留名称" }
        check(database.categoryGroupDao().countByName(name, current.id) == 0) { "一级文件夹名称已存在" }
        rejectLegacyHomeName(name)
        categories.getAll(current.id).forEach { requireIdle(it) }
        val root = currentRoot()
        checkPhysicalSource(root, null, current.name)
        checkPhysicalDestination(root, null, name)
        operation("RENAME_GROUP", null, current.id, current.id, root, current.name, name, current.name, name)
    }

    suspend fun retry(id: String): Result<Unit> = transaction {
        val row = checkNotNull(dao.find(id)?.takeIf { it.slot == 1 }) { "目录操作已完成" }
        check(storage.hasPersistedAccess(Uri.parse(row.rootTreeUri))) { "请先重新授权原根目录" }
        dao.update(row.copy(error = null, updatedAt = System.currentTimeMillis()))
        launch(id)
    }

    private suspend fun reserve(factory: suspend () -> DirectoryOperationEntity?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val row = database.withTransaction {
                check(dao.pending() == null) { "请先完成或重试上一个目录操作" }
                factory()?.also { dao.insert(it) }
            }
            row?.let { launch(it.id) }
            Unit
        }
    }

    private fun launch(id: String) { scope.launch { runOperation(id) } }

    private suspend fun runOperation(id: String) = worker.withLock {
        val row = dao.find(id)?.takeIf { it.slot == 1 } ?: return@withLock
        try {
            check(storage.hasPersistedAccess(Uri.parse(row.rootTreeUri))) { "原根目录授权已失效" }
            val restored = journals.read(row.id, row.stateJson)
            val request = restored?.request ?: run {
                val root = Uri.parse(row.rootTreeUri)
                val sourceGroup = if (row.kind == "RENAME_GROUP") null else row.sourceGroupName
                val targetGroup = if (row.kind == "RENAME_GROUP") null else row.targetGroupName
                DirectoryMutationRequest(if (row.kind == "MIGRATE_HOME_CATEGORY") "TRANSFER_CATEGORY" else row.kind,
                    storage.groupDirectoryUri(root, sourceGroup).getOrThrow().toString(),
                    storage.groupDirectoryUri(root, targetGroup).getOrThrow().toString(), row.sourceName, row.targetName)
            }
            val completed = engine.execute(request, restored) { checkpoint ->
                val reference = journals.write(id, checkpoint)
                database.withTransaction {
                    val latest = checkNotNull(dao.find(id)?.takeIf { it.slot == 1 }) { "目录操作记录不存在" }
                    dao.update(latest.copy(stateJson = reference,
                        stage = checkpoint.phase, error = null, updatedAt = System.currentTimeMillis()))
                }
            }
            check(completed.phase == "FILESYSTEM_DONE")
            beforeMetadataCommit()
            database.withTransaction {
                val latest = checkNotNull(dao.find(id)?.takeIf { it.slot == 1 })
                when (latest.kind) {
                    "RENAME_GROUP" -> {
                        val group = checkNotNull(database.categoryGroupDao().findById(latest.sourceGroupId))
                        check(group.name == latest.sourceName || group.name == latest.targetName) { "分组记录已变更" }
                        database.categoryGroupDao().setName(group.id, latest.targetName)
                    }
                    "TRANSFER_CATEGORY", "RENAME_CATEGORY", "MIGRATE_HOME_CATEGORY" -> {
                        val category = checkNotNull(database.categoryDao().findById(checkNotNull(latest.categoryId)))
                        check(category.groupId == latest.sourceGroupId && category.name == latest.sourceName) { "分类记录已变更" }
                        requireGroup(latest.targetGroupId)
                        val moved = category.copy(groupId = latest.targetGroupId, name = latest.targetName,
                            homeAtRoot = latest.targetGroupId == CategoryGroup.HOME_ID && latest.targetGroupName == null)
                        if (latest.kind == "TRANSFER_CATEGORY") {
                            val section = database.categoryDao().getForGroup(latest.targetGroupId)
                                .filter { it.isActive == category.isActive && it.id != category.id }.toMutableList()
                            val before = section.indexOfFirst { it.id == latest.targetBeforeCategoryId }
                            section.add(if (before >= 0) before else section.size, moved)
                            section.forEachIndexed { index, item -> database.categoryDao().update(item.copy(sortOrder = index)) }
                        } else database.categoryDao().update(moved)
                    }
                    else -> error("未知目录操作")
                }
                dao.update(latest.copy(stage = "COMMITTED", error = null, slot = null, updatedAt = System.currentTimeMillis()))
            }
            migrateHomeCategories()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            database.withTransaction {
                dao.find(id)?.takeIf { it.slot == 1 }?.let { latest ->
                    val message = if (latest.stage == "CLEANUP" || latest.stage == "FILESYSTEM_DONE")
                        "新文件已校验，目录清理或记录更新尚未完成，请重试。${error.message.orEmpty()}"
                    else "目录变更未完成，材料已保留，请检查授权、空间和目录后重试。${error.message.orEmpty()}"
                    dao.update(latest.copy(error = message, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    private suspend fun currentRoot(): StorageRoot {
        val root = checkNotNull(settings.settings.first().storageRoot) { "请先授权根目录" }
        check(storage.hasPersistedAccess(Uri.parse(root.treeUri))) { "目录授权已失效，请重新授权" }
        return root
    }

    private suspend fun currentCategory(id: String) = checkNotNull(categories.findById(id)) { "分类不存在" }
    private suspend fun requireGroup(id: String) = checkNotNull(groups.findById(id)) { "一级文件夹不存在" }
    private val CategoryGroup.directoryName: String get() = name

    private suspend fun checkCategoryNotMutating(category: Category) {
        check(dao.pending()?.blocksCategory(category.id, category.groupId) != true) { "该分类涉及未完成的目录操作" }
    }
    private suspend fun checkGroupNotRenaming(groupId: String) {
        val row = dao.pending()
        check(row?.kind != "RENAME_GROUP" || row.sourceGroupId != groupId) { "一级文件夹正在重命名" }
    }
    private suspend fun requireIdle(category: Category) {
        checkCategoryNotMutating(category)
        check(!hasOutstandingMedia(category)) { "该分类有待处理媒体或草稿，请先完成保存" }
    }
    private suspend fun hasOutstandingMedia(category: Category): Boolean =
        database.captureTaskDao().countOutstandingForCategory(category.id) != 0 ||
            database.documentDraftDao().countForCategory(category.id) != 0 ||
            settings.settings.first().pendingCapture?.categoryId == category.id

    private suspend fun rejectLegacyHomeName(name: String) {
        check(categories.getAll(CategoryGroup.HOME_ID).none { it.needsHomeMigration && it.name.equals(name, true) }) {
            "该名称由尚未迁移的常用分类占用，请先完成迁移"
        }
    }
    private suspend fun checkLegacySourceOwnership(category: Category) {
        if (category.needsHomeMigration) check(groups.getAll().none { !it.isHome && it.name.equals(category.name, true) }) {
            "旧分类“${category.name}”与一级文件夹共用目录，请先在文件管理器中备份并拆分；暂不能移动、改名或删除内容"
        }
    }
    private suspend fun reservedNames(groupId: String): Set<String> {
        val row = dao.pending() ?: return emptySet()
        return buildSet {
            if (row.kind != "RENAME_GROUP" && row.targetGroupId == groupId) add(row.targetName)
        }
    }
    private suspend fun checkPhysicalDestination(root: StorageRoot, groupName: String?, name: String) {
        val parent = storage.groupDirectoryUri(Uri.parse(root.treeUri), groupName).getOrThrow()
        check(backend.children(parent.toString()).none { it.name.equals(name, ignoreCase = true) }) { "目标已有同名文件或目录，请先改名，不会合并或覆盖" }
    }
    private suspend fun checkPhysicalSource(root: StorageRoot, groupName: String?, name: String) {
        val parent = storage.groupDirectoryUri(Uri.parse(root.treeUri), groupName).getOrThrow()
        val source = backend.children(parent.toString()).singleOrNull { it.name == name }
        check(source != null && source.directory) { "原分类目录不存在或不是文件夹，请先恢复目录后再操作" }
    }
    private fun validName(input: String): String {
        val validation = CategoryNameValidator.validate(input)
        require(validation.isValid && validation.normalizedName != "." && validation.normalizedName != "..") { validation.error ?: "目录名称无效" }
        return validation.normalizedName
    }
    private fun operation(kind: String, categoryId: String?, sourceGroupId: String, targetGroupId: String,
        root: StorageRoot, sourceGroupName: String?, targetGroupName: String?, sourceName: String, targetName: String): DirectoryOperationEntity {
        val now = System.currentTimeMillis()
        return DirectoryOperationEntity(UUID.randomUUID().toString(), kind, categoryId, sourceGroupId, targetGroupId,
            root.treeUri, sourceGroupName, targetGroupName, sourceName, targetName, "", "RESERVED", null, now, now)
    }
    private suspend fun <T> transaction(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching { database.withTransaction { block() } }
    }
}
