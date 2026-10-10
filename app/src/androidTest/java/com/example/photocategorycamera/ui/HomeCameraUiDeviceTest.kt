package com.example.photocategorycamera.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeCameraUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedLegacyPhotoAndVideoDecodeFrames() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT < 33)
        val app = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
        val root = runBlocking { app.container.settings.settings.first().storageRoot!! }
        val category = runBlocking { app.container.categories.getAll().first { it.isActive } }
        val directory = DocumentFile.fromTreeUri(app, Uri.parse(root.treeUri))!!.findFile(category.name)!!
        val documents = directory.listFiles()
        val photo = documents.last { it.type == "image/jpeg" }
        val video = documents.last { it.type == "video/mp4" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val bitmap = app.contentResolver.openInputStream(photo.uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
        org.junit.Assert.assertNotNull("保存后的JPEG像素必须可解码", bitmap)
        bitmap!!.recycle()
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(app, video.uri)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            org.junit.Assert.assertNotNull("保存后的视频必须能解码关键帧", frame)
            frame!!.recycle()
            File(app.filesDir, "legacy-media-report.txt").writeText(
                "photo=${bounds.outWidth}x${bounds.outHeight}\n" +
                    "video=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)}x${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)}\n" +
                    "durationMs=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)}\n" +
                    "audio=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)}\n",
            )
        }
    }

    @Test fun ordinaryPhotoAndAudioVideoStillSave() {
        val app = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
        val container = app.container
        val category = runBlocking { container.categories.getAll().first { it.isActive } }
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -n ${app.packageName}/.MainActivity",
            ),
        ).bufferedReader().use { it.readText() }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("个文件", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(category.name).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("拍照，长按录像").fetchSemanticsNodes().isNotEmpty()
        }
        val root = runBlocking { container.settings.settings.first().storageRoot!! }
        val directory = DocumentFile.fromTreeUri(app, Uri.parse(root.treeUri))!!.findFile(category.name)!!
        val before = directory.listFiles().map { it.uri }.toSet()
        android.os.SystemClock.sleep(2_500)
        compose.onNodeWithContentDescription("拍照，长按录像").performClick()
        compose.waitUntil(30_000) {
            directory.listFiles().any { it.uri !in before && it.type == "image/jpeg" } &&
                runBlocking { container.captureTasks.outstanding.first().isEmpty() }
        }
        val photo = directory.listFiles().first { it.uri !in before && it.type == "image/jpeg" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
        org.junit.Assert.assertTrue(options.outWidth > 0 && options.outHeight > 0)
        compose.onNodeWithContentDescription("拍照，长按录像").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("停止录像").fetchSemanticsNodes().isNotEmpty()
        }
        android.os.SystemClock.sleep(3_000)
        compose.onNodeWithContentDescription("停止录像").performClick()
        compose.waitUntil(30_000) {
            directory.listFiles().any { it.uri !in before && it.type == "video/mp4" } &&
                runBlocking { container.captureTasks.outstanding.first().isEmpty() }
        }
        val video = directory.listFiles().first { it.uri !in before && it.type == "video/mp4" }
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(app, video.uri)
            org.junit.Assert.assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            org.junit.Assert.assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            org.junit.Assert.assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
        }
    }
}
