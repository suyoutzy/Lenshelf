package com.example.photocategorycamera.data

import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup

fun DirectoryOperationEntity.blocksCategory(categoryId: String, groupId: String): Boolean =
    slot == 1 && (this.categoryId == categoryId || kind == "RENAME_GROUP" && sourceGroupId == groupId)

fun DirectoryOperationEntity.blocksGroup(groupId: String): Boolean =
    slot == 1 && (sourceGroupId == groupId || targetGroupId == groupId)

/** Call inside the same Room transaction that reserves a capture or directory operation. */
internal suspend fun AppDatabase.latestCaptureCategory(categoryId: String): Category {
    val entity = checkNotNull(categoryDao().findById(categoryId)) { "分类不存在，请返回主页面" }
    check(entity.isActive) { "分类已停用" }
    check(!entity.homeAtRoot) { "该分类等待迁入常用文件夹，请先处理旧任务并完成迁移" }
    check(directoryOperationDao().pending()?.blocksCategory(entity.id, entity.groupId) != true) {
        "该分类的目录正在变更，请完成或重试目录操作后拍摄"
    }
    val groupName = if (entity.groupId == CategoryGroup.HOME_ID) null else {
        checkNotNull(categoryGroupDao().findById(entity.groupId)) { "一级文件夹不存在" }.name
    }
    return entity.asDomain(groupName)
}
