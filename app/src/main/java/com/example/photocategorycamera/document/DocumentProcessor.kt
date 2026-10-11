package com.example.photocategorycamera.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentCoordinates
import com.example.photocategorycamera.domain.DocumentQuad
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Owns local image processing; callers run this synchronous work on a background dispatcher. */
class DocumentProcessor {
    private val initialized by lazy { OpenCVLoader.initLocal() }
    private val detector = DocumentBoundaryDetector()
    private val lineDetector = DocumentLineDetector()
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
            return detectFrame(gray, rgba, null)
        } finally { bitmap.recycle(); rgba.release(); gray.release() }
    }

    fun detectLuminance(pixels: ByteArray, width: Int, height: Int): DocumentQuad? {
        requireOpenCv()
        val gray = Mat(height, width, CvType.CV_8UC1)
        try { gray.put(0, 0, pixels); return detectFrame(gray, null, null) }
        finally { gray.release() }
    }

    fun detectRgb(pixels: ByteArray, width: Int, height: Int, preferred: DocumentQuad? = null): DocumentQuad? {
        requireOpenCv()
        val rgb = Mat(height, width, CvType.CV_8UC3)
        val gray = Mat()
        try {
            rgb.put(0, 0, pixels)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            return detectFrame(gray, rgb, preferred)
        } finally { rgb.release(); gray.release() }
    }

    private fun reduced(input: Mat, edge: Int, output: Mat) {
        val scale = minOf(1.0, edge.toDouble() / maxOf(input.cols(), input.rows()))
        Imgproc.resize(input, output, Size((input.cols() * scale).roundToInt().toDouble(),
            (input.rows() * scale).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
    }

    private fun detectFrame(gray: Mat, colour: Mat?, preferred: DocumentQuad?): DocumentQuad? {
        val smaller = Mat()
        val channel = Mat()
        try {
            // Preview and JPEG both start at the same scale; smoothing avoids aliases from ruled lines.
            for (edge in listOf(640, 960, 1280)) {
                if (edge != 640 && edge > maxOf(gray.cols(), gray.rows())) continue
                reduced(gray, edge, smaller)
                detector.detect(smaller, preferred)?.let { return lineDetector.centralPage(smaller, it, preferred) }
            }
            // Equal luminance does not imply equal colour: inspect R/G/B before giving up a page.
            if (colour != null) for (index in 0..2) {
                Core.extractChannel(colour, channel, index)
                reduced(channel, 640, smaller)
                detector.detect(smaller, preferred)?.let { return lineDetector.centralPage(smaller, it, preferred) }
            }
            reduced(gray, 640, smaller)
            return lineDetector.detect(smaller, preferred)
        } finally { smaller.release(); channel.release() }
    }

    /** Keep the shutter's page identity; never substitute an unrelated page if that box has moved. */
    fun resolveCaptureQuad(source: File, frozen: DocumentQuad?): DocumentQuad {
        if (frozen == null || frozen == DocumentQuad.FULL_IMAGE || !frozen.isValid) return detect(source) ?: DocumentQuad.FULL_IMAGE
        requireOpenCv()
        val bitmap = loadUpright(source, 640)
        val rgba = Mat()
        val channel = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, channel, Imgproc.COLOR_RGBA2GRAY)
            if (lineDetector.supports(channel, frozen)) return frozen
            for (index in 0..2) {
                Core.extractChannel(rgba, channel, index)
                if (lineDetector.supports(channel, frozen)) return frozen
            }
            return DocumentQuad.FULL_IMAGE
        } finally { bitmap.recycle(); rgba.release(); channel.release() }
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

    fun capturePreview(id: String, source: File, output: File, quad: DocumentQuad): DocumentCapturePreview {
        val original = loadUpright(source, 1280)
        try { return DocumentCapturePreview(id, original, loadUpright(output, 1280), quad) }
        catch (error: Exception) { original.recycle(); throw error }
    }
}
