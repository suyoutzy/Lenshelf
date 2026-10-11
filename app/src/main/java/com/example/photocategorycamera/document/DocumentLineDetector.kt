package com.example.photocategorycamera.document

import com.example.photocategorycamera.domain.DocumentCaptureGeometry
import com.example.photocategorycamera.domain.DocumentPageSelection
import com.example.photocategorycamera.domain.DocumentPoint
import com.example.photocategorycamera.domain.DocumentQuad
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Fallback for an open notebook: three outer edges and a weaker crease need not form a closed contour. */
internal class DocumentLineDetector {
    private data class Line(val x: Float, val y: Float, val dx: Float, val dy: Float, val score: Float) {
        fun intersect(other: Line): Pair<Float, Float>? {
            val cross = dx * other.dy - dy * other.dx
            if (abs(cross) < 0.001f) return null
            val t = ((other.x - x) * other.dy - (other.y - y) * other.dx) / cross
            return x + t * dx to y + t * dy
        }
    }

    private data class Edge(val step: Float, val ridge: Float, val ridgeCoverage: Float = 0f) {
        val strength: Float get() = maxOf(step, ridge)
    }

    /** Split only a unique, long central crease; repeated ruling must never split a single page. */
    fun centralPage(gray: Mat, outer: DocumentQuad, preferred: DocumentQuad?): DocumentQuad {
        fun length(a: Int, b: Int) = hypot((outer.points[a].x - outer.points[b].x) * gray.cols(),
            (outer.points[a].y - outer.points[b].y) * gray.rows())
        val width = (length(0, 1) + length(3, 2)) / 2
        val height = (length(0, 3) + length(1, 2)) / 2
        if (maxOf(width, height) / minOf(width, height) < 1.15f) return outer
        val pixels = ByteArray(gray.rows() * gray.cols())
        gray.get(0, 0, pixels)
        fun interpolate(a: Int, b: Int, t: Float): DocumentPoint {
            val p = outer.points[a]; val q = outer.points[b]
            return DocumentPoint(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
        }
        val vertical = width > height
        fun cut(t: Float) = if (vertical) interpolate(0, 1, t) to interpolate(3, 2, t)
            else interpolate(0, 3, t) to interpolate(1, 2, t)
        val ridges = mutableListOf<Pair<Int, Float>>()
        for (index in 0..100) {
            val t = 0.25f + index * 0.005f
            val (a, b) = cut(t)
            val evidence = edge(pixels, gray.cols(), gray.rows(), a.x * (gray.cols() - 1), a.y * (gray.rows() - 1),
                b.x * (gray.cols() - 1), b.y * (gray.rows() - 1))
            if (evidence.ridge > 8f && evidence.ridgeCoverage >= 0.8f && evidence.step < 25f)
                ridges.add(index to evidence.ridge)
        }
        if (ridges.isEmpty()) return outer
        // A ruled page or grid produces several separate parallel dark ridges, unlike a book crease.
        if (ridges.zipWithNext().count { (a, b) -> b.first - a.first > 3 } > 0) return outer
        val t = 0.25f + ridges.maxBy { it.second }.first * 0.005f
        val (a, b) = cut(t)
        val p = outer.points
        val pages = if (vertical) listOf(DocumentQuad(listOf(p[0], a, b, p[3])), DocumentQuad(listOf(a, p[1], p[2], b)))
            else listOf(DocumentQuad(listOf(p[0], p[1], b, a)), DocumentQuad(listOf(a, b, p[2], p[3])))
        val crediblePages = pages.filter { it.isValid && it.area >= 0.15f && supports(gray, it) }
        // Uncertain crease geometry must not silently become an arbitrary half-page crop.
        return DocumentPageSelection.select(crediblePages, preferred, { it }, DocumentPageSelection::centrality) ?: outer
    }

    fun detect(gray: Mat, preferred: DocumentQuad? = null): DocumentQuad? {
        val blur = Mat()
        val segments = Mat()
        val detector = Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD)
        val pixels = ByteArray(gray.rows() * gray.cols())
        gray.get(0, 0, pixels)
        try {
            Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)
            detector.detect(blur, segments)
            val horizontal = mutableListOf<Line>()
            val vertical = mutableListOf<Line>()
            for (row in 0 until segments.rows()) {
                val values = segments.get(row, 0) ?: continue
                val x = values[0].toFloat(); val y = values[1].toFloat()
                val dx = (values[2] - values[0]).toFloat(); val dy = (values[3] - values[1]).toFloat()
                val length = hypot(dx, dy)
                if (length < minOf(gray.cols(), gray.rows()) * 0.08f) continue
                val strength = edge(pixels, gray.cols(), gray.rows(), x, y, x + dx, y + dy).strength
                if (strength < 2f) continue
                val line = Line(x, y, dx, dy, length * (0.1f + (strength / 40f).coerceAtMost(1f)))
                when {
                    abs(dx) > abs(dy) * 2 -> horizontal.add(line)
                    abs(dy) > abs(dx) * 2 -> vertical.add(line)
                }
            }
            // Bound combinatorial work, independent of how much handwriting the frame contains.
            val h = horizontal.sortedByDescending { it.score }.take(12)
            val v = vertical.sortedByDescending { it.score }.take(12)
            val candidates = mutableListOf<Pair<DocumentQuad, Float>>()
            for (a in h.indices) for (b in a + 1 until h.size)
                for (c in v.indices) for (d in c + 1 until v.size) {
                    val corners = listOf(h[a].intersect(v[c]), h[a].intersect(v[d]), h[b].intersect(v[d]), h[b].intersect(v[c]))
                    if (corners.any { it == null }) continue
                    val quad = DocumentCaptureGeometry.normalizedQuad(corners.flatMap { listOf(it!!.first, it.second) }.toFloatArray(),
                        gray.cols() - 1, gray.rows() - 1) ?: continue
                    if (quad.area !in 0.15f..0.95f || quad.points.any { it.x !in 0.01f..0.99f || it.y !in 0.01f..0.99f }) continue
                    val evidence = evidence(gray.cols(), gray.rows(), pixels, quad)
                    // Ruled lines alone and bright desks do not provide three genuine outside edges.
                    if (!credible(evidence)) continue
                    val score = DocumentPageSelection.centrality(quad) *
                        (0.5f + (evidence.map { it.strength }.average().toFloat() / 50f).coerceAtMost(1f))
                    candidates.add(quad to score)
                }
            return DocumentPageSelection.select(candidates, preferred, { it.first }, { it.second })?.first
        } finally { detector.clear(); blur.release(); segments.release() }
    }

    /** Check the very same crop against the exposure JPEG, not another independently selected page. */
    fun supports(gray: Mat, quad: DocumentQuad): Boolean {
        if (!quad.isValid || quad == DocumentQuad.FULL_IMAGE) return false
        val pixels = ByteArray(gray.rows() * gray.cols())
        gray.get(0, 0, pixels)
        return credible(evidence(gray.cols(), gray.rows(), pixels, quad))
    }

    private fun credible(edges: List<Edge>): Boolean =
        edges.count { it.step > 4f } >= 3 && edges.all { it.strength >= 2f } && edges.map { it.strength }.average() >= 5

    private fun evidence(width: Int, height: Int, pixels: ByteArray, quad: DocumentQuad): List<Edge> =
        (0..3).map { index ->
            val a = quad.points[index]; val b = quad.points[(index + 1) % 4]
            edge(pixels, width, height, a.x * (width - 1), a.y * (height - 1), b.x * (width - 1), b.y * (height - 1))
        }

    private fun edge(pixels: ByteArray, width: Int, height: Int, x1: Float, y1: Float, x2: Float, y2: Float): Edge {
        val dx = x2 - x1; val dy = y2 - y1
        val length = hypot(dx, dy).coerceAtLeast(0.001f)
        val offset = maxOf(3f, minOf(width, height) * 0.0104f)
        val nx = -dy / length * offset; val ny = dx / length * offset
        fun sample(x: Float, y: Float): Int? {
            val ix = x.roundToInt(); val iy = y.roundToInt()
            return if (ix in 0 until width && iy in 0 until height) pixels[iy * width + ix].toInt() and 255 else null
        }
        var stepSum = 0f; var ridgeSum = 0f; var count = 0; var ridgeCount = 0
        for (i in 0 until 20) {
            val t = 0.12f + 0.76f * i / 19
            val x = x1 + t * dx; val y = y1 + t * dy
            val a = sample(x + nx, y + ny) ?: continue
            val b = sample(x - nx, y - ny) ?: continue
            val center = sample(x, y) ?: continue
            val ridge = (a + b) / 2f - center
            stepSum += a - b; ridgeSum += ridge; count++
            if (ridge > 8f) ridgeCount++
        }
        return if (count >= 12) Edge(abs(stepSum / count), (ridgeSum / count).coerceAtLeast(0f), ridgeCount.toFloat() / count) else Edge(0f, 0f)
    }
}
