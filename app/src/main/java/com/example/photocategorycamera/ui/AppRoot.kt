package com.example.photocategorycamera.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.photocategorycamera.AppContainer
import com.example.photocategorycamera.domain.FolderOpenMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val HOME = "home"
private const val CATEGORIES = "categories"
private const val SETTINGS = "settings"
private const val CAMERA = "camera"

@Composable
fun AppRoot(
    appViewModel: AppViewModel,
    container: AppContainer,
    onChooseDirectory: () -> Unit,
) {
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val xiaomiDevice = remember { isXiaomiDevice() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            appViewModel.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.storageRoot == null) {
            SetupScreen(state.busy, onChooseDirectory)
        } else {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = HOME) {
                    composable(HOME) {
                        val homeLifecycle = LocalLifecycleOwner.current
                        LaunchedEffect(
                            homeLifecycle,
                            state.storageRoot?.treeUri,
                            state.categories.map { it.id to it.isActive },
                            state.pendingCaptureCount,
                        ) {
                            homeLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                while (isActive) {
                                    appViewModel.refreshCategoryFileCounts()
                                    delay(3_000)
                                }
                            }
                        }
                        HomeScreen(
                            state = state,
                            onOpenCamera = { navController.navigate("$CAMERA/${it.id}") },
                            onOpenDirectory = { category ->
                                appViewModel.resolveCategoryDirectory(category) { uri ->
                                    val mode = if (xiaomiDevice) state.folderOpenMode else FolderOpenMode.SYSTEM
                                    if (!openDirectory(context, uri, mode)) {
                                        appViewModel.reportMessage("当前文件管理器不支持直接打开分类目录")
                                    }
                                }
                            },
                            onManage = { navController.navigate(CATEGORIES) },
                            onSettings = { navController.navigate(SETTINGS) },
                            onRetry = appViewModel::retryPending,
                            onDiscardFailed = appViewModel::discardFailedCaptures,
                        )
                    }
                    composable(CATEGORIES) {
                        CategoryManagementScreen(
                            state = state,
                            onBack = navController::popBackStack,
                            onAdd = appViewModel::addCategory,
                            onImport = appViewModel::importCategoriesFromRoot,
                            onSetActive = appViewModel::setCategoryActive,
                            onMove = appViewModel::moveCategory,
                            onRename = appViewModel::renameCategory,
                            onDelete = appViewModel::deleteCategory,
                        )
                    }
                    composable(SETTINGS) {
                        SettingsScreen(
                            state = state,
                            isXiaomiDevice = xiaomiDevice,
                            onBack = navController::popBackStack,
                            onChooseDirectory = onChooseDirectory,
                            onFolderOpenModeChanged = appViewModel::setFolderOpenMode,
                        )
                    }
                    composable("$CAMERA/{categoryId}") { entry ->
                        val categoryId = entry.arguments?.getString("categoryId") ?: return@composable
                        val cameraViewModel: CameraViewModel = viewModel(
                            key = "camera-$categoryId",
                            factory = CameraViewModel.factory(LocalContext.current, categoryId, container),
                        )
                        CameraScreen(
                            viewModel = cameraViewModel,
                            onBack = navController::popBackStack,
                            onSwitchCategory = cameraViewModel::switchCategory,
                            onOpenDirectory = { category ->
                                appViewModel.resolveCategoryDirectory(category) { uri ->
                                    val mode = if (xiaomiDevice) state.folderOpenMode else FolderOpenMode.SYSTEM
                                    if (!openDirectory(context, uri, mode)) {
                                        appViewModel.reportMessage("当前文件管理器不支持直接打开分类目录")
                                    }
                                }
                            },
                            gridEnabled = state.cameraGridEnabled,
                            onGridEnabledChanged = appViewModel::setCameraGridEnabled,
                            motionPhotoEnabled = state.motionPhotoEnabled,
                            onMotionPhotoEnabledChanged = appViewModel::setMotionPhotoEnabled,
                            videoStabilizationEnabled = state.videoStabilizationEnabled,
                            onVideoStabilizationEnabledChanged = appViewModel::setVideoStabilizationEnabled,
                        )
                    }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        if (state.busy) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun SetupScreen(busy: Boolean, onChooseDirectory: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                Icons.Default.Folder,
                null,
                Modifier.padding(18.dp).size(44.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("欢迎使用 Lenshelf", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text("先设置照片根目录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "请选择或新建一个本地目录。App 会在其中创建分类子目录和 .nomedia，照片在文件管理器中可见，系统图库通常会忽略它们。",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onChooseDirectory, enabled = !busy) { Text("选择根目录") }
        Spacer(Modifier.height(12.dp))
        Text("建议选择内部存储中的“Pictures/Lenshelf”，不要选择云盘目录。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun isXiaomiDevice(): Boolean {
    val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
    return listOf("xiaomi", "redmi", "poco").any(vendor::contains)
}
