package com.example.photocategorycamera.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.photocategorycamera.document.DocumentCapturePreview
import com.example.photocategorycamera.document.DocumentProcessor
import com.example.photocategorycamera.domain.*
import com.example.photocategorycamera.storage.MediaStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DocumentDraftRepositoryDeviceTest {
    private class Fixture(val context: Context, val db: AppDatabase, val scope: CoroutineScope, val settings: SettingsRepository) {
        val storage = RetryStorage()
        val tasks = CaptureTaskRepository(context, db, settings, storage, scope)
        val processor = DocumentProcessor()
        fun newRepository() = DocumentDraftRepository(context, db, processor, tasks, scope)
        val documents = newRepository()
        val category = Category("fixture", "课件", 0, true, 1)
        val root = StorageRoot("content://fixture/root-a", "原目录", 1)

        fun writePhoto(file: File) {
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            bitmap.recycle()
        }

        suspend fun complete(repository: DocumentDraftRepository, reservation: DocumentReservation, boundary: DocumentQuad? = null): Result<DocumentCapturePreview?> {
            val completion = CompletableDeferred<Result<DocumentCapturePreview?>>()
            repository.completeCapture(reservation.id, boundary) { completion.complete(it) }
            return withTimeout(15_000) { completion.await() }
        }

        suspend fun retryTask(id: String) = withTimeout(15_000) {
            tasks.outstanding.first { list -> list.any { it.id == id && it.taskStage == CaptureTaskStage.RETRY } }.single { it.id == id }
        }
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val files = File(appContext.cacheDir, "document-test-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val isolatedContext = object : ContextWrapper(appContext) {
            override fun getFilesDir(): File = files
            override fun getApplicationContext(): Context = this
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java).build()
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(files, "fixture.preferences_pb") })
        try { withTimeout(45_000) {
            val settings = SettingsRepository(isolatedContext, store)
            settings.setStorageRoot(StorageRoot("content://fixture/root-a", "原目录", 1))
            db.categoryGroupDao().ensureHome()
            db.categoryDao().insert(CategoryEntity("fixture", "课件", 0, true, 1))
            block(Fixture(isolatedContext, db, scope, settings))
        } }
        finally { scope.coroutineContext.job.cancelAndJoin(); db.close(); files.deleteRecursively() }
    }

    @Test fun automaticSavingIsIdempotentAndRetainsSourcesUntilSuccessfulReadBack() = withFixture { f ->
        val reservation = f.documents.reserve(f.category, f.root, DocumentEffect.GRAYSCALE).getOrThrow()
        assertEquals(CaptureTaskStage.DOCUMENT_PROCESSING, f.db.captureTaskDao().find(reservation.id)!!.taskStage)
        assertNull(f.db.documentDraftDao().editable())
        f.writePhoto(reservation.file)
        val preview = f.complete(f.documents, reservation).getOrThrow()!!
        assertEquals(DocumentQuad.FULL_IMAGE, preview.quad)
        preview.recycleUnpublished()
        assertNull(f.complete(f.documents, reservation).getOrThrow())
        val queued = f.retryTask(reservation.id)
        assertEquals(1, f.tasks.outstanding.first().size)
        assertEquals(MediaKind.PHOTO, queued.kind)
        assertEquals(f.root.treeUri, queued.rootTreeUri)
        assertEquals(f.category.id, queued.categoryId)
        assertEquals(DocumentEffect.GRAYSCALE, f.db.documentDraftDao().find(reservation.id)!!.effect)
        assertTrue(reservation.file.exists())
        assertTrue(File(queued.tempPath).exists())
        assertTrue(f.tasks.hasOutstandingForCategory(f.category.id))
        f.storage.allowSave.set(true)
        f.tasks.retryAll()
        withTimeout(15_000) { while (f.db.documentDraftDao().find(reservation.id) != null) delay(20) }
        assertFalse(reservation.file.exists())
        assertFalse(File(queued.tempPath).exists())
        assertFalse(f.tasks.hasOutstandingForCategory(f.category.id))
    }

    @Test fun interruptedCompleteSourceAutomaticallyRecoversWithItsFrozenTarget() = withFixture { f ->
        val first = f.documents.reserve(f.category, f.root, DocumentEffect.ENHANCED).getOrThrow()
        f.writePhoto(first.file)
        val restarted = f.newRepository()
        val recovered = f.retryTask(first.id)
        assertEquals(f.root.treeUri, recovered.rootTreeUri)
        assertEquals(f.category.name, recovered.categoryName)
        assertEquals(DocumentEffect.ENHANCED, f.db.documentDraftDao().find(first.id)!!.effect)
        assertNull(restarted.editable.first())
        f.db.categoryDao().insert(CategoryEntity("other", "其他", 1, true, 2))
        f.settings.setStorageRoot(f.root.copy(treeUri = "content://fixture/root-b"))
        val second = restarted.reserve(f.category.copy(id = "other", name = "其他"),
            f.root.copy(treeUri = "content://fixture/root-b")).getOrThrow()
        f.writePhoto(second.file)
        f.complete(restarted, second).getOrThrow()?.recycleUnpublished()
        assertEquals(f.root.treeUri, f.db.captureTaskDao().find(first.id)!!.rootTreeUri)
        assertEquals("content://fixture/root-b", f.retryTask(second.id).rootTreeUri)
    }

    @Test fun unverifiedPreviewBoundaryKeepsFullJpegAndRetryKeepsTheChosenRecipe() = withFixture { f ->
        val boundary = DocumentQuad(listOf(DocumentPoint(0.1f, 0.2f), DocumentPoint(0.8f, 0.2f),
            DocumentPoint(0.8f, 0.9f), DocumentPoint(0.1f, 0.9f)))
        val reservation = f.documents.reserve(f.category, f.root).getOrThrow()
        f.writePhoto(reservation.file) // Blank JPEG deliberately has no detectable contour.
        assertNull(f.processor.detect(reservation.file))
        val preview = f.complete(f.documents, reservation, boundary).getOrThrow()!!
        assertEquals(DocumentQuad.FULL_IMAGE, preview.quad)
        assertEquals(399, preview.result.width)
        assertEquals(299, preview.result.height)
        preview.recycleUnpublished()
        f.retryTask(reservation.id)
        assertEquals(DocumentQuad.FULL_IMAGE, f.db.documentDraftDao().find(reservation.id)!!.quad)
        assertNull(f.complete(f.documents, reservation, DocumentQuad.FULL_IMAGE).getOrThrow())
        assertEquals(DocumentQuad.FULL_IMAGE, f.db.documentDraftDao().find(reservation.id)!!.quad)
        assertEquals(1, f.db.captureTaskDao().countOutstanding())
    }

    @Test fun interruptedCaptureValidatesPersistedPreviewBoundaryDuringRecovery() = withFixture { f ->
        val boundary = DocumentQuad(listOf(DocumentPoint(0.2f, 0.1f), DocumentPoint(0.8f, 0.1f),
            DocumentPoint(0.8f, 0.9f), DocumentPoint(0.2f, 0.9f)))
        val reservation = f.documents.reserve(f.category, f.root).getOrThrow()
        f.writePhoto(reservation.file)
        f.db.documentDraftDao().setCaptureBoundary(reservation.id, boundary.encode())
        val restarted = f.newRepository()
        restarted.submit("absent").getOrThrow() // Wait for recovery.
        val task = f.retryTask(reservation.id)
        assertEquals(DocumentQuad.FULL_IMAGE, f.db.documentDraftDao().find(reservation.id)!!.quad)
        val result = f.processor.loadUpright(File(task.tempPath), 500)
        try { assertEquals(399, result.width); assertEquals(299, result.height) } finally { result.recycle() }
    }

    @Test fun truncatedCaptureBecomesExplicitFailureWithoutDeletingMaterials() = withFixture { f ->
        val reservation = f.documents.reserve(f.category, f.root).getOrThrow()
        f.writePhoto(reservation.file)
        RandomAccessFile(reservation.file, "rw").use { it.setLength(it.length() - 2) }
        assertTrue(f.complete(f.documents, reservation).isFailure)
        assertEquals(CaptureTaskStage.FAILED, f.db.captureTaskDao().find(reservation.id)!!.taskStage)
        assertEquals("AUTO_FAILED", f.db.documentDraftDao().find(reservation.id)!!.stage)
        assertTrue(reservation.file.exists())
        assertEquals(0, f.storage.writes)
        assertEquals(1, f.tasks.discardFailed())
        assertFalse(reservation.file.exists())
    }

    @Test fun processingFailureRetainsOriginalAndCanBeRetried() = withFixture { f ->
        val reservation = f.documents.reserve(f.category, f.root).getOrThrow()
        f.writePhoto(reservation.file)
        val blocker = File(reservation.file.parentFile, reservation.id + "_processed.jpg").apply { check(mkdir()) }
        assertTrue(f.complete(f.documents, reservation).isFailure)
        assertEquals(CaptureTaskStage.DOCUMENT_RETRY, f.db.captureTaskDao().find(reservation.id)!!.taskStage)
        assertTrue(reservation.file.exists())
        assertTrue(blocker.delete())
        f.documents.retryProcessing()
        f.retryTask(reservation.id)
        assertEquals(1, f.tasks.outstanding.first().size)
    }

    @Test fun eightAutomaticCapturesReserveQueueCapacityBeforeProcessing() = withFixture { f ->
        repeat(CaptureTaskRepository.MAX_OUTSTANDING) { f.documents.reserve(f.category, f.root).getOrThrow() }
        assertTrue(f.documents.reserve(f.category, f.root).isFailure)
        assertTrue(f.tasks.reserve(f.category, f.root, MediaKind.PHOTO) { error("Must not create an extra source") }.isFailure)
        assertEquals(8, f.db.captureTaskDao().countOutstanding())
    }

    @Test fun oldUnconfirmedDraftIsNotAutomaticallyArchivedAndKeepsItsRecipe() = withFixture { f ->
        // Await initialization before injecting a genuine 0.7.0 record.
        f.documents.submit("absent").getOrThrow()
        val source = File(f.context.filesDir, "legacy.jpg")
        f.writePhoto(source)
        val old = DocumentDraftEntity("legacy", source.absolutePath, f.category.id, f.category.name, f.root.treeUri,
            effectValue = DocumentEffect.GRAYSCALE.name, quarterTurns = 1, stage = "EDITING", createdAt = 1)
        f.db.documentDraftDao().insert(old)
        val restarted = f.newRepository()
        restarted.submit("absent").getOrThrow()
        assertEquals(old, restarted.editable.first())
        assertTrue(f.tasks.outstanding.first().isEmpty())
        assertEquals(0, f.storage.writes)
        restarted.submit(old.id).getOrThrow()
        restarted.submit(old.id).getOrThrow()
        val queued = f.retryTask(old.id)
        val image = f.processor.loadUpright(File(queued.tempPath), 500)
        assertEquals(299, image.width); assertEquals(399, image.height)
        image.recycle()
        assertTrue(source.exists())
        assertNull(restarted.editable.first())
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
