package com.example.photocategorycamera.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.FolderOpenMode
import com.example.photocategorycamera.domain.XiaomiFileManagerPath
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(
    state: AppUiState,
    onOpenCamera: (Category) -> Unit,
    onOpenDirectory: (Category) -> Unit,
    onManage: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onDiscardFailed: () -> Unit,
) {
    var introExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmDiscardFailed by rememberSaveable { mutableStateOf(false) }
    if (confirmDiscardFailed) {
        AlertDialog(
            onDismissRequest = { confirmDiscardFailed = false },
            title = { Text("清除失败任务？") },
            text = { Text("将删除无法恢复的临时文件和失败记录；已经保存到分类目录的媒体不会受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscardFailed = false
                    onDiscardFailed()
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscardFailed = false }) { Text("取消") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append("Len") }
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("shelf") }
                            },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 26.sp,
                                letterSpacing = (-0.4).sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 8.dp,
            ) {
                OutlinedButton(
                    onClick = onManage,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding().height(50.dp),
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("管理分类")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { introExpanded = !introExpanded }.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("选择一个分类", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "保存到 ${state.storageRoot?.displayName.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (introExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                if (introExpanded) "折叠说明" else "展开说明",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (introExpanded) {
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 15.dp)) {
                                Text("拍摄后自动保存，无需再次整理", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                ) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(6.dp))
                                        Text(state.storageRoot?.displayName.orEmpty(), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.hasPendingCapture) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        state.pendingCaptureCount > 0 && state.failedCaptureCount > 0 ->
                                            "${state.pendingCaptureCount} 个任务待保存，${state.failedCaptureCount} 个无法恢复"
                                        state.failedCaptureCount > 0 -> "有 ${state.failedCaptureCount} 个媒体任务无法恢复"
                                        else -> "有 ${state.pendingCaptureCount} 个媒体任务尚未保存"
                                    },
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("目标分类：${state.pendingCategoryName.orEmpty()}")
                                state.failedCaptureError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (state.canRetryCapture) {
                                    TextButton(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Text("重试") }
                                }
                                if (state.failedCaptureCount > 0) {
                                    TextButton(onClick = { confirmDiscardFailed = true }) { Text("清除失败记录") }
                                }
                            }
                        }
                    }
                }
            }
            items(state.categories.filter { it.isActive }, key = Category::id) { category ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).clickable { onOpenCamera(category) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    null,
                                    Modifier.padding(11.dp).size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column {
                                Text(category.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    state.categoryFileCounts[category.id]?.let { "$it 个文件" } ?: "暂无法统计",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(
                            Modifier.padding(end = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            IconButton(
                                onClick = { onOpenDirectory(category) },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                            ) {
                                Icon(Icons.Default.Folder, "在文件管理器中打开${category.name}", tint = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

internal fun openDirectory(context: Context, uri: Uri, mode: FolderOpenMode): Boolean {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    if (mode == FolderOpenMode.XIAOMI && openXiaomiDirectory(context, uri)) return true
    val mimeTypes = listOf(DocumentsContract.Document.MIME_TYPE_DIR, "resource/folder")
    return mimeTypes.any { mimeType ->
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(flags)
        }
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

private fun openXiaomiDirectory(context: Context, safUri: Uri): Boolean {
    val documentId = runCatching { DocumentsContract.getDocumentId(safUri) }.getOrNull() ?: return false
    val pathSegments = XiaomiFileManagerPath.primaryStorageSegments(documentId) ?: return false
    val absolutePath = pathSegments.fold(Environment.getExternalStorageDirectory()) { parent, segment ->
        java.io.File(parent, segment)
    }.absolutePath
    val packages = listOf("com.android.fileexplorer", "com.mi.android.globalFileexplorer")
    packages.forEach { packageName ->
        val xiaomiIntent = Intent("miui.intent.action.OPEN").apply {
            setPackage(packageName)
            putExtra("explorer_path", absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(xiaomiIntent)
            return true
        } catch (_: ActivityNotFoundException) {
            // 当前设备未安装这个版本的小米文件管理，继续尝试下一包名。
        } catch (_: SecurityException) {
            // 当前版本不允许外部使用该入口，继续尝试下一包名。
        } catch (_: IllegalArgumentException) {
            // 当前版本不接受该目录路径，交给 Android 通用方式处理。
        }
    }
    return false
}
