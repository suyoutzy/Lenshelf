package com.example.photocategorycamera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.photocategorycamera.domain.CategoryGroup

@Composable
internal fun HomeFolderBar(state: AppUiState, selectedId: String, actions: HomeActions,
    drag: ManagementDragState, categoryDragging: Boolean, onSelect: (String) -> Unit,
    onMove: (CategoryGroup, Int) -> Unit,
    onReorder: (List<String>, List<String>, (Boolean) -> Unit) -> Unit) {
    val list = rememberLazyListState()
    val custom = state.groups.filterNot { it.isHome }
    val partition = DragPartition("folder-order")
    val world = DragWorld(custom.map { DragRecord(it.id, partition, it.toString()) }, listOf(partition.groupId),
        lockedIds = state.directoryOperationGroupIds, busy = state.busy || actions.modal || categoryDragging,
        crossGroupEnabled = false)
    SideEffect {
        drag.update(world)
        drag.onDrop = { drop, done ->
            if (drop is ManagementDrop.Reorder) onReorder(drop.originalIds, drop.proposedIds, done) else done(false)
        }
    }
    val enabled = !state.busy && !categoryDragging && !drag.blocking && !actions.modal
    LaunchedEffect(selectedId, custom.map { it.id }, drag.blocking) {
        if (!drag.blocking) {
            val index = custom.indexOfFirst { it.id == selectedId }
            if (index >= 0) list.animateScrollToItem(index + 1) // The first item is the drop area.
        }
    }
    Surface(shadowElevation = 8.dp) {
        ManagementDragSurface(drag, list,
            modifier = Modifier.fillMaxWidth(), horizontal = true, excludedTrailingWidth = 0.dp,
            rootTag = "folder-drag-root", liftedContent = { id ->
                custom.firstOrNull { it.id == id }?.let { FolderLabel(it, selectedId, false, {}) }
            }) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                state.groups.firstOrNull { it.isHome }?.let { FolderLabel(it, selectedId, enabled, onSelect) }
                LazyRow(Modifier.weight(1f).height(56.dp).testTag("folder-tabs")
                    .onGloballyPositioned { drag.viewport = it.boundsInRoot() }, state = list,
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(key = "start") { Box(Modifier.width(4.dp).height(48.dp)
                        .dragDropArea(drag,"drop-head-folder-order-true",partition,custom.firstOrNull { it.id != drag.displaySession?.source?.id }?.id)) }
                    items(drag.orderedIds(partition,custom.map { it.id }), key = { it }) { id ->
                        val group = custom.first { it.id == id }
                        val count = (state.allCategories.ifEmpty { state.categories }).count { it.groupId == id }
                        val locked = id in state.lockedGroupIds
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FolderLabel(group,selectedId,enabled,onSelect,
                                Modifier.animateItem().dragItem(drag,id,partition)
                                    .graphicsLayer { alpha = if (drag.lift?.id == id) .25f else 1f }
                                    .semantics { if (id !in state.directoryOperationGroupIds) customActions = listOf(
                                        CustomAccessibilityAction("前移") { onMove(group,-1);true },
                                        CustomAccessibilityAction("后移") { onMove(group,1);true }) })
                            if (selectedId == id && !drag.dragging) {
                                var menu by remember(id) { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true; actions.menuOpen = true },
                                        enabled = enabled && !locked, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.MoreVert,"${group.name}的文件夹操作",Modifier.size(20.dp))
                                    }
                                    DropdownMenu(menu,onDismissRequest = { menu = false; actions.menuOpen = false }) {
                                        DropdownMenuItem(text = { Column { Text(group.name); Text("$count 个分类",style = MaterialTheme.typography.bodySmall) } },
                                            onClick = {}, enabled = false)
                                        DropdownMenuItem(text = { Text("重命名文件夹") },enabled = !locked,
                                            onClick = { menu = false; actions.menuOpen = false; actions.renameGroup = group })
                                        DropdownMenuItem(text = { Text("删除空文件夹") },enabled = !locked && count == 0,
                                            onClick = { menu = false; actions.menuOpen = false; actions.deleteGroup = group })
                                    }
                                }
                            }
                        }
                    }
                    item(key = "tail") { Box(Modifier.width(24.dp).height(48.dp)
                        .dragDropArea(drag,"drop-tail-folder-order-true",partition,null)) }
                }
                IconButton(onClick = { actions.addGroup = true },enabled = enabled) {
                    Icon(Icons.Outlined.CreateNewFolder,"新增文件夹")
                }
            }
        }
    }
}

@Composable
private fun FolderLabel(group: CategoryGroup, selectedId: String, enabled: Boolean, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier) {
    FilterChip(selected = selectedId == group.id,onClick = { onSelect(group.id) }, enabled = enabled,
        modifier = modifier.widthIn(max = 160.dp).semantics { contentDescription = "切换到${group.name}" },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary), label = {
            Text(shortGroupName(group.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
        })
}

private fun shortGroupName(name: String) = if (name.codePointCount(0,name.length) <= 4) name
    else name.substring(0,name.offsetByCodePoints(0,4)) + "…"

@Composable
internal fun HomeTransferTray(state: AppUiState, drag: ManagementDragState,
    list: androidx.compose.foundation.lazy.LazyListState) {
    DisposableEffect(drag) { onDispose { drag.trashBounds = Rect.Zero; drag.trayViewport = Rect.Zero } }
    Surface(tonalElevation = 3.dp,shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)) {
            Text("在文件夹停留后移入列表放置，拖到垃圾桶删除",Modifier.padding(horizontal = 16.dp),style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth().height(76.dp),verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f).fillMaxHeight().testTag("management-group-tray")
                    .onGloballyPositioned { drag.trayViewport = it.boundsInRoot() },state = list,
                    contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.groups,key = CategoryGroup::id) { group ->
                        val blocked = group.id in drag.world.blockedGroups || !drag.world.crossGroupEnabled &&
                            group.id != drag.displaySession?.source?.partition?.groupId
                        Surface(Modifier.width(112.dp).fillMaxHeight().padding(vertical = 6.dp)
                            .dragGroupEntry(drag,group.id).graphicsLayer { alpha = if (blocked) .4f else 1f },
                            color = if (group.id == drag.previewGroupId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(10.dp),verticalArrangement = Arrangement.Center) {
                                Text(group.name,maxLines = 1,overflow = TextOverflow.Ellipsis)
                                Text(if (blocked) "目录任务处理中" else "停留以预览",style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Surface(Modifier.size(64.dp).padding(6.dp).testTag("category-trash")
                    .onGloballyPositioned { drag.trashBounds = it.boundsInRoot() },shape = MaterialTheme.shapes.medium,
                    color = if (drag.overTrash) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.DeleteOutline,"删除分类",tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
