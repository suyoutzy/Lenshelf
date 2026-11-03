package com.example.photocategorycamera.domain

import java.util.concurrent.atomic.AtomicBoolean

/** User intent survives camera rebindings; a new camera's initial observation is not a selection. */
class CameraZoomHandoff {
    var requestedRatio: Float = 1f
        private set

    fun request(ratio: Float, minimum: Float, maximum: Float): Float {
        require(ratio.isFinite() && minimum > 0f && maximum >= minimum)
        return ratio.coerceIn(minimum, maximum).also { requestedRatio = it }
    }

    fun restoreBefore(
        minimum: Float,
        maximum: Float,
        apply: (Float, (Result<Unit>) -> Unit) -> Unit,
        ready: () -> Unit,
        failed: (Throwable) -> Unit,
    ) {
        val ratio = request(requestedRatio, minimum, maximum)
        val delivered = AtomicBoolean(false)
        val complete: (Result<Unit>) -> Unit = { result ->
            if (delivered.compareAndSet(false, true)) result.fold({ ready() }, failed)
        }
        try { apply(ratio, complete) } catch (error: Exception) { complete(Result.failure(error)) }
    }
}
