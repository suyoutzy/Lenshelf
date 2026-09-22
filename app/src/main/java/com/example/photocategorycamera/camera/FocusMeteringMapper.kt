package com.example.photocategorycamera.camera

internal object FocusMeteringMapper {
    fun map(
        viewX: Float,
        viewY: Float,
        activeLeft: Int,
        activeTop: Int,
        activeWidth: Int,
        activeHeight: Int,
        zoomRatio: Float,
        relativeRotationDegrees: Int,
        mirrored: Boolean,
    ): Pair<Float, Float> {
        val displayX = if (mirrored) 1f - viewX.coerceIn(0f, 1f) else viewX.coerceIn(0f, 1f)
        val displayY = viewY.coerceIn(0f, 1f)
        val (sensorX, sensorY) = when ((relativeRotationDegrees % 360 + 360) % 360) {
            90 -> displayY to (1f - displayX)
            180 -> (1f - displayX) to (1f - displayY)
            270 -> (1f - displayY) to displayX
            else -> displayX to displayY
        }
        val boundedZoom = zoomRatio.coerceAtLeast(1f)
        val cropWidth = activeWidth / boundedZoom
        val cropHeight = activeHeight / boundedZoom
        val cropLeft = activeLeft + (activeWidth - cropWidth) / 2f
        val cropTop = activeTop + (activeHeight - cropHeight) / 2f
        return (cropLeft + sensorX * cropWidth) to (cropTop + sensorY * cropHeight)
    }
}
