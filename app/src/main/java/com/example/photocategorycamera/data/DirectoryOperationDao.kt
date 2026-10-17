package com.example.photocategorycamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectoryOperationDao {
    @Query("SELECT * FROM directory_operations WHERE slot = 1 LIMIT 1")
    fun observePending(): Flow<DirectoryOperationEntity?>

    @Query("SELECT * FROM directory_operations WHERE id = :id LIMIT 1")
    suspend fun find(id: String): DirectoryOperationEntity?

    @Query("SELECT * FROM directory_operations WHERE slot = 1 LIMIT 1")
    suspend fun pending(): DirectoryOperationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DirectoryOperationEntity)

    @Update
    suspend fun update(entity: DirectoryOperationEntity)
}
