package com.example.photocategorycamera.data

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.photocategorycamera.domain.*
import com.example.photocategorycamera.storage.MediaStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureNoteRepositoryDeviceTest {
    @Test fun videoOutOfMemoryPreservesOriginalAndReleasesCodecForOtherWork() =
        withFixture(CoroutineExceptionHandler { _, _ -> }) { f ->
            f.videoWriter = com.example.photocategorycamera.video.VideoStampWriter { _, _, _, _ -> throw OutOfMemoryError("injected") }
            f.settings.setTimeStampEnabled(true)
            f.settings.setPostCaptureNoteEnabled(true)
            val source = File(f.context.filesDir, "oom-original.mp4").apply { writeText("original video") }
            val video = f.tasks.reserve(f.category, f.root, MediaKind.VIDEO) { source }.getOrThrow()
            f.tasks.markVideoStarted(video.id).getOrThrow()
            f.tasks.markReady(video.id, MediaKind.VIDEO)
            f.awaitStage(video.id, CaptureTaskStage.AWAITING_NOTE)
            f.tasks.confirmAnnotation(video.id, "保留的备注").getOrThrow()
            val retry = withTimeout(5_000) { f.awaitStage(video.id, CaptureTaskStage.VIDEO_RETRY) }
            assertEquals("保留的备注", retry.noteText)
            assertEquals("original video", source.readText())
            withTimeout(3_000) { f.tasks.videoCodecs.acquireRecording().release() }
            f.settings.setTimeStampEnabled(false)
            f.settings.setPostCaptureNoteEnabled(false)
            val photo = f.reservePhoto("after-oom.jpg")
            f.tasks.markReady(photo.id, MediaKind.PHOTO)
            withTimeout(5_000) { f.awaitStage(photo.id, CaptureTaskStage.RETRY) }
            assertTrue(f.storage.writes > 0)
        }
    @Test fun awaitingNotesDoNotExhaustTheEightActiveSlotsButStillProtectTheirCategory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.captureTaskDao()
            repeat(9) { index -> dao.insert(CaptureTaskEntity(
                "note-$index", "source.jpg", "category", "分类", "content://root", "PHOTO", "AWAITING_NOTE",
                createdAt = index.toLong(), updatedAt = 1,
            )) }
            assertEquals(0, dao.countOutstanding())
            assertEquals(9, dao.countOutstandingForCategory("category"))
        } finally { db.close() }
    }

    @Test fun notesAreIndependentAndOnlyTheFirstInFlightCaptureOwnsTheAutoPrompt() = withFixture { f ->
        f.settings.setPostCaptureNoteEnabled(true)
        assertFalse(f.settings.settings.first().timeStampEnabled)
        val first = f.reservePhoto("one.jpg")
        val second = f.reservePhoto("two.jpg")
        assertTrue(first.autoPromptEligible)
        assertFalse(second.autoPromptEligible)
        f.tasks.markReady(first.id, MediaKind.PHOTO)
        f.tasks.markReady(second.id, MediaKind.PHOTO)
        f.awaitStage(first.id, CaptureTaskStage.AWAITING_NOTE)
        f.awaitStage(second.id, CaptureTaskStage.AWAITING_NOTE)
        assertEquals(0, f.db.captureTaskDao().countOutstanding())
        assertEquals(2, f.tasks.awaitingNotes.first().size)
        assertEquals(0, f.storage.writes)
        f.tasks.updateAnnotationDraft(first.id, "待检查").getOrThrow()
        f.settings.setPostCaptureNoteEnabled(false)
        f.settings.setStorageRoot(StorageRoot("content://fixture/root-b", "新目录", 2))
        val pending = f.db.captureTaskDao().find(first.id)!!
        assertEquals("待检查", pending.noteDraft)
        assertEquals("content://fixture/root-a", pending.rootTreeUri)
        assertNull(pending.photoDateStampText)
        assertTrue(File(pending.sourcePath!!).exists())
    }

    @Test fun aFullSaveQueueKeepsTheDraftAndAnEmptyNoteCanBeConfirmedIdempotently() = withFixture { f ->
        f.settings.setPostCaptureNoteEnabled(true)
        val photo = f.reservePhoto("one.jpg")
        f.tasks.markReady(photo.id, MediaKind.PHOTO)
        f.awaitStage(photo.id, CaptureTaskStage.AWAITING_NOTE)
        repeat(8) { index -> f.db.captureTaskDao().insert(CaptureTaskEntity(
            "busy-$index", "source.jpg", "other", "其他", "content://fixture/root-a", "PHOTO", "RETRY", createdAt=0,updatedAt=0,
        )) }
        assertTrue(f.tasks.confirmAnnotation(photo.id, "设备已检查").isFailure)
        val held = f.db.captureTaskDao().find(photo.id)!!
        assertEquals(CaptureTaskStage.AWAITING_NOTE, held.taskStage)
        assertEquals("设备已检查", held.noteDraft)
        repeat(8) { f.db.captureTaskDao().delete("busy-$it") }
        f.tasks.confirmAnnotation(photo.id, "").getOrThrow()
        f.awaitStage(photo.id, CaptureTaskStage.RETRY)
        val writes = f.storage.writes
        f.tasks.confirmAnnotation(photo.id, "").getOrThrow()
        assertEquals(writes, f.storage.writes)
        assertTrue(f.tasks.awaitingNotes.first().isEmpty())
        assertTrue(photo.tempFile.exists())
    }

    @Test fun restartingDoesNotAutoConfirmNotesOrChangeTheirFrozenTargets() = withFixture { f ->
        f.settings.setPostCaptureNoteEnabled(true)
        val photo = f.reservePhoto("one.jpg")
        f.tasks.markReady(photo.id, MediaKind.PHOTO)
        f.awaitStage(photo.id, CaptureTaskStage.AWAITING_NOTE)
        f.tasks.updateAnnotationDraft(photo.id, "重新打开继续填写").getOrThrow()
        val recovered = CaptureTaskRepository(f.context, f.db, f.settings, f.storage, f.scope)
        val waiting = withTimeout(5000) { recovered.awaitingNotes.first { it.size == 1 } }.single()
        assertEquals("重新打开继续填写", waiting.noteDraft)
        assertEquals("fixture", waiting.categoryId)
        assertEquals("content://fixture/root-a", waiting.rootTreeUri)
        assertEquals(0, f.storage.writes)
    }

    @Test fun videoTimeFreezesAtActualStartInsteadOfTaskReservationOrSave() = withFixture { f ->
        f.settings.setTimeStampEnabled(true)
        f.settings.setTimeStampFormat(TimeStampFormat.DATE_TIME)
        f.clock = java.time.Instant.parse("2026-11-03T15:59:00Z").toEpochMilli()
        f.zone = java.time.ZoneId.of("Asia/Shanghai")
        val tasks = f.tasks
        val video = tasks.reserve(f.category, f.root, MediaKind.VIDEO) { File(f.context.filesDir, "one.mp4") }.getOrThrow()
        assertNull(f.db.captureTaskDao().find(video.id)!!.photoDateStampText)
        f.clock = java.time.Instant.parse("2026-11-03T16:00:00Z").toEpochMilli()
        tasks.markVideoStarted(video.id).getOrThrow()
        assertEquals("2026-11-04 00:00", f.db.captureTaskDao().find(video.id)!!.photoDateStampText)
        f.clock += 86400000
        f.zone = java.time.ZoneId.of("UTC")
        f.settings.setTimeStampEnabled(false)
        tasks.markVideoStarted(video.id).getOrThrow()
        assertEquals("2026-11-04 00:00", f.db.captureTaskDao().find(video.id)!!.photoDateStampText)
    }

    @Test fun confirmedVideoUsesItsFrozenRecipeAndRetainsTheOriginalUntilSafVerification() = withFixture { f ->
        f.settings.setTimeStampEnabled(true)
        f.settings.setPostCaptureNoteEnabled(true)
        val source = File(f.context.filesDir,"note-video.mp4")
        val video = f.tasks.reserve(f.category,f.root,MediaKind.VIDEO) {
            source.also { com.example.photocategorycamera.video.VideoStampFixture.write(it) }
        }.getOrThrow()
        f.tasks.markVideoStarted(video.id).getOrThrow()
        val frozen = f.db.captureTaskDao().find(video.id)!!.photoDateStampText
        val original = source.readBytes()
        f.tasks.markReady(video.id,MediaKind.VIDEO)
        f.awaitStage(video.id,CaptureTaskStage.AWAITING_NOTE)
        f.settings.setTimeStampEnabled(false)
        f.settings.setPostCaptureNoteEnabled(false)
        f.tasks.confirmAnnotation(video.id,"录制后填写的备注").getOrThrow()
        val ready = f.awaitStage(video.id,CaptureTaskStage.RETRY)
        assertEquals(frozen,ready.photoDateStampText)
        assertEquals("录制后填写的备注",ready.noteText)
        assertNotEquals(source.path,ready.tempPath)
        assertArrayEquals(original,source.readBytes())
        f.storage.allowSave.set(true)
        f.tasks.retryAll()
        withTimeout(15000) { while(f.db.captureTaskDao().find(video.id)!=null) delay(20) }
        assertFalse(source.exists());assertFalse(File(ready.tempPath).exists())
    }

    @Test fun queuedVideoWaitsWhileARecordingOwnsTheCodecAndResumesWhenItIsReleased() = withFixture { f ->
        f.settings.setTimeStampEnabled(true)
        val source=File(f.context.filesDir,"held-video.mp4")
        val video=f.tasks.reserve(f.category,f.root,MediaKind.VIDEO) {
            source.also { com.example.photocategorycamera.video.VideoStampFixture.write(it) }
        }.getOrThrow()
        f.tasks.markVideoStarted(video.id).getOrThrow()
        val lease=f.tasks.videoCodecs.acquireRecording()
        try {
            f.tasks.markReady(video.id,MediaKind.VIDEO)
            f.awaitStage(video.id,CaptureTaskStage.VIDEO_PROCESSING)
            f.tasks.resumeProcessing().join()
            assertEquals(0,f.storage.writes)
            assertEquals(source.path,f.db.captureTaskDao().find(video.id)!!.tempPath)
        } finally { lease.release() }
        f.tasks.resumeProcessing().join()
        f.awaitStage(video.id,CaptureTaskStage.RETRY)
        assertTrue(source.exists())
    }

    private class Fixture(val context: Context, val db: AppDatabase, val scope: CoroutineScope, val settings: SettingsRepository) {
        val storage = RetryStorage()
        var clock = System.currentTimeMillis()
        var zone = java.time.ZoneId.systemDefault()
        var videoWriter: com.example.photocategorycamera.video.VideoStampWriter? = null
        val tasks by lazy { CaptureTaskRepository(context, db, settings, storage, scope, captureTimeMillis={clock}, captureZone={zone},
            videoStamper=videoWriter ?: com.example.photocategorycamera.video.VideoStampProcessor(context)) }
        val category = Category("fixture", "设备", 0, true, 1)
        val root = StorageRoot("content://fixture/root-a", "原目录", 1)
        suspend fun reservePhoto(name: String) = tasks.reserve(category, root, MediaKind.PHOTO) {
            File(context.filesDir, name).also { file ->
                Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).let { bitmap ->
                    bitmap.eraseColor(Color.WHITE)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }
                    bitmap.recycle()
                }
            }
        }.getOrThrow()
        suspend fun awaitStage(id: String, stage: CaptureTaskStage) = withTimeout(30000) {
            tasks.outstanding.first { list -> list.any { it.id == id && it.taskStage == stage } }.single { it.id == id }
        }
    }

    private fun withFixture(handler: CoroutineExceptionHandler? = null, block: suspend (Fixture) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(app.cacheDir, "note-test-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + (handler ?: kotlin.coroutines.EmptyCoroutineContext))
        val db = Room.inMemoryDatabaseBuilder(app,AppDatabase::class.java).build()
        val store = PreferenceDataStoreFactory.create(scope=scope, produceFile={ File(directory,"settings.preferences_pb") })
        try { withTimeout(45000) {
            val settings = SettingsRepository(context,store)
            settings.setStorageRoot(StorageRoot("content://fixture/root-a", "原目录", 1))
            db.categoryGroupDao().ensureHome()
            db.categoryDao().insert(CategoryEntity("fixture","设备",0,true,1))
            block(Fixture(context,db,scope,settings))
        } } finally { scope.coroutineContext.job.cancelAndJoin(); db.close(); directory.deleteRecursively() }
    }
    private class RetryStorage : MediaStorage {
        val allowSave = AtomicBoolean(false)
        @Volatile var writes = 0
        override suspend fun prepareRoot(treeUri: Uri, categories: List<Category>) = Result.success(StorageRoot(treeUri.toString(), "fixture", 1))
        override suspend fun listCategoryDirectories(treeUri: Uri, groupName: String?) = Result.success(emptyList<String>())
        override suspend fun countCategoryMedia(treeUri: Uri, categoryNames: List<String>, groupName: String?) = Result.success(emptyMap<String, Int>())
        override suspend fun ensureCategory(treeUri: Uri, categoryName: String, groupName: String?) = Result.success(Unit)
        override suspend fun categoryDirectoryUri(treeUri: Uri, categoryName: String, groupName: String?) = Result.success(treeUri)
        override suspend fun renameCategoryDirectory(treeUri: Uri, oldName: String, newName: String, groupName: String?) = Result.success(Unit)
        override suspend fun deleteCategoryDirectoryWithContents(treeUri: Uri, categoryName: String, groupName: String?) = Result.success(Unit)
        override suspend fun reserveTarget(treeUri: Uri, category: Category, mediaKind: MediaKind) = Result.success(Uri.parse("content://fixture/target"))
        override suspend fun storeTempMedia(treeUri: Uri, tempFile: File, category: Category, mediaKind: MediaKind) = storeTempMediaToTarget(treeUri, tempFile, category, mediaKind)
        override suspend fun storeTempMediaToTarget(targetUri: Uri, tempFile: File, category: Category, mediaKind: MediaKind): CaptureResult {
            writes++
            return CaptureResult(category.id, targetUri.toString(),
                if (allowSave.get()) CaptureStatus.SAVED else CaptureStatus.RETRY_AVAILABLE, error = "模拟权限失效")
        }
        override suspend fun retry(treeUri: Uri, pending: PendingMedia) = CaptureResult(pending.categoryId, status = CaptureStatus.RETRY_AVAILABLE)
        override fun hasPersistedAccess(treeUri: Uri) = true
    }
}
