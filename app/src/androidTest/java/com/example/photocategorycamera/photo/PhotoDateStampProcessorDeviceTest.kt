package com.example.photocategorycamera.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.photocategorycamera.document.DocumentProcessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoDateStampProcessorDeviceTest {
    @Test fun stampsTheDisplayedBottomRightForEveryExifOrientationWithoutResizingOrChangingSource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (orientation in 1..8) {
            val source = File.createTempFile("stamp-source-", ".jpg", context.cacheDir)
            val output = File(context.cacheDir, source.name + "-result.jpg")
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(24, 40, 64))
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:28 14:35:00")
                setAttribute(ExifInterface.TAG_MAKE, "LenshelfFixture")
                saveAttributes()
            }
            val original = source.readBytes()
            try {
                PhotoDateStampProcessor().writeResult(source, "2026-10-28 14:35", output)
                assertArrayEquals(original, source.readBytes())
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.path, bounds)
                assertEquals(800, bounds.outWidth); assertEquals(600, bounds.outHeight)
                val exif = ExifInterface(output)
                assertEquals(orientation, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                assertEquals("2026:10:28 14:35:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals("LenshelfFixture", exif.getAttribute(ExifInterface.TAG_MAKE))
                val upright = DocumentProcessor().loadUpright(output, 1000)
                try {
                    var bright = 0
                    for (y in 0 until upright.height) for (x in 0 until upright.width) {
                        val pixel = upright.getPixel(x, y)
                        if (Color.red(pixel) > 150 && Color.green(pixel) > 150 && Color.blue(pixel) > 150) {
                            assertTrue("orientation=$orientation x=$x", x > upright.width * 0.5)
                            assertTrue("orientation=$orientation y=$y", y > upright.height * 0.85)
                            bright++
                        }
                    }
                    assertTrue("No readable stamp for orientation=$orientation", bright > 50)
                } finally { upright.recycle() }
                assertFalse(File(output.path + ".part").exists())
                if (orientation == 1 && androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("exportReviewSamples") == "true") {
                    val directory = File(context.filesDir, "v09-review").apply { mkdirs() }
                    output.copyTo(File(directory, "photo-date-stamp.jpg"), overwrite = true)
                }
            } finally { source.delete(); output.delete() }
        }
    }
}
