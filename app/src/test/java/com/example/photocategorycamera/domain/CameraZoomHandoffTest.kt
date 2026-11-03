package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class CameraZoomHandoffTest {
    @Test fun recordingWaitsForTheSelectedZoomToActuallyApply() {
        val zoom = CameraZoomHandoff()
        zoom.request(2f, .5f, 10f)
        var complete: ((Result<Unit>) -> Unit)? = null
        var applied = 0f
        var started = false
        zoom.restoreBefore(.5f, 10f, { ratio, done -> applied = ratio; complete = done },
            { started = true }, { throw AssertionError(it) })
        assertEquals(2f, applied, 0f)
        assertFalse(started)
        complete!!(Result.success(Unit))
        assertTrue(started)
    }

    @Test fun bindingToASmallerRangeUsesTheNearestLegalZoom() {
        val zoom = CameraZoomHandoff()
        zoom.request(5f, 1f, 10f)
        var applied = 0f
        zoom.restoreBefore(1f, 3f, { ratio, done -> applied = ratio; done(Result.success(Unit)) }, {},
            { throw AssertionError(it) })
        assertEquals(3f, applied, 0f)
        assertEquals(3f, zoom.requestedRatio, 0f)
    }

    @Test fun failedZoomNeverStartsRecordingAndReportsTheFailureOnce() {
        val zoom = CameraZoomHandoff()
        var started = false
        var failures = 0
        zoom.restoreBefore(1f, 4f, { _, done -> done(Result.failure(IllegalStateException("closed"))) },
            { started = true }, { failures++ })
        assertFalse(started)
        assertEquals(1, failures)
    }
}
