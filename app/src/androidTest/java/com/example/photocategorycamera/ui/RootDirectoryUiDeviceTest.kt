package com.example.photocategorycamera.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.photocategorycamera.PhotoCategoryCameraApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in navigation smoke test. Reads the existing grant; does not change settings or media. */
@RunWith(AndroidJUnit4::class)
class RootDirectoryUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    @Test fun settingsOpensRootInDeviceFileManager() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowDirectoryOpen") == "true")
        val app = ApplicationProvider.getApplicationContext<PhotoCategoryCameraApplication>()
        assumeTrue(runBlocking { app.container.settings.settings.first().storageRoot } != null)
        shell("am start -W -f 0x10008000 -n ${app.packageName}/.MainActivity")
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithContentDescription("打开根目录").assertExists()
        shell("screencap -p /sdcard/lenshelf_v081_settings.png")
        compose.onNodeWithContentDescription("打开根目录").performClick()
        val manager = Regex("topResumedActivity=.*(?:fileexplorer|documentsui)", RegexOption.IGNORE_CASE)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var opened = false
        while (!opened && SystemClock.elapsedRealtime() < deadline) {
            opened = manager.containsMatchIn(shell("dumpsys activity activities"))
            if (!opened) SystemClock.sleep(250)
        }
        assertTrue("No device file manager opened from the root-directory action", opened)
        shell("screencap -p /sdcard/lenshelf_v081_root_open.png")
    }
}
