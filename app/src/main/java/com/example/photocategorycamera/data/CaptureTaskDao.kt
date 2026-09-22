package com.example.photocategorycamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureTaskDao {
    @Query("SELECT * FROM capture_tasks ORDER BY createdAt ASC")
    fun observeOutstanding(): Flow<List<CaptureTaskEntity>>

    @Query("SELECT COUNT(*) FROM capture_tasks WHERE stage != 'FAILED'")
    suspend fun countOutstanding(): Int

    @Query("SELECT COUNT(*) FROM capture_tasks WHERE categoryId = :categoryId")
    suspend fun countOutstandingForCategory(categoryId: String): Int

    @Query("SELECT * FROM capture_tasks WHERE stage IN ('READY', 'SAVING') ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextSavable(): CaptureTaskEntity?

    @Query("SELECT * FROM capture_tasks WHERE stage = 'RETRY' ORDER BY createdAt ASC")
    suspend fun retryable(): List<CaptureTaskEntity>

    @Query("SELECT * FROM capture_tasks WHERE stage = 'FAILED' ORDER BY createdAt ASC")
    suspend fun failed(): List<CaptureTaskEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: CaptureTaskEntity): Long

    @Query("UPDATE capture_tasks SET mediaKind = :kind, stage = :stage, error = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markReady(id: String, kind: String, stage: String = "READY", now: Long)

    @Query("UPDATE capture_tasks SET stage = :stage, targetUri = :targetUri, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, stage: String, targetUri: String?, error: String?, now: Long)

    @Query("UPDATE capture_tasks SET stage = 'READY', error = NULL, updatedAt = :now WHERE stage = 'RETRY'")
    suspend fun retryAll(now: Long)

    @Query("DELETE FROM capture_tasks WHERE id = :id")
    suspend fun delete(id: String)
}
