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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup
import com.example.photocategorycamera.domain.FolderOpenMode
import com.example.photocategorycamera.domain.XiaomiFileManagerPath
import com.example.photocategorycamera.R

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
    onSaveLegacyDocument: (String) -> Unit,
    onDiscardDocument: (String) -> Unit,
    onSelectGroup: (String) -> Unit = {},
    onManageGroups: () -> Unit = {},
    onRetryDirectoryOperation: (String) -> Unit = {},
    onMigrateHome: () -> Unit = {},
) {
    var confirmDiscardFailed by rememberSaveable { mutableStateOf(false) }
    var confirmDiscardDocument by rememberSaveable { mutableStateOf(false) }
    if (confirmDiscardDocument) {
        AlertDialog(
            onDismissRequest = { confirmDiscardDocument = false },
            title = { Text("丢弃待确认文档？") },
            text = { Text("将删除这张未归档文档的临时原图，不影响已保存照片。") },
            confirmButton = { TextButton(onClick = {
                confirmDiscardDocument = false
                state.documentDraft?.let { onDiscardDocument(it.id) }
            }) { Text("丢弃") } },
            dismissButton = { TextButton(onClick = { confirmDiscardDocument = false }) { Text("取消") } },
        )
    }
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
                actions = {
                    IconButton(onClick = onManage) { Icon(painterResource(R.drawable.ic_manage_categories), "管理分类") }
                    IconButton(onClick = onManageGroups) { Icon(Icons.Outlined.FolderCopy, "管理文件夹") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 8.dp,
            ) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GroupTab(state.groups.firstOrNull { it.isHome } ?: HOME_GROUP, state.selectedGroup.id, onSelectGroup)
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.groups.filterNot { it.isHome }, key = CategoryGroup::id) { group ->
                            GroupTab(group, state.selectedGroup.id, onSelectGroup)
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp).testTag("category-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.homeMigrationLabels.isNotEmpty() && state.directoryOperations.isEmpty()) {
                item(key = "home-migration") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("常用分类等待迁移", style = MaterialTheme.typography.titleMedium)
                            Text("${state.homeMigrationLabels.size} 个分类将迁入根目录下的“常用”文件夹；完成前暂不可拍摄。")
                            state.homeMigrationIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = onMigrateHome) { Text("继续迁移常用分类") }
                        }
                    }
                }
            }
            items(state.directoryOperations, key = { "operation-${it.id}" }) { operation ->
                DirectoryOperationCard(operation, onRetryDirectoryOperation)
            }
            state.documentDraft?.let { draft ->
                item(key = "document-draft") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("旧版待确认文档", style = MaterialTheme.typography.titleMedium)
                            Text("分类：" + (state.documentDraftLabel ?: "${CategoryGroup.HOME_NAME}/${draft.categoryName}") + " · 尚未归档")
                            Row {
                                TextButton(onClick = { onSaveLegacyDocument(draft.id) }, enabled = !state.busy) { Text("保存旧草稿") }
                                TextButton(onClick = { confirmDiscardDocument = true }) { Text("丢弃") }
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
                                state.pendingCategoryLabels.ifEmpty { listOf(state.pendingCategoryName.orEmpty()) }
                                    .forEach { Text("目标分类：$it", style = MaterialTheme.typography.bodySmall) }
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
                val directoryLocked = category.id in state.directoryLockedCategoryIds
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
                            Modifier.weight(1f).semantics { contentDescription = "拍摄${category.directoryLabel}" }
                                .clickable(enabled = !directoryLocked) { onOpenCamera(category) }.padding(16.dp),
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
                                    if (category.needsHomeMigration) "等待迁入常用文件夹"
                                    else if (directoryLocked) "目录正在变更，暂不可拍摄"
                                    else state.categoryFileCounts[category.id]?.let { "$it 个文件" } ?: "暂无法统计",
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
                                enabled = !directoryLocked,
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                            ) {
                                Icon(Icons.Default.Folder, "在文件管理器中打开${category.name}", tint = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
            if (state.categories.none { it.isActive }) {
                item { Text("当前文件夹暂无启用分类，可在“管理分类”中新建或恢复分类。",
                    style = MaterialTheme.typography.bodyMedium) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun GroupTab(group: CategoryGroup, selectedId: String, onSelect: (String) -> Unit) {
    FilterChip(selected = group.id == selectedId, onClick = { onSelect(group.id) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.widthIn(max = 160.dp).semantics { contentDescription = "切换到${group.name}" },
        label = { Text(shortGroupName(group.name), maxLines = 1, overflow = TextOverflow.Ellipsis) })
}

private fun shortGroupName(name: String): String =
    if (name.codePointCount(0, name.length) <= 4) name else name.substring(0, name.offsetByCodePoints(0, 4)) + "…"

@Composable
internal fun DirectoryOperationCard(operation: DirectoryOperationUiState, onRetry: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor =
        if (operation.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(operation.status, style = MaterialTheme.typography.titleMedium)
            Text("从：${operation.sourcePath}", style = MaterialTheme.typography.bodySmall)
            Text("到：${operation.targetPath}", style = MaterialTheme.typography.bodySmall)
            if (operation.error == null) Text("${when (operation.stage) {
                "COPYING" -> "正在复制全部内容"
                "VERIFYING", "VERIFY_NATIVE", "VERIFY_COPY" -> "正在校验内容"
                "CLEANUP" -> "正在清理已校验的原目录"
                "COMMITTING", "FILESYSTEM_DONE" -> "正在更新分类记录"
                else -> "目录操作进行中"
            }} · 保持应用打开以继续处理", style = MaterialTheme.typography.bodySmall)
            operation.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Text("相关分类与文件夹保持锁定，重试后继续。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onRetry(operation.id) }) { Text("重试目录操作") }
            }
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
