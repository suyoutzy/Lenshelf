package com.example.photocategorycamera.ui

import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File

/** Own-app Compose fixtures only; callbacks record proposals without repositories or storage. */
@RunWith(AndroidJUnit4::class)
class ManagementDragUiDeviceTest {
    @get:Rule(order = 0) val foreground = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val component = "${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -n $component"))
                .bufferedReader().use { it.readText() }
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()
    private val home = CategoryGroup(CategoryGroup.HOME_ID, "常用", 0, 1)
    private val work = CategoryGroup("work", "实验文件夹", 1, 1)
    private val other = CategoryGroup("other", "另一个文件夹", 2, 1)
    private val a = Category("a", "样品 A", 0, true, 1)
    private val b = Category("b", "样品 B", 1, true, 1)
    private val c = Category("c", "样品 C", 2, true, 1)
    private val x = Category("x", "目标 X", 0, true, 1, work.id, work.name)
    private val y = Category("y", "目标 Y", 1, true, 1, work.id, work.name)

    private fun showCategories(state: () -> AppUiState = { fixture() },
        reorder: (String, Boolean, List<String>, List<String>, (Boolean) -> Unit) -> Unit = { _, _, _, _, done -> done(false) },
        transfer: (Category, String, String?, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(false) },
        lifecycleOwner: LifecycleOwner? = null) {
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides (lifecycleOwner ?: LocalLifecycleOwner.current)) {
            MaterialTheme { CategoryManagementScreen(state(), {}, { _, _ -> }, {}, { _, _ -> },
                { _, _ -> }, { _, _, _ -> }, { _, _, _ -> }, onReorder = reorder, onDropTransfer = transfer) }
        } }
    }
    private fun fixture() = AppUiState(categories = listOf(a, b, c), allCategories = listOf(a, b, c, x, y), groups = listOf(home, work, other))
    private fun center(tag: String): Offset = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
    private fun begin(id: String) {
        val point = center("drag-item-$id")
        // Freeze only after Activity/content startup; the drag's frame loop is intentionally
        // continuous, so tests advance its clock explicitly while the finger is held.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("management-drag-root").performTouchInput {
            down(point.copy(x = point.x - 30f)); advanceEventTime(650); moveBy(Offset(0f, 1f))
        }
        compose.mainClock.advanceTimeBy(48)
    }
    private fun moveTo(point: Offset) {
        compose.onNodeWithTag("management-drag-root").performTouchInput { moveTo(point) }
        compose.mainClock.advanceTimeByFrame()
    }
    private fun release() {
        compose.onNodeWithTag("management-drag-root").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(240)
        compose.mainClock.autoAdvance = true
    }
    private fun advanceUntilTagExists(tag: String) {
        val deadline = compose.mainClock.currentTime + 10_000
        // advanceTimeUntil evaluates its predicate on the UI thread. Semantics queries
        // synchronize with that thread, so query only here on the instrumentation thread.
        while (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && compose.mainClock.currentTime < deadline) {
            compose.mainClock.advanceTimeByFrame()
        }
        compose.onNodeWithTag(tag).assertExists()
    }

    @Test fun longPressReorderDoesNotSaveUntilReleaseAndSavesExactlyOnce() {
        var calls = 0
        var proposed = emptyList<String>()
        showCategories(reorder = { group, active, original, result, done ->
            assertEquals("home", group); assertTrue(active); assertEquals(listOf("a", "b", "c"), original)
            calls++; proposed = result; done(true)
        })
        begin("a")
        moveTo(center("drop-tail-home-true"))
        compose.runOnIdle { assertEquals(0, calls) }
        release()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(listOf("b", "c", "a"), proposed) }
    }

    @Test fun trayReleaseAndPointerCancelDoNotSubmit() {
        var calls = 0
        showCategories(reorder = { _, _, _, _, _ -> calls++ }, transfer = { _, _, _, _ -> calls++ })
        begin("a")
        moveTo(center("drag-group-work"))
        release()
        compose.runOnIdle { assertEquals(0, calls) }
        begin("a")
        moveTo(center("drop-tail-home-true"))
        compose.onNodeWithTag("management-drag-root").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(240)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun hoverSwitchesAt600msAndTargetReleasePreservesExactAnchor() {
        var result: Pair<String, String?>? = null
        showCategories(transfer = { category, group, before, done ->
            assertEquals("a", category.id); result = group to before; done(true)
        })
        begin("a")
        moveTo(center("drag-group-work"))
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("drag-item-x").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("drag-item-x").assertExists()
        // Semantics can update before the platform draws the switched list. Keep the
        // pointer held, finish its preview animation, then capture the rendered frame.
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        val screenshot = compose.onNodeWithTag("management-drag-root").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "lenshelf-v082-drag-preview.png")
            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val bounds = compose.onNodeWithTag("drag-item-y").fetchSemanticsNode().boundsInRoot
        moveTo(Offset(bounds.center.x, bounds.top + 2f))
        compose.runOnIdle { assertNull(result) }
        release()
        compose.runOnIdle { assertEquals("work" to "y", result) }
    }

    @Test fun hoverReturnAndBackCancelRestoreSourceWithoutPersistence() {
        var calls = 0
        showCategories(transfer = { _, _, _, _ -> calls++ }, reorder = { _, _, _, _, _ -> calls++ })
        begin("a"); moveTo(center("drag-group-work")); compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("drag-item-x").assertExists()
        moveTo(center("drag-group-home")); compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("drag-item-b").assertExists()
        compose.onNodeWithContentDescription("返回").performSemanticsAction(SemanticsActions.OnClick) { it() }
        release()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun activityBackDispatcherCancelsTargetPreviewWithoutSubmitting() {
        var calls = 0
        showCategories(transfer = { _, _, _, _ -> calls++ }, reorder = { _, _, _, _, _ -> calls++ })
        begin("a")
        moveTo(center("drag-group-work"))
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("drag-item-x").assertExists()
        // Exercise the activity's BackHandler route without global event injection,
        // which Xiaomi restricts even to instrumentation on this device.
        compose.runOnUiThread {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).single() as ComponentActivity
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("drag-item-b").assertExists()
        compose.onNodeWithTag("drag-group-work").assertDoesNotExist()
        release()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun lockedCategoryDoesNotLiftAndMoreMenuStillOpensRenameDialog() {
        showCategories(state = { fixture().copy(mutationLockedCategoryIds = setOf("a")) })
        begin("a"); release()
        compose.onNodeWithTag("drag-group-work").assertDoesNotExist()
        compose.onNodeWithContentDescription("样品 B的更多操作").performClick()
        compose.onNodeWithText("重命名分类").performClick()
        compose.onNodeWithText("分类名称").assertExists()
    }

    @Test fun incomingFailureBecomesRealRowWithOneStableId() {
        val pending = DirectoryOperationUiState("op", "TRANSFER_CATEGORY", "常用/样品 A", "实验文件夹/样品 A", "COPY", "故障",
            a.id, home.id, work.id, y.id)
        var state by mutableStateOf(fixture().copy(selectedGroup = work, categories = listOf(x, y), directoryOperations = listOf(pending)))
        showCategories(state = { state })
        compose.onAllNodesWithTag("drag-item-a").assertCountEquals(1)
        compose.onNodeWithText("未完成 · 移动失败，等待重试").assertExists()
        compose.runOnIdle { state = state.copy(allCategories = listOf(a.copy(groupId = work.id, groupName = work.name), b, c, x, y)) }
        compose.onAllNodesWithTag("drag-item-a").assertCountEquals(1)
        compose.runOnIdle { state = state.copy(directoryOperations = emptyList()) }
        compose.onAllNodesWithTag("drag-item-a").assertCountEquals(1)
    }

    @Test fun backgroundIncomingRowSurvivesOperationClearingBeforeCategoryFlow() {
        val pending = DirectoryOperationUiState("background-op", "TRANSFER_CATEGORY", "常用/样品 A", "实验文件夹/样品 A", "COPY", null,
            a.id, home.id, work.id, y.id)
        var state by mutableStateOf(fixture().copy(selectedGroup = work, categories = listOf(x, y)))
        showCategories(state = { state })
        compose.runOnIdle { state = state.copy(directoryOperations = listOf(pending)) }
        compose.onAllNodesWithTag("drag-item-a").assertCountEquals(1)
        compose.runOnIdle { state = state.copy(directoryOperations = emptyList()) }
        compose.onAllNodesWithTag("drag-item-a").assertCountEquals(1)
        compose.onNodeWithText("未完成 · 正在移动分类").assertExists()
        compose.onNodeWithContentDescription("样品 A的更多操作").assertIsNotEnabled()
        val aBounds = compose.onNodeWithTag("drag-item-a").fetchSemanticsNode().boundsInRoot
        val yBounds = compose.onNodeWithTag("drag-item-y").fetchSemanticsNode().boundsInRoot
        assertTrue(aBounds.top < yBounds.top)
        compose.runOnIdle {
            val moved = a.copy(groupId = work.id, groupName = work.name, sortOrder = 1)
            state = state.copy(allCategories = listOf(b, c, x, moved, y.copy(sortOrder = 2)))
        }
        compose.onAllNodesWithTag("drag-item-a").assertCountEquals(1)
        compose.onNodeWithText("未完成 · 正在移动分类").assertDoesNotExist()
    }

    @Test fun inactiveCategoryCanDropIntoEmptyMatchingSectionOnly() {
        val inactive = a.copy(isActive = false)
        var result: Pair<String, String?>? = null
        showCategories(state = { fixture().copy(categories = listOf(inactive), allCategories = listOf(inactive)) },
            transfer = { category, group, before, done ->
                assertFalse(category.isActive); result = group to before; done(false)
            })
        begin("a"); moveTo(center("drag-group-work")); compose.mainClock.advanceTimeBy(650)
        moveTo(center("drop-tail-work-false")); release()
        compose.runOnIdle { assertEquals("work" to null, result) }
        compose.onNodeWithTag("drag-item-a").assertExists()
    }

    @Test fun releasingInOppositeStatusSectionCancels() {
        var calls = 0
        showCategories(state = { fixture().copy(categories = listOf(a), allCategories = listOf(a)) },
            reorder = { _, _, _, _, _ -> calls++ }, transfer = { _, _, _, _ -> calls++ })
        begin("a"); moveTo(center("drop-tail-home-false")); release()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun groupSortingIgnoresCaptureLockButHomeStaysFixedAndMenusStayLocked() {
        var proposed = emptyList<String>()
        var accessibleMoves = 0
        compose.setContent { MaterialTheme {
            GroupManagementScreen(AppUiState(groups = listOf(home, work, other), lockedGroupIds = setOf(work.id)),
                {}, { _, _ -> }, { _, _ -> accessibleMoves++ }, { _, _, _ -> }, { _, _ -> },
                onReorder = { original, ordered, done ->
                    assertEquals(listOf(work.id, other.id), original); proposed = ordered; done(false)
                })
        } }
        compose.onNodeWithText("固定常用").assertExists()
        compose.onNodeWithContentDescription("${work.name}的更多操作").assertIsNotEnabled()
        val actions = compose.onNodeWithTag("drag-item-work").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertTrue(actions.first { it.label == "后移" }.action())
        }
        compose.runOnIdle { assertEquals(1, accessibleMoves) }
        begin("work"); moveTo(center("drop-tail-folder-order-true")); release()
        compose.runOnIdle { assertEquals(listOf(other.id, work.id), proposed) }
    }

    @Test fun stationaryFingerScrollsLongVariableHeightList() {
        val categories = (0..30).map { index ->
            a.copy(id = "item-$index", name = if (index % 3 == 0) "很长的分类名称用于检查卡片高度和拖拽位置是否正确保持第${index}条记录" else "分类 $index", sortOrder = index)
        }
        var calls = 0
        showCategories(state = { fixture().copy(categories = categories, allCategories = categories) },
            reorder = { _, _, _, _, done -> calls++; done(false) })
        begin("item-0")
        val list = compose.onNodeWithTag("management-list").fetchSemanticsNode().boundsInRoot
        moveTo(Offset(list.center.x, list.bottom - 3f))
        advanceUntilTagExists("drag-item-item-20")
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("management-drag-root").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(240); compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun stationaryFingerScrollsTrayWithoutSubmitting() {
        val groups = listOf(home) + (1..12).map { CategoryGroup("g$it", "文件夹 $it", it, 1) }
        var calls = 0
        showCategories(state = { fixture().copy(groups = groups) }, transfer = { _, _, _, _ -> calls++ })
        begin("a")
        val tray = compose.onNodeWithTag("management-group-tray").fetchSemanticsNode().boundsInRoot
        moveTo(Offset(tray.right - 3f, tray.center.y))
        advanceUntilTagExists("drag-group-g12")
        release()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("drag-item-a").assertExists()
    }

    @Test fun backgroundEventCancelsActiveGestureWithoutCallingPersistence() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        var calls = 0
        showCategories(reorder = { _, _, _, _, _ -> calls++ }, lifecycleOwner = owner)
        begin("a"); moveTo(center("drop-tail-home-true"))
        compose.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        release()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("drag-group-work").assertDoesNotExist()
    }
}
