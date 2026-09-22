package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomPresetSelectorTest {
    @Test
    fun keepsOnlyPresetsSupportedByCamera() {
        assertEquals(listOf(1f, 2f, 5f), ZoomPresetSelector.forRange(1f, 8f))
    }

    @Test
    fun includesWideAnglePresetsWhenCameraReportsThem() {
        assertEquals(listOf(0.5f, 0.7f, 1f, 2f), ZoomPresetSelector.forRange(0.5f, 3f))
    }

    @Test
    fun suppliesRangeEndpointsWhenFewPreferredValuesFit() {
        val presets = ZoomPresetSelector.forRange(1.1f, 1.6f)
        assertEquals(listOf(1.1f, 1.6f), presets)
        assertTrue(presets.all { it in 1.1f..1.6f })
    }

    @Test
    fun doesNotOfferTenTimesShortcut() {
        assertEquals(listOf(1f, 2f, 5f), ZoomPresetSelector.forRange(1f, 12f))
    }
}
