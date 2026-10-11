package com.example.photocategorycamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.photocategorycamera.MainActivity
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import com.example.photocategorycamera.domain.DocumentEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in hardware smoke test. Never selects, overwrites, or deletes existing user media. */
@RunWith(AndroidJUnit4::class)
class DocumentCameraUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
    private val container get() = app.container
    private fun draft() = runBlocking { container.documents.editable.first() }
    private fun waitText(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitCamera(document: Boolean = false) = compose.waitUntil(30_000) {
        compose.onAllNodesWithContentDescription(if (document) "拍摄文档" else "拍照，长按录像")
            .fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitReady() = compose.waitUntil(30_000) {
        compose.onAllNodesWithText("确认保存").fetchSemanticsNodes().any {
            SemanticsProperties.Disabled !in it.config
        }
    }
    private fun waitSaved(directory: DocumentFile, before: Set<Uri>, mime: String) = compose.waitUntil(45_000) {
        directory.listFiles().any { it.uri !in before && it.type == mime } &&
            runBlocking { container.captureTasks.outstanding.first().isEmpty() }
    }
    private fun startApp() {
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -n ${app.packageName}/.MainActivity",
            ),
        ).bufferedReader().use { it.readText() }
    }
    private fun restartFixtureName(): String {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Process restart phases require explicit opt-in", arguments.getString("allowDeviceCapture") == "true")
        return requireNotNull(arguments.getString("fixtureCategory")).also {
            require(it.startsWith("设备回归_"))
        }
    }

    /** Run alone, stop the app process externally, then run resumeDraftAfterProcessRestart alone. */
    @Test fun stageDraftForProcessRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("processRestartPhase") == "stage")
        val name = restartFixtureName()
        assumeTrue(draft() == null)
        assumeTrue(runBlocking { container.captureTasks.outstanding.first().isEmpty() })
        startApp()
        waitText(name)
        compose.onNodeWithText(name).performScrollTo().performClick()
        waitCamera()
        compose.onNodeWithText("文档").performClick()
        waitCamera(document = true)
        SystemClock.sleep(2_000)
        compose.onNodeWithContentDescription("拍摄文档").performClick()
        waitReady()
        compose.onNodeWithText("增强").performClick()
        compose.onNodeWithText("旋转 90°").performClick()
        waitReady()
        compose.onNodeWithContentDescription("返回首页，保留草稿").performClick()
        waitText("待确认文档")
        val pending = draft()!!
        assertEquals(name, pending.categoryName)
        assertEquals(DocumentEffect.ENHANCED, pending.effect)
        assertEquals(1, pending.quarterTurns)
        assertTrue(File(pending.sourcePath).isFile)
        assertTrue(runBlocking { container.captureTasks.outstanding.first().isEmpty() })
        Log.i("LenshelfDeviceTest", "restartDraft=${pending.id}, stage=EDITING, queued=0")
    }

    @Test fun resumeDraftAfterProcessRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("processRestartPhase") == "resume")
        val name = restartFixtureName()
        val pending = draft() ?: error("Staged draft was lost after process restart")
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedDraftId"), pending.id)
        assertEquals(name, pending.categoryName)
        assertEquals(DocumentEffect.ENHANCED, pending.effect)
        assertEquals(1, pending.quarterTurns)
        assertTrue(File(pending.sourcePath).isFile)
        assertTrue(runBlocking { container.captureTasks.outstanding.first().isEmpty() })
        val directory = DocumentFile.fromTreeUri(app, Uri.parse(pending.rootTreeUri))!!.findFile(name)!!
        val before = directory.listFiles().map { it.uri }.toSet()
        startApp()
        waitText("待确认文档")
        compose.onNodeWithText("继续编辑").performClick()
        waitReady()
        assertEquals(pending, draft())
        compose.onNodeWithText("确认保存").performClick()
        waitText(name)
        waitSaved(directory, before, "image/jpeg")
        assertEquals(1, directory.listFiles().count { it.uri !in before && it.type == "image/jpeg" })
        compose.waitUntil(15_000) { !File(pending.sourcePath).exists() }
        assertNull(draft())
        Log.i("LenshelfDeviceTest", "processRestart=restored-and-saved-once, privateSource=cleaned")
    }

    @Test fun documentReviewRetakeSaveDiscardAndOrdinaryPhotoAudioVideo() {
        assumeTrue("Actual capture requires explicit opt-in", InstrumentationRegistry.getArguments().getString("allowDeviceCapture") == "true")
        assumeTrue("Camera permission must be granted by the device owner", app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Microphone permission must be granted by the device owner", app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Do not replace a user's unconfirmed draft", draft() == null)
        assumeTrue("Do not interact with a user's existing saving queue", runBlocking { container.captureTasks.outstanding.first().isEmpty() })
        val root = runBlocking { container.settings.settings.first().storageRoot }
        assumeTrue("Device owner must authorize a storage root first", root != null)
        val rootDocument = DocumentFile.fromTreeUri(app, Uri.parse(root!!.treeUri))!!
        val name = InstrumentationRegistry.getArguments().getString("fixtureCategory")
            ?: "设备回归_${UUID.randomUUID().toString().take(8)}"
        require(name.startsWith("设备回归_"))
        assertTrue(rootDocument.findFile(name)?.listFiles().isNullOrEmpty())
        val category = runBlocking {
            container.categories.getAll().firstOrNull { it.name == name }
                ?: container.categories.create(name).getOrThrow()
        }
        // Keep only this owned category and its camera samples for the owner to inspect afterwards.
        Log.i("LenshelfDeviceTest", "fixtureCategory=$name")
        startApp()
        run {
            waitText(name)
            compose.onNodeWithText(name).performScrollTo().performClick()
            waitCamera()
            compose.onNodeWithText("文档").performClick()
            waitCamera(document = true)
            SystemClock.sleep(2_500)
            compose.onNodeWithContentDescription("拍摄文档").performClick()
            waitText("确认文档")
            waitReady()
            val original = draft()!!
            assertEquals(category.id, original.categoryId)
            assertEquals(root.treeUri, original.rootTreeUri)
            assertEquals(DocumentEffect.ORIGINAL, original.effect)
            assertTrue(runBlocking { container.captureTasks.outstanding.first().isEmpty() })

            compose.onNodeWithText("调整四角").performClick()
            val preview = container.documentProcessor.loadUpright(File(original.sourcePath), 1280)
            val editor = compose.onNodeWithContentDescription("文档四角调整区域")
            val size = editor.fetchSemanticsNode().boundsInRoot.size
            val scale = minOf(size.width / preview.width, size.height / preview.height)
            val width = preview.width * scale
            val height = preview.height * scale
            preview.recycle()
            val corner = original.quad.points.first()
            val start = Offset((size.width - width) / 2 + corner.x * width, (size.height - height) / 2 + corner.y * height)
            editor.performTouchInput { swipe(start, start + Offset(width * 0.04f, height * 0.04f), 400) }
            compose.waitUntil(15_000) { draft()?.quadValue != original.quadValue }
            compose.onNodeWithText("查看效果").performClick()
            compose.onNodeWithText("增强").performClick()
            waitReady()
            compose.onNodeWithText("黑白").performClick()
            compose.onNodeWithText("旋转 90°").performClick()
            waitReady()
            compose.waitUntil(15_000) { draft()?.effect == DocumentEffect.GRAYSCALE && draft()?.quarterTurns == 1 }
            val edited = draft()!!
            compose.onNodeWithContentDescription("返回首页，保留草稿").performClick()
            waitText("待确认文档")
            assertTrue(File(edited.sourcePath).isFile)
            assertTrue(runBlocking { container.captureTasks.outstanding.first().isEmpty() })
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single().recreate()
            }
            waitText("继续编辑")
            compose.onNodeWithText("继续编辑").performClick()
            waitReady()
            assertEquals(edited, draft())
            compose.onNodeWithText("重拍").performClick()
            waitCamera(document = true)
            assertTrue("Retake must not discard the previous source before replacement", File(edited.sourcePath).isFile)
            SystemClock.sleep(2_000)
            compose.onNodeWithContentDescription("拍摄文档").performClick()
            waitReady()
            assertEquals(edited.id, draft()!!.id)
            assertEquals(edited.categoryId, draft()!!.categoryId)
            assertNotEquals(edited.sourcePath, draft()!!.sourcePath)
            assertFalse(File(edited.sourcePath).exists())
            compose.onNodeWithText("确认保存").performClick()
            waitText(name)
            val directory = rootDocument.findFile(name) ?: error("Saved category directory missing")
            waitSaved(directory, emptySet(), "image/jpeg")
            val document = directory.listFiles().single { it.type == "image/jpeg" }
            app.contentResolver.openInputStream(document.uri)!!.use {
                val bitmap = BitmapFactory.decodeStream(it)
                assertNotNull("Document output must decode", bitmap)
                assertTrue(maxOf(bitmap!!.width, bitmap.height) <= 4096)
                Log.i("LenshelfDeviceTest", "document=${bitmap.width}x${bitmap.height}")
                bitmap.recycle()
            }
            assertNull(draft())

            compose.onNodeWithText(name).performScrollTo().performClick()
            waitCamera()
            compose.onNodeWithText("文档").performClick()
            waitCamera(document = true)
            SystemClock.sleep(2_000)
            compose.onNodeWithContentDescription("拍摄文档").performClick()
            waitReady()
            val discardedSource = draft()!!.sourcePath
            compose.onNodeWithText("丢弃").performClick()
            waitText("丢弃文档草稿？")
            compose.onNode(hasText("丢弃") and hasAnyAncestor(isDialog())).performClick()
            waitText(name)
            compose.waitUntil(15_000) { draft() == null }
            assertFalse(File(discardedSource).exists())
            assertEquals(1, directory.listFiles().count { it.type == "image/jpeg" })

            compose.onNodeWithText(name).performScrollTo().performClick()
            waitCamera()
            val before = directory.listFiles().map { it.uri }.toSet()
            SystemClock.sleep(2_000)
            compose.onNodeWithContentDescription("拍照，长按录像").performClick()
            waitSaved(directory, before, "image/jpeg")
            compose.onNodeWithContentDescription("拍照，长按录像").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("停止录像").fetchSemanticsNodes().isNotEmpty() }
            SystemClock.sleep(3_000)
            compose.onNodeWithContentDescription("停止录像").performClick()
            waitSaved(directory, before, "video/mp4")
            val video = directory.listFiles().single { it.type == "video/mp4" }
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(app, video.uri)
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue(duration >= 2_000)
                val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull("Video frame must decode", frame)
                frame!!.recycle()
                Log.i("LenshelfDeviceTest", "video=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)}x${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)}, durationMs=$duration, audio=yes")
            }
            assertEquals(3, runBlocking { container.mediaStorage.countCategoryMedia(Uri.parse(root.treeUri), listOf(name)).getOrThrow()[name] })
        }
    }
}
