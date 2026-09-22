package com.example.photocategorycamera.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.MediaKind
import com.example.photocategorycamera.domain.StorageRoot

enum class CaptureTaskStage { CAPTURING, READY, SAVING, RETRY, FAILED }

@Entity(
    tableName = "capture_tasks",
    indices = [Index("stage"), Index("createdAt")],
)
data class CaptureTaskEntity(
    @PrimaryKey val id: String,
    val tempPath: String,
    val categoryId: String,
    val categoryName: String,
    val rootTreeUri: String,
    val mediaKind: String,
    val stage: String,
    val targetUri: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val kind: MediaKind get() = MediaKind.entries.firstOrNull { it.name == mediaKind } ?: MediaKind.PHOTO
    val taskStage: CaptureTaskStage get() = CaptureTaskStage.entries.firstOrNull { it.name == stage } ?: CaptureTaskStage.FAILED
    fun category() = Category(categoryId, categoryName, 0, true, createdAt)
    fun root() = StorageRoot(rootTreeUri, "", createdAt)
}
