package com.example.photocategorycamera.data

import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.CategoryImportPlanner
import com.example.photocategorycamera.domain.CategoryNameValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class CategoryRepository(private val dao: CategoryDao, private val groupDao: CategoryGroupDao? = null) {
    private val seedMutex = Mutex()

    val categories: Flow<List<Category>> = if (groupDao == null) {
        dao.observeAll().map { list -> list.map { it.asDomain() } }
    } else {
        combine(dao.observeAll(), groupDao.observeAll()) { list, groups ->
            val names = groups.associate { it.id to it.name }
            list.map { it.asDomain(names[it.groupId]) }
        }
    }

    suspend fun ensureSeeded() = seedMutex.withLock {
        if (groupDao == null && dao.count() > 0) return@withLock
        val now = System.currentTimeMillis()
        val seeds = listOf("工作记录", "生活随拍", "票据").mapIndexed { index, name ->
            CategoryEntity(UUID.randomUUID().toString(), name, index, true, now + index)
        }
        if (groupDao == null) dao.insertAll(seeds) else dao.seedHomeOnce(seeds)
    }

    suspend fun getAll(groupId: String? = null): List<Category> {
        val names = groupDao?.getAll()?.associate { it.id to it.name }.orEmpty()
        return (if (groupId == null) dao.getAll() else dao.getForGroup(groupId)).map { it.asDomain(names[it.groupId]) }
    }

    suspend fun findById(id: String): Category? = dao.findById(id)?.let { it.asDomain(groupName(it.groupId)) }

    suspend fun create(input: String, groupId: String = CategoryGroup.HOME_ID): Result<Category> {
        if (!groupExists(groupId)) return Result.failure(IllegalArgumentException("分组不存在"))
        val validation = CategoryNameValidator.validate(input)
        if (!validation.isValid) return Result.failure(IllegalArgumentException(validation.error))
        if (dao.countByName(validation.normalizedName, groupId) > 0) {
            return Result.failure(IllegalArgumentException("分类名称已存在"))
        }
        val category = Category(
            id = UUID.randomUUID().toString(),
            name = validation.normalizedName,
            sortOrder = dao.maxSortOrder(groupId) + 1,
            isActive = true,
            createdAt = System.currentTimeMillis(),
            groupId = groupId,
            groupName = groupName(groupId),
        )
        dao.insert(category.asEntity())
        return Result.success(category)
    }

    suspend fun importDirectories(directoryNames: List<String>, groupId: String = CategoryGroup.HOME_ID): CategoryImportResult {
        require(groupExists(groupId)) { "分组不存在" }
        val existing = getAll(groupId)
        val plan = CategoryImportPlanner.plan(directoryNames, existing)
        val now = System.currentTimeMillis()
        val firstOrder = (existing.maxOfOrNull(Category::sortOrder) ?: -1) + 1
        val created = plan.namesToCreate.mapIndexed { index, name ->
            Category(
                id = UUID.randomUUID().toString(),
                name = name,
                sortOrder = firstOrder + index,
                isActive = true,
                createdAt = now + index,
                groupId = groupId,
                groupName = groupName(groupId),
            )
        }
        dao.insertAll(created.map(Category::asEntity))
        return CategoryImportResult(
            createdNames = created.map(Category::name),
            existingCount = plan.existingCount,
            ignoredCount = plan.ignoredCount,
        )
    }

    suspend fun setActive(id: String, active: Boolean) = dao.setActiveAtSectionTail(id, active)

    suspend fun reorder(groupId: String, active: Boolean, expectedIds: List<String>, orderedIds: List<String>) =
        dao.reorderSection(groupId, active, expectedIds, orderedIds)

    suspend fun rename(id: String, input: String): Result<Category> {
        val existing = dao.findById(id)
            ?: return Result.failure(IllegalArgumentException("分类不存在"))
        val validation = CategoryNameValidator.validate(input)
        if (!validation.isValid) return Result.failure(IllegalArgumentException(validation.error))
        if (dao.countByNameExcludingId(validation.normalizedName, id, existing.groupId) > 0) {
            return Result.failure(IllegalArgumentException("分类名称已存在"))
        }
        dao.setName(id, validation.normalizedName)
        return Result.success(existing.copy(name = validation.normalizedName).asDomain(groupName(existing.groupId)))
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun move(id: String, direction: Int) {
        if (direction == 0) return
        val current = dao.findById(id) ?: return
        val section = dao.getForGroup(current.groupId).filter { it.isActive == current.isActive }
        val index = section.indexOfFirst { it.id == id }
        val target = index + direction
        if (index !in section.indices || target !in section.indices) return
        val expected = section.map { it.id }
        val ordered = expected.toMutableList().apply { set(index, expected[target]); set(target, expected[index]) }
        reorder(current.groupId, current.isActive, expected, ordered)
    }

    private suspend fun groupName(groupId: String): String? =
        if (groupId == CategoryGroup.HOME_ID) CategoryGroup.HOME_NAME else groupDao?.findById(groupId)?.name

    private suspend fun groupExists(groupId: String): Boolean =
        if (groupDao == null) groupId == CategoryGroup.HOME_ID else groupDao.findById(groupId) != null
}

data class CategoryImportResult(
    val createdNames: List<String>,
    val existingCount: Int,
    val ignoredCount: Int,
) {
    val importedCount: Int get() = createdNames.size
}
