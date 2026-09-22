package com.example.photocategorycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimestampMapperTest {
    @Test fun mapsStartOfReadBufferFromHardwareFrameAnchor() {
        assertEquals(
            9_980_000L,
            AudioTimestampMapper.bufferStartUs(
                hardwareFramePosition = 1_920,
                hardwareTimestampNs = 10_000_000_000L,
                bufferStartFramePosition = 960,
                sampleRate = 48_000,
            ),
        )
    }

    @Test fun updatedAnchorPreservesTheSameCaptureTimeline() {
        val first = AudioTimestampMapper.bufferStartUs(1_024, 1_021_333_333L, 0, 48_000)
        val later = AudioTimestampMapper.bufferStartUs(49_024, 2_021_333_333L, 48_000, 48_000)
        assertEquals(first + 1_000_000L, later)
    }
}
