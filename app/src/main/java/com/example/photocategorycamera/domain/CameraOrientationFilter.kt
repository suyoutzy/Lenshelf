package com.example.photocategorycamera.domain

enum class CameraOrientation(val centerDegrees: Int, val controlRotationDegrees: Float) {
    PORTRAIT(0, 0f),
    LANDSCAPE_LEFT(90, -90f),
    UPSIDE_DOWN(180, 180f),
    LANDSCAPE_RIGHT(270, 90f),
}

class CameraOrientationFilter(
    private val stableDurationMs: Long = 550L,
    private val entryToleranceDegrees: Int = 28,
    private val exitToleranceDegrees: Int = 55,
) {
    private var accepted = CameraOrientation.PORTRAIT
    private var candidate: CameraOrientation? = null
    private var candidateSinceMs = 0L

    fun update(rawDegrees: Int, nowMs: Long): CameraOrientation? {
        if (rawDegrees !in 0..359) return null
        if (angularDistance(rawDegrees, accepted.centerDegrees) <= exitToleranceDegrees) {
            candidate = null
            return null
        }

        val detected = CameraOrientation.entries.minBy {
            angularDistance(rawDegrees, it.centerDegrees)
        }
        if (angularDistance(rawDegrees, detected.centerDegrees) > entryToleranceDegrees) {
            candidate = null
            return null
        }
        if (candidate != detected) {
            candidate = detected
            candidateSinceMs = nowMs
            return null
        }
        if (nowMs - candidateSinceMs < stableDurationMs) return null

        accepted = detected
        candidate = null
        return accepted
    }

    private fun angularDistance(first: Int, second: Int): Int {
        val direct = kotlin.math.abs(first - second)
        return minOf(direct, 360 - direct)
    }
}
