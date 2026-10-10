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

        suspend fun complete(repository: DocumentDraftRepository, reservation: DocumentReservation): DocumentDraftEntity {
            writePhoto(reservation.file)
            val completion = CompletableDeferred<Result<DocumentDraftEntity>>()
            repository.completeCapture(reservation.id) { completion.complete(it) }
            return withTimeout(15_000) { completion.await().getOrThrow() }
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
        try { withTimeout(30_000) { block(Fixture(isolatedContext, db, scope, SettingsRepository(isolatedContext, store))) } }
        finally { scope.coroutineContext.job.cancelAndJoin(); db.close(); files.deleteRecursively() }
    }

    @Test fun unconfirmedDraftSurvivesRestartAndRetakeKeepsItsFrozenTarget() = withFixture { f ->
        val reservation = f.documents.reserve(f.category, f.root).getOrThrow()
        val original = f.complete(f.documents, reservation)
        f.documents.edit(original.id, original.quad, DocumentEffect.GRAYSCALE, 1)
        val restarted = f.newRepository()
        val restored = restarted.editable.first()!!
        assertEquals(DocumentEffect.GRAYSCALE, restored.effect)
        assertEquals(1, restored.quarterTurns)
        assertTrue(f.db.captureTaskDao().observeOutstanding().first().isEmpty())
        assertEquals(0, f.storage.writes)
        val otherCategory = f.category.copy(id = "other", name = "其他")
        val otherRoot = f.root.copy(treeUri = "content://fixture/root-b")
        val replacement = restarted.reserve(otherCategory, otherRoot, restored.id).getOrThrow()
        assertTrue(File(restored.sourcePath).exists())
        val retaken = f.complete(restarted, replacement)
        assertEquals(f.root.treeUri, retaken.rootTreeUri)
        assertEquals(f.category.id, retaken.categoryId)
        assertFalse(File(restored.sourcePath).exists())
        assertTrue(File(retaken.sourcePath).exists())

        restarted.submit(retaken.id).getOrThrow()
        restarted.submit(retaken.id).getOrThrow()
        val queued = withTimeout(15_000) {
            f.tasks.outstanding.first { it.singleOrNull()?.taskStage == CaptureTaskStage.RETRY }.single()
        }
        assertEquals(retaken.id, queued.id)
        assertEquals(MediaKind.PHOTO, queued.kind)
        assertEquals(f.root.treeUri, queued.rootTreeUri)
        assertTrue(File(retaken.sourcePath).exists())
        assertTrue(File(queued.tempPath).exists())
        assertTrue(f.tasks.hasOutstandingForCategory(f.category.id))
        f.storage.allowSave.set(true)
        f.tasks.retryAll()
        withTimeout(15_000) {
            while (f.db.documentDraftDao().find(retaken.id) != null) delay(20)
        }
        assertFalse(File(retaken.sourcePath).exists())
        assertFalse(File(queued.tempPath).exists())
    }

    @Test fun interruptedCaptureReturnsToReviewWithoutAutomaticSaving() = withFixture { f ->
        val reservation = f.documents.reserve(f.category, f.root).getOrThrow()
        f.writePhoto(reservation.file)
        val restarted = f.newRepository()
        val recovered = withTimeout(15_000) { restarted.editable.first { it?.stage == "EDITING" }!! }
        assertEquals(reservation.id, recovered.id)
        assertTrue(reservation.file.exists())
        assertTrue(f.tasks.outstanding.first().isEmpty())
        assertEquals(0, f.storage.writes)
    }

    @Test fun truncatedRetakePreservesThePreviousImageAndItsEdits() = withFixture { f ->
        val first = f.documents.reserve(f.category, f.root).getOrThrow()
        val original = f.complete(f.documents, first)
        f.documents.edit(original.id, original.quad, DocumentEffect.GRAYSCALE, 1)
        val bytes = File(original.sourcePath).readBytes()
        val retake = f.documents.reserve(f.category, f.root, original.id).getOrThrow()
        f.writePhoto(retake.file)
        RandomAccessFile(retake.file, "rw").use { it.setLength(it.length() - 2) }
        val completion = CompletableDeferred<Result<DocumentDraftEntity>>()
        f.documents.completeCapture(retake.id) { completion.complete(it) }
        assertTrue(withTimeout(15_000) { completion.await() }.isFailure)
        f.documents.captureFailed(retake.id)
        val restored = withTimeout(15_000) {
            f.documents.editable.first { it?.replacementPath == null }!!
        }
        assertEquals(DocumentEffect.GRAYSCALE, restored.effect)
        assertEquals(1, restored.quarterTurns)
        assertArrayEquals(bytes, File(restored.sourcePath).readBytes())
        assertTrue(f.tasks.outstanding.first().isEmpty())
        assertEquals(0, f.storage.writes)
    }

    @Test fun onlyOneEditableDraftAndDiscardDoesNotTouchArchivedFiles() = withFixture { f ->
        val unrelated = File(f.context.filesDir, "archived.jpg")
        f.writePhoto(unrelated)
        val bytes = unrelated.readBytes()
        val reservation = f.documents.reserve(f.category, f.root).getOrThrow()
        f.complete(f.documents, reservation)
        assertTrue(f.documents.reserve(f.category, f.root).isFailure)
        assertTrue(f.tasks.hasOutstandingForCategory(f.category.id))
        f.documents.discard(reservation.id)
        assertNull(f.db.documentDraftDao().editable())
        assertFalse(reservation.file.exists())
        assertArrayEquals(bytes, unrelated.readBytes())
    }

    private class RetryStorage : MediaStorage {
        val allowSave = AtomicBoolean(false)
        @Volatile var writes = 0
        override suspend fun prepareRoot(treeUri: Uri, categories: List<Category>) = Result.success(StorageRoot(treeUri.toString(), "fixture", 1))
        override suspend fun listCategoryDirectories(treeUri: Uri) = Result.success(emptyList<String>())
        override suspend fun countCategoryMedia(treeUri: Uri, categoryNames: List<String>) = Result.success(emptyMap<String, Int>())
        override suspend fun ensureCategory(treeUri: Uri, categoryName: String) = Result.success(Unit)
        override suspend fun categoryDirectoryUri(treeUri: Uri, categoryName: String) = Result.success(treeUri)
        override suspend fun renameCategoryDirectory(treeUri: Uri, oldName: String, newName: String) = Result.success(Unit)
        override suspend fun deleteCategoryDirectoryWithContents(treeUri: Uri, categoryName: String) = Result.success(Unit)
        override suspend fun reserveTarget(treeUri: Uri, category: Category, mediaKind: MediaKind) = Result.success(Uri.parse("content://fixture/target"))
        override suspend fun storeTempMedia(treeUri: Uri, tempFile: File, category: Category, mediaKind: MediaKind) =
            storeTempMediaToTarget(treeUri, tempFile, category, mediaKind)
        override suspend fun storeTempMediaToTarget(targetUri: Uri, tempFile: File, category: Category, mediaKind: MediaKind): CaptureResult {
            writes++
            return CaptureResult(category.id, targetUri.toString(),
                if (allowSave.get()) CaptureStatus.SAVED else CaptureStatus.RETRY_AVAILABLE, error = "模拟权限失效")
        }
        override suspend fun retry(treeUri: Uri, pending: PendingMedia) =
            CaptureResult(pending.categoryId, status = CaptureStatus.RETRY_AVAILABLE)
        override fun hasPersistedAccess(treeUri: Uri) = true
    }
}
