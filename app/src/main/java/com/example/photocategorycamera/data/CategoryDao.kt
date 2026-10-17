package com.example.photocategorycamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.SectionOrdering

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isActive DESC, sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY isActive DESC, sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE groupId = :groupId ORDER BY isActive DESC, sortOrder ASC, createdAt ASC")
    suspend fun getForGroup(groupId: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE groupId = :groupId AND name = :name COLLATE NOCASE")
    suspend fun countByName(name: String, groupId: String = CategoryGroup.HOME_ID): Int

    @Query("SELECT COUNT(*) FROM categories WHERE groupId = :groupId AND name = :name COLLATE NOCASE AND id != :id")
    suspend fun countByNameExcludingId(name: String, id: String, groupId: String = CategoryGroup.HOME_ID): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories WHERE groupId = :groupId")
    suspend fun maxSortOrder(groupId: String = CategoryGroup.HOME_ID): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<CategoryEntity>)

    @Query("INSERT OR IGNORE INTO category_groups (id, name, sortOrder, createdAt) VALUES ('home', '常用', -1, 0)")
    suspend fun insertHomeForSeed(): Long

    @Transaction
    suspend fun seedHomeOnce(entities: List<CategoryEntity>) {
        val insertedHome = insertHomeForSeed() != -1L
        if (insertedHome && count() == 0) insertAll(entities)
    }

    @Update
    suspend fun update(entity: CategoryEntity)

    @Query("UPDATE categories SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

    @Transaction
    suspend fun setActiveAtSectionTail(id: String, active: Boolean) {
        val current = findById(id) ?: return
        if (current.isActive == active) return
        val tail = getForGroup(current.groupId).filter { it.isActive == active }.maxOfOrNull { it.sortOrder } ?: -1
        update(current.copy(isActive = active, sortOrder = tail + 1))
    }

    @Transaction
    suspend fun reorderSection(groupId: String, active: Boolean, expectedIds: List<String>, orderedIds: List<String>) {
        val current = getForGroup(groupId).filter { it.isActive == active }.map { it.id }
        SectionOrdering.validate(current, expectedIds, orderedIds)
        if (current == orderedIds) return
        orderedIds.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun setName(id: String, name: String)

    @Query("UPDATE categories SET sortOrder = :orderValue WHERE id = :id")
    suspend fun setSortOrder(id: String, orderValue: Int)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Transaction
    suspend fun swapSortOrders(firstId: String, firstOrder: Int, secondId: String, secondOrder: Int) {
        setSortOrder(firstId, secondOrder)
        setSortOrder(secondId, firstOrder)
    }

}
