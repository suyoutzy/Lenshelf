package com.example.photocategorycamera.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentCoordinates
import com.example.photocategorycamera.domain.DocumentPoint
import com.example.photocategorycamera.domain.DocumentQuad
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.ceil

/** Owns local image processing; callers run this synchronous work on a background dispatcher. */
class DocumentProcessor {
    private val initialized by lazy { OpenCVLoader.initLocal() }
    private fun requireOpenCv() { check(initialized) { "文档处理组件无法加载，请使用普通拍照" } }

    fun loadUpright(source: File, maxEdge: Int): Bitmap {
        require(maxEdge > 1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "文档原图不存在或无法读取" }
        var sample = 1
        val sourceEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (sourceEdge / (sample * 2) >= maxEdge) sample *= 2
        val sampledEdge = ceil(sourceEdge.toDouble() / sample).toInt()
        val decoded = checkNotNull(BitmapFactory.decodeFile(source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // Decode to the requested edge instead of rounding down to half its resolution.
                inScaled = true
                inDensity = sampledEdge
                inTargetDensity = minOf(maxEdge, sampledEdge)
            })) {
            "无法解码文档原图"
        }
        val exif = ExifInterface(source)
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
            postScale(scale, scale)
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (upright !== decoded) decoded.recycle()
        return upright
    }

    fun detect(source: File): DocumentQuad? {
        requireOpenCv()
        val bitmap = loadUpright(source, 1280)
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return detectGray(gray)
        } finally { bitmap.recycle(); rgba.release(); gray.release() }
    }

    fun detectLuminance(pixels: ByteArray, width: Int, height: Int): DocumentQuad? {
        requireOpenCv()
        val gray = Mat(height, width, CvType.CV_8UC1)
        try { gray.put(0, 0, pixels); return detectGray(gray) }
        finally { gray.release() }
    }

    private fun detectGray(gray: Mat): DocumentQuad? {
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 45.0, 130.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            try { Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel) }
            finally { kernel.release() }
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            var best: DocumentQuad? = null
            for (contour in contours) {
                if (Imgproc.contourArea(contour) < gray.total() * 0.15) continue
                val curve = MatOfPoint2f(*contour.toArray())
                val polygon = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(curve, polygon, Imgproc.arcLength(curve, true) * 0.025, true)
                    val vertices = polygon.toArray()
                    if (vertices.size != 4) continue
                    val cx = vertices.sumOf { it.x } / 4
                    val cy = vertices.sumOf { it.y } / 4
                    val ordered = vertices.sortedBy { atan2(it.y - cy, it.x - cx) }
                    val start = ordered.indices.minBy { ordered[it].x + ordered[it].y }
                    val quad = DocumentQuad((0..3).map {
                        val p = ordered[(start + it) % 4]
                        DocumentPoint((p.x / (gray.cols() - 1)).toFloat().coerceIn(0f, 1f),
                            (p.y / (gray.rows() - 1)).toFloat().coerceIn(0f, 1f))
                    })
                    if (quad.isValid && quad.area > (best?.area ?: 0f)) best = quad
                } finally { curve.release(); polygon.release() }
            }
            return best
        } finally {
            blurred.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    fun render(source: File, quad: DocumentQuad, effect: DocumentEffect, quarterTurns: Int, maxEdge: Int = 4096): Bitmap {
        requireOpenCv()
        require(quad.isValid) { "文档四角无效，请重新调整" }
        val bitmap = loadUpright(source, maxEdge)
        val rgba = Mat()
        val warped = Mat()
        var transform: Mat? = null
        val from = MatOfPoint2f(*quad.points.map { Point((it.x * (bitmap.width - 1)).toDouble(), (it.y * (bitmap.height - 1)).toDouble()) }.toTypedArray())
        val (cropWidth, cropHeight) = quad.outputSize(bitmap.width, bitmap.height, maxEdge)
        val turns = (quarterTurns % 4 + 4) % 4
        val width = if (turns % 2 == 0) cropWidth else cropHeight
        val height = if (turns % 2 == 0) cropHeight else cropWidth
        val to = MatOfPoint2f(*DocumentQuad.FULL_IMAGE.points.map {
            val point = DocumentCoordinates.rotate(it, turns)
            Point((point.x * (width - 1)).toDouble(), (point.y * (height - 1)).toDouble())
        }.toTypedArray())
        try {
            Utils.bitmapToMat(bitmap, rgba)
            bitmap.recycle()
            transform = Imgproc.getPerspectiveTransform(from, to)
            Imgproc.warpPerspective(rgba, warped, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            rgba.release()
            applyEffect(warped, effect)
            val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, result)
            return result
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            rgba.release(); warped.release(); from.release(); to.release(); transform?.release()
        }
    }

    private fun applyEffect(rgba: Mat, effect: DocumentEffect) {
        if (effect == DocumentEffect.ORIGINAL) return
        val rgb = Mat()
        val ycc = Mat()
        val blur = Mat()
        val channels = mutableListOf<Mat>()
        try {
            if (effect == DocumentEffect.GRAYSCALE) {
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_GRAY2RGBA)
            } else {
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
                Core.split(ycc, channels)
                val clahe = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
                try { clahe.apply(channels[0], channels[0]) }
                finally { clahe.collectGarbage(); clahe.clear() }
                Core.merge(channels, ycc)
                Imgproc.cvtColor(ycc, rgb, Imgproc.COLOR_YCrCb2RGB)
                channels.forEach { it.release() }; channels.clear(); ycc.release()
                Imgproc.GaussianBlur(rgb, blur, Size(3.0, 3.0), 0.7)
                Core.addWeighted(rgb, 1.15, blur, -0.15, 0.0, rgb)
                Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
            }
        } finally { rgb.release(); ycc.release(); blur.release(); channels.forEach { it.release() } }
    }

    fun writeResult(source: File, quad: DocumentQuad, effect: DocumentEffect, quarterTurns: Int, output: File) {
        val bitmap = render(source, quad, effect, quarterTurns)
        val part = File(output.parentFile, output.name + ".part")
        try {
            FileOutputStream(part).use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "无法生成文档图片" }
                it.flush()
                it.fd.sync()
            }
            check(part.renameTo(output)) { "无法完成文档图片写入" }
        } finally { bitmap.recycle(); if (part.exists()) part.delete() }
    }
}
