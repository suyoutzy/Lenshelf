package com.example.photocategorycamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isActive DESC, sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY isActive DESC, sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE name = :name COLLATE NOCASE")
    suspend fun countByName(name: String): Int

    @Query("SELECT COUNT(*) FROM categories WHERE name = :name COLLATE NOCASE AND id != :id")
    suspend fun countByNameExcludingId(name: String, id: String): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<CategoryEntity>)

    @Update
    suspend fun update(entity: CategoryEntity)

    @Query("UPDATE categories SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean)

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
