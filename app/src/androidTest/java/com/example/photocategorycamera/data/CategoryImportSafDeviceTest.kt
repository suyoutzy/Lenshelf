package com.example.photocategorycamera.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.documentfile.provider.DocumentFile
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import com.example.photocategorycamera.domain.CategoryGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The former root-import tests now exercise refresh inside one unique, owned folder. */
@RunWith(AndroidJUnit4::class)
class CategoryImportSafDeviceTest {
    private fun fixture(block: suspend (PhotoCategoryCameraApplication, Uri, CategoryGroup, DocumentFile) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowHomeDirectoryFixture") == "true")
        val app = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
        val container = app.container
        val root = container.settings.settings.first().storageRoot
        assumeTrue(root != null)
        assumeTrue(container.captureTasks.outstanding.first().isEmpty() && container.directoryOperations.pending.first().isEmpty())
        val uri = Uri.parse(root!!.treeUri)
        val group = container.directoryOperations.createGroup("目录验收_${UUID.randomUUID().toString().take(8)}").getOrThrow()
        val parent = DocumentFile.fromTreeUri(app, uri)!!.findFile(group.name)!!
        try { block(app, uri, group, parent) }
        finally {
            container.categories.getAll(group.id).forEach { container.directoryOperations.deleteCategory(it).getOrThrow() }
            parent.listFiles().forEach { assertTrue("Owned fixture cleanup failed", it.delete()) }
            container.directoryOperations.deleteGroup(group).getOrThrow()
        }
    }

    @Test fun refreshesOnlyOwnedFolderAndIsIdempotent() = fixture { app, _, group, parent ->
        val ops = app.container.directoryOperations
        val keep = ops.createCategory("keep", group.id).getOrThrow()
        assertNotNull(parent.createDirectory("external"))
        val first = ops.refreshCategories(group.id).getOrThrow()
        assertEquals(listOf("external"), first.addedNames)
        assertEquals(keep, app.container.categories.findById(keep.id))
        assertTrue(ops.refreshCategories(group.id).getOrThrow().addedNames.isEmpty())
        assertEquals(2, app.container.categories.getAll(group.id).size)
    }

    @Test fun checkedDeletionRemovesDirectoryAndNestedContents() = fixture { app, rootUri, group, parent ->
        val name = "delete"
        val directory = parent.createDirectory(name)!!
        val nested = directory.createDirectory("nested")!!
        val marker = nested.createFile("application/octet-stream", "marker.bin")!!
        app.contentResolver.openOutputStream(marker.uri, "w")!!.use { it.write(byteArrayOf(0, 1, -1)) }
        app.container.mediaStorage.deleteCategoryDirectoryWithContents(rootUri, name, group.name).getOrThrow()
        assertNull(parent.findFile(name))
    }

    @Test fun countsDirectPhotosAndVideosAfterExternalDeletion() = fixture { app, rootUri, group, parent ->
        val name = "counts"
        val directory = parent.createDirectory(name)!!
        val photo = directory.createFile("image/jpeg", "motion.jpg")!!
        directory.createFile("video/mp4", "video.mp4")!!
        directory.createFile("text/plain", "notes.txt")!!
        directory.createFile("image/jpeg", ".hidden.jpg")!!
        directory.createDirectory("nested")!!.createFile("image/jpeg", "other.jpg")!!
        assertEquals(2, app.container.mediaStorage.countCategoryMedia(rootUri, listOf(name), group.name).getOrThrow()[name])
        assertTrue(photo.delete())
        assertEquals(1, app.container.mediaStorage.countCategoryMedia(rootUri, listOf(name), group.name).getOrThrow()[name])
        assertEquals(0, app.container.mediaStorage.countCategoryMedia(rootUri, listOf("missing"), group.name).getOrThrow()["missing"])
    }
}
