package com.example.photocategorycamera.domain

import kotlin.math.roundToInt

object ExposureDragMapper {
    fun valueForDrag(
        startValue: Int,
        minValue: Int,
        maxValue: Int,
        dragPixels: Float,
        trackLengthPixels: Float,
        distanceMultiplier: Float,
    ): Int {
        if (maxValue <= minValue || trackLengthPixels <= 0f || distanceMultiplier <= 0f) return startValue
        val fractionDelta = dragPixels / (trackLengthPixels * distanceMultiplier)
        return (startValue + fractionDelta * (maxValue - minValue))
            .roundToInt()
            .coerceIn(minValue, maxValue)
    }
}
