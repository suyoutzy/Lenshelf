package com.example.photocategorycamera.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.exifinterface.media.ExifInterface
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentQuad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic fixtures only: no personal documents are checked into the repository. */
@RunWith(AndroidJUnit4::class)
class DocumentProcessorDeviceTest {
    private val processor = DocumentProcessor()

    @Test fun decodingUsesRequestedEdgeWithoutUpscalingAndHonorsExifRotation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("document-size-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(4300, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        try {
            val resized = processor.loadUpright(source, 4096)
            assertEquals(4096, resized.width)
            assertTrue(resized.height > 1 && resized.height < 100)
            resized.recycle()
            val originalSize = processor.loadUpright(source, 5000)
            assertEquals(4300, originalSize.width)
            assertEquals(100, originalSize.height)
            originalSize.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val rotated = processor.loadUpright(source, 4096)
            assertEquals(4096, rotated.height)
            assertTrue(rotated.width > 1 && rotated.width < 100)
            rotated.recycle()
        } finally { source.delete() }
    }

    @Test fun perspectiveColorGrayscaleAndRotation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("document-fixture-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawRect(0f, 0f, 180f, 300f, Paint().apply { color = Color.RED })
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        try {
            val original = processor.render(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ORIGINAL, 0)
            assertEquals(399, original.width); assertEquals(299, original.height)
            assertTrue(Color.red(original.getPixel(60, 60)) > Color.green(original.getPixel(60, 60)) + 80)
            original.recycle()
            val gray = processor.render(source, DocumentQuad.FULL_IMAGE, DocumentEffect.GRAYSCALE, 1)
            assertEquals(299, gray.width); assertEquals(399, gray.height)
            val pixel = gray.getPixel(60, 60)
            assertEquals(Color.red(pixel), Color.green(pixel)); assertEquals(Color.red(pixel), Color.blue(pixel))
            gray.recycle()
            val enhanced = processor.render(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0)
            assertTrue(Color.red(enhanced.getPixel(60, 60)) > Color.green(enhanced.getPixel(60, 60)) + 60)
            enhanced.recycle()
        } finally { source.delete() }
    }

    @Test fun highContrastDocumentDetectedAndBlankFrameFallsBack() {
        val pixels = ByteArray(400 * 300) { 20 }
        for (y in 30 until 270) for (x in 40 until 360) pixels[y * 400 + x] = 240.toByte()
        val quad = processor.detectLuminance(pixels, 400, 300)
        assertNotNull(quad); assertTrue(quad!!.isValid); assertTrue(quad.area > 0.5f)
        assertNull(processor.detectLuminance(ByteArray(400 * 300) { 100 }, 400, 300))
    }
}
