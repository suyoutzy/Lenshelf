package com.example.photocategorycamera.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

enum class CaptureMode { PHOTO, DOCUMENT }
enum class DocumentEffect(val label: String) { ORIGINAL("原色"), ENHANCED("增强"), GRAYSCALE("黑白") }

data class DocumentPoint(val x: Float, val y: Float)

data class DocumentQuad(val points: List<DocumentPoint>) {
    init { require(points.size == 4) }

    val isValid: Boolean get() {
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
        val crosses = points.indices.map { index ->
            val a = points[index]
            val b = points[(index + 1) % 4]
            val c = points[(index + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return crosses.all { it > 0.0001f } && area >= 0.005f
    }

    val area: Float get() = abs(points.indices.sumOf { index ->
        val a = points[index]
        val b = points[(index + 1) % 4]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2f

    fun move(index: Int, point: DocumentPoint): DocumentQuad {
        val changed = points.toMutableList()
        changed[index] = DocumentPoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
        return DocumentQuad(changed).takeIf { it.isValid } ?: this
    }

    fun encode(): String = points.joinToString(";") { it.x.toString() + "," + it.y }

    /** ImageProxyTransformFactory using crop rect expects coordinates relative to its top-left. */
    fun cropCoordinates(width: Int, height: Int): FloatArray = FloatArray(8) { index ->
        if (index % 2 == 0) points[index / 2].x * width else points[index / 2].y * height
    }

    fun outputSize(width: Int, height: Int, maxEdge: Int = 4096): Pair<Int, Int> {
        require(isValid && width > 1 && height > 1 && maxEdge > 1)
        fun distance(a: Int, b: Int): Float = hypot(
            (points[a].x - points[b].x) * (width - 1),
            (points[a].y - points[b].y) * (height - 1),
        )
        val w = max(distance(0, 1), distance(3, 2))
        val h = max(distance(0, 3), distance(1, 2))
        val scale = minOf(1f, maxEdge / max(w, h))
        return max(2, (w * scale).roundToInt()) to max(2, (h * scale).roundToInt())
    }

    companion object {
        val FULL_IMAGE = DocumentQuad(listOf(
            DocumentPoint(0f, 0f), DocumentPoint(1f, 0f),
            DocumentPoint(1f, 1f), DocumentPoint(0f, 1f),
        ))

        fun decode(value: String): DocumentQuad? = runCatching {
            DocumentQuad(value.split(';').map {
                val coordinates = it.split(',')
                require(coordinates.size == 2)
                DocumentPoint(coordinates[0].toFloat(), coordinates[1].toFloat())
            }).takeIf { it.isValid }
        }.getOrNull()
    }
}

/** Clockwise quarter turns of normalized upright coordinates, before preview fitting. */
object DocumentCoordinates {
    fun rotate(point: DocumentPoint, quarterTurns: Int): DocumentPoint = when ((quarterTurns % 4 + 4) % 4) {
        1 -> DocumentPoint(1f - point.y, point.x)
        2 -> DocumentPoint(1f - point.x, 1f - point.y)
        3 -> DocumentPoint(point.y, 1f - point.x)
        else -> point
    }
}

/** Suppress transient candidates and smooth only geometrically valid outlines. */
class DocumentOutlineStabilizer {
    private var previous: DocumentQuad? = null
    private var consecutive = 0
    fun update(candidate: DocumentQuad?): DocumentQuad? {
        if (candidate == null || !candidate.isValid) {
            previous = null
            consecutive = 0
            return null
        }
        val old = previous
        val close = old != null && old.points.zip(candidate.points).all { (a, b) -> hypot(a.x - b.x, a.y - b.y) < 0.08f }
        consecutive = if (close) consecutive + 1 else 1
        previous = if (close) DocumentQuad(old!!.points.zip(candidate.points).map { (a, b) ->
            DocumentPoint(a.x * 0.6f + b.x * 0.4f, a.y * 0.6f + b.y * 0.4f)
        }).takeIf { it.isValid } ?: candidate else candidate
        return previous.takeIf { consecutive >= 2 }
    }
}
