package com.example.photocategorycamera.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.photocategorycamera.domain.CategoryGroup

@Entity(tableName = "category_groups", indices = [Index(value = ["name"], unique = true)])
data class CategoryGroupEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val sortOrder: Int,
    val createdAt: Long,
) {
    fun asDomain() = CategoryGroup(id, if (id == CategoryGroup.HOME_ID) CategoryGroup.HOME_NAME else name, sortOrder, createdAt)
}
