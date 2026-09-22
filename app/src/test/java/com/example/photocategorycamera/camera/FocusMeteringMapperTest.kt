package com.example.photocategorycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusMeteringMapperTest {
    @Test fun portraitBackCameraMapsDisplayIntoRotatedSensor() {
        val topLeft = FocusMeteringMapper.map(0f, 0f, 0, 0, 4000, 3000, 1f, 90, false)
        assertEquals(0f, topLeft.first, 0.01f)
        assertEquals(3000f, topLeft.second, 0.01f)

        val bottomRight = FocusMeteringMapper.map(1f, 1f, 0, 0, 4000, 3000, 1f, 90, false)
        assertEquals(4000f, bottomRight.first, 0.01f)
        assertEquals(0f, bottomRight.second, 0.01f)
    }

    @Test fun zoomMapsIntoTheActiveCenterCrop() {
        val topLeft = FocusMeteringMapper.map(0f, 0f, 0, 0, 4000, 3000, 2f, 0, false)
        assertEquals(1000f, topLeft.first, 0.01f)
        assertEquals(750f, topLeft.second, 0.01f)
    }

    @Test fun frontPreviewMirrorIsUndoneBeforeRotation() {
        val left = FocusMeteringMapper.map(0f, 0.5f, 0, 0, 4000, 3000, 1f, 0, true)
        assertEquals(4000f, left.first, 0.01f)
        assertEquals(1500f, left.second, 0.01f)
    }
}
