package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiFileManagerPathTest {
    @Test
    fun extractsPrimaryStoragePathSegments() {
        assertEquals(
            listOf("Pictures", "Lenshelf", "工作记录"),
            XiaomiFileManagerPath.primaryStorageSegments("primary:Pictures/Lenshelf/工作记录"),
        )
    }

    @Test
    fun rejectsSecondaryStorageVolume() {
        assertNull(XiaomiFileManagerPath.primaryStorageSegments("1234-5678:Pictures/Lenshelf"))
    }

    @Test
    fun rejectsPrimaryStorageRoot() {
        assertNull(XiaomiFileManagerPath.primaryStorageSegments("primary:"))
    }
}
