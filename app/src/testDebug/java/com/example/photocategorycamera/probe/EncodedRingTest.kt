package com.example.photocategorycamera.probe

import org.junit.Assert.*
import org.junit.Test

class EncodedRingTest {
    private fun EncodedRing.video(t: Long, key: Boolean = true) =
        append(EncodedRing.Track.VIDEO, t, key, byteArrayOf(1, 2))

    @Test fun overlappingSnapshotsSurviveEvictionAndReleaseOnce() {
        val ring = EncodedRing(historyUs = 4_000_000, capacityBytes = 100)
        (0..6).forEach { ring.video(it * 500_000L) }
        val first = ring.snapshot(1_000_000)
        val second = ring.snapshot(1_500_000)
        assertSame(first.packets[1], second.packets[0])
        ring.video(10_000_000)
        assertEquals(14L, ring.retainedBytes)
        first.close()
        first.close()
        assertEquals(12L, ring.retainedBytes)
        second.close()
        assertEquals(2L, ring.retainedBytes)
        ring.clear()
        assertEquals(0L, ring.retainedBytes)
    }

    @Test fun warmupUsesAvailableKeyframeAndCommonAudioOrigin() {
        val ring = EncodedRing()
        ring.video(900_000)
        ring.append(EncodedRing.Track.AUDIO, 910_000, false, byteArrayOf(3))
        ring.video(1_000_000, false)
        ring.video(1_100_000, false)
        ring.snapshot(1_000_000).use {
            assertEquals(900_000L, it.originUs)
            assertEquals(10_000L, it.packets.first { p -> p.track == EncodedRing.Track.AUDIO }.ptsUs - it.originUs)
        }
    }

    @Test fun oldKeyframeCannotPretendToMeetWindow() {
        val ring = EncodedRing()
        ring.video(0)
        ring.video(2_000_000, false)
        assertThrows(IllegalStateException::class.java) { ring.snapshot(2_000_000) }
    }

    @Test fun photographOutsideVideoFailsInsteadOfClampingTimestamp() {
        val ring = EncodedRing()
        ring.video(1_000_000)
        assertThrows(IllegalStateException::class.java) { ring.snapshot(1_100_000) }
    }

    @Test fun pinnedPacketsCountAgainstBudget() {
        val ring = EncodedRing(historyUs = 1_000_000, capacityBytes = 4)
        ring.video(0)
        val pinned = ring.snapshot(0)
        ring.video(2_000_000)
        assertThrows(IllegalStateException::class.java) { ring.video(2_100_000) }
        pinned.close()
        ring.video(2_100_000)
        assertEquals(4L, ring.retainedBytes)
    }

    @Test fun audioClockUsesSamplePositionNotReadCallbackTime() {
        assertEquals(1_000_000L, ProbeTiming.audioPtsUs(48_000, 2_000_000_000, 0, 48_000))
        assertEquals(2_500_000L, ProbeTiming.audioPtsUs(48_000, 2_000_000_000, 72_000, 48_000))
    }

    @Test fun frameGapsAndPhotoErrorsAreMeasured() {
        assertEquals(100_000L, ProbeTiming.maxGapUs(listOf(10L, 33_343L, 133_343L)))
        assertEquals(10L, ProbeTiming.nearestErrorUs(100, listOf(50, 110)))
        assertNull(ProbeTiming.nearestErrorUs(100, emptyList()))
    }
}
