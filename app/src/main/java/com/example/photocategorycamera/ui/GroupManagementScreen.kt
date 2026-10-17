package com.example.photocategorycamera.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.photocategorycamera.domain.CategoryGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupManagementScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onAdd: (String, (Boolean) -> Unit) -> Unit,
    onMove: (CategoryGroup, Int) -> Unit,
    onRename: (CategoryGroup, String, (Boolean) -> Unit) -> Unit,
    onDelete: (CategoryGroup, (Boolean) -> Unit) -> Unit,
    onRetryDirectoryOperation: (String) -> Unit = {},
    onReorder: (List<String>, List<String>, (Boolean) -> Unit) -> Unit = { _, _, complete -> complete(false) },
) {
    var showAdd by remember { mutableStateOf(false) }
    var menuGroupId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<CategoryGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<CategoryGroup?>(null) }
    val drag = rememberManagementDragState()
    val listState = rememberLazyListState()
    val partition = DragPartition("folder-order")
    val custom = state.groups.filterNot { it.isHome }
    val world = DragWorld(custom.map { DragRecord(it.id, partition, it.toString()) }, listOf(partition.groupId),
        lockedIds = state.directoryOperationGroupIds,
        busy = state.busy || showAdd || renameTarget != null || deleteTarget != null || menuGroupId != null,
        crossGroupEnabled = false)
    SideEffect {
        drag.update(world)
        drag.onDrop = { drop, complete ->
            if (drop is ManagementDrop.Reorder) onReorder(drop.originalIds, drop.proposedIds, complete) else complete(false)
        }
    }
    val ordered = drag.orderedIds(partition, custom.map { it.id }).mapNotNull { id -> custom.firstOrNull { it.id == id } }
    val actionsEnabled = !state.busy && !drag.blocking
    ManagementDragSurface(drag, listState, modifier = Modifier.fillMaxSize(), liftedContent = { id ->
        custom.firstOrNull { it.id == id }?.let { group ->
            GroupManagementCard(group, "${state.allCategories.count { it.groupId == id }} 个分类（含停用分类）",
                Modifier.fillMaxSize().clearAndSetSemantics { }) { Icon(Icons.Default.MoreVert, null, Modifier.padding(12.dp)) }
        }
    }) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("管理文件夹") },
                navigationIcon = { IconButton(onClick = { if (drag.dragging) drag.cancel() else onBack() }, enabled = !drag.submitting) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                } }, actions = { IconButton(onClick = { showAdd = true }, enabled = actionsEnabled) { Icon(Icons.Default.Add, "新增文件夹") } })
        }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)
                .onGloballyPositioned { drag.viewport = it.boundsInRoot() }, state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "guidance") { Text("长按文件夹卡片拖动排序，常用固定在最前。排序不会移动文件。",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.directoryOperations, key = { "operation-${it.id}" }) { operation ->
                    DirectoryOperationCard(operation) { if (!drag.blocking) onRetryDirectoryOperation(it) }
                }
                items(state.groups.filter { it.isHome }, key = CategoryGroup::id) { group ->
                    GroupManagementCard(group, "固定常用", Modifier.fillMaxWidth()) { }
                }
                item(key = "custom-start") {
                    Box(Modifier.fillMaxWidth().height(32.dp).dragDropArea(drag, "drop-head-folder-order-true", partition,
                        custom.firstOrNull { it.id != drag.displaySession?.source?.id }?.id)
                        .semantics { contentDescription = "自定义文件夹首位放置区域" })
                }
                items(ordered, key = CategoryGroup::id) { group ->
                    val count = state.allCategories.count { it.groupId == group.id }
                    val menuLocked = !actionsEnabled || group.id in state.lockedGroupIds
                    val sortingLocked = !actionsEnabled || group.id in state.directoryOperationGroupIds
                    val caption = when {
                        group.id in state.directoryOperationGroupIds -> "目录操作完成后可排序或修改"
                        group.id in state.lockedGroupIds -> "$count 个分类 · 可排序，保存任务或草稿完成后可重命名或删除"
                        else -> "$count 个分类（含停用分类）"
                    }
                    GroupManagementCard(group, caption, Modifier.fillMaxWidth()
                        .animateItem(placementSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
                        .dragItem(drag, group.id, partition).graphicsLayer { alpha = if (drag.lift?.id == group.id) .25f else 1f }
                        .semantics {
                            if (!sortingLocked) customActions = listOf(
                                CustomAccessibilityAction("前移") { onMove(group, -1); true },
                                CustomAccessibilityAction("后移") { onMove(group, 1); true })
                        }) {
                        Box {
                            IconButton(onClick = { menuGroupId = group.id }, enabled = !menuLocked) { Icon(Icons.Default.MoreVert, "${group.name}的更多操作") }
                            DropdownMenu(expanded = menuGroupId == group.id, onDismissRequest = { menuGroupId = null }) {
                                DropdownMenuItem(text = { Text("重命名文件夹") }, enabled = !menuLocked,
                                    onClick = { menuGroupId = null; renameTarget = group })
                                DropdownMenuItem(text = { Text("删除空文件夹") }, enabled = !menuLocked && count == 0,
                                    onClick = { menuGroupId = null; deleteTarget = group })
                            }
                        }
                    }
                }
                item(key = "custom-tail") {
                    Box(Modifier.fillMaxWidth().height(64.dp).dragDropArea(drag, "drop-tail-folder-order-true", partition, null)
                        .semantics { contentDescription = "自定义文件夹末尾放置区域" }, contentAlignment = Alignment.Center) {
                        if (custom.isEmpty()) Text("暂无自定义文件夹", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (showAdd) GroupNameDialog("新增文件夹", "", onDismiss = { showAdd = false },
        onConfirm = { name, complete -> onAdd(name) { success -> complete(); if (success) showAdd = false } })
    renameTarget?.let { group ->
        GroupNameDialog("重命名文件夹", group.name, onDismiss = { renameTarget = null },
            onConfirm = { name, complete -> onRename(group, name) { success -> complete(); if (success) renameTarget = null } })
    }
    deleteTarget?.let { group ->
        var deleting by remember(group.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("删除空文件夹“${group.name}”？") },
            text = { Text("只有未注册任何分类，且实际文件夹完全为空（包括隐藏文件）时才能删除。\n目录：${state.storageRoot?.let(::directoryRootPath).orEmpty()}/${group.name}") },
            confirmButton = { TextButton(enabled = !deleting && group.id !in state.lockedGroupIds,
                onClick = { deleting = true; onDelete(group) { success -> deleting = false; if (success) deleteTarget = null } }) {
                Text(if (deleting) "正在检查" else "确认删除")
            } }, dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !deleting) { Text("取消") } })
    }
}

@Composable
private fun GroupManagementCard(group: CategoryGroup, caption: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(group.name, fontWeight = FontWeight.SemiBold)
                Text(caption, style = MaterialTheme.typography.bodySmall)
            }
            trailing()
        }
    }
}
@Composable
private fun GroupNameDialog(title: String, initialName: String, onDismiss: () -> Unit,
    onConfirm: (String, () -> Unit) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var submitting by remember(initialName) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!submitting) onDismiss() }, title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !submitting,
            label = { Text("文件夹名称") }, singleLine = true,
            supportingText = { Text(if (initialName.isEmpty()) "创建独立空文件夹，已有同名文件夹不能直接采用"
                else "将重命名文件夹及其全部内容") }) },
        confirmButton = { TextButton(onClick = { submitting = true; onConfirm(name) { submitting = false } },
            enabled = name.isNotBlank() && !submitting) { Text(if (submitting) "正在提交" else "确认") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } })
}
