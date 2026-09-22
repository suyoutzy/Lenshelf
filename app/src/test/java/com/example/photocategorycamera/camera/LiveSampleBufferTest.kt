package com.example.photocategorycamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveSampleBufferTest {
    private fun LiveSampleBuffer.video(time: Long, key: Boolean = true) =
        append(LiveSampleBuffer.Track.VIDEO, time, key, byteArrayOf(1, 2))

    private fun LiveSampleBuffer.audio(time: Long) =
        append(LiveSampleBuffer.Track.AUDIO, time, false, byteArrayOf(3))

    @Test fun overlappingCapturesShareAndReleaseSamples() {
        val buffer = LiveSampleBuffer(historyUs = 4_000_000, capacityBytes = 100)
        (0..6).forEach { buffer.video(it * 500_000L) }
        val first = buffer.snapshot(1_000_000)
        val second = buffer.snapshot(1_500_000)
        assertSame(first.samples[1], second.samples[0])
        buffer.video(10_000_000)
        assertEquals(14L, buffer.retainedBytes)
        first.close(); first.close(); second.close()
        assertEquals(2L, buffer.retainedBytes)
    }

    @Test fun snapshotRequiresPhotoCoveredByVideo() {
        val buffer = LiveSampleBuffer()
        buffer.video(1_000_000)
        assertThrows(IllegalStateException::class.java) { buffer.snapshot(1_100_000) }
    }

    @Test fun keyframeMayExtendPrerollByAtMostHalfSecond() {
        val buffer = LiveSampleBuffer()
        buffer.video(0)
        buffer.video(2_000_000, false)
        assertThrows(IllegalStateException::class.java) { buffer.snapshot(2_000_000) }
    }

    @Test fun normalExportWaitsForFullTailButForcedExportMayUseAvailableTail() {
        val buffer = LiveSampleBuffer()
        (0..4).forEach { buffer.video(it * 500_000L) }
        assertThrows(IllegalStateException::class.java) { buffer.snapshot(1_500_000) }
        buffer.snapshot(1_500_000, requireFullTail = false).use { snapshot ->
            assertEquals(2_000_000L, snapshot.samples.last().ptsUs)
        }
    }

    @Test fun snapshotRejectsVideoPauseInsideWindow() {
        val buffer = LiveSampleBuffer()
        (0..15).forEach { buffer.video(it * 33_333L, key = it == 0) }
        (0..45).forEach { buffer.video(700_000L + it * 33_333L, key = it == 0) }
        assertThrows(IllegalStateException::class.java) { buffer.snapshot(1_000_000) }
    }

    @Test fun shiftedAudioUsesLaterSourceWindowAndKeepsTheOutputTailCovered() {
        val buffer = LiveSampleBuffer()
        (0..4).forEach { buffer.video(it * 500_000L) }
        (0..23).forEach { buffer.audio(it * 100_000L) }

        buffer.snapshot(1_000_000L, audioAdvanceUs = 320_000L).use { snapshot ->
            val audioTimes = snapshot.samples.filter { it.track == LiveSampleBuffer.Track.AUDIO }.map { it.ptsUs }
            assertEquals(400_000L, audioTimes.first())
            assertEquals(2_300_000L, audioTimes.last())
        }
    }

    @Test fun shiftedAudioWaitsForItsExtendedTail() {
        val buffer = LiveSampleBuffer()
        (0..4).forEach { buffer.video(it * 500_000L) }
        (0..20).forEach { buffer.audio(it * 100_000L) }

        assertThrows(IllegalStateException::class.java) {
            buffer.snapshot(1_000_000L, audioAdvanceUs = 320_000L)
        }
    }
}
