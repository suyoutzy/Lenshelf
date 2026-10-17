package com.example.photocategorycamera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import com.example.photocategorycamera.data.AppSettings
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.StorageRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith

/** Compose-only fixtures: no application repositories, camera, or user media are opened. */
@RunWith(AndroidJUnit4::class)
class FolderGroupsUiDeviceTest {
    @get:Rule(order = 0) val foreground = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            // Some device systems block ActivityScenario launches while the target UID is in the background.
            val component = "${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -n $component"))
                .bufferedReader().use { it.readText() }
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()
    private val home = CategoryGroup(CategoryGroup.HOME_ID, "常用", 0, 1)
    private val work = CategoryGroup("work", "测试测试测试测试测试", 1, 1)
    private val root = StorageRoot("content://fixture", "Fixture", 1)

    @Test fun folderTabsSwitchVisibleCategoriesAndKeepManageActionsSeparate() {
        var selected by mutableStateOf(home.id)
        var manageFolders = false
        var manageCategories = false
        val categories = listOf(Category("home-photo", "生活照片", 0, true, 1),
            Category("work-photo", "实验记录", 0, true, 1, work.id, work.name))
        compose.setContent {
            MaterialTheme {
                HomeScreen(AppUiStateMapper.create(categories, AppSettings(storageRoot = root,
                    selectedGroupId = selected), emptyList(), false, null, listOf(home, work)),
                    {}, {}, { manageCategories = true }, {}, {}, {}, {}, {},
                    onSelectGroup = { selected = it }, onManageGroups = { manageFolders = true })
            }
        }
        compose.onNodeWithText("生活照片").assertExists()
        compose.onNodeWithText("实验记录").assertDoesNotExist()
        compose.onNodeWithText("测试测试…").assertExists()
        compose.onNodeWithText("选择一个分类").assertDoesNotExist()
        compose.onNodeWithText(work.name).assertDoesNotExist()
        compose.onNodeWithContentDescription("切换到${work.name}").performClick()
        compose.onNodeWithText("实验记录").assertExists()
        compose.onNodeWithText("生活照片").assertDoesNotExist()
        compose.onNodeWithContentDescription("管理分类").performClick()
        compose.onNodeWithContentDescription("管理文件夹").performClick()
        compose.onNodeWithText("管理分类").assertDoesNotExist()
        compose.onNodeWithText("管理文件夹").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(true, manageFolders)
            assertEquals(true, manageCategories)
        }
    }

    @Test fun homeGroupHasNoRenameOrDeleteControls() {
        compose.setContent {
            MaterialTheme { GroupManagementScreen(AppUiState(groups = listOf(home)), {}, { _, _ -> },
                { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {}) }
        }
        compose.onNodeWithContentDescription("常用的更多操作").assertDoesNotExist()
        compose.onNodeWithContentDescription("常用上移").assertDoesNotExist()
        compose.onNodeWithText("固定常用").assertExists()
    }

    @Test fun lockedCategoryCannotOpenCameraButUnrelatedCategoryRemainsUsable() {
        val categories = listOf(Category("moving", "移动中", 0, true, 1),
            Category("available", "可拍摄", 1, true, 1))
        var opened: String? = null
        compose.setContent {
            MaterialTheme { HomeScreen(AppUiState(categories = categories,
                directoryLockedCategoryIds = setOf("moving")), { opened = it.id }, {}, {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("拍摄常用/移动中").assertIsNotEnabled()
        compose.onNodeWithContentDescription("拍摄常用/可拍摄").performClick()
        compose.runOnIdle { assertEquals("available", opened) }
    }
}
