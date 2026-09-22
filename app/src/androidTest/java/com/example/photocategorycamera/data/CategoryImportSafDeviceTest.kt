package com.example.photocategorycamera.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.documentfile.provider.DocumentFile
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import com.example.photocategorycamera.domain.CategoryImportPlanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CategoryImportSafDeviceTest {
    @Test
    fun importsCurrentRootDirectoriesAndIsIdempotent() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
        val container = application.container
        val root = container.settings.settings.first().storageRoot
        assertNotNull("目标机需要先授权分类根目录", root)

        val directoryNames = container.mediaStorage
            .listCategoryDirectories(Uri.parse(root!!.treeUri))
            .getOrThrow()
        val before = container.categories.getAll()
        val expected = CategoryImportPlanner.plan(directoryNames, before)
        val first = container.categories.importDirectories(directoryNames)

        assertEquals(expected.namesToCreate, first.createdNames)
        val activeNames = container.categories.getAll().filter { it.isActive }.map { it.name }.toSet()
        assertTrue(expected.namesToCreate.all(activeNames::contains))

        val second = container.categories.importDirectories(directoryNames)
        assertEquals(0, second.importedCount)
    }

    @Test
    fun checkedDeletionRemovesDirectoryAndNestedContents() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
        val container = application.container
        val root = container.settings.settings.first().storageRoot
        assertNotNull("目标机需要先授权分类根目录", root)
        val rootUri = Uri.parse(root!!.treeUri)
        val rootDocument = DocumentFile.fromTreeUri(application, rootUri)
        assertNotNull(rootDocument)
        val directoryName = "删除测试_${UUID.randomUUID().toString().take(8)}"
        val directory = rootDocument!!.createDirectory(directoryName)
        assertNotNull(directory)
        try {
            val nested = directory!!.createDirectory("二级目录")
            val marker = nested!!.createFile("text/plain", "marker.txt")
            application.contentResolver.openOutputStream(marker!!.uri, "w")!!.use {
                it.write("Lenshelf delete integration test".toByteArray())
            }

            container.mediaStorage.deleteCategoryDirectoryWithContents(rootUri, directoryName).getOrThrow()
            assertNull(rootDocument.findFile(directoryName))
        } finally {
            rootDocument.findFile(directoryName)?.delete()
        }
    }

    @Test
    fun countsDirectPhotosAndVideosAndRefreshesAfterExternalDeletion() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
        val container = application.container
        val root = container.settings.settings.first().storageRoot
        assertNotNull("目标机需要先授权分类根目录", root)
        val rootUri = Uri.parse(root!!.treeUri)
        val rootDocument = DocumentFile.fromTreeUri(application, rootUri)!!
        val name = "数量测试_${UUID.randomUUID().toString().take(8)}"
        val directory = rootDocument.createDirectory(name)!!
        try {
            val photo = directory.createFile("image/jpeg", "motion.jpg")!!
            directory.createFile("video/mp4", "video.mp4")!!
            directory.createFile("text/plain", "notes.txt")!!
            directory.createFile("image/jpeg", ".hidden.jpg")!!
            directory.createDirectory("nested")!!.createFile("image/jpeg", "other.jpg")!!
            assertEquals(2, container.mediaStorage.countCategoryMedia(rootUri, listOf(name)).getOrThrow()[name])
            assertTrue(photo.delete())
            assertEquals(1, container.mediaStorage.countCategoryMedia(rootUri, listOf(name)).getOrThrow()[name])
            assertEquals(0, container.mediaStorage.countCategoryMedia(rootUri, listOf("不存在_$name")).getOrThrow()["不存在_$name"])
        } finally {
            directory.delete()
        }
    }
}
