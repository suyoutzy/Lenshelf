package com.example.photocategorycamera.ui

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.photocategorycamera.data.AppSettings
import com.example.photocategorycamera.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** UI fixtures never invoke repositories or delete actual files. */
@RunWith(AndroidJUnit4::class)
class HomeManagementUiDeviceTest {
    @get:Rule(order=0) val foreground = object : ExternalResource() {
        override fun before() {
            val i = InstrumentationRegistry.getInstrumentation()
            ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand(
                "am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity"))
                .bufferedReader().use { it.readText() }
        }
    }
    @get:Rule(order=1) val compose = createComposeRule()
    private val home = CategoryGroup("home", "常用", -1, 0)
    private val work = CategoryGroup("work", "实验记录文件夹", 0, 1)
    private val empty = CategoryGroup("empty", "空文件夹", 1, 2)
    private val a = Category("a", "样品 A", 0, true, 1)
    private val b = Category("b", "样品 B", 1, true, 2)
    private val x = Category("x", "工作照片", 0, true, 1, work.id, work.name)
    private var selected by mutableStateOf(home.id)
    private var rows by mutableStateOf(listOf(a,b,x))
    private var groupRows by mutableStateOf(listOf(home,work,empty))
    private fun state() = AppUiStateMapper.create(rows, AppSettings(storageRoot=StorageRoot("content://fixture", "fixture",1),
        selectedGroupId=selected), emptyList(), false,null,groupRows)
    private fun show(onAdd: (String,String,(Boolean)->Unit)->Unit = { _,_,done->done(true) },
        onRefresh: (String)->Unit = {}, onAddGroup: (String,(Boolean)->Unit)->Unit = { _,done->done(true) },
        onDelete: (Category,(Boolean)->Unit)->Unit = { _,done->done(true) }) {
        compose.setContent { MaterialTheme {
            HomeScreen(state(), {}, {}, onSettings={}, onRetry={}, onDiscardFailed={},
                onSaveLegacyDocument={}, onDiscardDocument={}, onSelectGroup={selected=it},
                onAddCategory=onAdd, onRefreshCategories=onRefresh, onAddGroup=onAddGroup,
                onDeleteCategory=onDelete)
        } }
    }

    @Test fun homeExposesDistinctAddButtonsAndRefreshesOnlyVisibleGroup() {
        var refreshed: String? = null
        show(onRefresh={refreshed=it})
        compose.onNodeWithContentDescription("新增分类").assertIsDisplayed()
        compose.onNodeWithContentDescription("新增文件夹").assertIsDisplayed()
        compose.onNodeWithContentDescription("管理分类").assertDoesNotExist()
        compose.onNodeWithContentDescription("管理文件夹").assertDoesNotExist()
        compose.onNodeWithContentDescription("切换到${work.name}").performClick()
        compose.onNodeWithContentDescription("刷新当前文件夹分类").performClick()
        compose.runOnIdle { assertEquals("work",refreshed) }
    }

    @Test fun addCategoryAndFolderDialogsDispatchTheirOwnActions() {
        var createdCategory: Pair<String,String>? = null
        var createdGroup: String? = null
        show(onAdd={group,name,done->createdCategory=group to name;done(true)},
            onAddGroup={name,done->createdGroup=name;done(true)})
        compose.onNodeWithContentDescription("新增分类").performClick()
        compose.onNodeWithTag("category-name-input").performTextInput("新分类")
        compose.onNodeWithText("创建").performClick()
        compose.runOnIdle { assertEquals("home" to "新分类",createdCategory) }
        compose.onNodeWithContentDescription("新增文件夹").performClick()
        compose.onNodeWithTag("folder-name-input").performTextInput("新文件夹")
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals("新文件夹",createdGroup) }
    }

    @Test fun categoryMenuHasRenameAndWholeDirectoryDeleteWithCancelAndConfirmation() {
        var deleted=0
        show(onDelete={category,done->assertEquals(a.id,category.id);deleted++;done(true)})
        compose.onNodeWithContentDescription("${a.name}的更多操作").performClick()
        compose.onNodeWithText("重命名分类").assertExists()
        compose.onNodeWithText("停用分类").assertDoesNotExist()
        compose.onNodeWithText("转移分类").assertDoesNotExist()
        compose.onNodeWithText("删除分类").performClick()
        compose.onNodeWithText("将永久删除实际分类文件夹及全部内容，包括照片、视频、子文件夹和其他文件，无法恢复。").assertExists()
        compose.runOnIdle { assertEquals(0,deleted) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0,deleted) }
        compose.onNodeWithContentDescription("${a.name}的更多操作").performClick()
        compose.onNodeWithText("删除分类").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals(1,deleted) }
    }

    @Test fun swipingVisitsEmptyGroupStopsAtEndsAndMatchesTabSelection() {
        show()
        compose.onNodeWithTag("home-pager").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals("home",selected) }
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("拍摄${work.name}/${x.name}").assertIsDisplayed()
        compose.runOnIdle { assertEquals("work",selected) }
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("当前文件夹暂无分类，点击顶部＋新增，或刷新已有目录。").assertIsDisplayed()
        compose.runOnIdle { assertEquals("empty",selected) }
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals("empty",selected) }
        compose.onNodeWithContentDescription("切换到常用").performClick()
        compose.onNodeWithContentDescription("拍摄常用/${a.name}").assertIsDisplayed()
    }

    @Test fun selectedFolderMenuShowsFullNameAndEmptyOnlyDeletion() {
        show()
        compose.onNodeWithContentDescription("常用的更多操作").assertDoesNotExist()
        compose.onNodeWithContentDescription("切换到${work.name}").performClick()
        compose.onNodeWithContentDescription("${work.name}的文件夹操作").performClick()
        compose.onNodeWithText(work.name).assertExists()
        compose.onNodeWithText("1 个分类").assertExists()
        compose.onNodeWithText("删除空文件夹").assertIsNotEnabled()
    }

    @Test fun reorderingGroupIdsKeepsTheSameFolderVisible() {
        show()
        compose.onNodeWithContentDescription("切换到${work.name}").performClick()
        compose.runOnIdle { groupRows=listOf(home,empty.copy(sortOrder=0),work.copy(sortOrder=1)) }
        compose.onNodeWithContentDescription("拍摄${work.name}/${x.name}").assertIsDisplayed()
        compose.runOnIdle { assertEquals("work",selected) }
    }

    @Test fun addingAndRemovingLastFolderKeepsPagerKeysAndPageCountConsistent() {
        show()
        val added = empty.copy(id = "added", name = "新增文件夹")
        compose.runOnIdle { groupRows = listOf(home, work, empty, added) }
        compose.onNodeWithContentDescription("拍摄常用/${a.name}").assertIsDisplayed()
        compose.runOnIdle { groupRows = listOf(home, work, empty) }
        compose.onNodeWithContentDescription("拍摄常用/${a.name}").assertIsDisplayed()
    }

    @Test fun eachFolderRestoresItsOwnListPosition() {
        rows=(0..60).map { a.copy(id="long-$it",name="分类 $it",sortOrder=it) } + x
        show()
        compose.onNodeWithTag("category-list").performScrollToNode(hasText("分类 40"))
        compose.onNodeWithText("分类 40").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换到${work.name}").performClick()
        compose.onNodeWithText(x.name).assertIsDisplayed()
        compose.onNodeWithContentDescription("切换到常用").performClick()
        compose.onNodeWithText("分类 40").assertIsDisplayed()
    }

    @Test fun groupSelectionIsNotSavedWhileTheFingerIsStillPaging() {
        show()
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("home-pager").performTouchInput {
            down(Offset(width*.9f,height*.5f));advanceEventTime(100);moveTo(Offset(width*.15f,height*.5f))
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals("home",selected) }
        compose.onNodeWithTag("home-pager").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000);compose.mainClock.autoAdvance=true
        compose.onNodeWithText(x.name).assertIsDisplayed()
        compose.runOnIdle { assertEquals("work",selected) }
    }

    @Test fun bottomBarScrollDoesNotChangeTheSelectedFolder() {
        groupRows=listOf(home)+(0..12).map { work.copy(id="group-$it",name="文件夹 $it",sortOrder=it) }
        show()
        compose.onNodeWithTag("folder-tabs").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals("home",selected) }
        compose.onNodeWithContentDescription("新增文件夹").assertIsDisplayed()
    }

    @Test fun selectingAFolderBeyondTheVisibleTabsRevealsItsMenu() {
        groupRows=listOf(home)+(0..12).map { work.copy(id="group-$it",name="文件夹 $it",sortOrder=it) }
        show()
        compose.runOnIdle { selected="group-12" }
        compose.onNodeWithContentDescription("文件夹 12的文件夹操作").assertIsDisplayed().performClick()
        compose.onNodeWithText("重命名文件夹").assertIsDisplayed()
    }
}
