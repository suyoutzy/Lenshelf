package com.example.photocategorycamera.data

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test fun `video stabilization is enabled by default`() {
        assertTrue(AppSettings().videoStabilizationEnabled)
    }

    @Test fun `legacy settings select Home without prior group selection`() {
        assertEquals("home", AppSettings().selectedGroupId)
    }
}
