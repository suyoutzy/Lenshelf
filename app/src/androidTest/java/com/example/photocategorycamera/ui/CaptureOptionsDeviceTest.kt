package com.example.photocategorycamera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.PhotoDateStampFormat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

class CaptureOptionsDeviceTest {
    @get:Rule(order = 0) val foreground = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${instrumentation.targetContext.packageName}/androidx.activity.ComponentActivity"))
                .bufferedReader().use { it.readText() }
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun dateMenuTogglesAndSelectsTimeFormat() {
        var enabled = false
        var format = PhotoDateStampFormat.DATE
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf(PhotoDateStampFormat.DATE) }
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    PhotoDateStampSettings(checked, selected,
                        { checked = it; enabled = it }, { selected = it; format = it })
                }
            }
        }
        compose.onNodeWithText("日期水印").performClick()
        compose.onNodeWithText("日期和时分").performClick()
        compose.runOnIdle { assertTrue(enabled); assertEquals(PhotoDateStampFormat.DATE_TIME, format) }
    }

    @Test fun documentEffectIsVisibleAndSelectionReflectsTheSharedValue() {
        var selected = DocumentEffect.ORIGINAL
        compose.setContent {
            var effect by remember { mutableStateOf(DocumentEffect.ORIGINAL) }
            MaterialTheme { DocumentEffectSelector(effect, true) { effect = it; selected = it } }
        }
        compose.onNodeWithText("效果：原色").performClick()
        compose.onNodeWithText("增强").performClick()
        compose.onNodeWithText("效果：增强").assertIsDisplayed()
        compose.runOnIdle { assertEquals(DocumentEffect.ENHANCED, selected) }
    }
}
