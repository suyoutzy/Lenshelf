package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraOrientationFilterTest {
    @Test
    fun leftAndRightUseCorrectVisualRotationDirections() {
        assertEquals(-90f, CameraOrientation.LANDSCAPE_LEFT.controlRotationDegrees, 0f)
        assertEquals(90f, CameraOrientation.LANDSCAPE_RIGHT.controlRotationDegrees, 0f)
    }

    @Test
    fun requiresStableLandscapeBeforeChanging() {
        val filter = CameraOrientationFilter(stableDurationMs = 500)
        assertNull(filter.update(91, 0))
        assertNull(filter.update(88, 499))
        assertEquals(CameraOrientation.LANDSCAPE_LEFT, filter.update(92, 500))
    }

    @Test
    fun ignoresBoundaryJitterAndKeepsAcceptedDirection() {
        val filter = CameraOrientationFilter(stableDurationMs = 500)
        filter.update(90, 0)
        filter.update(90, 500)
        assertNull(filter.update(136, 600))
        assertNull(filter.update(128, 700))
        assertNull(filter.update(142, 800))
    }

    @Test
    fun cancelsCandidateWhenPhoneLeavesEntryZone() {
        val filter = CameraOrientationFilter(stableDurationMs = 500)
        assertNull(filter.update(90, 0))
        assertNull(filter.update(40, 300))
        assertNull(filter.update(90, 600))
        assertNull(filter.update(90, 1_099))
        assertEquals(CameraOrientation.LANDSCAPE_LEFT, filter.update(90, 1_100))
    }
}
