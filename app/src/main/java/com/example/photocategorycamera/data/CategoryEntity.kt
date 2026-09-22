package com.example.photocategorycamera.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.photocategorycamera.domain.Category

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val sortOrder: Int,
    val isActive: Boolean,
    val createdAt: Long,
) {
    fun asDomain() = Category(id, name, sortOrder, isActive, createdAt)
}

fun Category.asEntity() = CategoryEntity(id, name, sortOrder, isActive, createdAt)
