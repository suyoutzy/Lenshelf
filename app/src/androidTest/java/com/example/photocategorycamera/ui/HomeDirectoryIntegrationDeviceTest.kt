package com.example.photocategorycamera.ui

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

/** Explicit opt-in: all writes and deletions stay in the group created by this invocation. */
@RunWith(AndroidJUnit4::class)
class HomeDirectoryIntegrationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
    private val container get() = app.container
    private fun waitFor(condition: suspend () -> Boolean) = compose.waitUntil(30_000) { runBlocking { condition() } }
    private fun clickWhenEnabled(description: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().any {
                androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config
            }
        }
        compose.onNodeWithContentDescription(description).performClick()
    }

    @Test fun homeActionsRefreshAndDeleteOnlyTheNewOwnedDirectory() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowHomeDirectoryFixture") == "true")
        val before = runBlocking { container.settings.settings.first() }
        assumeTrue(before.storageRoot != null)
        assumeTrue(runBlocking { container.captureTasks.outstanding.first().isEmpty() && container.directoryOperations.pending.first().isEmpty() })
        val root = DocumentFile.fromTreeUri(app, Uri.parse(before.storageRoot!!.treeUri))!!
        val groupName = "首页验收_${UUID.randomUUID().toString().take(8)}"
        var ownedGroupId: String? = null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("am start -W -f 0x10008000 -n ${app.packageName}/.MainActivity"))
                .bufferedReader().use { it.readText() }
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("新增文件夹").fetchSemanticsNodes().isNotEmpty() }
            clickWhenEnabled("新增文件夹")
            compose.onNodeWithTag("folder-name-input").performTextInput(groupName)
            compose.onNodeWithText("确认").performClick()
            waitFor { container.groups.groups.first().any { it.name == groupName } }
            val group = runBlocking { container.groups.groups.first().single { it.name == groupName } }
            ownedGroupId = group.id
            waitFor { container.settings.settings.first().selectedGroupId == group.id }
            val parent = root.findFile(groupName)!!
            clickWhenEnabled("新增分类")
            compose.onNodeWithTag("category-name-input").performTextInput("原分类")
            compose.onNodeWithText("创建").performClick()
            waitFor { container.categories.getAll(group.id).size == 1 }
            val original = runBlocking { container.categories.getAll(group.id).single() }
            if (InstrumentationRegistry.getArguments().getString("exportReviewSamples") == "true") {
                compose.waitForIdle()
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                assertNotNull(screenshot)
                java.io.File(app.cacheDir, "home-management-home.png").outputStream().use {
                    screenshot!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot!!.recycle()
            }
            val directory = parent.findFile(original.name)!!
            val nested = directory.createDirectory("子目录")!!
            val content = nested.createFile("application/octet-stream", "content.bin")!!
            app.contentResolver.openOutputStream(content.uri, "wt")!!.use { it.write(byteArrayOf(0, 1, -1)) }

            // External additions/removals are reconciled only in this owned group.
            val external = parent.createDirectory("Receipts")!!
            clickWhenEnabled("刷新当前文件夹分类")
            waitFor { container.categories.getAll(group.id).size == 2 }
            assertEquals(original.id, runBlocking { container.categories.getAll(group.id).single { it.name == original.name }.id })
            val oldExternalId = runBlocking { container.categories.getAll(group.id).single { it.name == "Receipts" }.id }
            assertTrue(external.renameTo("receipts"))
            // Some real providers return "receipts (1)" for a case-only rename; follow their exact path.
            val renamedExternal = checkNotNull(external.name)
            assertNotEquals("Receipts", renamedExternal)
            clickWhenEnabled("刷新当前文件夹分类")
            waitFor { container.categories.getAll(group.id).any { it.name == renamedExternal } }
            val imported = runBlocking { container.categories.getAll(group.id).single { it.name == renamedExternal } }
            assertNotEquals(oldExternalId, imported.id)
            assertEquals(external.uri, runBlocking { container.directoryOperations.resolveCategoryDirectory(imported).getOrThrow() })
            assertTrue(external.delete())
            clickWhenEnabled("刷新当前文件夹分类")
            waitFor { container.categories.getAll(group.id).size == 1 }

            clickWhenEnabled("原分类的更多操作")
            compose.onNodeWithText("重命名分类").performClick()
            compose.onNodeWithTag("category-name-input").performTextReplacement("新分类")
            compose.onNodeWithText("保存").performClick()
            waitFor { container.categories.findById(original.id)?.name == "新分类" && container.directoryOperations.pending.first().isEmpty() }
            assertNull(parent.findFile("原分类"))
            assertNotNull(parent.findFile("新分类")!!.findFile("子目录"))
            clickWhenEnabled("新分类的更多操作")
            compose.onNodeWithText("删除分类").performClick()
            compose.onNodeWithText("取消").performClick()
            assertNotNull(parent.findFile("新分类"))
            assertNotNull(runBlocking { container.categories.findById(original.id) })
            clickWhenEnabled("新分类的更多操作")
            compose.onNodeWithText("删除分类").performClick()
            compose.onNodeWithText("确认删除").performClick()
            waitFor { container.categories.findById(original.id) == null }
            assertNull(parent.findFile("新分类"))

            clickWhenEnabled("${groupName}的文件夹操作")
            compose.onNodeWithText("重命名文件夹").performClick()
            compose.onNodeWithTag("folder-name-input").performTextReplacement("${groupName}_改名")
            compose.onNodeWithText("确认").performClick()
            waitFor { container.groups.findById(group.id)?.name == "${groupName}_改名" && container.directoryOperations.pending.first().isEmpty() }
            clickWhenEnabled("${groupName}_改名的文件夹操作")
            compose.onNodeWithText("删除空文件夹").performClick()
            compose.onNodeWithText("确认删除").performClick()
            waitFor { container.groups.findById(group.id) == null }
            assertNull(root.findFile("${groupName}_改名"))
        } finally {
            runBlocking {
                // Cleanup is by the ID returned for our unique group, never by a prefix search.
                ownedGroupId?.let { id ->
                    container.categories.getAll(id).forEach { container.directoryOperations.deleteCategory(it).getOrThrow() }
                    container.groups.findById(id)?.let { container.directoryOperations.deleteGroup(it).getOrThrow() }
                }
                container.settings.setSelectedGroupId(before.selectedGroupId)
            }
        }
    }
}
