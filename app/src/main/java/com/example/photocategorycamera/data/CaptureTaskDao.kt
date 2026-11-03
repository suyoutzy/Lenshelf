package com.example.photocategorycamera.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureTaskDao {
    @Query("SELECT * FROM capture_tasks WHERE id = :id")
    suspend fun find(id: String): CaptureTaskEntity?

    @Query("SELECT * FROM capture_tasks ORDER BY createdAt ASC")
    fun observeOutstanding(): Flow<List<CaptureTaskEntity>>

    @Query("SELECT COUNT(*) FROM capture_tasks WHERE stage NOT IN ('FAILED','AWAITING_NOTE')")
    suspend fun countOutstanding(): Int

    @Query("SELECT * FROM capture_tasks WHERE stage = 'AWAITING_NOTE' ORDER BY createdAt,id")
    fun observeAwaitingNotes(): Flow<List<CaptureTaskEntity>>

    @Query("SELECT COUNT(*) FROM capture_tasks WHERE noteRequired = 1 AND stage IN ('CAPTURING','AWAITING_NOTE')")
    suspend fun countUnconfirmedNotes(): Int

    @Query("UPDATE capture_tasks SET noteDraft = :text, updatedAt = :now WHERE id = :id AND stage = 'AWAITING_NOTE'")
    suspend fun updateNoteDraft(id: String, text: String, now: Long): Int

    @Query("UPDATE capture_tasks SET noteText = :text, noteDraft = :text, stage = :stage, error = NULL, updatedAt = :now WHERE id = :id AND stage = 'AWAITING_NOTE'")
    suspend fun confirmNote(id: String, text: String, stage: String, now: Long): Int

    @Query("UPDATE capture_tasks SET photoDateStampText = :text, recordingStartedAt = :startedAt, captureZoneId = :zone, updatedAt = :startedAt WHERE id = :id AND stage = 'CAPTURING' AND mediaKind = 'VIDEO' AND recordingStartedAt IS NULL")
    suspend fun markVideoStarted(id: String, text: String?, startedAt: Long, zone: String): Int

    @Query("SELECT * FROM capture_tasks WHERE stage = 'VIDEO_PROCESSING' ORDER BY createdAt,id LIMIT 1")
    suspend fun nextVideoProcessing(): CaptureTaskEntity?

    @Query("UPDATE capture_tasks SET tempPath = :path, stage = 'READY', error = NULL, updatedAt = :now WHERE id = :id AND stage = 'VIDEO_PROCESSING'")
    suspend fun markVideoProcessed(id: String, path: String, now: Long)

    @Query("SELECT COUNT(*) FROM capture_tasks WHERE categoryId = :categoryId")
    suspend fun countOutstandingForCategory(categoryId: String): Int

    @Query("SELECT * FROM capture_tasks WHERE stage IN ('READY', 'SAVING') ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextSavable(): CaptureTaskEntity?

    @Query("SELECT * FROM capture_tasks WHERE stage = 'PHOTO_PROCESSING' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextPhotoProcessing(): CaptureTaskEntity?

    @Query("UPDATE capture_tasks SET stage = :stage, error = NULL, updatedAt = :now WHERE id = :id AND stage = 'CAPTURING'")
    suspend fun completeCapture(id: String, stage: String, now: Long): Int

    @Query("UPDATE capture_tasks SET tempPath = :path, stage = 'READY', error = NULL, updatedAt = :now WHERE id = :id AND stage = 'PHOTO_PROCESSING'")
    suspend fun markPhotoProcessed(id: String, path: String, now: Long)

    @Query("SELECT * FROM capture_tasks WHERE stage = 'RETRY' ORDER BY createdAt ASC")
    suspend fun retryable(): List<CaptureTaskEntity>

    @Query("SELECT * FROM capture_tasks WHERE stage = 'FAILED' ORDER BY createdAt ASC")
    suspend fun failed(): List<CaptureTaskEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(task: CaptureTaskEntity): Long

    @Query("UPDATE capture_tasks SET mediaKind = :kind, stage = :stage, error = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markReady(id: String, kind: String, stage: String = "READY", now: Long)

    @Query("UPDATE capture_tasks SET tempPath = :path, stage = 'READY', error = NULL, updatedAt = :now WHERE id = :id")
    suspend fun markDocumentReady(id: String, path: String, now: Long)

    @Query("UPDATE capture_tasks SET stage = :stage, targetUri = :targetUri, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, stage: String, targetUri: String?, error: String?, now: Long)

    @Query("UPDATE capture_tasks SET stage = CASE WHEN stage = 'PHOTO_RETRY' THEN 'PHOTO_PROCESSING' WHEN stage = 'VIDEO_RETRY' THEN 'VIDEO_PROCESSING' ELSE 'READY' END, error = NULL, updatedAt = :now WHERE stage IN ('RETRY', 'PHOTO_RETRY', 'VIDEO_RETRY')")
    suspend fun retryAll(now: Long)

    @Query("DELETE FROM capture_tasks WHERE id = :id")
    suspend fun delete(id: String)
}
