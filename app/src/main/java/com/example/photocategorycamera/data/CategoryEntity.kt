package com.example.photocategorycamera.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup

@Entity(
    tableName = "categories",
    indices = [Index(value = ["groupId", "name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val sortOrder: Int,
    val isActive: Boolean,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'home'") val groupId: String = CategoryGroup.HOME_ID,
    @ColumnInfo(defaultValue = "0") val homeAtRoot: Boolean = false,
) {
    fun asDomain(groupName: String? = null) = Category(
        id, name, sortOrder, isActive, createdAt, groupId,
        if (groupId == CategoryGroup.HOME_ID) {
            if (homeAtRoot) null else CategoryGroup.HOME_NAME
        } else groupName,
    )
}

fun Category.asEntity() = CategoryEntity(id, name, sortOrder, isActive, createdAt, groupId,
    groupId == CategoryGroup.HOME_ID && groupName == null)
