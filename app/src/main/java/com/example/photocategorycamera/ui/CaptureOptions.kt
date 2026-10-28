package com.example.photocategorycamera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.PhotoDateStampFormat

@Composable
internal fun PhotoDateStampSettings(enabled: Boolean, format: PhotoDateStampFormat,
    onEnabledChange: (Boolean) -> Unit, onFormatChange: (PhotoDateStampFormat) -> Unit) {
    DropdownMenuItem(text = { Text("日期水印") },
        trailingIcon = { Switch(checked = enabled, onCheckedChange = null) },
        onClick = { onEnabledChange(!enabled) })
    if (enabled) PhotoDateStampFormat.entries.forEach { choice ->
        DropdownMenuItem(text = { Text(choice.label) },
            trailingIcon = { RadioButton(selected = format == choice, onClick = null) },
            onClick = { onFormatChange(choice) })
    }
}

@Composable
internal fun DocumentEffectSelector(effect: DocumentEffect, enabled: Boolean, onChange: (DocumentEffect) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.ui.graphics.Color(0xFFFFD34E))) { Text("效果：${effect.label}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DocumentEffect.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) },
                    trailingIcon = { RadioButton(selected = choice == effect, onClick = null) },
                    onClick = { expanded = false; onChange(choice) })
            }
        }
    }
}
