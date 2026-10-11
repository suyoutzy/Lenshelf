package com.example.photocategorycamera.domain

import kotlin.math.atan2

/** Pixel transforms belong to CameraX; only validated upright coordinates cross into saving. */
object DocumentCaptureGeometry {
    fun isFresh(analyzedAtMs: Long, shutterAtMs: Long): Boolean =
        shutterAtMs >= analyzedAtMs && shutterAtMs - analyzedAtMs <= 500L

    fun normalizedQuad(pixels: FloatArray, width: Int, height: Int): DocumentQuad? {
        if (pixels.size != 8 || width < 2 || height < 2) return null
        val points = (0..3).map { DocumentPoint(pixels[it * 2] / width, pixels[it * 2 + 1] / height) }
        // Allow subpixel rounding at the border, not a crop from a different camera geometry.
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in -0.01f..1.01f || it.y !in -0.01f..1.01f }) return null
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / 4
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / 4
        val ordered = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = ordered.indices.minBy { ordered[it].x + ordered[it].y }
        return DocumentQuad((0..3).map {
            val point = ordered[(start + it) % 4]
            DocumentPoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
        }).takeIf { it.isValid }
    }
}
