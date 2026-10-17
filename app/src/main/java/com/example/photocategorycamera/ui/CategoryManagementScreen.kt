package com.example.photocategorycamera.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup

internal data class IncomingCategoryReservation(val operation: DirectoryOperationUiState, val category: Category)

internal fun retainIncomingCategoryReservations(
    previous: List<IncomingCategoryReservation>,
    operations: List<DirectoryOperationUiState>,
    categories: List<Category>,
): List<IncomingCategoryReservation> {
    val retained = previous.associateBy { it.category.id }.toMutableMap()
    operations.filter { it.kind == "TRANSFER_CATEGORY" && it.targetGroupId != null }.forEach { operation ->
        val category = categories.firstOrNull { it.id == operation.categoryId }
            ?: retained[operation.categoryId]?.category
        if (category != null) retained[category.id] = IncomingCategoryReservation(operation, category)
    }
    // Independent Room flows may clear the operation before emitting the committed category.
    // Only that category echo can retire its presentation; elapsed time is not a commit signal.
    return retained.values.filter { reservation -> categories.none {
        it.id == reservation.category.id && it.groupId == reservation.operation.targetGroupId
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryManagementScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onAdd: (String, (Boolean) -> Unit) -> Unit,
    onImport: () -> Unit,
    onSetActive: (Category, Boolean) -> Unit,
    onMove: (Category, Int) -> Unit,
    onRename: (Category, String, (Boolean) -> Unit) -> Unit,
    onDelete: (Category, Boolean, (Boolean) -> Unit) -> Unit,
    onTransfer: (Category, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onRetryDirectoryOperation: (String) -> Unit = {},
    onReorder: (String, Boolean, List<String>, List<String>, (Boolean) -> Unit) -> Unit = { _, _, _, _, complete -> complete(false) },
    onDropTransfer: (Category, String, String?, (Boolean) -> Unit) -> Unit = { _, _, _, complete -> complete(false) },
) {
    var showAdd by remember { mutableStateOf(false) }
    var menuCategoryId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Category?>(null) }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }
    var transferTarget by remember { mutableStateOf<Category?>(null) }
    val drag = rememberManagementDragState()
    val listState = rememberLazyListState()
    val trayState = rememberLazyListState()
    val all = state.allCategories.ifEmpty { state.categories }
    val previewId = drag.previewGroupId ?: state.selectedGroup.id
    val previewGroup = state.groups.firstOrNull { it.id == previewId } ?: state.selectedGroup
    var retainedReservations by remember { mutableStateOf(emptyList<IncomingCategoryReservation>()) }
    val reservations = retainIncomingCategoryReservations(retainedReservations, state.directoryOperations, all)
    SideEffect { retainedReservations = reservations }
    val displayOperations = (state.directoryOperations + reservations.map { it.operation }).distinctBy { it.id }
    val directoryGroupIds = state.directoryOperationGroupIds + reservations.flatMap {
        listOfNotNull(it.operation.sourceGroupId, it.operation.targetGroupId)
    }
    val incoming = reservations.map { it.operation }.filter { it.targetGroupId == previewGroup.id }
    val realRows = all.filter { it.groupId == previewGroup.id }
    val pendingRows = incoming.mapNotNull { operation ->
        if (realRows.any { it.id == operation.categoryId }) null
        else reservations.firstOrNull { it.category.id == operation.categoryId }?.category
            ?.copy(groupId = previewGroup.id, groupName = previewGroup.name)
    }
    val rowById = (all + pendingRows).associateBy { it.id }
    val shownRows = realRows + pendingRows
    val world = DragWorld(all.map { DragRecord(it.id, DragPartition(it.groupId, it.isActive), it.toString()) },
        state.groups.map { it.id }, state.mutationLockedCategoryIds + state.directoryLockedCategoryIds + reservations.map { it.category.id } + all.filter { it.needsHomeMigration }.map { it.id },
        directoryGroupIds,
        state.busy || showAdd || renameTarget != null || deleteTarget != null || transferTarget != null || menuCategoryId != null,
        displayOperations.isEmpty())
    SideEffect {
        drag.update(world, displayOperations.filter { it.kind == "TRANSFER_CATEGORY" }.mapNotNull { it.categoryId }.toSet())
        drag.onDrop = { drop, complete ->
            when (drop) {
                is ManagementDrop.Reorder -> onReorder(drop.source.partition.groupId, drop.source.partition.active, drop.originalIds, drop.proposedIds, complete)
                is ManagementDrop.Transfer -> all.firstOrNull { it.id == drop.source.id }?.let {
                    onDropTransfer(it, drop.targetGroupId, drop.beforeId, complete)
                } ?: complete(false)
                else -> complete(false)
            }
        }
    }
    LaunchedEffect(state.selectedGroup.id) { drag.followSelectedGroup(state.selectedGroup.id) }
    val groupOpacity = remember { Animatable(1f) }
    LaunchedEffect(previewGroup.id) { groupOpacity.snapTo(.45f); groupOpacity.animateTo(1f, tween(180)) }
    val actionsEnabled = !state.busy && !drag.blocking

    ManagementDragSurface(drag, listState, trayState, Modifier.fillMaxSize(), liftedContent = { id ->
        all.firstOrNull { it.id == id }?.let { category ->
            CategoryManagementCard(category, if (category.isActive) "启用中" else "已停用",
                Modifier.fillMaxSize().clearAndSetSemantics { }) { Icon(Icons.Default.MoreVert, null, Modifier.padding(12.dp)) }
        }
    }) {
        Scaffold(topBar = {
            TopAppBar(title = { Column { Text("管理分类"); Text(previewGroup.name, style = MaterialTheme.typography.bodySmall) } },
                navigationIcon = { IconButton(onClick = { if (drag.dragging) drag.cancel() else onBack() }, enabled = !drag.submitting) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                } },
                actions = {
                    TextButton(onClick = onImport, enabled = actionsEnabled) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(4.dp)); Text("导入") }
                    IconButton(onClick = { showAdd = true }, enabled = actionsEnabled) { Icon(Icons.Default.Add, "新增") }
                })
        }, bottomBar = {
            if (drag.dragging) CategoryDragTray(state, drag, trayState)
        }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp).testTag("management-list")
                .onGloballyPositioned { drag.viewport = it.boundsInRoot() }.graphicsLayer { alpha = groupOpacity.value },
                state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "guidance") { Text("长按分类卡片拖动排序；拖到下方文件夹停留后可转移。",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(displayOperations, key = { "operation-${it.id}" }) { operation ->
                    DirectoryOperationCard(operation) { if (!drag.blocking) onRetryDirectoryOperation(it) }
                }
                listOf(true, false).forEach { active ->
                    val partition = DragPartition(previewGroup.id, active)
                    val actual = shownRows.filter { it.isActive == active }.map { it.id }.toMutableList()
                    // Incoming reservations carry a frozen insertion anchor and retain the category key.
                    incoming.filter { operation -> pendingRows.any { it.id == operation.categoryId && it.isActive == active } }.forEach { operation ->
                        val id = operation.categoryId!!
                        actual.remove(id)
                        val index = operation.targetBeforeCategoryId?.let(actual::indexOf)?.takeIf { it >= 0 } ?: actual.size
                        actual.add(index, id)
                    }
                    val ids = drag.orderedIds(partition, actual)
                    item(key = "section-$active") {
                        Text(if (active) "启用分类" else "停用分类", fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().dragDropArea(drag, "drop-head-${previewGroup.id}-$active", partition,
                                actual.firstOrNull { it != drag.displaySession?.source?.id }).padding(vertical = 12.dp))
                    }
                    items(ids, key = { it }) { id ->
                        val category = rowById[id] ?: all.firstOrNull { it.id == id } ?: return@items
                        val pending = incoming.firstOrNull { it.categoryId == id }?.takeIf { realRows.none { row -> row.id == id } }
                        val sourcePending = reservations.firstOrNull { it.category.id == id && it.operation.sourceGroupId == previewGroup.id }?.operation
                        val optimisticIncoming = category.groupId != previewGroup.id && drag.displaySession?.source?.id == id
                        val mutationLocked = state.isCategoryMutationLocked(category) || category.needsHomeMigration ||
                            category.id in state.directoryLockedCategoryIds || previewGroup.id in directoryGroupIds
                        val locked = !actionsEnabled || mutationLocked || pending != null || optimisticIncoming
                        val caption = when {
                            pending?.error != null -> "未完成 · 移动失败，等待重试"
                            pending != null -> "未完成 · 正在移动分类"
                            sourcePending?.error != null -> "未完成 · 移动失败，等待重试"
                            sourcePending != null -> "未完成 · 正在转移分类"
                            optimisticIncoming -> if (drag.dragging) "松手移动到此处" else "未完成 · 正在准备移动"
                            mutationLocked -> if (category.id in state.directoryLockedCategoryIds || category.needsHomeMigration || previewGroup.id in directoryGroupIds)
                                "目录操作完成后可修改" else "完成该分类的保存任务或草稿后可修改"
                            else -> if (category.isActive) "启用中" else "已停用"
                        }
                        CategoryManagementCard(category, caption,
                            Modifier.fillMaxWidth().animateItem(placementSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
                                .dragItem(drag, id, partition).graphicsLayer { alpha = if (drag.lift?.id == id) .25f else 1f }
                                .semantics {
                                    if (!locked) customActions = listOf(
                                        CustomAccessibilityAction("前移") { onMove(category, -1); true },
                                        CustomAccessibilityAction("后移") { onMove(category, 1); true })
                                }) {
                            Box {
                                IconButton(onClick = { menuCategoryId = id }, enabled = !locked) { Icon(Icons.Default.MoreVert, "${category.name}的更多操作") }
                                DropdownMenu(expanded = menuCategoryId == id, onDismissRequest = { menuCategoryId = null }) {
                                    DropdownMenuItem(text = { Text(if (category.isActive) "停用分类" else "恢复分类") }, enabled = !locked,
                                        onClick = { menuCategoryId = null; onSetActive(category, !category.isActive) })
                                    DropdownMenuItem(text = { Text("重命名分类") }, enabled = !locked,
                                        onClick = { menuCategoryId = null; renameTarget = category })
                                    DropdownMenuItem(text = { Text("转移分类") }, enabled = !locked && state.groups.any { it.id != category.groupId },
                                        onClick = { menuCategoryId = null; transferTarget = category })
                                    DropdownMenuItem(text = { Text("删除分类", color = MaterialTheme.colorScheme.error) }, enabled = !locked,
                                        onClick = { menuCategoryId = null; deleteTarget = category })
                                }
                            }
                        }
                    }
                    item(key = "section-tail-$active") {
                        val empty = actual.isEmpty()
                        Box(Modifier.fillMaxWidth().height(if (empty) 96.dp else 40.dp)
                            .dragDropArea(drag, "drop-tail-${previewGroup.id}-$active", partition, null)
                            .semantics { contentDescription = "${if (active) "启用" else "停用"}分类末尾放置区域" }, contentAlignment = Alignment.Center) {
                            if (empty) Text(if (drag.dragging && drag.displaySession?.source?.partition?.active == active) "拖到这里放入${if (active) "启用" else "停用"}分类"
                                else "暂无${if (active) "启用" else "停用"}分类", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    transferTarget?.let { category ->
        TransferCategoryDialog(category, state, onDismiss = { transferTarget = null },
            onConfirm = { groupId -> onTransfer(category, groupId) { success -> if (success) transferTarget = null } })
    }
    if (showAdd) AddCategoryDialog(onDismiss = { showAdd = false }, onConfirm = { name -> onAdd(name) { success -> if (success) showAdd = false } })
    renameTarget?.let { category -> RenameCategoryDialog(category, onDismiss = { renameTarget = null },
        onConfirm = { name -> onRename(category, name) { success -> if (success) renameTarget = null } }) }
    deleteTarget?.let { category -> DeleteCategoryDialog(category, onDismiss = { deleteTarget = null },
        onConfirm = { deleteContents -> onDelete(category, deleteContents) { success -> if (success) { deleteTarget = null; menuCategoryId = null } } }) }
}

@Composable
private fun CategoryManagementCard(category: Category, caption: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(category.name, fontWeight = FontWeight.SemiBold)
                Text(caption, style = MaterialTheme.typography.bodySmall)
            }
            trailing()
        }
    }
}

@Composable
private fun CategoryDragTray(state: AppUiState, drag: ManagementDragState, listState: androidx.compose.foundation.lazy.LazyListState) {
    Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 8.dp)) {
            Text("在文件夹上停留 0.6 秒预览，回到列表松手放置", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            LazyRow(Modifier.fillMaxWidth().height(80.dp).testTag("management-group-tray").onGloballyPositioned { drag.trayViewport = it.boundsInRoot() },
                state = listState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.groups, key = CategoryGroup::id) { group ->
                    val source = group.id == drag.displaySession?.source?.partition?.groupId
                    val selected = group.id == drag.previewGroupId
                    val enabled = group.id !in drag.world.blockedGroups && (source || drag.world.crossGroupEnabled)
                    Surface(Modifier.width(132.dp).fillMaxHeight().padding(vertical = 6.dp).dragGroupEntry(drag, group.id)
                        .graphicsLayer { alpha = if (enabled) 1f else .4f }.semantics(mergeDescendants = true) {
                            contentDescription = "${group.name}${if (source) "，来源" else ""}${if (selected) "，正在预览" else ""}，停留以预览"
                            if (!enabled) disabled()
                        }, shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                            Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            Text(if (source && selected) "来源 · 预览中" else if (source) "来源" else if (selected) "预览中" else if (!enabled) "目录任务处理中" else "停留以预览",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun TransferCategoryDialog(category: Category, state: AppUiState,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var destinationId by remember(category.id) { mutableStateOf<String?>(null) }
    val destinations = state.groups.filter { it.id != category.groupId }
    val destination = destinations.firstOrNull { it.id == destinationId }
    fun path(group: CategoryGroup): String = categoryDirectoryPath(state.storageRoot,
        category.copy(groupId = group.id, groupName = group.name))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("转移分类“${category.name}”") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("从：${categoryDirectoryPath(state.storageRoot, category)}", style = MaterialTheme.typography.bodySmall)
                Text("选择目标文件夹")
                LazyColumn(Modifier.height(160.dp)) {
                    items(destinations, key = CategoryGroup::id) { group ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = group.id == destinationId,
                                onClick = { destinationId = group.id }, enabled = group.id !in state.lockedGroupIds)
                            Text(group.name)
                        }
                    }
                }
                destination?.let { Text("到：${path(it)}", style = MaterialTheme.typography.bodySmall) }
                Text("将移动分类文件夹及全部内容，包括子文件夹、隐藏文件和其他文件。目标存在同名分类或目录时无法转移，请先重命名。",
                    style = MaterialTheme.typography.bodySmall)
                Text("转移后仍停留在“${state.selectedGroup.name}”。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { destinationId?.let(onConfirm) },
            enabled = destination != null && destination.id !in state.lockedGroupIds && !state.isCategoryMutationLocked(category)) {
            Text("确认转移全部内容")
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun RenameCategoryDialog(
    category: Category,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名分类") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("分类名称") },
                singleLine = true,
                supportingText = { Text("对应文件夹会同步重命名") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteCategoryDialog(
    category: Category,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var deleteFolderWithContents by remember(category.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除分类“${category.name}”？") },
        text = {
            Column {
                Text("默认只删除分类，文件夹及其中照片会保留。")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = deleteFolderWithContents,
                        onCheckedChange = { deleteFolderWithContents = it },
                    )
                    Column {
                        Text("同时删除分类文件夹及全部内容")
                        Text(
                            "文件夹中的照片和其他文件将永久删除，无法恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteFolderWithContents) }) {
                Text("确认删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AddCategoryDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增分类") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("分类名称") },
                singleLine = true,
                supportingText = { Text("1–40 个字符，不能包含 / 或 \\") },
            )
        },
        confirmButton = { Button(onClick = { onConfirm(name) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
