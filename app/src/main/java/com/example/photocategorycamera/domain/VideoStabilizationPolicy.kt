package com.example.photocategorycamera.domain

enum class VideoStabilizationStatus {
    ACTIVE,
    DISABLED_BY_USER,
    UNSUPPORTED,
    BIND_FALLBACK,
}

object VideoStabilizationPolicy {
    fun resolveRequestedStatus(
        preferenceEnabled: Boolean,
        stabilizationSupported: Boolean,
        frameRateRanges: Collection<IntRange>,
    ): VideoStabilizationStatus = when {
        !preferenceEnabled -> VideoStabilizationStatus.DISABLED_BY_USER
        !stabilizationSupported || frameRateRanges.none { 30 in it } -> VideoStabilizationStatus.UNSUPPORTED
        else -> VideoStabilizationStatus.ACTIVE
    }

    fun afterStabilizedBindingFailure(status: VideoStabilizationStatus): VideoStabilizationStatus =
        if (status == VideoStabilizationStatus.ACTIVE) VideoStabilizationStatus.BIND_FALLBACK else status

    fun canSwitchLensDuringRecording(
        frozenStatus: VideoStabilizationStatus,
        targetStabilizationSupported: Boolean,
        targetFrameRateRanges: Collection<IntRange>,
    ): Boolean = frozenStatus != VideoStabilizationStatus.ACTIVE ||
        (targetStabilizationSupported && targetFrameRateRanges.any { 30 in it })
}
