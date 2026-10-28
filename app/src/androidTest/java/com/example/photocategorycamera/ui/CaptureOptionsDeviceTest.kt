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

class CaptureOptionsDeviceTest {
    @get:Rule val compose = createComposeRule()

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
