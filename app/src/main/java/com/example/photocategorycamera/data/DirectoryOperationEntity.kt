package com.example.photocategorycamera.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "directory_operations", indices = [Index(value = ["slot"], unique = true)])
data class DirectoryOperationEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val categoryId: String?,
    val sourceGroupId: String,
    val targetGroupId: String,
    val rootTreeUri: String,
    val sourceGroupName: String?,
    val targetGroupName: String?,
    val sourceName: String,
    val targetName: String,
    val stateJson: String,
    val stage: String,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val slot: Int? = 1,
    @ColumnInfo(defaultValue = "NULL") val targetBeforeCategoryId: String? = null,
)
