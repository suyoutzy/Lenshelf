package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStabilizationPolicyTest {
    @Test fun `default supported 30fps request is active`() {
        assertEquals(
            VideoStabilizationStatus.ACTIVE,
            VideoStabilizationPolicy.resolveRequestedStatus(true, true, listOf(15..30)),
        )
    }

    @Test fun `disabled preference wins over capability`() {
        assertEquals(
            VideoStabilizationStatus.DISABLED_BY_USER,
            VideoStabilizationPolicy.resolveRequestedStatus(false, true, listOf(30..30)),
        )
    }

    @Test fun `missing stabilization or 30fps is unsupported`() {
        assertEquals(
            VideoStabilizationStatus.UNSUPPORTED,
            VideoStabilizationPolicy.resolveRequestedStatus(true, false, listOf(30..60)),
        )
        assertEquals(
            VideoStabilizationStatus.UNSUPPORTED,
            VideoStabilizationPolicy.resolveRequestedStatus(true, true, listOf(24..24, 60..60)),
        )
    }

    @Test fun `stabilized bind failure maps to fallback`() {
        assertEquals(
            VideoStabilizationStatus.BIND_FALLBACK,
            VideoStabilizationPolicy.afterStabilizedBindingFailure(VideoStabilizationStatus.ACTIVE),
        )
    }

    @Test fun `active session only switches to supported 30fps lens`() {
        assertTrue(VideoStabilizationPolicy.canSwitchLensDuringRecording(VideoStabilizationStatus.ACTIVE, true, listOf(24..30)))
        assertFalse(VideoStabilizationPolicy.canSwitchLensDuringRecording(VideoStabilizationStatus.ACTIVE, false, listOf(30..30)))
        assertFalse(VideoStabilizationPolicy.canSwitchLensDuringRecording(VideoStabilizationStatus.ACTIVE, true, listOf(24..24)))
        assertTrue(VideoStabilizationPolicy.canSwitchLensDuringRecording(VideoStabilizationStatus.BIND_FALLBACK, false, emptyList()))
    }
}
