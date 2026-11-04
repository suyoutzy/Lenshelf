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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import com.example.photocategorycamera.domain.CategoryGroup
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

    @Test fun consecutiveDocumentsSaveWithoutConfirmationAndPhotoAudioVideoStillWork() = captureInGroup(false)

    @Test fun documentsPhotoAndAudioVideoSaveInCustomGroup() = captureInGroup(true)

    @Test fun stampedPhotosAndEnhancedDocumentsSaveWithVisibleOptions() = captureInGroup(false, true)

    private fun captureInGroup(custom: Boolean, verifyNewOptions: Boolean = false) {
        assumeTrue("Actual capture requires explicit opt-in", InstrumentationRegistry.getArguments().getString("allowDeviceCapture") == "true")
        assumeTrue(app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Do not interact with existing user tasks", runBlocking { container.captureTasks.outstanding.first().isEmpty() })
        val legacyBefore = runBlocking { container.documents.editable.first() }
        val root = runBlocking { container.settings.settings.first().storageRoot }
        assumeTrue(root != null)
        val rootDocument = DocumentFile.fromTreeUri(app, Uri.parse(root!!.treeUri))!!
        InstrumentationRegistry.getArguments().getString("cleanupOwnedFixture")?.let { previousName ->
            require(previousName.matches(Regex("设备回归_[a-f0-9]{8}")))
            val previous = runBlocking { container.categories.getAll(CategoryGroup.HOME_ID).single { it.name == previousName } }
            val previousParent = previous.groupName?.let { rootDocument.findFile(it) } ?: rootDocument
            require(previousParent?.findFile(previousName)?.isDirectory == true)
            runBlocking { container.directoryOperations.deleteCategory(previous).getOrThrow() }
        }
        val settingsBefore = runBlocking { container.settings.settings.first() }
        val selectedBefore = settingsBefore.selectedGroupId
        val group = if (custom) runBlocking {
            container.directoryOperations.createGroup("分组回归_${UUID.randomUUID().toString().take(8)}").getOrThrow()
        } else null
        runBlocking { container.settings.setSelectedGroupId(group?.id ?: CategoryGroup.HOME_ID) }
        val parentName = group?.name ?: CategoryGroup.HOME_NAME
        val name = "设备回归_${UUID.randomUUID().toString().take(8)}"
        val category = runBlocking { container.directoryOperations.createCategory(name, group?.id ?: CategoryGroup.HOME_ID).getOrThrow() }
        if (verifyNewOptions) runBlocking { container.settings.updateCategoryCameraSettings(category.id) {
            it.copy(timeStampEnabled=true, timeStampFormat=com.example.photocategorycamera.domain.PhotoDateStampFormat.DATE)
        } }
        val parent = rootDocument.findFile(parentName)!!
        try {
        Log.i("LenshelfDeviceTest", "fixtureCategory=$name")
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -f 0x10008000 -n ${app.packageName}/.MainActivity"),
        ).bufferedReader().use { it.readText() }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("category-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("category-list").performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
        waitCamera()
        compose.onNodeWithText("文档").performClick()
        waitCamera(document = true)
        if (verifyNewOptions) {
            compose.onNodeWithText("效果：原色").performClick()
            compose.onNodeWithText("增强").performClick()
            compose.onNodeWithText("效果：增强").assertIsDisplayed()
        }
        SystemClock.sleep(2_000)
        compose.onNodeWithContentDescription("拍摄文档").performClick()
        waitCamera(document = true)
        assertTrue(compose.onAllNodesWithText("确认保存").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("拍摄文档").performClick()
        compose.waitUntil(30_000) { parent.findFile(name)?.listFiles()?.count { it.type == "image/jpeg" } == 2 }
        val directory = parent.findFile(name)!!
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
        if (verifyNewOptions) {
            runBlocking { container.settings.updateCategoryCameraSettings(category.id) {
                it.copy(timeStampFormat=com.example.photocategorycamera.domain.PhotoDateStampFormat.DATE_TIME)
            } }
            compose.onNodeWithContentDescription("拍照，长按录像").performClick()
            waitSaved(directory, 4)
            val photos = directory.listFiles().filter { it.type == "image/jpeg" }.sortedBy { it.name }
            val sizes = photos.drop(2).map { photo ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(photo.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                bounds.outWidth to bounds.outHeight
            }
            assertEquals(2, sizes.size)
            assertEquals(sizes[0], sizes[1])
            assertTrue(maxOf(sizes[0].first, sizes[0].second) >= 1920)
            if (InstrumentationRegistry.getArguments().getString("exportReviewSamples") == "true") {
                val review = java.io.File(app.filesDir,"v09-review").apply { mkdirs() }
                listOf(0 to "camera-document-enhanced.jpg", 2 to "camera-photo-date.jpg", 3 to "camera-photo-date-time.jpg").forEach { (index, label) ->
                    app.contentResolver.openInputStream(photos[index].uri)!!.use { input -> java.io.File(review,label).outputStream().use(input::copyTo) }
                }
            }
        }
        val expectedFiles = if (verifyNewOptions) 5 else 4
        compose.onNodeWithContentDescription("拍照，长按录像").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("停止录像").fetchSemanticsNodes().isNotEmpty() }
        SystemClock.sleep(3_000)
        compose.onNodeWithContentDescription("停止录像").performClick()
        waitSaved(directory, expectedFiles)
        val video = directory.listFiles().single { it.type == "video/mp4" }
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(app, video.uri)
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 2_000)
            assertNotNull(retriever.getFrameAtTime(1_000_000)?.also { it.recycle() })
        }
        assertEquals(expectedFiles, runBlocking { container.mediaStorage.countCategoryMedia(Uri.parse(root.treeUri), listOf(name), parentName).getOrThrow()[name] })
        runBlocking {
            container.directoryOperations.deleteCategory(category).getOrThrow()
            if (group != null) container.directoryOperations.deleteGroup(group).getOrThrow()
        }
        } finally { runBlocking {
            container.settings.setSelectedGroupId(selectedBefore)
        } }
    }
}
