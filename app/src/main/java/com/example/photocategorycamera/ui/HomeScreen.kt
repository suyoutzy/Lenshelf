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

import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(
    state: AppUiState,
    onOpenCamera: (Category) -> Unit,
    onOpenDirectory: (Category) -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onDiscardFailed: () -> Unit,
    onSaveLegacyDocument: (String) -> Unit,
    onDiscardDocument: (String) -> Unit,
    onSelectGroup: (String) -> Unit = {},
    onRetryDirectoryOperation: (String) -> Unit = {},
    onMigrateHome: () -> Unit = {},
    onAddCategory: (String, String, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onRefreshCategories: (String) -> Unit = {},
    onRenameCategory: (Category, String, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onDeleteCategory: (Category, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onAddGroup: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onRenameGroup: (CategoryGroup, String, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onDeleteGroup: (CategoryGroup, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    onReorderCategories: (String, List<String>, List<String>, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(false) },
    onMoveCategory: (Category, Int) -> Unit = { _, _ -> },
    onDropTransfer: (Category, String, String?, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(false) },
    onReorderGroups: (List<String>, List<String>, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onMoveGroup: (CategoryGroup, Int) -> Unit = { _, _ -> },
    onLoadGroupCounts: (String) -> Unit = {},
    onOpenPendingNotes: () -> Unit = {},
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

    val actions = remember { HomeActions() }
    val drag = rememberManagementDragState()
    val folderDrag = rememberManagementDragState()
    val tray = rememberLazyListState()
    val fallbackList = rememberLazyListState()
    val lists = remember { mutableStateMapOf<String, LazyListState>() }
    val groups = state.groups.ifEmpty { listOf(HOME_GROUP) }
    val groupIds = groups.map { it.id }
    val all = state.allCategories.ifEmpty { state.categories }
    var retained by remember { mutableStateOf(emptyList<IncomingCategoryReservation>()) }
    val reservations = retainIncomingCategoryReservations(retained, state.directoryOperations, all)
    SideEffect { retained = reservations }
    val displayState = state.copy(
        directoryOperations = (state.directoryOperations + reservations.map { it.operation }).distinctBy { it.id },
        directoryOperationGroupIds = state.directoryOperationGroupIds + reservations.flatMap {
            listOfNotNull(it.operation.sourceGroupId, it.operation.targetGroupId)
        },
        lockedGroupIds = state.lockedGroupIds + reservations.flatMap {
            listOfNotNull(it.operation.sourceGroupId, it.operation.targetGroupId)
        },
        mutationLockedCategoryIds = state.mutationLockedCategoryIds + reservations.map { it.category.id },
    )
    // The Pager may evaluate its previous key callback while applying a new count.
    // Count, keys and content must all read the same snapshot-backed list.
    val latestGroups by rememberUpdatedState(groups)
    val pager = rememberPagerState(initialPage = groupIds.indexOf(state.selectedGroup.id).coerceAtLeast(0), pageCount = { latestGroups.size })
    val visible = groups.getOrElse(pager.currentPage.coerceAtMost(groups.lastIndex)) { groups.first() }
    val desiredId = if (drag.blocking) drag.previewGroupId ?: state.selectedGroup.id else state.selectedGroup.id
    var reconciledIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var reconciledId by remember { mutableStateOf<String?>(null) }
    var navigating by remember { mutableStateOf(false) }
    var lastReportedId by remember { mutableStateOf(state.selectedGroup.id) }
    val externalPending by rememberUpdatedState(groupIds != reconciledIds || desiredId != reconciledId)
    val latestSelect by rememberUpdatedState(onSelectGroup)
    val scope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(groupIds, desiredId) {
        navigationJob?.cancel()
        navigating = true
        val index = groupIds.indexOf(desiredId).coerceAtLeast(0)
        pager.scrollToPage(index)
        if (!drag.blocking) lastReportedId = groupIds[index]
        reconciledIds = groupIds
        reconciledId = desiredId
        navigating = false
    }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(pager.isScrollInProgress, pager.settledPage,
            externalPending || navigating || drag.blocking || folderDrag.blocking || actions.modal) }.collect { (scrolling, index, blocked) ->
            if (!scrolling && !blocked) {
                val id = pager.layoutInfo.visiblePagesInfo.firstOrNull { it.index == index }?.key as? String
                if (id != null && latestGroups.any { it.id == id } && id != lastReportedId) {
                    lastReportedId = id
                    latestSelect(id)
                }
            }
        }
    }
    val select: (String) -> Unit = { id ->
        if (!drag.blocking && !folderDrag.blocking && !actions.modal && !state.busy) {
            navigationJob?.cancel()
            navigationJob = scope.launch {
                navigating = true
                try {
                    val index = latestGroups.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        pager.animateScrollToPage(index)
                        if (latestGroups.any { it.id == id }) { lastReportedId = id; latestSelect(id) }
                    }
                } finally { navigating = false }
            }
        }
    }
    LaunchedEffect(visible.id, groupIds) {
        val index = groupIds.indexOf(visible.id)
        listOf(index - 1,index,index + 1).mapNotNull { groups.getOrNull(it) }.forEach { onLoadGroupCounts(it.id) }
    }
    val world = DragWorld(all.map { DragRecord(it.id, DragPartition(it.groupId), it.toString()) },groupIds,
        displayState.mutationLockedCategoryIds + displayState.directoryLockedCategoryIds,
        displayState.directoryOperationGroupIds,
        state.busy || actions.modal || folderDrag.blocking || (pager.isScrollInProgress && !drag.blocking),
        displayState.directoryOperations.isEmpty(), deleteEnabled = true)
    SideEffect {
        drag.update(world, displayState.directoryOperations.filter { it.kind == "TRANSFER_CATEGORY" }.mapNotNull { it.categoryId }.toSet())
        drag.onDrop = { drop, complete ->
            when (drop) {
                is ManagementDrop.Reorder -> onReorderCategories(drop.source.partition.groupId,drop.originalIds,drop.proposedIds,complete)
                is ManagementDrop.Transfer -> all.firstOrNull { it.id == drop.source.id }?.let {
                    onDropTransfer(it,drop.targetGroupId,drop.beforeId,complete)
                } ?: complete(false)
                is ManagementDrop.Delete -> { actions.deleteCategory = all.firstOrNull { it.id == drop.source.id }; complete(true) }
                else -> complete(false)
            }
        }
    }
    LaunchedEffect(state.selectedGroup.id) { drag.followSelectedGroup(state.selectedGroup.id) }
    val enabled = !state.busy && !drag.blocking && !folderDrag.blocking && !actions.modal && !pager.isScrollInProgress && !navigating
    val activeGroup = groups.firstOrNull { it.id == (if (drag.blocking) drag.previewGroupId else visible.id) } ?: visible
    ManagementDragSurface(drag,lists[activeGroup.id] ?: fallbackList,tray,Modifier.fillMaxSize(),
        excludedTrailingWidth = 104.dp, liftedContent = { id ->
            all.firstOrNull { it.id == id }?.let { category ->
                HomeCategoryCard(category,null,false,false,false,{}, {}, {}, {}, {},
                    Modifier.fillMaxSize().clearAndSetSemantics { })
            }
        }) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append("Len") }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("shelf") }
            },style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp,letterSpacing = (-.4).sp,fontWeight = FontWeight.Bold)) },
                actions = {
                    IconButton(onClick = { actions.addCategoryIn = visible },enabled = enabled) { Icon(Icons.Default.Add,"新增分类") }
                    IconButton(onClick = { onRefreshCategories(visible.id) },enabled = enabled) { Icon(Icons.Default.Refresh,"刷新当前文件夹分类") }
                    IconButton(onClick = onSettings,enabled = enabled) { Icon(Icons.Default.Settings,"设置") }
                },colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },bottomBar = {
            if (drag.dragging) HomeTransferTray(displayState,drag,tray)
            else HomeFolderBar(displayState,visible.id,actions,folderDrag,drag.blocking || pager.isScrollInProgress || navigating,select,onMoveGroup,onReorderGroups)
        }) { padding ->
            HorizontalPager(pager,Modifier.padding(padding).fillMaxSize().testTag("home-pager")
                .onGloballyPositioned { drag.viewport = it.boundsInRoot() }, key = { latestGroups[it].id },
                userScrollEnabled = !drag.blocking && !folderDrag.blocking && !actions.modal && !state.busy && !navigating) { page ->
                val group = latestGroups.getOrNull(page) ?: return@HorizontalPager
                val list = rememberLazyListState()
                DisposableEffect(group.id,list) { lists[group.id] = list; onDispose { lists.remove(group.id) } }
                val partition = DragPartition(group.id)
                val real = all.filter { it.groupId == group.id }
                val incoming = reservations.filter { it.operation.targetGroupId == group.id && real.none { row -> row.id == it.category.id } }
                val pending = incoming.map { it.category.copy(groupId = group.id,groupName = group.name) }
                val rows = real + pending
                val ids = real.map { it.id }.toMutableList()
                incoming.forEach { reservation ->
                    val anchor = reservation.operation.targetBeforeCategoryId?.let(ids::indexOf)?.takeIf { it >= 0 } ?: ids.size
                    ids.add(anchor,reservation.category.id)
                }
                val ordered = drag.orderedIds(partition,ids)
                val active = group.id == activeGroup.id
                val counts = state.categoryCountsByGroup[group.id].orEmpty().ifEmpty {
                    if (group.id == state.selectedGroup.id) state.categoryFileCounts else emptyMap()
                }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)
                    .testTag(if (active) "category-list" else "category-list-${group.id}"),state = list,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    homeStatusItems(displayState,onMigrateHome,onRetryDirectoryOperation,onSaveLegacyDocument,
                        { confirmDiscardDocument = true },onRetry,{ confirmDiscardFailed = true },onOpenPendingNotes)
                    item(key = "head") { Box(Modifier.fillMaxWidth().height(4.dp).let {
                        if (active) it.dragDropArea(drag,"drop-head-${group.id}-true",partition,ids.firstOrNull { it != drag.displaySession?.source?.id }) else it
                    }) }
                    items(ordered,key = { it }) { id ->
                        // A cross-folder drag previews the source before a transfer is reserved.
                        val category = rows.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == id }
                            ?.copy(groupId = group.id, groupName = group.name) ?: return@items
                        val transferring = displayState.directoryOperations.any { it.categoryId == id }
                        val directoryLocked = id in displayState.directoryLockedCategoryIds || transferring
                        val mutationLocked = id in displayState.mutationLockedCategoryIds || directoryLocked || category.needsHomeMigration || group.id in displayState.directoryOperationGroupIds
                        HomeCategoryCard(category,counts[id],directoryLocked,enabled,mutationLocked,
                            { onOpenCamera(category) },{ onOpenDirectory(category) },{ actions.renameCategory = category },
                            { actions.deleteCategory = category },{ actions.menuOpen = it },
                            Modifier.fillMaxWidth().animateItem().let {
                                if (active) it.dragItem(drag,id,partition) else it
                            }.graphicsLayer { alpha = if (drag.lift?.id == id) .25f else 1f }
                                .semantics { if (!mutationLocked) customActions = listOf(
                                    CustomAccessibilityAction("前移") { onMoveCategory(category,-1);true },
                                    CustomAccessibilityAction("后移") { onMoveCategory(category,1);true }) },
                            statusCaption = reservations.firstOrNull { it.category.id == id }?.let {
                                if (it.operation.error != null) "未完成 · 移动失败，等待重试"
                                else if (it.operation.targetGroupId == group.id) "未完成 · 正在移动分类" else "未完成 · 正在转移分类"
                            })
                    }
                    if (rows.isEmpty()) item(key = "empty") {
                        Text("当前文件夹暂无分类，点击顶部＋新增，或刷新已有目录。",style = MaterialTheme.typography.bodyMedium)
                    }
                    item(key = "tail") { Box(Modifier.fillMaxWidth().height(if (rows.isEmpty()) 160.dp else 64.dp).let {
                        if (active) it.dragDropArea(drag,"drop-tail-${group.id}-true",partition,null) else it
                    }) }
                }
            }
        }
    }
    HomeActionDialogs(actions,state,onAddCategory,onRenameCategory,onDeleteCategory,onAddGroup,onRenameGroup,onDeleteGroup)
}

