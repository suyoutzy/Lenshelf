package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryGroupModelsTest {
    @Test fun `legacy pending captures retain a Home location`() {
        val pending = PendingMedia("/pending.jpg", "id", "票据")
        assertEquals("home", pending.groupId)
        assertEquals(null, pending.groupName)
    }

    @Test fun `pending group snapshot remains frozen across directory labels`() {
        val pending = PendingMedia("/pending.jpg", "id", "票据", groupId = "trip", groupName = "旅行")
        val category = Category("id", "票据", 0, true, 1, "trip", "出差")
        assertEquals("出差/票据", category.directoryLabel)
        assertEquals("旅行", pending.groupName)
    }

    @Test fun `legacy categories identify their Home directory`() {
        assertEquals("常用/票据", Category("id", "票据", 0, true, 1).directoryLabel)
    }

    @Test fun `custom directories include their current group name`() {
        val category = Category("id", "票据", 0, true, 1, "trip", "旅行")
        assertEquals("旅行/票据", category.directoryLabel)
        assertEquals("出差/票据", category.copy(groupName = "出差").directoryLabel)
    }

    @Test fun `home identity remains fixed regardless of label`() {
        assertTrue(CategoryGroup("home", "首页", -1, 0).isHome)
        assertFalse(CategoryGroup("trip", "首页", 0, 1).isHome)
        assertEquals("常用/票据", Category("id", "票据", 0, true, 1, "home", "忽略").directoryLabel)
    }
}
