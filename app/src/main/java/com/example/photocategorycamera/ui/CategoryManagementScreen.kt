package com.example.photocategorycamera.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.photocategorycamera.domain.Category
import kotlinx.coroutines.isActive

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
) {
    var showAdd by remember { mutableStateOf(false) }
    var menuCategoryId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Category?>(null) }
    var deleteTarget by remember { mutableStateOf<Category?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理分类") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = onImport, enabled = !state.busy) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(4.dp))
                        Text("导入")
                    }
                    IconButton(onClick = { showAdd = true }, enabled = !state.busy) {
                        Icon(Icons.Default.Add, "新增")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.categories, key = Category::id) { category ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(category.name, fontWeight = FontWeight.SemiBold)
                            Text(if (category.isActive) "启用中" else "已停用", style = MaterialTheme.typography.bodySmall)
                        }
                        if (category.isActive) {
                            IconButton(onClick = { onMove(category, -1) }) { Icon(Icons.Default.ArrowUpward, "上移") }
                            IconButton(onClick = { onMove(category, 1) }) { Icon(Icons.Default.ArrowDownward, "下移") }
                        }
                        Box {
                            IconButton(onClick = { menuCategoryId = category.id }) {
                                Icon(Icons.Default.MoreVert, "${category.name}的更多操作")
                            }
                            DropdownMenu(
                                expanded = menuCategoryId == category.id,
                                onDismissRequest = { menuCategoryId = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (category.isActive) "停用分类" else "恢复分类") },
                                    onClick = {
                                        menuCategoryId = null
                                        onSetActive(category, !category.isActive)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("重命名分类") },
                                    onClick = {
                                        menuCategoryId = null
                                        renameTarget = category
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除分类", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuCategoryId = null
                                        deleteTarget = category
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) {
        AddCategoryDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name -> onAdd(name) { success -> if (success) showAdd = false } },
        )
    }
    renameTarget?.let { category ->
        RenameCategoryDialog(
            category = category,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRename(category, name) { success ->
                    if (success) renameTarget = null
                }
            },
        )
    }
    deleteTarget?.let { category ->
        DeleteCategoryDialog(
            category = category,
            onDismiss = { deleteTarget = null },
            onConfirm = { deleteFolderWithContents ->
                onDelete(category, deleteFolderWithContents) { success ->
                    if (success) {
                        deleteTarget = null
                        menuCategoryId = null
                    }
                }
            },
        )
    }
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
