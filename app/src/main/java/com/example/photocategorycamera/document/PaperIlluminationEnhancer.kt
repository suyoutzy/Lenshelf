package com.example.photocategorycamera.document

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.roundToInt

/** Estimate paper illumination at low resolution; preserve ink/chroma and bound shadow amplification. */
internal object PaperIlluminationEnhancer {
    fun apply(rgba: Mat) {
        val rgb = Mat(); val lab = Mat(); val small = Mat(); val luminance = Mat(); val background = Mat()
        var kernel: Mat? = null
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            rgb.release()
            val scale = minOf(1.0, 512.0 / maxOf(lab.cols(), lab.rows()))
            Imgproc.resize(lab, small, Size(maxOf(2.0, (lab.cols() * scale).roundToInt().toDouble()),
                maxOf(2.0, (lab.rows() * scale).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
            val sample = ByteArray(small.rows() * small.cols() * 3)
            small.get(0, 0, sample)
            var neutral = 0; var bright = 0
            val histogram = IntArray(256)
            for (i in sample.indices step 3) {
                val chroma = maxOf(abs((sample[i + 1].toInt() and 255) - 128), abs((sample[i + 2].toInt() and 255) - 128))
                if (chroma < 24) {
                    neutral++
                    histogram[sample[i].toInt() and 255]++
                    if ((sample[i].toInt() and 255) > 150) bright++
                }
            }
            val count = small.rows() * small.cols()
            // A photograph, saturated paper or dark slide is not assumed to be a white document.
            if (neutral < count * .60 || bright < count * .20) return
            org.opencv.core.Core.extractChannel(small, luminance, 0)
            val side = minOf(small.rows(), small.cols())
            val diameter = maxOf(15, (side / 20) or 1)
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(diameter.toDouble(), diameter.toDouble()))
            Imgproc.morphologyEx(luminance, background, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.GaussianBlur(background, background, Size(0.0, 0.0), maxOf(2.0, side * .02))
            var accumulated = 0
            val reference = histogram.indices.first { value ->
                accumulated += histogram[value]
                accumulated >= neutral * .9
            }
            // An enclosed gray panel can be printed content, not a shadow. Preserve it instead
            // of normalizing its gray levels into white. Broad edge-to-edge lighting still qualifies.
            if (containsAmbiguousPanel(background, reference - 25.0, diameter)) return
            val field = ByteArray(background.rows() * background.cols())
            background.get(0, 0, field)
            val sw = background.cols(); val sh = background.rows()
            val row = ByteArray(lab.cols() * 3)
            for (y in 0 until lab.rows()) {
                lab.get(y, 0, row)
                val sy = y.toFloat() * (sh - 1) / (lab.rows() - 1).coerceAtLeast(1)
                val y0 = sy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val fy = sy - y0
                for (x in 0 until lab.cols()) {
                    val sx = x.toFloat() * (sw - 1) / (lab.cols() - 1).coerceAtLeast(1)
                    val x0 = sx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val fx = sx - x0
                    fun at(xx: Int, yy: Int) = field[yy * sw + xx].toInt() and 255
                    val top = at(x0, y0) * (1f - fx) + at(x1, y0) * fx
                    val bottom = at(x0, y1) * (1f - fx) + at(x1, y1) * fx
                    val localIllumination = top * (1f - fy) + bottom * fy
                    val tx = sx / (sw - 1); val ty = sy / (sh - 1)
                    val north = at(x0, 0) * (1f - fx) + at(x1, 0) * fx
                    val south = at(x0, sh - 1) * (1f - fx) + at(x1, sh - 1) * fx
                    val west = at(0, y0) * (1f - fy) + at(0, y1) * fy
                    val east = at(sw - 1, y0) * (1f - fy) + at(sw - 1, y1) * fy
                    // Printed blocks can join an edge shadow in the threshold mask. Opposite
                    // borders anchor illumination so the block cannot become its own white level.
                    val borderIllumination = minOf(north * (1f - ty) + south * ty, west * (1f - tx) + east * tx)
                    val illumination = maxOf(localIllumination, borderIllumination, 1f)
                    val i = x * 3
                    val chroma = maxOf(abs((row[i + 1].toInt() and 255) - 128), abs((row[i + 2].toInt() and 255) - 128))
                    val weight = ((48f - chroma) / 24f).coerceIn(0f, 1f)
                    val gain = (245f / illumination).coerceIn(1f, 1.8f)
                    row[i] = ((row[i].toInt() and 255) * (1f + (gain - 1f) * weight)).roundToInt().coerceIn(0, 250).toByte()
                }
                lab.put(y, 0, row)
            }
            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
            Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        } finally {
            rgb.release(); lab.release(); small.release(); luminance.release(); background.release(); kernel?.release()
        }
    }

    private fun containsAmbiguousPanel(background: Mat, threshold: Double, minimumSide: Int): Boolean {
        val mask = Mat(); val hierarchy = Mat(); val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.threshold(background, mask, threshold, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val minimumArea = background.rows() * background.cols() * .01
            return contours.any { contour ->
                val bounds = Imgproc.boundingRect(contour)
                bounds.x > 1 && bounds.y > 1 && bounds.x + bounds.width < background.cols() - 1 &&
                    bounds.y + bounds.height < background.rows() - 1 && bounds.width >= minimumSide &&
                    bounds.height >= minimumSide && Imgproc.contourArea(contour) >= minimumArea
            }
        } finally { mask.release(); hierarchy.release(); contours.forEach { it.release() } }
    }
}
