package com.example.photocategorycamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.SectionOrdering
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryGroupDao {
    @Query("SELECT * FROM category_groups ORDER BY CASE WHEN id = 'home' THEN 0 ELSE 1 END, sortOrder, createdAt")
    fun observeAll(): Flow<List<CategoryGroupEntity>>

    @Query("SELECT * FROM category_groups ORDER BY CASE WHEN id = 'home' THEN 0 ELSE 1 END, sortOrder, createdAt")
    suspend fun getAll(): List<CategoryGroupEntity>

    @Query("SELECT * FROM category_groups WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CategoryGroupEntity?

    @Query("SELECT COUNT(*) FROM category_groups WHERE name = :name COLLATE NOCASE AND id != :excludedId")
    suspend fun countByName(name: String, excludedId: String = ""): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM category_groups WHERE id != 'home'")
    suspend fun maxSortOrder(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE groupId = :groupId")
    suspend fun countCategories(groupId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CategoryGroupEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHome(entity: CategoryGroupEntity)

    @Query("UPDATE category_groups SET name = :name WHERE id = :id AND id != 'home'")
    suspend fun setName(id: String, name: String)

    @Query("DELETE FROM category_groups WHERE id = :id AND id != 'home' AND NOT EXISTS (SELECT 1 FROM categories WHERE groupId = :id)")
    suspend fun deleteEmpty(id: String): Int

    @Query("UPDATE category_groups SET sortOrder = :sortOrder WHERE id = :id AND id != 'home'")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    @Transaction
    suspend fun swapSortOrders(firstId: String, firstOrder: Int, secondId: String, secondOrder: Int) {
        setSortOrder(firstId, secondOrder)
        setSortOrder(secondId, firstOrder)
    }

    @Transaction
    suspend fun reorder(expectedIds: List<String>, orderedIds: List<String>) {
        val current = getAll().filter { it.id != CategoryGroup.HOME_ID }.map { it.id }
        SectionOrdering.validate(current, expectedIds, orderedIds)
        if (current == orderedIds) return
        orderedIds.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    suspend fun ensureHome() = insertHome(CategoryGroupEntity(CategoryGroup.HOME_ID, CategoryGroup.HOME_NAME, -1, 0))
}
