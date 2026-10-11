package com.example.photocategorycamera.camera

import android.graphics.BitmapFactory
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.FileTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.exifinterface.media.ExifInterface
import com.example.photocategorycamera.document.DocumentAnalysisResult
import com.example.photocategorycamera.domain.DocumentCaptureGeometry
import com.example.photocategorycamera.domain.DocumentQuad
import java.io.File

/** An immutable analysis-frame snapshot, never the mutable on-screen preview coordinates. */
@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
internal data class DocumentCaptureBoundary(val analysis: DocumentAnalysisResult) {
    fun mapToUpright(file: File): DocumentQuad? = runCatching {
        val quad = analysis.quad ?: return@runCatching null
        val points = quad.cropCoordinates(analysis.crop.width(), analysis.crop.height())
        val target = FileTransformFactory().apply { isUsingExifOrientation = true }.getOutputTransform(file)
        CoordinateTransform(analysis.transform, target).mapPoints(points)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rotated = ExifInterface(file).rotationDegrees % 180 != 0
        DocumentCaptureGeometry.normalizedQuad(points,
            if (rotated) bounds.outHeight else bounds.outWidth,
            if (rotated) bounds.outWidth else bounds.outHeight)
    }.getOrNull()
}

/** The view transform is copied at the shutter, not read after JPEG delivery or another layout. */
@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
internal class DocumentCaptureProjection(
    private val frame: OutputTransform,
    preview: OutputTransform,
    private val viewWidth: Int,
    private val viewHeight: Int,
) {
    // CoordinateTransform copies the mapping internally. No restricted OutputTransform constructor/getters.
    private val frameToPreview = CoordinateTransform(frame, preview)

    fun project(file: File): FloatArray? = runCatching {
        check(viewWidth > 0 && viewHeight > 0)
        val source = FileTransformFactory().apply { isUsingExifOrientation = true }.getOutputTransform(file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rotated = ExifInterface(file).rotationDegrees % 180 != 0
        val width = if (rotated) bounds.outHeight else bounds.outWidth
        val height = if (rotated) bounds.outWidth else bounds.outHeight
        check(width > 0 && height > 0)
        val points = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        CoordinateTransform(source, frame).mapPoints(points)
        frameToPreview.mapPoints(points)
        points.indices.forEach { points[it] /= if (it % 2 == 0) viewWidth else viewHeight }
        points
    }.getOrNull()
}
