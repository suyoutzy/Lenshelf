package com.example.photocategorycamera.domain

object CaptureSpaceBudget {
    private const val MIB = 1024L * 1024
    const val MIN_VIDEO_BYTES = 128L * MIB
    fun safetyMargin(free: Long): Long = maxOf(256L * MIB, free / 10)
    // Source, local export, same-volume SAF copy, and encoding/container headroom.
    fun videoBudget(free: Long, pendingHeadroom: Long): Long =
        ((free - safetyMargin(free) - pendingHeadroom.coerceAtLeast(0)) / 4).coerceAtLeast(0)
    fun canAcceptPhoto(free: Long, pendingHeadroom: Long): Boolean =
        free - safetyMargin(free) - pendingHeadroom.coerceAtLeast(0) >= 64L * MIB
}
