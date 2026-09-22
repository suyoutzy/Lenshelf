package com.example.photocategorycamera.data

import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryImportPlanner
import com.example.photocategorycamera.domain.CategoryNameValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class CategoryRepository(private val dao: CategoryDao) {
    private val seedMutex = Mutex()

    val categories: Flow<List<Category>> = dao.observeAll().map { list -> list.map(CategoryEntity::asDomain) }

    suspend fun ensureSeeded() = seedMutex.withLock {
        if (dao.count() > 0) return@withLock
        val now = System.currentTimeMillis()
        dao.insertAll(
            listOf("工作记录", "生活随拍", "票据").mapIndexed { index, name ->
                CategoryEntity(UUID.randomUUID().toString(), name, index, true, now + index)
            },
        )
    }

    suspend fun getAll(): List<Category> = dao.getAll().map(CategoryEntity::asDomain)

    suspend fun findById(id: String): Category? = dao.findById(id)?.asDomain()

    suspend fun create(input: String): Result<Category> {
        val validation = CategoryNameValidator.validate(input)
        if (!validation.isValid) return Result.failure(IllegalArgumentException(validation.error))
        if (dao.countByName(validation.normalizedName) > 0) {
            return Result.failure(IllegalArgumentException("分类名称已存在"))
        }
        val category = Category(
            id = UUID.randomUUID().toString(),
            name = validation.normalizedName,
            sortOrder = dao.maxSortOrder() + 1,
            isActive = true,
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(category.asEntity())
        return Result.success(category)
    }

    suspend fun importDirectories(directoryNames: List<String>): CategoryImportResult {
        val existing = getAll()
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
            )
        }
        dao.insertAll(created.map(Category::asEntity))
        return CategoryImportResult(
            createdNames = created.map(Category::name),
            existingCount = plan.existingCount,
            ignoredCount = plan.ignoredCount,
        )
    }

    suspend fun setActive(id: String, active: Boolean) = dao.setActive(id, active)

    suspend fun rename(id: String, input: String): Result<Category> {
        val existing = dao.findById(id)
            ?: return Result.failure(IllegalArgumentException("分类不存在"))
        val validation = CategoryNameValidator.validate(input)
        if (!validation.isValid) return Result.failure(IllegalArgumentException(validation.error))
        if (dao.countByNameExcludingId(validation.normalizedName, id) > 0) {
            return Result.failure(IllegalArgumentException("分类名称已存在"))
        }
        dao.setName(id, validation.normalizedName)
        return Result.success(existing.copy(name = validation.normalizedName).asDomain())
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun move(id: String, direction: Int) {
        if (direction == 0) return
        val active = dao.getAll().filter { it.isActive }.sortedBy { it.sortOrder }
        val index = active.indexOfFirst { it.id == id }
        val target = index + direction
        if (index !in active.indices || target !in active.indices) return
        dao.swapSortOrders(active[index].id, active[index].sortOrder, active[target].id, active[target].sortOrder)
    }
}

data class CategoryImportResult(
    val createdNames: List<String>,
    val existingCount: Int,
    val ignoredCount: Int,
) {
    val importedCount: Int get() = createdNames.size
}
