package com.example.photocategorycamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in hardware regression. Only a new, owned category receives test photos/video. */
@RunWith(AndroidJUnit4::class)
class DocumentCameraUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
    private val container get() = app.container
    private fun waitCamera(document: Boolean = false) = compose.waitUntil(30_000) {
        compose.onAllNodesWithContentDescription(if (document) "拍摄文档" else "拍照，长按录像")
            .fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config }
    }
    private fun waitSaved(directory: DocumentFile, count: Int) = compose.waitUntil(45_000) {
        directory.listFiles().count { it.type == "image/jpeg" || it.type == "video/mp4" } == count &&
            runBlocking { container.captureTasks.outstanding.first().isEmpty() }
    }

    @Test fun consecutiveDocumentsSaveWithoutConfirmationAndPhotoAudioVideoStillWork() {
        assumeTrue("Actual capture requires explicit opt-in", InstrumentationRegistry.getArguments().getString("allowDeviceCapture") == "true")
        assumeTrue(app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Do not interact with existing user tasks", runBlocking { container.captureTasks.outstanding.first().isEmpty() })
        val legacyBefore = runBlocking { container.documents.editable.first() }
        val root = runBlocking { container.settings.settings.first().storageRoot }
        assumeTrue(root != null)
        val rootDocument = DocumentFile.fromTreeUri(app, Uri.parse(root!!.treeUri))!!
        val name = "设备回归_${UUID.randomUUID().toString().take(8)}"
        assertNull(rootDocument.findFile(name))
        runBlocking { container.categories.create(name).getOrThrow() }
        Log.i("LenshelfDeviceTest", "fixtureCategory=$name")
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -n ${app.packageName}/.MainActivity"),
        ).bufferedReader().use { it.readText() }
        compose.waitUntil(30_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).performScrollTo().performClick()
        waitCamera()
        compose.onNodeWithText("文档").performClick()
        waitCamera(document = true)
        SystemClock.sleep(2_000)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("拍摄文档").performClick()
            // Do not let test auto-advance skip over the deliberately brief result animation.
            compose.waitUntil(30_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithContentDescription("文档成片快速预览").fetchSemanticsNodes().isNotEmpty()
            }
            waitCamera(document = true)
            assertTrue(compose.onAllNodesWithText("确认保存").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("拍摄文档").performClick()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(30_000) { rootDocument.findFile(name)?.listFiles()?.count { it.type == "image/jpeg" } == 2 }
        val directory = rootDocument.findFile(name)!!
        waitSaved(directory, 2)
        assertEquals("Legacy material must not be replaced or silently submitted", legacyBefore,
            runBlocking { container.documents.editable.first() })
        assertTrue(compose.onAllNodesWithText("确认文档").fetchSemanticsNodes().isEmpty())
        directory.listFiles().filter { it.type == "image/jpeg" }.forEach { file ->
            app.contentResolver.openInputStream(file.uri)!!.use {
                val bitmap = BitmapFactory.decodeStream(it)
                assertNotNull(bitmap)
                assertTrue(maxOf(bitmap!!.width, bitmap.height) <= 4096)
                bitmap.recycle()
            }
        }
        compose.onNodeWithText("照片").performClick()
        waitCamera()
        SystemClock.sleep(1_000)
        compose.onNodeWithContentDescription("拍照，长按录像").performClick()
        waitSaved(directory, 3)
        compose.onNodeWithContentDescription("拍照，长按录像").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("停止录像").fetchSemanticsNodes().isNotEmpty() }
        SystemClock.sleep(3_000)
        compose.onNodeWithContentDescription("停止录像").performClick()
        waitSaved(directory, 4)
        val video = directory.listFiles().single { it.type == "video/mp4" }
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(app, video.uri)
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 2_000)
            assertNotNull(retriever.getFrameAtTime(1_000_000)?.also { it.recycle() })
        }
        assertEquals(4, runBlocking { container.mediaStorage.countCategoryMedia(Uri.parse(root.treeUri), listOf(name)).getOrThrow()[name] })
    }
}
