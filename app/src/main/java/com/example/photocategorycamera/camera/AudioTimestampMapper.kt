package com.example.photocategorycamera.camera

internal object AudioTimestampMapper {
    fun bufferStartUs(
        hardwareFramePosition: Long,
        hardwareTimestampNs: Long,
        bufferStartFramePosition: Long,
        sampleRate: Int,
    ): Long = hardwareTimestampNs / 1_000L +
        (bufferStartFramePosition - hardwareFramePosition) * 1_000_000L / sampleRate
}
