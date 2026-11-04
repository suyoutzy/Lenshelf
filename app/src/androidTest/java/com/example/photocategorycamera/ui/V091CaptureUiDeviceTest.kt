package com.example.photocategorycamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import com.example.photocategorycamera.data.CaptureTaskStage
import com.example.photocategorycamera.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit hardware opt-in. All new media and recursive cleanup belong to this invocation's group. */
class V091CaptureUiDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
    private val container get()=app.container
    private fun waitFor(condition:suspend ()->Boolean)=compose.waitUntil(60_000) { runBlocking { condition() } }
    private fun waitCamera()=compose.waitUntil(30_000) {
        var streaming = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun hasStreamingPreview(view: View): Boolean =
                (view is PreviewView && view.previewStreamState.value == PreviewView.StreamState.STREAMING) ||
                    (view is ViewGroup && (0 until view.childCount).any { hasStreamingPreview(view.getChildAt(it)) })
            streaming = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { hasStreamingPreview(it.window.decorView) }
        }
        streaming && compose.onAllNodesWithContentDescription("拍照，长按录像").fetchSemanticsNodes()
            .any { SemanticsProperties.Disabled !in it.config }
    }
    private fun waitEditor()=compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-input").fetchSemanticsNodes().isNotEmpty() }
    private fun screenshot(name:String) {
        if(InstrumentationRegistry.getArguments().getString("exportReviewSamples")!="true") return
        compose.waitForIdle()
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(app.filesDir,"v091-review").apply{mkdirs()}.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { bitmap.recycle() }
    }

    @Test fun retryAndCleanOnlyTheExplicitOwnedFixture() {
        val args=InstrumentationRegistry.getArguments()
        val groupId=args.getString("retryOwnedGroup")
        assumeTrue(args.getString("allowDeviceCapture")=="true" && !groupId.isNullOrBlank())
        val group=runBlocking { checkNotNull(container.groups.findById(groupId!!)) }
        require(group.name.startsWith("备注验收_"))
        val categories=runBlocking { container.categories.categories.first().filter { it.groupId==group.id } }
        require(categories.map { it.name }.toSet()==setOf("照片备注","视频备注"))
        val pending=runBlocking { container.captureTasks.outstanding.first() }
        require(pending.size==1 && pending.single().groupId==group.id && pending.single().taskStage==CaptureTaskStage.VIDEO_RETRY)
        val source=File(checkNotNull(pending.single().sourcePath))
        assertTrue(source.isFile)
        File(app.filesDir,"v091-review").apply { mkdirs() }.resolve("retry-original.mp4").let { source.copyTo(it,overwrite=true) }
        container.captureTasks.retryAll()
        waitFor { container.captureTasks.outstanding.first().isEmpty() }
        val root=runBlocking { checkNotNull(container.settings.settings.first().storageRoot) }
        val directory=checkNotNull(DocumentFile.fromTreeUri(app,Uri.parse(root.treeUri))?.findFile(group.name))
        val video=checkNotNull(directory.findFile("视频备注")).listFiles().single { it.type=="video/mp4" }
        app.contentResolver.openInputStream(video.uri)!!.use { input ->
            File(app.filesDir,"v091-review/retry-stamped.mp4").outputStream().use(input::copyTo)
        }
        runBlocking {
            categories.forEach { container.directoryOperations.deleteCategory(it).getOrThrow() }
            container.directoryOperations.deleteGroup(group).getOrThrow()
        }
    }

    @Test fun finishOnlyTheExplicitOwnedPendingFixture() {
        val args=InstrumentationRegistry.getArguments()
        val groupId=args.getString("finishOwnedGroup")
        assumeTrue(args.getString("allowDeviceCapture")=="true" && !groupId.isNullOrBlank())
        val group=runBlocking { checkNotNull(container.groups.findById(groupId!!)) }
        require(group.name.startsWith("备注验收_"))
        val categories=runBlocking { container.categories.getAll(group.id) }
        require(categories.map { it.name }.toSet()==setOf("照片备注","视频备注"))
        val pending=runBlocking { container.captureTasks.outstanding.first() }
        require(pending.isNotEmpty() && pending.all { it.groupId==group.id && it.taskStage==CaptureTaskStage.AWAITING_NOTE })
        runBlocking { pending.forEach { container.captureTasks.confirmAnnotation(it.id, "").getOrThrow() } }
        waitFor { container.captureTasks.outstanding.first().isEmpty() }
        runBlocking {
            categories.forEach { container.directoryOperations.deleteCategory(it).getOrThrow() }
            container.directoryOperations.deleteGroup(group).getOrThrow()
        }
    }

    @Test fun firstPhotoPromptsCollapseDefersAndCrossCategoryVideoKeepsZoomAndFrozenTime() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowDeviceCapture")=="true")
        assumeTrue(app.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
        assumeTrue(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
        assumeTrue("Existing user work is left untouched",runBlocking {
            container.captureTasks.outstanding.first().isEmpty() && container.directoryOperations.pending.first().isEmpty()
        })
        val before=runBlocking { container.settings.settings.first() }
        assumeTrue(before.storageRoot!=null)
        val root=checkNotNull(DocumentFile.fromTreeUri(app,Uri.parse(before.storageRoot!!.treeUri)))
        val group=runBlocking { container.directoryOperations.createGroup("备注验收_${UUID.randomUUID().toString().take(8)}").getOrThrow() }
        val a=runBlocking { container.directoryOperations.createCategory("照片备注",group.id).getOrThrow() }
        val b=runBlocking { container.directoryOperations.createCategory("视频备注",group.id).getOrThrow() }
        val owned=setOf(a.id,b.id)
        Log.i("LenshelfDeviceTest","v091OwnedGroupId=${group.id};name=${group.name}")
        try {
            runBlocking {
                container.settings.setSelectedGroupId(group.id)
                container.settings.setTimeStampEnabled(true)
                container.settings.setTimeStampFormat(TimeStampFormat.DATE_TIME)
                container.settings.setPostCaptureNoteEnabled(true)
            }
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -f 0x10008000 -n ${app.packageName}/.MainActivity")).bufferedReader().use { it.readText() }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("category-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("category-list").performScrollToNode(hasText(a.name))
            compose.onNodeWithText(a.name).performClick();waitCamera();SystemClock.sleep(1200)
            compose.onNodeWithContentDescription("拍照，长按录像").performClick()
            waitEditor()
            compose.onNodeWithTag("note-input").performTextInput("第一张，现场检查")
            screenshot("note-sheet.png")
            compose.onNodeWithText("收起").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("note-input").fetchSemanticsNodes().isEmpty() }
            waitCamera()
            compose.onNodeWithContentDescription("拍照，长按录像").performClick()
            waitFor { container.captureTasks.awaitingNotes.first().size==2 }
            compose.onAllNodesWithTag("note-input").assertCountEquals(0)
            compose.onNodeWithText(a.name).performClick();compose.onNodeWithText(b.name).performClick()
            waitCamera()
            val zoom=if(compose.onAllNodesWithText("2×").fetchSemanticsNodes().isNotEmpty()) 2.0 else 1.0
            if(zoom==2.0) compose.onAllNodesWithText("2×").onLast().performClick()
            // Deliberately do not wait for the zoom animation: recording must use the target selection.
            compose.onNodeWithContentDescription("拍照，长按录像").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("停止录像").fetchSemanticsNodes().isNotEmpty() }
            SystemClock.sleep(2000)
            val recording=runBlocking { container.captureTasks.outstanding.first().single { it.categoryId==b.id } }
            val frozen=recording.photoDateStampText
            assertNotNull(frozen);assertNotNull(recording.recordingStartedAt)
            compose.onNodeWithContentDescription("暂停录像").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("继续录像").fetchSemanticsNodes().isNotEmpty() }
            SystemClock.sleep(250)
            compose.onNodeWithContentDescription("继续录像").performClick();SystemClock.sleep(1000)
            compose.onNodeWithContentDescription("停止录像").performClick()
            waitFor { container.captureTasks.awaitingNotes.first().size==3 }
            waitCamera()
            if (zoom==2.0) compose.waitUntil(30_000) {
                // One label is the preset; the other is the camera's current reported zoom.
                compose.onAllNodesWithText("2×").fetchSemanticsNodes().size>=2
            }
            val pendingVideo=runBlocking { container.captureTasks.awaitingNotes.first().single { it.id==recording.id } }
            assertEquals(frozen,pendingVideo.photoDateStampText)
            if(InstrumentationRegistry.getArguments().getString("exportReviewSamples")=="true") {
                File(app.filesDir,"v091-review").apply { mkdirs() }.resolve("camera-video-original.mp4")
                    .let { File(checkNotNull(pendingVideo.sourcePath)).copyTo(it,overwrite=true) }
            }
            compose.onAllNodesWithTag("note-input").assertCountEquals(0)
            val events=File(app.filesDir,"capture-events/events.jsonl")
            var start:JSONObject?=null
            compose.waitUntil(10000) {
                start=events.takeIf{it.exists()}?.readLines()?.takeLast(100)?.mapNotNull { runCatching{JSONObject(it)}.getOrNull() }
                    ?.lastOrNull{it.optString("event")=="video_started" && it.optString("taskId")==recording.id}
                start!=null
            }
            assertEquals(zoom,start!!.getDouble("appliedZoom"),.05)
            assertEquals(zoom,start!!.getDouble("captureResultZoom"),.15)
            screenshot("pending-note-entry.png")
            compose.onNodeWithText("待处理 3").performClick();waitEditor()
            compose.onNodeWithTag("note-input").assertTextContains("第一张，现场检查")
            compose.onNodeWithText("保存到：${a.directoryLabel}").assertIsDisplayed()
            runBlocking { container.settings.setTimeStampEnabled(false);container.settings.setPostCaptureNoteEnabled(false) }
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("note-input").performTextReplacement("第二张")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("保存到：${b.directoryLabel}").assertIsDisplayed()
            compose.onNodeWithTag("note-input").performTextReplacement("录像后补充的备注")
            screenshot("video-note-sheet.png")
            compose.onNodeWithText("保存").performClick()
            waitFor {
                val tasks=container.captureTasks.outstanding.first().filter{it.categoryId in owned}
                val problem=tasks.firstOrNull{it.taskStage.isRetryable || it.taskStage==CaptureTaskStage.FAILED}
                assertNull("Actual capture processing failed: ${problem?.error}",problem)
                tasks.isEmpty()
            }
            val parent=checkNotNull(root.findFile(group.name))
            val photos=checkNotNull(parent.findFile(a.name)).listFiles().filter{it.type=="image/jpeg"}
            val video=checkNotNull(parent.findFile(b.name)).listFiles().single{it.type=="video/mp4"}
            assertEquals(2,photos.size)
            if(InstrumentationRegistry.getArguments().getString("exportReviewSamples")=="true") {
                val review=File(app.filesDir,"v091-review").apply{mkdirs()}
                photos.sortedBy{it.name}.forEachIndexed { index,file ->
                    app.contentResolver.openInputStream(file.uri)!!.use { input -> review.resolve("camera-note-${index+1}.jpg").outputStream().use(input::copyTo) }
                }
                app.contentResolver.openInputStream(video.uri)!!.use { input -> review.resolve("camera-video-note.mp4").outputStream().use(input::copyTo) }
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(app,video.uri)
                    listOf(0L to "video-first-frame.png",1_000_000L to "video-middle-frame.png").forEach { (time,name) ->
                        val bitmap=checkNotNull(retriever.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST))
                        try { review.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
                        finally { bitmap.recycle() }
                    }
                }
            }
        } finally {
            runBlocking {
                container.settings.setTimeStampEnabled(before.timeStampEnabled)
                container.settings.setTimeStampFormat(before.timeStampFormat)
                container.settings.setPostCaptureNoteEnabled(before.postCaptureNoteEnabled)
                container.settings.setSelectedGroupId(before.selectedGroupId)
                val pending=container.captureTasks.outstanding.first().filter{it.categoryId in owned}
                if(pending.isEmpty()) {
                    container.categories.findById(a.id)?.let { container.directoryOperations.deleteCategory(it).getOrThrow() }
                    container.categories.findById(b.id)?.let { container.directoryOperations.deleteCategory(it).getOrThrow() }
                    container.directoryOperations.deleteGroup(group).getOrThrow()
                } else Log.e("LenshelfDeviceTest","Retained this invocation's fixture and originals for diagnosis: ${group.id}")
            }
        }
    }
}
