package com.example.photocategorycamera.domain

import kotlin.math.hypot

/** Display-only geometry: preserve the captured rotation/mirroring throughout the unfolding. */
object DocumentPreviewGeometry {
    fun flatCorners(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float,
        reference: List<DocumentPoint>? = null): List<DocumentPoint> {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val variants = (0..3).flatMap { turns ->
            listOf(false, true).map { mirrored ->
                val width = if (turns % 2 == 0) imageWidth else imageHeight
                val height = if (turns % 2 == 0) imageHeight else imageWidth
                val scale = minOf(viewWidth / width, viewHeight / height)
                val left = (viewWidth - width * scale) / 2f
                val top = (viewHeight - height * scale) / 2f
                DocumentQuad.FULL_IMAGE.points.map {
                    val rotated = DocumentCoordinates.rotate(it, turns)
                    DocumentPoint(left + (if (mirrored) 1f - rotated.x else rotated.x) * width * scale,
                        top + rotated.y * height * scale)
                }
            }
        }
        if (reference == null || reference.size != 4) return variants.first()
        fun direction(points: List<DocumentPoint>, index: Int): DocumentPoint {
            val a = points[index]; val b = points[(index + 1) % 4]
            val length = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.001f)
            return DocumentPoint((b.x - a.x) / length, (b.y - a.y) / length)
        }
        return variants.maxBy { candidate ->
            (0..3).sumOf { index ->
                val a = direction(reference, index); val b = direction(candidate, index)
                (a.x * b.x + a.y * b.y).toDouble()
            }
        }
    }

    fun interpolate(from: List<DocumentPoint>, to: List<DocumentPoint>, progress: Float): List<DocumentPoint> {
        require(from.size == 4 && to.size == 4)
        val t = progress.coerceIn(0f, 1f)
        return from.zip(to).map { (a, b) -> DocumentPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) }
    }
}
