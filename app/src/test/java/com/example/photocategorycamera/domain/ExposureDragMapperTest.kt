package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ExposureDragMapperTest {
    @Test
    fun threePointSevenFiveTrackLengthsTraverseTheFullExposureRange() {
        assertEquals(
            6,
            ExposureDragMapper.valueForDrag(
                startValue = -6,
                minValue = -6,
                maxValue = 6,
                dragPixels = 300f,
                trackLengthPixels = 80f,
                distanceMultiplier = 3.75f,
            ),
        )
    }

    @Test
    fun oneTrackLengthMovesAboutOneQuarterOfTheRange() {
        assertEquals(
            3,
            ExposureDragMapper.valueForDrag(
                startValue = 0,
                minValue = -6,
                maxValue = 6,
                dragPixels = 80f,
                trackLengthPixels = 80f,
                distanceMultiplier = 3.75f,
            ),
        )
    }

    @Test
    fun resultIsClampedToCameraRange() {
        assertEquals(
            -3,
            ExposureDragMapper.valueForDrag(0, -3, 3, -1_000f, 80f, 3.75f),
        )
    }
}
