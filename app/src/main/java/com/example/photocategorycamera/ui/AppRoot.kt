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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.photocategorycamera.AppContainer
import com.example.photocategorycamera.domain.FolderOpenMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

private const val HOME = "home"
private const val SETTINGS = "settings"
private const val CAMERA = "camera"

@Composable
fun AppRoot(
    appViewModel: AppViewModel,
    container: AppContainer,
    onChooseDirectory: () -> Unit,
) {
    val appState by appViewModel.state.collectAsStateWithLifecycle()
    val videoProgress by container.captureTasks.videoProgress.collectAsStateWithLifecycle()
    val state = appState.copy(videoProcessingPercent=videoProgress?.percent)
    val notesViewModel: PendingAnnotationViewModel = viewModel(key="pending-annotations",
        factory=PendingAnnotationViewModel.factory(container.captureTasks))
    val noteState by notesViewModel.state.collectAsStateWithLifecycle()
    val rootLifecycle = LocalLifecycleOwner.current
    DisposableEffect(rootLifecycle,notesViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) notesViewModel.onBackground()
            if (event == Lifecycle.Event.ON_START) notesViewModel.onForeground()
        }
        rootLifecycle.lifecycle.addObserver(observer)
        onDispose { rootLifecycle.lifecycle.removeObserver(observer) }
    }
    val snackbar = remember { SnackbarHostState() }
    val folderFeedback = remember { SnackbarHostState() }
    val context = LocalContext.current
    val xiaomiDevice = remember { isXiaomiDevice() }
    val navController = rememberNavController()
    val navigationEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(state.message) {
        state.message?.let {
            if (it.startsWith("已新增文件夹")) {
                withTimeoutOrNull(1_400) { folderFeedback.showSnackbar(it) }
            } else {
                snackbar.showSnackbar(it)
            }
            appViewModel.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.storageRoot == null) {
            SetupScreen(state.busy, onChooseDirectory)
        } else {
            NavHost(navController = navController, startDestination = HOME) {
                    composable(HOME) {
                        val homeLifecycle = LocalLifecycleOwner.current
                        LaunchedEffect(
                            homeLifecycle,
                            state.storageRoot?.treeUri,
                            state.selectedGroup.id,
                            state.selectedGroup.name,
                            state.categories.map { it.id },
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
                            onSettings = { navController.navigate(SETTINGS) },
                            onRetry = appViewModel::retryPending,
                            onDiscardFailed = appViewModel::discardFailedCaptures,
                            onSaveLegacyDocument = appViewModel::saveLegacyDocument,
                            onDiscardDocument = appViewModel::discardDocument,
                            onSelectGroup = appViewModel::selectGroup,
                            onRetryDirectoryOperation = appViewModel::retryDirectoryOperation,
                            onMigrateHome = appViewModel::migrateHomeCategories,
                            onAddCategory = appViewModel::addCategory,
                            onRefreshCategories = appViewModel::refreshCategories,
                            onRenameCategory = appViewModel::renameCategory,
                            onDeleteCategory = appViewModel::deleteCategory,
                            onAddGroup = appViewModel::addGroup,
                            onRenameGroup = appViewModel::renameGroup,
                            onDeleteGroup = appViewModel::deleteGroup,
                            onReorderCategories = appViewModel::reorderCategories,
                            onMoveCategory = appViewModel::moveCategory,
                            onDropTransfer = appViewModel::dropTransferCategory,
                            onReorderGroups = appViewModel::reorderGroups,
                            onMoveGroup = appViewModel::moveGroup,
                            onLoadGroupCounts = appViewModel::loadGroupFileCounts,
                            onOpenPendingNotes = { notesViewModel.open() },
                        )
                    }
                    composable(SETTINGS) {
                        SettingsScreen(
                            state = state,
                            isXiaomiDevice = xiaomiDevice,
                            onBack = navController::popBackStack,
                            onChooseDirectory = onChooseDirectory,
                            onFolderOpenModeChanged = appViewModel::setFolderOpenMode,
                            onOpenRoot = {
                                appViewModel.resolveRootDirectory { uri ->
                                    val mode = if (xiaomiDevice) state.folderOpenMode else FolderOpenMode.SYSTEM
                                    if (!openDirectory(context, uri, mode)) {
                                        appViewModel.reportMessage("当前文件管理器不支持直接打开根目录")
                                    }
                                }
                            },
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
                            noteEditorVisible = noteState.visible,
                            onOpenPendingNotes = { notesViewModel.open(it) },
                            postCaptureNoteEnabled = state.postCaptureNoteEnabled,
                            onPostCaptureNoteEnabledChanged = appViewModel::setPostCaptureNoteEnabled,
                            photoDateStampEnabled = state.photoDateStampEnabled,
                            photoDateStampFormat = state.photoDateStampFormat,
                            onPhotoDateStampEnabledChanged = appViewModel::setPhotoDateStampEnabled,
                            onPhotoDateStampFormatChanged = appViewModel::setPhotoDateStampFormat,
                            gridEnabled = state.cameraGridEnabled,
                            onGridEnabledChanged = appViewModel::setCameraGridEnabled,
                            videoStabilizationEnabled = state.videoStabilizationEnabled,
                            onVideoStabilizationEnabledChanged = appViewModel::setVideoStabilizationEnabled,
                        )
                    }
            }
        }
        // Keep operation feedback above the home folder controls so they remain tappable.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(bottom = if (navigationEntry?.destination?.route == HOME) 64.dp else 0.dp))
        SnackbarHost(folderFeedback, Modifier.align(Alignment.Center).padding(horizontal = 24.dp)) { data ->
            Surface(
                modifier = Modifier.widthIn(max = 320.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.68f),
            ) {
                Text(data.visuals.message, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (state.busy) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
        PendingAnnotationSheet(noteState, notesViewModel::changeDraft, notesViewModel::collapse, notesViewModel::save)

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
