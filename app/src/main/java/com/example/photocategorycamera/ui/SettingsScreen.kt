package com.example.photocategorycamera.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.photocategorycamera.domain.FolderOpenMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: AppUiState,
    isXiaomiDevice: Boolean,
    onBack: () -> Unit,
    onChooseDirectory: () -> Unit,
    onFolderOpenModeChanged: (FolderOpenMode) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("当前根目录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(state.storageRoot?.displayName.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    Text(state.storageRoot?.treeUri.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onChooseDirectory, Modifier.fillMaxWidth()) { Text("修改根目录") }
                    Spacer(Modifier.height(10.dp))
                    Text("只影响未来拍摄；旧目录和历史照片不会移动或删除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VisibilityOff, null, tint = MaterialTheme.colorScheme.primary)
                    Text("使用 .nomedia 请求图库忽略照片", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (isXiaomiDevice) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("小米文件管理兼容", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "优先用小米文件管理打开分类目录；关闭时使用 Android 通用方式",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.folderOpenMode == FolderOpenMode.XIAOMI,
                            onCheckedChange = {
                                onFolderOpenModeChanged(if (it) FolderOpenMode.XIAOMI else FolderOpenMode.SYSTEM)
                            },
                        )
                    }
                }
            }
        }
    }
}
