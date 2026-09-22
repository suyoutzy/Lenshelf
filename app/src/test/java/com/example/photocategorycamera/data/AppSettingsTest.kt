package com.example.photocategorycamera.data

import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test fun `video stabilization is enabled by default`() {
        assertTrue(AppSettings().videoStabilizationEnabled)
    }
}
