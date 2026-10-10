package com.example.photocategorycamera.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentQuad
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "document_drafts", indices = [Index(value = ["editingSlot"], unique = true)])
data class DocumentDraftEntity(
    @PrimaryKey val id: String,
    val sourcePath: String,
    val categoryId: String,
    val categoryName: String,
    val rootTreeUri: String,
    val quadValue: String = DocumentQuad.FULL_IMAGE.encode(),
    val effectValue: String = DocumentEffect.ORIGINAL.name,
    val quarterTurns: Int = 0,
    val stage: String = "CAPTURING",
    val editingSlot: Int? = 1,
    val replacementPath: String? = null,
    val createdAt: Long,
) {
    val quad: DocumentQuad get() = DocumentQuad.decode(quadValue) ?: DocumentQuad.FULL_IMAGE
    val effect: DocumentEffect get() = DocumentEffect.entries.firstOrNull { it.name == effectValue } ?: DocumentEffect.ORIGINAL
}

@Dao
interface DocumentDraftDao {
    @Query("SELECT * FROM document_drafts WHERE editingSlot = 1 LIMIT 1")
    fun observeEditable(): Flow<DocumentDraftEntity?>

    @Query("SELECT * FROM document_drafts WHERE editingSlot = 1 LIMIT 1")
    suspend fun editable(): DocumentDraftEntity?

    @Query("SELECT * FROM document_drafts WHERE id = :id")
    suspend fun find(id: String): DocumentDraftEntity?

    @Query("SELECT * FROM document_drafts")
    suspend fun all(): List<DocumentDraftEntity>

    @Query("SELECT COUNT(*) FROM document_drafts WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(draft: DocumentDraftEntity)

    @Update
    suspend fun update(draft: DocumentDraftEntity)

    @Query("DELETE FROM document_drafts WHERE id = :id")
    suspend fun delete(id: String)
}
