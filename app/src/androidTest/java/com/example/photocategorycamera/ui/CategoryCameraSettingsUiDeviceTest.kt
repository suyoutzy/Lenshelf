package com.example.photocategorycamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import com.example.photocategorycamera.data.CategoryFlashMode
import com.example.photocategorycamera.domain.DocumentEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Uses only a unique temporary group; requires an explicitly configured emulator/device. */
class CategoryCameraSettingsUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
    private val container get() = app.container
    private fun waitFor(condition: suspend () -> Boolean) = compose.waitUntil(30_000) { runBlocking { condition() } }
    private fun waitSettings() = compose.waitUntil(30_000) {
        compose.onAllNodesWithContentDescription("相机设置").fetchSemanticsNodes()
            .any { SemanticsProperties.Disabled !in it.config }
    }

    @Test fun switchingCategoriesShowsAndRestoresTheirOwnSettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowHomeDirectoryFixture") == "true")
        assumeTrue(app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val before = runBlocking { container.settings.settings.first() }
        assumeTrue(before.storageRoot != null)
        assumeTrue(runBlocking { container.captureTasks.outstanding.first().isEmpty() && container.directoryOperations.pending.first().isEmpty() })
        val group = runBlocking { container.directoryOperations.createGroup("设置验收_${UUID.randomUUID().toString().take(8)}").getOrThrow() }
        val a = runBlocking { container.directoryOperations.createCategory("分类甲",group.id).getOrThrow() }
        val b = runBlocking { container.directoryOperations.createCategory("分类乙",group.id).getOrThrow() }
        try {
            runBlocking { container.settings.setSelectedGroupId(group.id) }
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start -W -f 0x10008000 -n ${app.packageName}/.MainActivity")).bufferedReader().use { it.readText() }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("category-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(a.name).performClick(); waitSettings()
            compose.onNodeWithContentDescription("相机设置").performClick()
            compose.onNodeWithText("仅日期").assertIsDisplayed()
            compose.onNodeWithText("时间水印").performClick()
            waitFor { !container.settings.categoryCameraSettings(a.id).first().timeStampEnabled }
            compose.onNodeWithText("闪光灯：关闭").performClick()
            waitFor { container.settings.categoryCameraSettings(a.id).first().flashMode == CategoryFlashMode.AUTO }
            compose.onNodeWithText("井字格参考线").performClick()
            waitFor { container.settings.categoryCameraSettings(a.id).first().gridEnabled }
            compose.onNodeWithText(a.name).performClick(); compose.onNodeWithText(b.name).performClick(); waitSettings()
            compose.onNodeWithContentDescription("相机设置").performClick()
            compose.onNodeWithText("仅日期").assertIsDisplayed()
            compose.onNodeWithText("闪光灯：关闭").assertIsDisplayed()
            compose.onNodeWithText("拍后备注").performClick()
            waitFor { container.settings.categoryCameraSettings(b.id).first().postCaptureNoteEnabled }
            compose.onNodeWithText("井字格参考线").performClick()
            waitFor { container.settings.categoryCameraSettings(b.id).first().gridEnabled }
            compose.onNodeWithText(b.name).performClick(); compose.onNodeWithText(a.name).performClick(); waitSettings()
            compose.onNodeWithContentDescription("相机设置").performClick()
            compose.onNodeWithText("仅日期").assertDoesNotExist()
            compose.onNodeWithText("闪光灯：自动").assertIsDisplayed()
            val aSettings = runBlocking { container.settings.categoryCameraSettings(a.id).first() }
            val bSettings = runBlocking { container.settings.categoryCameraSettings(b.id).first() }
            assertTrue(aSettings.gridEnabled); assertFalse(aSettings.postCaptureNoteEnabled)
            assertTrue(bSettings.gridEnabled); assertTrue(bSettings.timeStampEnabled)
            compose.onNodeWithText("井字格参考线").performClick()
            waitFor { !container.settings.categoryCameraSettings(a.id).first().gridEnabled }
            compose.onNodeWithText("文档").performClick()
            compose.onNodeWithText("效果：原色").performClick(); compose.onNodeWithText("增强").performClick()
            waitFor { container.settings.categoryCameraSettings(a.id).first().documentEffect == DocumentEffect.ENHANCED }
            compose.onNodeWithText(a.name).performClick(); compose.onNodeWithText(b.name).performClick(); waitSettings()
            compose.onNodeWithText("效果：原色").assertIsDisplayed()
            compose.onNodeWithText(b.name).performClick(); compose.onNodeWithText(a.name).performClick(); waitSettings()
            compose.onNodeWithText("效果：增强").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("category-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(a.name).performClick(); waitSettings()
            compose.onNodeWithText("文档").performClick()
            compose.onNodeWithText("效果：增强").assertIsDisplayed()
        } finally {
            runBlocking {
                container.settings.setSelectedGroupId(before.selectedGroupId)
                container.categories.findById(a.id)?.let { container.directoryOperations.deleteCategory(it).getOrThrow() }
                container.categories.findById(b.id)?.let { container.directoryOperations.deleteCategory(it).getOrThrow() }
                container.groups.findById(group.id)?.let { container.directoryOperations.deleteGroup(it).getOrThrow() }
            }
        }
    }
}
