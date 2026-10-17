package com.example.photocategorycamera.storage.operations

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafDirectoryBackendDeviceTest {
    @Test fun nullAndLoadingProviderListingsAreNotTreatedAsEmptyDirectories() {
        val provider = ListingProvider()
        val backend = SafDirectoryBackend(ContentResolver.wrap(provider))
        val root = "content://lenshelf-fixture/tree/root/document/root"
        for (mode in listOf("null", "loading", "error", "permission")) {
            provider.mode = mode
            try { backend.children(root); fail("Incomplete provider result was accepted: $mode") }
            catch (_: IOException) { }
            catch (_: SecurityException) { }
        }
        provider.mode = "empty"
        assertTrue(backend.children(root).isEmpty())
    }

    @Test fun grantedRootCopiesHiddenNestedBinaryContentAndThenRenames() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
        val root = app.container.settings.settings.first().storageRoot
        assertNotNull("Requires the already-granted test storage root", root)
        val rootDocument = DocumentFile.fromTreeUri(app, Uri.parse(root!!.treeUri))!!
        val fixture = rootDocument.createDirectory("directory_engine_${UUID.randomUUID()}")!!
        try {
            val sourceParent = fixture.createDirectory("source")!!
            val targetParent = fixture.createDirectory("target")!!
            val source = sourceParent.createDirectory("category")!!
            val nested = source.createDirectory("nested")!!
            val hidden = source.createFile("application/octet-stream", ".hidden")!!
            val binary = nested.createFile("application/octet-stream", "data.bin")!!
            val bytes = byteArrayOf(0, 1, 127, -128, -1)
            app.contentResolver.openOutputStream(hidden.uri, "wt")!!.use { it.write("hidden".toByteArray()) }
            app.contentResolver.openOutputStream(binary.uri, "wt")!!.use { it.write(bytes) }
            val actualHiddenName = hidden.name!!
            val actualBinaryName = binary.name!!
            val saf = SafDirectoryBackend(app.contentResolver)
            // Force the fallback on a real provider; native support varies across storage providers.
            val fallback = object : DirectoryBackend by saf {
                override fun children(parentUri: String) = saf.children(parentUri).map { it.copy(supportsMove = false, supportsRename = false) }
            }
            var journal: DirectoryMutationState? = null
            val request = DirectoryMutationRequest("TRANSFER_CATEGORY", sourceParent.uri.toString(), targetParent.uri.toString(), "category", "category")
            val done = DirectoryMutationEngine(fallback).execute(request) {
                journal = DirectoryMutationStateCodec.decode(DirectoryMutationStateCodec.encode(it))
            }
            assertEquals("FILESYSTEM_DONE", done.phase)
            assertEquals(done, journal)
            assertNull(sourceParent.findFile("category"))
            val target = targetParent.findFile("category")!!
            assertEquals("hidden", app.contentResolver.openInputStream(target.findFile(actualHiddenName)!!.uri)!!.use { String(it.readBytes()) })
            assertArrayEquals(bytes, app.contentResolver.openInputStream(target.findFile("nested")!!.findFile(actualBinaryName)!!.uri)!!.use { it.readBytes() })
            val renamed = DirectoryMutationEngine(saf).execute(request.copy(kind = "RENAME_CATEGORY", sourceParentUri = targetParent.uri.toString(), targetName = "renamed")) {}
            assertEquals("FILESYSTEM_DONE", renamed.phase)
            assertNull(targetParent.findFile("category"))
            assertNotNull(targetParent.findFile("renamed"))
            val supportsMove = saf.children(targetParent.uri.toString()).single { it.name == "renamed" }.supportsMove
            var nativeMoves = 0
            val observed = object : DirectoryBackend by saf {
                override fun move(uri: String, sourceParentUri: String, targetParentUri: String): DirectoryNode {
                    nativeMoves++
                    return saf.move(uri, sourceParentUri, targetParentUri)
                }
            }
            DirectoryMutationEngine(observed).execute(DirectoryMutationRequest("TRANSFER_CATEGORY",
                targetParent.uri.toString(), sourceParent.uri.toString(), "renamed", "renamed")) {}
            assertEquals(if (supportsMove) 1 else 0, nativeMoves)
            assertNull(targetParent.findFile("renamed"))
            val moved = sourceParent.findFile("renamed")!!
            assertArrayEquals(bytes, app.contentResolver.openInputStream(moved.findFile("nested")!!.findFile(actualBinaryName)!!.uri)!!.use { it.readBytes() })
            android.util.Log.i("LenshelfDeviceTest", "SAF provider native directory move exercised=$supportsMove")
        } finally { assertTrue("Fixture cleanup failed", fixture.delete()) }
    }

    private class ListingProvider : ContentProvider() {
        var mode = "null"
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (mode == "null") return null
            if (mode == "permission") throw SecurityException("Revoked permission")
            return MatrixCursor(projection ?: emptyArray()).apply {
                extras = Bundle().apply {
                    if (mode == "loading") putBoolean(DocumentsContract.EXTRA_LOADING, true)
                    if (mode == "error") putString(DocumentsContract.EXTRA_ERROR, "Provider unavailable")
                }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unused")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Unused")
    }
}
