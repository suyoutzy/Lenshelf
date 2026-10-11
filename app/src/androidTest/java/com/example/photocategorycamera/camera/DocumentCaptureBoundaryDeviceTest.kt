package com.example.photocategorycamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import androidx.camera.view.transform.OutputTransform
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.photocategorycamera.document.DocumentAnalysisResult
import com.example.photocategorycamera.domain.DocumentCaptureGeometry
import com.example.photocategorycamera.domain.DocumentCoordinates
import com.example.photocategorycamera.domain.DocumentPoint
import com.example.photocategorycamera.domain.DocumentQuad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
@RunWith(AndroidJUnit4::class)
class DocumentCaptureBoundaryDeviceTest {
    @Test fun shutterProjectionDoesNotChangeWhenTheLivePreviewMatrixChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("projection-frozen-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        val mutableMatrix = Matrix().apply {
            setRectToRect(RectF(-1f, -1f, 1f, 1f), RectF(0f, 0f, 600f, 800f), Matrix.ScaleToFit.FILL)
        }
        val frame = OutputTransform(Matrix().apply {
            setRectToRect(RectF(-1f, -1f, 1f, 1f), RectF(0f, 0f, 800f, 600f), Matrix.ScaleToFit.FILL)
        }, Size(800, 600))
        val projection = DocumentCaptureProjection(frame, OutputTransform(mutableMatrix, Size(600, 800)), 600, 800)
        try {
            val expected = projection.project(file)!!
            mutableMatrix.postScale(1.5f, 1.5f)
            mutableMatrix.postTranslate(90f, 50f)
            assertArrayEquals(expected, projection.project(file), 0.0001f)
        } finally { file.delete() }
    }

    @Test fun analysisCropMapsToJpegForAllExifRotationsAndMirrors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("boundary-transform-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        val quad = DocumentQuad(listOf(DocumentPoint(0.1f, 0.2f), DocumentPoint(0.8f, 0.15f),
            DocumentPoint(0.9f, 0.7f), DocumentPoint(0.2f, 0.85f)))
        val transform = OutputTransform(Matrix().apply {
            setRectToRect(RectF(-1f, -1f, 1f, 1f), RectF(0f, 0f, 640f, 480f), Matrix.ScaleToFit.FILL)
        }, Size(640, 480))
        // Input coordinates are relative to crop origin, even when the actual buffer crop is offset.
        val boundary = DocumentCaptureBoundary(DocumentAnalysisResult(quad, Rect(80, 40, 720, 520), transform, 1000))
        try {
            for (orientation in 1..8) {
                val exif = ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val expectedPoints = quad.points.map {
                    DocumentCoordinates.rotate(if (exif.isFlipped) DocumentPoint(1f - it.x, it.y) else it, exif.rotationDegrees / 90)
                }
                val expected = DocumentCaptureGeometry.normalizedQuad(expectedPoints.flatMap { listOf(it.x * 800, it.y * 600) }.toFloatArray(), 800, 600)!!
                val actual = boundary.mapToUpright(file)
                assertNotNull("EXIF $orientation", actual)
                expected.points.zip(actual!!.points).forEach { (a, b) ->
                    assertEquals("EXIF $orientation x", a.x, b.x, 0.002f)
                    assertEquals("EXIF $orientation y", a.y, b.y, 0.002f)
                }
            }
        } finally { file.delete() }
    }
}
