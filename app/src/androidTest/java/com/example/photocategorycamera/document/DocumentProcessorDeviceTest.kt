package com.example.photocategorycamera.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.exifinterface.media.ExifInterface
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentQuad
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

/** Synthetic fixtures only: no personal documents are checked into the repository. */
@RunWith(AndroidJUnit4::class)
class DocumentProcessorDeviceTest {
    private val processor = DocumentProcessor()

    /** Optional local regression inputs; no personal images or paths are bundled in the APK. */
    @Test fun suppliedNotebookPhotosProduceCroppedResults() {
        val sources = InstrumentationRegistry.getArguments().getString("notebookSourceUris")
        assumeTrue("No local notebook regression inputs supplied", !sources.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (uri in sources!!.split('|')) {
            val source = File.createTempFile("notebook-regression-", ".jpg", context.cacheDir)
            try {
                context.contentResolver.openInputStream(Uri.parse(uri)).use { input ->
                    source.outputStream().use { output -> checkNotNull(input).copyTo(output) }
                }
                val quad = processor.detect(source)
                assertNotNull("Notebook must be detected at a fallback analysis scale", quad)
                assertTrue(quad!!.isValid && quad.area in 0.2f..0.85f)
                android.util.Log.i("DocumentRegression", "case=" + uri.substringAfterLast('/') + " quad=" + quad.encode())
                assertEquals("A still photo's detected box must survive exposure verification", quad, processor.resolveCaptureQuad(source, quad))
                val original = processor.loadUpright(source, 4096)
                val result = processor.render(source, quad, DocumentEffect.ORIGINAL, 0)
                try { assertTrue(result.width < original.width && result.height < original.height) }
                finally { original.recycle(); result.recycle() }
            } finally { source.delete() }
        }
    }

    @Test fun twoCompleteConnectedPagesAreNotSavedAsOneSpread() {
        val width = 800; val height = 600
        val pixels = ByteArray(width * height) { 40 }
        for (y in 70 until 540) for (x in 50 until 750) pixels[y * width + x] = 210.toByte()
        for (y in 72 until 538) for (x in 439..442) pixels[y * width + x] = 145.toByte()
        val quad = processor.detectLuminance(pixels, width, height)
        assertNotNull(quad)
        assertTrue("Must stop at the crease, not include both pages", quad!!.points.maxOf { it.x } < 0.57f)
        assertTrue(quad.area in 0.30f..0.45f)
    }

    @Test fun connectedNotebookWithAWeakSpineSelectsTheCentralSinglePage() {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 40, 40))
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.rgb(210, 210, 210) }
        canvas.drawRect(65f, 80f, 600f, 740f, paint)
        paint.color = Color.rgb(150, 150, 150); paint.strokeWidth = 3f
        canvas.drawLine(470f, 82f, 470f, 738f, paint)
        paint.strokeWidth = 1f
        for (y in 130..710 step 25) canvas.drawLine(85f, y.toFloat(), 455f, y.toFloat(), paint)
        try {
            val pixels = ByteArray(600 * 800) { index -> Color.red(bitmap.getPixel(index % 600, index / 600)).toByte() }
            val quad = processor.detectLuminance(pixels, 600, 800)
            assertNotNull("Weak spine must complete a single page, not the connected pair", quad)
            assertTrue(quad!!.points.maxOf { it.x } < 0.81f)
            assertTrue(quad.area in 0.50f..0.60f)
        } finally { bitmap.recycle() }
    }

    @Test fun colourChannelsFindAPageWithTheSameLuminanceAsItsBackground() {
        val width = 400; val height = 300
        val rgb = ByteArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val colour = if (x in 40 until 360 && y in 30 until 270) intArrayOf(245, 90, 10) else intArrayOf(20, 162, 230)
            for (channel in 0..2) rgb[(y * width + x) * 3 + channel] = colour[channel].toByte()
        }
        assertNotNull(processor.detectRgb(rgb, width, height))
    }

    @Test fun shutterBoxIsCheckedAgainstTheJpegWithoutSwitchingToAnotherPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("document-verified-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 20, 20))
        Canvas(bitmap).drawRect(40f, 30f, 360f, 270f, Paint().apply { color = Color.WHITE })
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        try {
            val detected = processor.detect(source)!!
            assertEquals(detected, processor.resolveCaptureQuad(source, detected))
            val stale = DocumentQuad(listOf(
                com.example.photocategorycamera.domain.DocumentPoint(0.25f, 0.25f),
                com.example.photocategorycamera.domain.DocumentPoint(0.85f, 0.25f),
                com.example.photocategorycamera.domain.DocumentPoint(0.85f, 0.85f),
                com.example.photocategorycamera.domain.DocumentPoint(0.25f, 0.85f),
            ))
            assertEquals(DocumentQuad.FULL_IMAGE, processor.resolveCaptureQuad(source, stale))
        } finally { source.delete() }
    }

    @Test fun quickPreviewDecodesTheExactJpegSubmittedForSaving() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("document-preview-source-", ".jpg", context.cacheDir)
        val output = File.createTempFile("document-preview-output-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawRect(40f, 30f, 180f, 270f, Paint().apply { color = Color.RED })
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        try {
            processor.writeResult(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ORIGINAL, 0, output)
            val preview = processor.capturePreview("same-jpeg", source, output, DocumentQuad.FULL_IMAGE)
            val actual = processor.loadUpright(output, 1280)
            try {
                assertEquals(actual.width, preview.result.width)
                assertEquals(actual.height, preview.result.height)
                assertTrue(actual.sameAs(preview.result))
            } finally { actual.recycle(); preview.recycleUnpublished() }
        } finally { source.delete(); output.delete() }
    }

    @Test fun roundedRuledNotebookDetectedInPortraitAndLandscape() {
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(65, 60, 55))
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Keyboard distractors and a bright desk touching two frame corners.
        paint.color = Color.rgb(20, 20, 25)
        for (y in 0..7) for (x in 0..9) canvas.drawRect(x * 60f + 5, y * 95f + 5, x * 60f + 53, y * 95f + 45, paint)
        paint.color = Color.rgb(150, 125, 90)
        canvas.drawRect(0f, 500f, 600f, 800f, paint)
        paint.color = Color.rgb(218, 207, 181)
        canvas.drawRoundRect(65f, 80f, 540f, 745f, 25f, 25f, paint)
        paint.strokeWidth = 1.5f
        paint.color = Color.rgb(139, 130, 110)
        for (y in 125..715 step 27) canvas.drawLine(85f, y.toFloat(), 520f, y.toFloat(), paint)
        paint.textSize = 21f
        paint.color = Color.rgb(105, 100, 92)
        canvas.drawText("x + y = 3", 110f, 175f, paint)
        canvas.drawText("A v = lambda v", 110f, 205f, paint)
        fun detect(image: Bitmap) {
            val pixels = ByteArray(image.width * image.height)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                pixels[y * image.width + x] = ((Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000).toByte()
            }
            val quad = processor.detectLuminance(pixels, image.width, image.height)
            assertNotNull("Rounded notebook page must be detected", quad)
            assertTrue(quad!!.isValid)
            assertTrue(quad.area in 0.5f..0.8f)
            assertTrue(quad.points.all { it.x in 0.04f..0.96f && it.y in 0.04f..0.96f })
        }
        try {
            detect(bitmap)
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                android.graphics.Matrix().apply { postRotate(90f) }, true)
            try { detect(rotated) } finally { rotated.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun ruledLinesWithoutAnOuterPageAndFrameSpanningDeskAreRejected() {
        val width = 400; val height = 300
        val ruled = ByteArray(width * height) { 200.toByte() }
        for (y in 20 until height step 20) for (x in 20 until width - 20) ruled[y * width + x] = 80
        assertNull(processor.detectLuminance(ruled, width, height))
        val desk = ByteArray(width * height) { 30 }
        for (y in 80 until height) for (x in 0 until width) desk[y * width + x] = 190.toByte()
        assertNull(processor.detectLuminance(desk, width, height))
    }

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

    @Test fun enhancedPaperReducesShadowAndPreservesColoredAndFaintStrokes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("paper-shadow-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        for (y in 0 until 600) for (x in 0 until 800) {
            val paper = 150 + x * 85 / 799
            bitmap.setPixel(x, y, Color.rgb(paper, paper, paper))
        }
        val canvas = Canvas(bitmap)
        canvas.drawRect(80f, 100f, 650f, 106f, Paint().apply { color = Color.rgb(70, 70, 70) })
        canvas.drawRect(80f, 170f, 650f, 176f, Paint().apply { color = Color.rgb(120, 120, 120) })
        canvas.drawRect(80f, 250f, 300f, 270f, Paint().apply { color = Color.rgb(150, 35, 35) })
        canvas.drawRect(400f, 250f, 650f, 270f, Paint().apply { color = Color.rgb(35, 70, 160) })
        canvas.drawText("Lenshelf 0.9 - Paper notes", 80f, 345f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(65,65,65); textSize = 30f })
        canvas.drawText("Faint strokes / Colored annotations", 80f, 505f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(125,125,125); textSize = 24f })
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        val old = processor.render(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0, processingVersion = 1)
        val enhanced = processor.render(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0, processingVersion = 2)
        try {
            fun light(image: Bitmap, x: Int, y: Int) = Color.red(image.getPixel(x, y))
            val oldDifference = kotlin.math.abs(light(old, 100, 400) - light(old, 700, 400))
            val newDifference = kotlin.math.abs(light(enhanced, 100, 400) - light(enhanced, 700, 400))
            assertTrue("Shadow remains: old=$oldDifference new=$newDifference", newDifference < oldDifference * 0.65)
            assertTrue(light(enhanced, 100, 400) > light(old, 100, 400) + 20)
            assertTrue(light(enhanced, 200, 160) - light(enhanced, 200, 172) > 20)
            val red = enhanced.getPixel(200, 260); val blue = enhanced.getPixel(500, 260)
            assertTrue(Color.red(red) > Color.green(red) + 45)
            assertTrue(Color.blue(blue) > Color.red(blue) + 45)
            if (InstrumentationRegistry.getArguments().getString("exportReviewSamples") == "true") {
                val directory = File(context.filesDir, "v09-review").apply { mkdirs() }
                source.copyTo(File(directory, "document-source.jpg"), overwrite = true)
                processor.writeResult(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0,
                    File(directory, "document-old.jpg"), processingVersion = 1)
                processor.writeResult(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0,
                    File(directory, "document-enhanced.jpg"), processingVersion = 2)
            }
        } finally { old.recycle(); enhanced.recycle(); source.delete() }
    }

    @Test fun grayPaperAndCreaseFixturesKeepHighlighterFaintInkAndThinLines() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (fixture in listOf("gray", "gradient", "crease")) {
            val source = File.createTempFile("paper-$fixture-", ".jpg", context.cacheDir)
            val output = File(source.parentFile, source.name + "-result.jpg")
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(240, 240, 240))
            val canvas = Canvas(bitmap)
            canvas.drawRect(120f, 220f, 680f, 270f, Paint().apply { color = Color.rgb(240, 235, 80) })
            canvas.drawRect(120f, 300f, 680f, 302f, Paint().apply { color = Color.rgb(205, 205, 205) })
            canvas.drawRect(120f, 350f, 680f, 351f, Paint().apply { color = Color.rgb(90, 90, 90) })
            for (y in 0 until 600) for (x in 0 until 800) {
                val lighting = when (fixture) {
                    "gray" -> .80
                    "gradient" -> .64 + .34 * x / 799.0
                    else -> .98 - .28 * kotlin.math.exp(-.5 * ((x - 400) / 75.0) * ((x - 400) / 75.0))
                }
                val pixel = bitmap.getPixel(x, y)
                bitmap.setPixel(x, y, Color.rgb((Color.red(pixel) * lighting).toInt(),
                    (Color.green(pixel) * lighting).toInt(), (Color.blue(pixel) * lighting).toInt()))
            }
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            bitmap.recycle()
            val old = processor.render(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0, processingVersion = 1)
            processor.writeResult(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0, output, processingVersion = 2)
            val result = processor.loadUpright(output, 1000)
            try {
                fun light(image: Bitmap, x: Int, y: Int) = Color.red(image.getPixel(x, y))
                assertTrue("$fixture faint ink lost", light(result, 200, 310) - light(result, 200, 300) > 8)
                assertTrue("$fixture thin line lost", light(result, 200, 360) - light(result, 200, 350) > 40)
                val yellow = result.getPixel(200, 245)
                assertTrue("$fixture highlighter lost", Color.red(yellow) - Color.blue(yellow) > 70 &&
                    Color.green(yellow) - Color.blue(yellow) > 70 && kotlin.math.abs(Color.red(yellow) - Color.green(yellow)) < 25)
                if (fixture == "gray") assertTrue(light(result, 200, 500) > light(old, 200, 500) + 15)
                else {
                    val darkX = if (fixture == "crease") 400 else 60
                    val oldDifference = kotlin.math.abs(light(old, darkX, 500) - light(old, 740, 500))
                    val difference = kotlin.math.abs(light(result, darkX, 500) - light(result, 740, 500))
                    assertTrue("$fixture shadow remains: old=$oldDifference new=$difference", difference < oldDifference * .8)
                }
                if (InstrumentationRegistry.getArguments().getString("exportReviewSamples") == "true") {
                    val directory = File(context.filesDir, "v09-review").apply { mkdirs() }
                    source.copyTo(File(directory, "corpus-$fixture-source.jpg"), overwrite = true)
                    output.copyTo(File(directory, "corpus-$fixture-enhanced.jpg"), overwrite = true)
                    processor.writeResult(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0,
                        File(directory, "corpus-$fixture-old.jpg"), processingVersion = 1)
                }
            } finally { old.recycle(); result.recycle(); source.delete(); output.delete() }
        }
    }

    @Test fun enhancedPaperRetainsLargeGrayPanelsAndTheirDifferentShades() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("printed-gray-panel-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(240,240,240))
        val canvas = Canvas(bitmap)
        canvas.drawRect(80f, 80f, 300f, 320f, Paint().apply { color = Color.rgb(185,185,185) })
        canvas.drawRect(300f, 80f, 520f, 320f, Paint().apply { color = Color.rgb(140,140,140) })
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        val output = File(source.parentFile, source.name + "-processed.jpg")
        processor.writeResult(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0, output, processingVersion = 2)
        val enhanced = processor.loadUpright(output, 1000)
        try {
            val paper = Color.red(enhanced.getPixel(30, 200))
            val lightPanel = Color.red(enhanced.getPixel(200, 200))
            val darkPanel = Color.red(enhanced.getPixel(400, 200))
            assertTrue("Gray panel erased: paper=$paper panel=$lightPanel", paper - lightPanel > 25)
            assertTrue("Printed gray shades collapsed: $lightPanel vs $darkPanel", lightPanel - darkPanel > 25)
        } finally { enhanced.recycle(); source.delete(); output.delete() }
    }

    @Test fun enhancedGrayPrintedContentAlsoSurvivesUnevenLighting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("shaded-printed-panel-", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        for (y in 0 until 600) for (x in 0 until 800) {
            val paper = 150 + x * 85 / 799
            val factor = when { y in 100..500 && x in 80..350 -> .7f; y in 100..500 && x in 450..720 -> .45f; else -> 1f }
            val value = (paper * factor).toInt()
            bitmap.setPixel(x,y,Color.rgb(value,value,value))
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
        val output = File(source.parentFile, source.name + "-result.jpg")
        processor.writeResult(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0, output, processingVersion = 2)
        val result = processor.loadUpright(output, 1000)
        try {
            val paper = Color.red(result.getPixel(200,40))
            val panel = Color.red(result.getPixel(200,300))
            assertTrue("Printed panel was flattened under a gradient: paper=$paper panel=$panel", paper-panel > 40)
        } finally { result.recycle(); source.delete(); output.delete() }
    }

    @Test fun enhancedDarkAndColoredBackgroundsAreNotBleached() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (color in listOf(Color.rgb(35, 35, 35), Color.rgb(35, 85, 150))) {
            val source = File.createTempFile("non-paper-", ".jpg", context.cacheDir)
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bitmap.recycle()
            val result = processor.render(source, DocumentQuad.FULL_IMAGE, DocumentEffect.ENHANCED, 0, processingVersion = 2)
            try { assertTrue(Color.red(result.getPixel(100, 100)) < 100) }
            finally { result.recycle(); source.delete() }
        }
    }

    @Test fun highContrastDocumentDetectedAndBlankFrameFallsBack() {
        val pixels = ByteArray(400 * 300) { 20 }
        for (y in 30 until 270) for (x in 40 until 360) pixels[y * 400 + x] = 240.toByte()
        val quad = processor.detectLuminance(pixels, 400, 300)
        assertNotNull(quad); assertTrue(quad!!.isValid); assertTrue(quad.area > 0.5f)
        assertNull(processor.detectLuminance(ByteArray(400 * 300) { 100 }, 400, 300))
        val darkSlide = ByteArray(400 * 300) { 230.toByte() }
        for (y in 30 until 270) for (x in 40 until 360) darkSlide[y * 400 + x] = 20
        assertNotNull(processor.detectLuminance(darkSlide, 400, 300))
    }

    @Test fun triangularBackgroundDoesNotThrowOrBecomeAFourCornerDocument() {
        val pixels = ByteArray(400 * 300) { 20 }
        for (y in 30 until 270) {
            val halfWidth = ((y - 30) * 0.6f).toInt()
            for (x in 200 - halfWidth..200 + halfWidth) pixels[y * 400 + x] = 230.toByte()
        }
        assertNull(processor.detectLuminance(pixels, 400, 300))
    }
}
