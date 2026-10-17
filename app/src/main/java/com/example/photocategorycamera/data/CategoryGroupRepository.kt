package com.example.photocategorycamera.data

import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.CategoryNameValidator
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class CategoryGroupRepository(private val dao: CategoryGroupDao) {
    private val mutationMutex = Mutex()
    val groups = dao.observeAll().map { rows -> rows.map(CategoryGroupEntity::asDomain) }

    suspend fun ensureHome() = dao.ensureHome()
    suspend fun getAll(): List<CategoryGroup> = dao.getAll().map(CategoryGroupEntity::asDomain)
    suspend fun findById(id: String): CategoryGroup? = dao.findById(id)?.asDomain()

    suspend fun create(input: String): Result<CategoryGroup> = mutationMutex.withLock {
        ensureHome()
        val name = validateName(input).getOrElse { return@withLock Result.failure(it) }
        if (dao.countByName(name) > 0) return@withLock failure("分组名称已存在")
        val entity = CategoryGroupEntity(UUID.randomUUID().toString(), name, dao.maxSortOrder() + 1, System.currentTimeMillis())
        dao.insert(entity)
        Result.success(entity.asDomain())
    }

    suspend fun rename(id: String, input: String): Result<CategoryGroup> = mutationMutex.withLock {
        if (id == CategoryGroup.HOME_ID) return@withLock failure("常用不能重命名")
        val existing = dao.findById(id) ?: return@withLock failure("分组不存在")
        val name = validateName(input).getOrElse { return@withLock Result.failure(it) }
        if (dao.countByName(name, id) > 0) return@withLock failure("分组名称已存在")
        dao.setName(id, name)
        Result.success(existing.copy(name = name).asDomain())
    }

    suspend fun delete(id: String): Result<Unit> = mutationMutex.withLock {
        if (id == CategoryGroup.HOME_ID) return@withLock failure("常用不能删除")
        if (dao.findById(id) == null) return@withLock failure("分组不存在")
        if (dao.deleteEmpty(id) != 1) return@withLock failure("分组仍有分类")
        Result.success(Unit)
    }

    suspend fun move(id: String, direction: Int) = mutationMutex.withLock {
        if (direction == 0 || id == CategoryGroup.HOME_ID) return@withLock
        val custom = dao.getAll().filter { it.id != CategoryGroup.HOME_ID }
        val index = custom.indexOfFirst { it.id == id }
        val target = index + direction
        if (index !in custom.indices || target !in custom.indices) return@withLock
        val expected = custom.map { it.id }
        val ordered = expected.toMutableList().apply { set(index, expected[target]); set(target, expected[index]) }
        reorder(expected, ordered)
    }

    /** The DAO validates and writes atomically, including when called outside the coordinator. */
    internal suspend fun reorder(expectedIds: List<String>, orderedIds: List<String>) {
        dao.reorder(expectedIds, orderedIds)
    }

    private fun validateName(input: String): Result<String> {
        val validation = CategoryNameValidator.validate(input)
        if (!validation.isValid) return failure(validation.error ?: "分组名称无效")
        val name = validation.normalizedName
        if (name == CategoryGroup.HOME_NAME || name == "." || name == "..") return failure("分组名称不可用")
        return Result.success(name)
    }

    private fun <T> failure(message: String): Result<T> = Result.failure(IllegalArgumentException(message))
}
