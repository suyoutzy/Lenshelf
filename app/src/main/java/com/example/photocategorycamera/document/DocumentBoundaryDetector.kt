package com.example.photocategorycamera.document

import com.example.photocategorycamera.domain.DocumentPoint
import com.example.photocategorycamera.domain.DocumentQuad
import com.example.photocategorycamera.domain.DocumentPageSelection
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Edge candidates plus bright-region silhouettes, including rounded, ruled notebook pages. */
internal class DocumentBoundaryDetector {
    private data class Candidate(val quad: DocumentQuad, val score: Float)

    fun detect(gray: Mat, preferred: DocumentQuad? = null): DocumentQuad? {
        val blur = Mat()
        val soft = Mat()
        val mask = Mat()
        val pixels = ByteArray(gray.rows() * gray.cols())
        gray.get(0, 0, pixels)
        val found = mutableListOf<Candidate>()
        fun inspect(binary: Mat) {
            found += candidates(binary, gray.cols(), gray.rows(), pixels)
        }
        try {
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
            for ((low, high, kernelSize) in listOf(Triple(45.0, 130.0, 3), Triple(20.0, 65.0, 5))) {
                Imgproc.Canny(blur, mask, low, high)
                close(mask, kernelSize)
                inspect(mask)
            }
            // Smooth ink and ruled lines before looking for the connected paper silhouette.
            Imgproc.GaussianBlur(gray, soft, Size(15.0, 15.0), 0.0)
            val threshold = Imgproc.threshold(soft, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            for (offset in listOf(0.0, 20.0, 40.0, 60.0)) {
                Imgproc.threshold(soft, mask, (threshold + offset).coerceAtMost(250.0), 255.0, Imgproc.THRESH_BINARY)
                close(mask, 15)
                inspect(mask)
            }
            return DocumentPageSelection.select(found, preferred, { it.quad }, { it.score })?.quad
        } finally { blur.release(); soft.release(); mask.release() }
    }

    private fun close(binary: Mat, size: Int) {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size.toDouble(), size.toDouble()))
        try { Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel) }
        finally { kernel.release() }
    }

    private fun candidates(binary: Mat, width: Int, height: Int, pixels: ByteArray): List<Candidate> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        val results = mutableListOf<Candidate>()
        try {
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < width * height * 0.15) continue
                val indices = MatOfInt()
                val polygon = MatOfPoint2f()
                var hull: MatOfPoint? = null
                var curve: MatOfPoint2f? = null
                try {
                    Imgproc.convexHull(contour, indices)
                    val original = contour.toArray()
                    val points = indices.toArray().map { original[it] }.toTypedArray()
                    if (points.size < 4) continue
                    hull = MatOfPoint(*points)
                    val hullArea = Imgproc.contourArea(hull)
                    val solidity = (area / hullArea).toFloat()
                    // Connected desks/backgrounds with deep concavities are not a single page.
                    if (!solidity.isFinite() || solidity < 0.9f) continue
                    curve = MatOfPoint2f(*points)
                    val perimeter = Imgproc.arcLength(curve, true)
                    for (epsilon in listOf(0.015, 0.025, 0.04, 0.06)) {
                        Imgproc.approxPolyDP(curve, polygon, perimeter * epsilon, true)
                        candidate(polygon.toArray(), width, height, pixels, solidity)?.let(results::add)
                    }
                    // Fits four enclosing sides instead of mistaking rounded corners for extra edges.
                    Imgproc.approxPolyN(curve, polygon, 4, 0.08f, true)
                    candidate(polygon.toArray(), width, height, pixels, solidity)?.let(results::add)
                } finally { indices.release(); polygon.release(); hull?.release(); curve?.release() }
            }
            return results
        } finally { hierarchy.release(); contours.forEach { it.release() } }
    }

    private fun candidate(vertices: Array<Point>, width: Int, height: Int, pixels: ByteArray, solidity: Float): Candidate? {
        if (vertices.size != 4) return null
        if (vertices.any { !it.x.isFinite() || !it.y.isFinite() || it.x < -2 || it.y < -2 || it.x > width + 1 || it.y > height + 1 }) return null
        val cx = vertices.sumOf { it.x } / 4
        val cy = vertices.sumOf { it.y } / 4
        val ordered = vertices.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = ordered.indices.minBy { ordered[it].x + ordered[it].y }
        val quad = DocumentQuad((0..3).map {
            val p = ordered[(start + it) % 4]
            DocumentPoint((p.x / (width - 1)).toFloat().coerceIn(0f, 1f), (p.y / (height - 1)).toFloat().coerceIn(0f, 1f))
        })
        if (!quad.isValid || quad.area !in 0.15f..0.96f) return null
        // A rasterized triangle can contain a tiny fourth side; it is not a page corner.
        if ((0..3).any { index ->
            val a = quad.points[index]; val b = quad.points[(index + 1) % 4]
            hypot((b.x - a.x) * (width - 1), (b.y - a.y) * (height - 1)) < minOf(width, height) * 0.06f
        }) return null
        // A frame-spanning bright desk must not win over the notebook lying on it.
        if (quad.points.count { it.x < 0.015f || it.x > 0.985f || it.y < 0.015f || it.y > 0.985f } >= 2) return null
        val contrast = boundaryContrast(quad, width, height, pixels)
        if (contrast < 5f) return null
        val score = DocumentPageSelection.centrality(quad) * (0.5f + (contrast / 50f).coerceAtMost(1f)) * solidity
        return Candidate(quad, score)
    }

    private fun boundaryContrast(quad: DocumentQuad, width: Int, height: Int, pixels: ByteArray): Float {
        val points = quad.points.map { DocumentPoint(it.x * (width - 1), it.y * (height - 1)) }
        val center = DocumentPoint(points.sumOf { it.x.toDouble() }.toFloat() / 4, points.sumOf { it.y.toDouble() }.toFloat() / 4)
        val distance = maxOf(3f, minOf(width, height) * 0.012f)
        var sum = 0f
        var edges = 0
        fun sample(x: Float, y: Float): Int? {
            val ix = x.roundToInt()
            val iy = y.roundToInt()
            return if (ix in 0 until width && iy in 0 until height) pixels[iy * width + ix].toInt() and 255 else null
        }
        for (index in 0..3) {
            val a = points[index]
            val b = points[(index + 1) % 4]
            val nx = center.x - (a.x + b.x) / 2
            val ny = center.y - (a.y + b.y) / 2
            val length = hypot(nx, ny).coerceAtLeast(0.001f)
            val dx = nx / length * distance
            val dy = ny / length * distance
            var inside = 0f; var outside = 0f; var count = 0
            for (step in 0..11) {
                val t = 0.1f + 0.8f * step / 11
                val x = a.x + (b.x - a.x) * t
                val y = a.y + (b.y - a.y) * t
                val inner = sample(x + dx, y + dy)
                val outer = sample(x - dx, y - dy)
                if (inner != null && outer != null) { inside += inner; outside += outer; count++ }
            }
            // Dark slides/boards on a brighter wall are also legitimate documents.
            if (count > 0) { sum += abs((inside - outside) / count); edges++ }
        }
        return if (edges == 0) 0f else sum / edges
    }
}
