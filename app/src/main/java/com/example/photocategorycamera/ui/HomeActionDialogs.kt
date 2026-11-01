package com.example.photocategorycamera.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CategoryGroup

@Stable
internal class HomeActions {
    var addCategoryIn by mutableStateOf<CategoryGroup?>(null)
    var addGroup by mutableStateOf(false)
    var renameCategory by mutableStateOf<Category?>(null)
    var deleteCategory by mutableStateOf<Category?>(null)
    var renameGroup by mutableStateOf<CategoryGroup?>(null)
    var deleteGroup by mutableStateOf<CategoryGroup?>(null)
    var menuOpen by mutableStateOf(false)
    val modal: Boolean get() = addCategoryIn != null || addGroup || renameCategory != null ||
        deleteCategory != null || renameGroup != null || deleteGroup != null || menuOpen
}

@Composable
internal fun HomeActionDialogs(
    actions: HomeActions,
    state: AppUiState,
    onAddCategory: (String, String, (Boolean) -> Unit) -> Unit,
    onRenameCategory: (Category, String, (Boolean) -> Unit) -> Unit,
    onDeleteCategory: (Category, (Boolean) -> Unit) -> Unit,
    onAddGroup: (String, (Boolean) -> Unit) -> Unit,
    onRenameGroup: (CategoryGroup, String, (Boolean) -> Unit) -> Unit,
    onDeleteGroup: (CategoryGroup, (Boolean) -> Unit) -> Unit,
) {
    actions.addCategoryIn?.let { group ->
        HomeNameDialog("新增分类", "分类名称", "", "category-name-input", "创建",
            "将在“${group.name}”中创建分类", { actions.addCategoryIn = null }) { name, complete ->
            onAddCategory(group.id, name) { success -> complete(); if (success) actions.addCategoryIn = null }
        }
    }
    actions.renameCategory?.let { category ->
        HomeNameDialog("重命名分类", "分类名称", category.name, "category-name-input", "保存",
            "对应文件夹会同步重命名", { actions.renameCategory = null }) { name, complete ->
            onRenameCategory(category, name) { success -> complete(); if (success) actions.renameCategory = null }
        }
    }
    actions.deleteCategory?.let { category ->
        HomeDeleteDialog("删除分类“${category.name}”？",
            "将永久删除实际分类文件夹及全部内容，包括照片、视频、子文件夹和其他文件，无法恢复。",
            state.isCategoryMutationLocked(category), { actions.deleteCategory = null }) { complete ->
            onDeleteCategory(category) { success -> complete(); if (success) actions.deleteCategory = null }
        }
    }
    if (actions.addGroup) {
        HomeNameDialog("新增文件夹", "文件夹名称", "", "folder-name-input", "确认",
            "创建独立空文件夹，已有同名文件夹不能直接采用", { actions.addGroup = false }) { name, complete ->
            onAddGroup(name) { success -> complete(); if (success) actions.addGroup = false }
        }
    }
    actions.renameGroup?.let { group ->
        HomeNameDialog("重命名文件夹", "文件夹名称", group.name, "folder-name-input", "确认",
            "将重命名文件夹及其全部内容", { actions.renameGroup = null }) { name, complete ->
            onRenameGroup(group, name) { success -> complete(); if (success) actions.renameGroup = null }
        }
    }
    actions.deleteGroup?.let { group ->
        HomeDeleteDialog("删除空文件夹“${group.name}”？",
            "只有没有分类记录，且实际文件夹完全为空（包括隐藏文件）时才能删除。",
            group.id in state.lockedGroupIds, { actions.deleteGroup = null }) { complete ->
            onDeleteGroup(group) { success -> complete(); if (success) actions.deleteGroup = null }
        }
    }
}

@Composable
private fun HomeNameDialog(title: String, label: String, initialName: String, tag: String, confirm: String,
    guidance: String, dismiss: () -> Unit, submit: (String, () -> Unit) -> Unit) {
    var value by remember(initialName) { mutableStateOf(initialName) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!working) dismiss() }, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, Modifier.testTag(tag), enabled = !working,
            label = { Text(label) }, singleLine = true, supportingText = { Text(guidance) }) },
        confirmButton = { TextButton(enabled = value.isNotBlank() && !working, onClick = {
            working = true; submit(value) { working = false }
        }) { Text(if (working) "正在提交" else confirm) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !working) { Text("取消") } })
}

@Composable
private fun HomeDeleteDialog(title: String, description: String, locked: Boolean,
    dismiss: () -> Unit, submit: (() -> Unit) -> Unit) {
    var working by remember(title) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!working) dismiss() }, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(description)
            if (locked) Text("请先完成相关保存任务或目录操作。", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = !working && !locked, onClick = {
            working = true; submit { working = false }
        }) { Text(if (working) "正在删除" else "确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !working) { Text("取消") } })
}
