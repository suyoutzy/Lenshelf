package com.example.photocategorycamera.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.MediaKind

enum class CaptureTaskStage {
    AWAITING_NOTE, VIDEO_PROCESSING, VIDEO_RETRY, CAPTURING, PHOTO_PROCESSING, PHOTO_RETRY, DOCUMENT_PROCESSING, DOCUMENT_RETRY, READY, SAVING, RETRY, FAILED;

    val occupiesActiveSlot: Boolean get() = this != AWAITING_NOTE && this != FAILED

    val isRetryable: Boolean get() = this == VIDEO_RETRY || this == RETRY || this == DOCUMENT_RETRY || this == PHOTO_RETRY
}

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
    @ColumnInfo(defaultValue = "'home'") val groupId: String = CategoryGroup.HOME_ID,
    @ColumnInfo(defaultValue = "NULL") val groupName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val photoDateStampText: String? = null,
    @ColumnInfo(defaultValue = "NULL") val sourcePath: String? = null,
    @ColumnInfo(defaultValue = "0") val timeStampEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "'DATE'") val timeStampFormat: String = "DATE",
    @ColumnInfo(defaultValue = "0") val noteRequired: Boolean = false,
    @ColumnInfo(defaultValue = "''") val noteDraft: String = "",
    @ColumnInfo(defaultValue = "''") val noteText: String = "",
    @ColumnInfo(defaultValue = "1") val stampVersion: Int = 1,
    @ColumnInfo(defaultValue = "NULL") val captureZoneId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val recordingStartedAt: Long? = null,
) {
    val kind: MediaKind get() = MediaKind.entries.firstOrNull { it.name == mediaKind } ?: MediaKind.PHOTO
    val taskStage: CaptureTaskStage get() = CaptureTaskStage.entries.firstOrNull { it.name == stage } ?: CaptureTaskStage.FAILED
    val stampRecipe: com.example.photocategorycamera.domain.MediaStampRecipe get() =
        com.example.photocategorycamera.domain.MediaStampRecipe(photoDateStampText, noteText, stampVersion)
    fun category() = Category(categoryId, categoryName, 0, true, createdAt, groupId, groupName)
}
