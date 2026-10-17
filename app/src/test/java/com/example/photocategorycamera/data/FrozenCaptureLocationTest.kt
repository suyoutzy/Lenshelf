package com.example.photocategorycamera.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FrozenCaptureLocationTest {
    @Test fun newHomeCategoryUsesPhysicalCommonFolder() {
        val category = CategoryEntity("new", "笔记", 0, true, 1).asDomain()
        assertEquals("常用", category.groupName)
    }

    @Test fun legacyCategoryKeepsRootLocationUntilMoveCommits() {
        val entity = CategoryEntity("old", "笔记", 4, false, 1, homeAtRoot = true)
        assertEquals(null, entity.asDomain().groupName)
        assertEquals(true, entity.asDomain().needsHomeMigration)
        assertEquals(entity, entity.asDomain().asEntity())
        assertEquals("常用", entity.copy(homeAtRoot = false).asDomain().groupName)
    }

    @Test fun legacyTaskUsesRootWithoutAnExtraHomeDirectory() {
        val task = task()
        assertEquals("home", task.groupId)
        assertEquals(null, task.groupName)
        assertEquals("常用/笔记", task.category().directoryLabel)
    }

    @Test fun retryCategoryKeepsTheCapturedGroupEvenIfDisplayGroupLaterChanges() {
        val task = task().copy(groupId = "course", groupName = "课程学习")
        val frozen = task.category()
        assertEquals("course", frozen.groupId)
        assertEquals("课程学习", frozen.groupName)
        assertEquals("课程学习/笔记", frozen.directoryLabel)
        assertEquals("content://fixture/tree/root", task.rootTreeUri)
        assertEquals("content://fixture/tree/root/document/captured", task.targetUri)
    }

    private fun task() = CaptureTaskEntity("task", "source.jpg", "category", "笔记",
        "content://fixture/tree/root", "PHOTO", "RETRY", "content://fixture/tree/root/document/captured",
        createdAt = 1L, updatedAt = 1L)

}