@Composable
private fun HomeCategoryCard(category: Category, count: Int?, directoryLocked: Boolean, enabled: Boolean,
    mutationLocked: Boolean, openCamera: () -> Unit, openDirectory: () -> Unit, rename: () -> Unit, delete: () -> Unit,
    onMenu: (Boolean) -> Unit, modifier: Modifier = Modifier, statusCaption: String? = null) {
    var menu by remember(category.id) { mutableStateOf(false) }
    Card(modifier,colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).semantics { contentDescription = "拍摄${category.directoryLabel}" }
                .clickable(enabled = enabled && !directoryLocked && !category.needsHomeMigration,onClick = openCamera).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(14.dp),color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.CameraAlt,null,Modifier.padding(11.dp).size(24.dp),tint = MaterialTheme.colorScheme.primary)
                }
                Column {
                    Text(category.name,style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        statusCaption != null -> statusCaption
                        category.needsHomeMigration -> "等待迁入常用文件夹"
                        directoryLocked -> "目录操作完成后可修改"
                        else -> count?.let { "$it 个文件" } ?: "暂无法统计"
                    },style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menu = true; onMenu(true) },enabled = enabled && !mutationLocked) {
                    Icon(Icons.Default.MoreVert,"${category.name}的更多操作")
                }
                DropdownMenu(menu,onDismissRequest = { menu = false; onMenu(false) }) {
                    DropdownMenuItem(text = { Text("重命名分类") },onClick = { menu = false; onMenu(false); rename() })
                    DropdownMenuItem(text = { Text("删除分类",color = MaterialTheme.colorScheme.error) },onClick = { menu = false; onMenu(false); delete() })
                }
            }
            IconButton(onClick = openDirectory,enabled = enabled && !directoryLocked,modifier = Modifier.padding(end = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(12.dp))) {
                Icon(Icons.Default.Folder,"在文件管理器中打开${category.name}",tint = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

private fun LazyListScope.homeStatusItems(state: AppUiState,onMigrateHome: () -> Unit,
    onRetryDirectoryOperation: (String) -> Unit,onSaveLegacyDocument: (String) -> Unit,
    onRequestDiscardDocument: () -> Unit,onRetry: () -> Unit,onRequestDiscardFailed: () -> Unit,onOpenPendingNotes: () -> Unit) {
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
                                TextButton(onClick = { onRequestDiscardDocument() }) { Text("丢弃") }
                            }
                        }
                    }
                }
            }
            if (state.hasPendingCapture) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor =
                        if (state.pendingCaptureCount>0 || state.failedCaptureCount>0) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        state.awaitingNoteCount > 0 && state.pendingCaptureCount == 0 && state.failedCaptureCount == 0 -> "待备注 ${state.awaitingNoteCount} 项"
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
                                if (state.awaitingNoteCount > 0) {
                                    TextButton(onClick=onOpenPendingNotes) { Text("填写备注") }
                                }
                                if (state.canRetryCapture) {
                                    TextButton(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Text("重试") }
                                }
                                if (state.failedCaptureCount > 0) {
                                    TextButton(onClick = { onRequestDiscardFailed() }) { Text("清除失败记录") }
                                }
                            }
                        }
                    }
                }
            }

}

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
