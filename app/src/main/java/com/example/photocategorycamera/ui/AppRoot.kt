package com.example.photocategorycamera.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.Surface as AndroidSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VideoStable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.photocategorycamera.AppContainer
import com.example.photocategorycamera.camera.CameraController
import com.example.photocategorycamera.camera.LiveCaptureState
import com.example.photocategorycamera.camera.LivePhotoController
import com.example.photocategorycamera.camera.VideoRecordingCallbacks
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CameraOrientation
import com.example.photocategorycamera.domain.CameraOrientationFilter
import com.example.photocategorycamera.domain.FolderOpenMode
import com.example.photocategorycamera.domain.ExposureDragMapper
import com.example.photocategorycamera.domain.XiaomiFileManagerPath
import com.example.photocategorycamera.domain.ZoomPresetSelector
import com.example.photocategorycamera.domain.VideoStabilizationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

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

@Composable
private fun LiveModeToggle(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tint by animateColorAsState(
        if (checked) Color(0xFFFFD76A) else Color.White,
        animationSpec = tween(240), label = "liveToggleTint",
    )
    val slash by animateFloatAsState(
        if (checked) 0f else 1f, animationSpec = tween(240), label = "liveToggleSlash",
    )
    val turn by animateFloatAsState(
        if (checked) 0f else -15f, animationSpec = tween(240), label = "liveToggleTurn",
    )
    IconToggleButton(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "Live 图" },
    ) {
        Canvas(Modifier.size(28.dp)) {
            val color = tint.copy(alpha = if (enabled) 1f else 0.38f)
            rotate(turn) {
                repeat(24) { index ->
                    val angle = Math.toRadians(index * 15.0)
                    drawCircle(
                        color, radius = size.minDimension * 0.033f,
                        center = center + Offset(
                            cos(angle).toFloat() * size.minDimension * 0.45f,
                            sin(angle).toFloat() * size.minDimension * 0.45f,
                        ),
                    )
                }
                drawCircle(color, size.minDimension * 0.22f, style = Stroke(width = 2.dp.toPx()))
            }
            if (slash > 0f) {
                val start = Offset(size.width * 0.13f, size.height * 0.13f)
                val end = start + Offset(size.width * 0.74f, size.height * 0.74f) * slash
                drawLine(Color.Black, start, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(color, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(
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

private fun openDirectory(context: Context, uri: Uri, mode: FolderOpenMode): Boolean {
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

private fun isXiaomiDevice(): Boolean {
    val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
    return listOf("xiaomi", "redmi", "poco").any(vendor::contains)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryManagementScreen(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: AppUiState,
    isXiaomiDevice: Boolean,
    onBack: () -> Unit,
    onChooseDirectory: () -> Unit,
    onFolderOpenModeChanged: (FolderOpenMode) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("当前根目录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(state.storageRoot?.displayName.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    Text(state.storageRoot?.treeUri.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onChooseDirectory, Modifier.fillMaxWidth()) { Text("修改根目录") }
                    Spacer(Modifier.height(10.dp))
                    Text("只影响未来拍摄；旧目录和历史照片不会移动或删除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VisibilityOff, null, tint = MaterialTheme.colorScheme.primary)
                    Text("使用 .nomedia 请求图库忽略照片", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (isXiaomiDevice) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("小米文件管理兼容", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "优先用小米文件管理打开分类目录；关闭时使用 Android 通用方式",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.folderOpenMode == FolderOpenMode.XIAOMI,
                            onCheckedChange = {
                                onFolderOpenModeChanged(if (it) FolderOpenMode.XIAOMI else FolderOpenMode.SYSTEM)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@SuppressLint("SourceLockedOrientationActivity")
@Composable
private fun CameraScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    onSwitchCategory: (String) -> Unit,
    onOpenDirectory: (Category) -> Unit,
    gridEnabled: Boolean,
    onGridEnabledChanged: (Boolean) -> Unit,
    motionPhotoEnabled: Boolean,
    onMotionPhotoEnabledChanged: (Boolean) -> Unit,
    videoStabilizationEnabled: Boolean,
    onVideoStabilizationEnabledChanged: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var audioPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    var requestingLiveAudio by remember { mutableStateOf(false) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audioPermissionGranted = it
        viewModel.reportMessage(
            if (requestingLiveAudio) {
                if (it) "麦克风已授权，Live将包含声音" else "麦克风未授权，Live将以无声方式拍摄"
            } else {
                if (it) "麦克风已授权，请再次长按快门开始录像" else "录像需要麦克风权限，未开始录制"
            },
        )
        requestingLiveAudio = false
    }
    LaunchedEffect(Unit) { if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA) }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (activity?.isChangingConfigurations == false && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    if (!permissionGranted) {
        PermissionRequiredScreen(onBack) { permissionLauncher.launch(Manifest.permission.CAMERA) }
        return
    }

    val controller = remember {
        CameraController(context, false, videoStabilizationEnabled)
    }
    val haptics = LocalHapticFeedback.current
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var exposure by remember { mutableIntStateOf(0) }
    var minExposure by remember { mutableIntStateOf(0) }
    var maxExposure by remember { mutableIntStateOf(0) }
    var categoryMenu by remember { mutableStateOf(false) }
    var settingsMenu by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var minZoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }
    var focusPoint by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var focusSequence by remember { mutableIntStateOf(0) }
    var bottomExposureActive by remember { mutableStateOf(false) }
    val livePlatformSupported = Build.VERSION.SDK_INT >= 33
    var livePhotoEnabled by remember { mutableStateOf(motionPhotoEnabled && livePlatformSupported) }
    var liveState by remember { mutableStateOf(LiveCaptureState()) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    val liveController = remember(audioPermissionGranted) {
        LivePhotoController(
            context = context,
            audioAllowed = audioPermissionGranted,
            onState = { liveState = it },
            onFocusTap = { normalizedX, normalizedY ->
                focusPoint = normalizedX * previewSize.width to normalizedY * previewSize.height
                focusSequence++
            },
            onZoomChanged = { current, minimum, maximum ->
                zoomRatio = current
                minZoomRatio = minimum
                maxZoomRatio = maximum
            },
            onExposureChanged = { current, minimum, maximum ->
                exposure = current
                minExposure = minimum
                maxExposure = maximum
            },
        )
    }
    var stabilizationSupported by remember { mutableStateOf(false) }
    var rotationTarget by remember { mutableStateOf(0f) }
    var leaveAfterVideo by remember { mutableStateOf(false) }
    val previewAspectRatio by animateFloatAsState(
        targetValue = if (state.video.isActive) 9f / 16f else 3f / 4f,
        animationSpec = tween(durationMillis = 260),
        label = "preview-aspect-ratio",
    )
    val zoomTextRotation by animateFloatAsState(
        targetValue = rotationTarget,
        animationSpec = tween(durationMillis = 320),
        label = "zoom-text-rotation",
    )
    val bottomExposureAlpha by animateFloatAsState(
        targetValue = if (bottomExposureActive) 1f else 0.28f,
        animationSpec = tween(durationMillis = 160),
        label = "bottom-exposure-alpha",
    )
    val snackbar = remember { SnackbarHostState() }
    val focusMarkerRadiusPx = with(LocalDensity.current) { 28.dp.toPx() }
    val focusSliderHeightPx = with(LocalDensity.current) { 80.dp.roundToPx() }
    val focusSliderWidthPx = with(LocalDensity.current) { 28.dp.roundToPx() }
    val focusGapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val zoomPresets = remember(minZoomRatio, maxZoomRatio) {
        ZoomPresetSelector.forRange(minZoomRatio, maxZoomRatio)
    }
    val exposureSupported = maxExposure > minExposure
    val liveBackendActive = livePhotoEnabled && !state.video.isActive

    LaunchedEffect(liveBackendActive) {
        if (liveBackendActive) controller.suspendCamera() else controller.resumeCamera()
    }
    LaunchedEffect(livePhotoEnabled) {
        if (livePhotoEnabled && !audioPermissionGranted) {
            requestingLiveAudio = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(videoStabilizationEnabled) {
        controller.setVideoStabilizationEnabled(videoStabilizationEnabled)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.video.phase, leaveAfterVideo) {
        if (leaveAfterVideo && state.video.phase == VideoRecordingPhase.IDLE) onBack()
    }
    LaunchedEffect(focusSequence) {
        if (focusSequence > 0) {
            delay(4_000)
            focusPoint = null
        }
    }
    DisposableEffect(controller) {
        val orientationFilter = CameraOrientationFilter()
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val filtered = orientationFilter.update(orientation, SystemClock.elapsedRealtime()) ?: return
                val (captureRotation, visualRotation) = cameraRotationFor(filtered)
                controller.setTargetRotation(captureRotation)
                liveController.setTargetRotation(captureRotation)
                rotationTarget = visualRotation
            }
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        onDispose { orientationListener.disable() }
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    DisposableEffect(liveController) { onDispose { liveController.close() } }
    LaunchedEffect(liveState.warning) {
        liveState.warning?.let(viewModel::reportMessage)
    }
    DisposableEffect(lifecycleOwner, controller, liveController, liveBackendActive) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (controller.isVideoRunning()) {
                        viewModel.markVideoFinalizing()
                        controller.stopVideo()
                    }
                    if (liveBackendActive) liveController.pauseForLifecycle()
                }
                Lifecycle.Event.ON_START -> if (liveBackendActive) liveController.resumeForLifecycle()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun stopVideoAndMaybeLeave(leave: Boolean = false) {
        if (leave) leaveAfterVideo = true
        viewModel.markVideoFinalizing()
        controller.stopVideo()
    }

    fun capturePhoto() {
        viewModel.beginCapture { reservation ->
            val temp = reservation.tempFile
            if (liveBackendActive) {
                liveController.capture(
                    taskId = reservation.id,
                    file = temp,
                    onSaved = { _, warning ->
                        viewModel.saveCapturedPhoto(reservation.id, isMotionPhoto = true)
                        warning?.let(viewModel::reportMessage)
                    },
                    onPhotoOnly = { _, warning ->
                        viewModel.saveCapturedPhoto(reservation.id)
                        viewModel.reportMessage(warning)
                    },
                    onError = { viewModel.onCaptureFailed(reservation.id, temp, it) },
                )
            } else {
                controller.takePhoto(reservation.id, temp,
                    onSaved = { viewModel.saveCapturedPhoto(reservation.id) },
                    onError = { viewModel.onCaptureFailed(reservation.id, temp, it) })
            }
        }
    }

    val hardwareShutter by rememberUpdatedState<() -> Unit> {
        if (!categoryMenu && !settingsMenu) {
            when (state.video.phase) {
                VideoRecordingPhase.RECORDING, VideoRecordingPhase.PAUSED -> stopVideoAndMaybeLeave()
                VideoRecordingPhase.IDLE -> capturePhoto()
                else -> Unit
            }
        }
    }
    DisposableEffect(activity) {
        val host = activity as? com.example.photocategorycamera.MainActivity
        host?.volumeShutter = { hardwareShutter() }
        onDispose { host?.volumeShutter = null }
    }

    fun startVideoFromLongPress() {
        if (!audioPermissionGranted) {
            requestingLiveAudio = false
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        viewModel.prepareVideo { preparation ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val beginRecording = {
                controller.startVideo(
                    file = preparation.tempFile,
                    fileSizeLimitBytes = preparation.fileSizeLimitBytes,
                    callbacks = VideoRecordingCallbacks(
                        onStarted = viewModel::onVideoStarted,
                        onStatus = viewModel::onVideoStatus,
                        onPaused = viewModel::onVideoPaused,
                        onResumed = viewModel::onVideoResumed,
                        onFinalized = { file, warning ->
                            viewModel.onVideoReady(preparation.taskId, file, warning)
                        },
                        onError = { file, error ->
                            viewModel.onVideoFailed(preparation.taskId, file, error)
                        },
                    ),
                )
            }
            if (liveBackendActive) {
                liveController.stop { controller.resumeCamera(beginRecording) }
            } else {
                controller.resumeCamera(beginRecording)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .zIndex(2f)
                    .background(Color.Black)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (state.video.isActive) stopVideoAndMaybeLeave(true) else onBack()
                    },
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Surface(
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = Color.White.copy(alpha = 0.12f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Box(
                                Modifier.fillMaxSize().clickable(enabled = !state.controlsLocked) { categoryMenu = true }.padding(horizontal = 14.dp),
                            ) {
                                Text(
                                    (state.category?.name ?: "加载中").take(5),
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    textAlign = TextAlign.Center,
                                    color = Color.White,
                                    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    "选择分类",
                                    tint = Color.White.copy(alpha = 0.82f),
                                    modifier = Modifier.align(Alignment.CenterEnd).size(20.dp),
                                )
                            }
                            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                                state.activeCategories.forEach { category ->
                                    DropdownMenuItem(
                                        text = { Text(category.name) },
                                        onClick = {
                                            categoryMenu = false
                                            if (category.id != state.category?.id) onSwitchCategory(category.id)
                                        },
                                    )
                                }
                            }
                        }
                        Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.20f)))
                        IconButton(
                            onClick = { state.category?.let(onOpenDirectory) },
                            enabled = state.category != null && !state.controlsLocked,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Default.Folder, "打开当前分类文件夹", tint = Color.White)
                        }
                    }
                }
                LiveModeToggle(
                    checked = livePhotoEnabled,
                    enabled = !state.controlsLocked && !state.video.isActive,
                    onCheckedChange = {
                        if (livePlatformSupported) {
                            livePhotoEnabled = it
                            onMotionPhotoEnabledChanged(it)
                        } else {
                            viewModel.reportMessage("当前 Android ${Build.VERSION.RELEASE} 支持普通拍照和录像；Live需要Android 13及以上的时间同步接口")
                        }
                    },
                )
                Box {
                    IconButton(onClick = { settingsMenu = true }, enabled = !state.controlsLocked, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Default.Settings, "相机设置", tint = Color.White)
                    }
                    DropdownMenu(expanded = settingsMenu, onDismissRequest = { settingsMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("闪光灯：${flashModeLabel(flashMode)}") },
                            leadingIcon = {
                                Icon(
                                    when (flashMode) {
                                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                                        else -> Icons.Default.FlashOff
                                    },
                                    null,
                                )
                            },
                            onClick = {
                                flashMode = when (flashMode) {
                                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                                    else -> ImageCapture.FLASH_MODE_OFF
                                }
                                if (liveBackendActive) liveController.setFlashMode(flashMode)
                                else flashMode = controller.cycleFlash()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("井字格参考线") },
                            trailingIcon = { Switch(checked = gridEnabled, onCheckedChange = null) },
                            onClick = {
                                onGridEnabledChanged(!gridEnabled)
                                settingsMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("同步防抖")
                                    Text(
                                        if (stabilizationSupported) {
                                            "预览与成片一致 · 视野会略微裁切"
                                        } else {
                                            "当前镜头不支持，录像时自动关闭"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.VideoStable, null) },
                            trailingIcon = {
                                Switch(checked = videoStabilizationEnabled, onCheckedChange = null)
                            },
                            onClick = {
                                val enabled = !videoStabilizationEnabled
                                controller.setVideoStabilizationEnabled(enabled)
                                onVideoStabilizationEnabledChanged(enabled)
                                settingsMenu = false
                            },
                        )
                    }
                }
            }

            Box(
                Modifier
                    .then(
                        if (state.video.isActive) {
                            Modifier.weight(1f).align(Alignment.CenterHorizontally)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .aspectRatio(previewAspectRatio)
                    .clipToBounds()
                    .zIndex(0f),
            ) {
                if (liveBackendActive) {
                    key(liveController) {
                        AndroidView(
                            factory = { previewContext ->
                                TextureView(previewContext).also(liveController::attach)
                            },
                            modifier = Modifier.fillMaxSize().onSizeChanged { previewSize = it },
                        )
                    }
                } else {
                    AndroidView(
                        factory = { previewContext ->
                            PreviewView(previewContext).also { view ->
                                controller.attach(
                                    previewView = view,
                                    lifecycleOwner = lifecycleOwner,
                                    onError = viewModel::reportMessage,
                                    onFocusTap = { x, y ->
                                        focusPoint = x to y
                                        focusSequence++
                                    },
                                    onZoomChanged = { current, minimum, maximum ->
                                        zoomRatio = current
                                        minZoomRatio = minimum
                                        maxZoomRatio = maximum
                                    },
                                    onExposureChanged = { current, minimum, maximum ->
                                        exposure = current
                                        minExposure = minimum
                                        maxExposure = maximum
                                    },
                                    onVideoStabilizationCapabilityChanged = { supported ->
                                        stabilizationSupported = supported
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize().onSizeChanged { previewSize = it },
                    )
                }

                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            top = if (liveBackendActive && (!liveState.ready || liveState.warming)) 52.dp else 12.dp,
                            start = 28.dp,
                            end = 28.dp,
                        ),
                    snackbar = { data ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Black.copy(alpha = 0.48f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.72f),
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    data.visuals.message,
                                    color = Color.White.copy(alpha = 0.86f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    },
                )

                if (liveBackendActive && (!liveState.ready || liveState.warming)) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.48f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                if (liveState.ready) "Live预录中，可立即拍摄" else "正在启动Live…",
                                color = Color.White.copy(alpha = 0.86f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                if (gridEnabled) {
                    Canvas(Modifier.fillMaxSize()) {
                        val lineColor = Color.White.copy(alpha = 0.42f)
                        val stroke = 1.dp.toPx()
                        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(size.width / 3f, 0f), end = androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), strokeWidth = stroke)
                        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f), end = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height), strokeWidth = stroke)
                        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, size.height / 3f), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), strokeWidth = stroke)
                        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f), end = androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f), strokeWidth = stroke)
                    }
                }

                if (state.video.isActive) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Black.copy(alpha = 0.58f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Box(Modifier.size(8.dp).background(Color(0xFFFF3B30), CircleShape))
                            Text(
                                when (state.video.phase) {
                                    VideoRecordingPhase.PREPARING -> "准备中"
                                    VideoRecordingPhase.FINALIZING -> "处理中"
                                    else -> formatVideoDuration(state.video.elapsedNanos)
                                },
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.video.qualityLabel?.let {
                                Text(it, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
                            }
                            if (state.video.stabilizationStatus == VideoStabilizationStatus.ACTIVE) {
                                Icon(
                                    Icons.Default.VideoStable,
                                    "同步防抖已生效",
                                    tint = Color(0xFFFFD34E),
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }

                focusPoint?.let { point ->
                    val previewWidth = previewSize.width.coerceAtLeast((focusMarkerRadiusPx * 2).roundToInt())
                    val previewHeight = previewSize.height.coerceAtLeast((focusMarkerRadiusPx * 2).roundToInt())
                    val markerX = point.first.coerceIn(focusMarkerRadiusPx, previewWidth - focusMarkerRadiusPx)
                    val markerY = point.second.coerceIn(focusMarkerRadiusPx, previewHeight - focusMarkerRadiusPx)
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (markerX - focusMarkerRadiusPx).roundToInt(),
                                    (markerY - focusMarkerRadiusPx).roundToInt(),
                                )
                            }
                            .size(56.dp)
                            .border(2.dp, Color(0xFFFFD34E), RoundedCornerShape(7.dp)),
                    ) {
                        Box(Modifier.size(5.dp).background(Color(0xFFFFD34E), CircleShape).align(Alignment.Center))
                    }
                    if (exposureSupported) {
                        val preferredRight = (markerX + focusMarkerRadiusPx).roundToInt() + focusGapPx
                        val sliderX = if (preferredRight + focusSliderWidthPx <= previewWidth) {
                            preferredRight
                        } else {
                            (markerX - focusMarkerRadiusPx).roundToInt() - focusGapPx - focusSliderWidthPx
                        }.coerceIn(0, (previewWidth - focusSliderWidthPx).coerceAtLeast(0))
                        val sliderY = (markerY.roundToInt() - focusSliderHeightPx / 2)
                            .coerceIn(0, (previewHeight - focusSliderHeightPx).coerceAtLeast(0))
                        Box(
                            Modifier.offset { IntOffset(sliderX, sliderY) }.size(width = 28.dp, height = 80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CompactExposureSlider(
                                value = exposure,
                                minValue = minExposure,
                                maxValue = maxExposure,
                                vertical = true,
                                dragDistanceMultiplier = 3.75f,
                                onValueChange = { value ->
                                    exposure = if (liveBackendActive) liveController.setExposureCompensation(value)
                                    else controller.setExposureCompensation(value)
                                    focusSequence++
                                },
                                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.42f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            zoomLabel(zoomRatio),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(52.dp).rotate(zoomTextRotation),
                        )
                        Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.24f)))
                        zoomPresets.forEach { preset ->
                            val selected = abs(zoomRatio - preset) < 0.08f
                            val itemColor by animateColorAsState(
                                targetValue = if (selected) Color.White.copy(alpha = 0.28f) else Color.Transparent,
                                animationSpec = tween(durationMillis = 220),
                                label = "zoom-item-color-$preset",
                            )
                            val itemScale by animateFloatAsState(
                                targetValue = if (selected) 1.08f else 1f,
                                animationSpec = tween(durationMillis = 220),
                                label = "zoom-item-scale-$preset",
                            )
                            Surface(
                                modifier = Modifier
                                    .graphicsLayer { scaleX = itemScale; scaleY = itemScale }
                                    .clickable {
                                        if (liveBackendActive) liveController.animateZoomRatio(preset)
                                        else controller.animateZoomRatio(preset)
                                    },
                                shape = CircleShape,
                                color = itemColor,
                            ) {
                                Text(
                                    zoomLabel(preset),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp).rotate(zoomTextRotation),
                                    color = if (selected) Color(0xFFFFD34E) else Color.White,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (state.video.isActive) Modifier.height(156.dp)
                        else Modifier.weight(1f),
                    )
                    .clipToBounds()
                    .background(Color.Black)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 18.dp)
                    .padding(
                        top = if (state.video.isActive) 4.dp else 18.dp,
                        bottom = if (state.video.isActive) 4.dp else 10.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Row(Modifier.fillMaxWidth().height(if (state.video.isActive) 76.dp else 72.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (state.video.phase == VideoRecordingPhase.RECORDING || state.video.phase == VideoRecordingPhase.PAUSED) {
                            IconButton(
                                onClick = {
                                    if (state.video.phase == VideoRecordingPhase.PAUSED) controller.resumeVideo()
                                    else controller.pauseVideo()
                                },
                                modifier = Modifier.size(52.dp).background(Color.White.copy(alpha = 0.14f), CircleShape),
                            ) {
                                Icon(
                                    if (state.video.phase == VideoRecordingPhase.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    if (state.video.phase == VideoRecordingPhase.PAUSED) "继续录像" else "暂停录像",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        } else Spacer(Modifier.size(52.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (state.video.phase == VideoRecordingPhase.RECORDING || state.video.phase == VideoRecordingPhase.PAUSED) {
                            Surface(
                                modifier = Modifier.size(72.dp)
                                    .border(4.dp, Color.White, CircleShape)
                                    .semantics { contentDescription = "停止录像" }
                                    .clickable { stopVideoAndMaybeLeave() },
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.12f),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(28.dp).background(Color(0xFFFF3B30), RoundedCornerShape(6.dp)))
                                }
                            }
                        } else {
                            Surface(
                                modifier = Modifier.size(68.dp)
                                    .border(5.dp, Color.White, CircleShape)
                                    .semantics { contentDescription = "拍照，长按录像" }
                                    .combinedClickable(
                                        enabled = !state.controlsLocked && !state.captureQueueFull && state.category != null,
                                        onClick = ::capturePhoto,
                                        onLongClick = ::startVideoFromLongPress,
                                    ),
                                shape = CircleShape,
                                color = if (state.controlsLocked || state.captureQueueFull) Color.Gray else Color.White.copy(alpha = 0.88f),
                            ) {
                                if (state.controlsLocked || state.captureQueueFull) Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = {
                                val switched = if (liveBackendActive) {
                                    liveController.switchLens()
                                    true
                                } else if (
                                    state.video.phase == VideoRecordingPhase.RECORDING ||
                                    state.video.phase == VideoRecordingPhase.PAUSED
                                ) {
                                    controller.switchLensDuringVideo()
                                } else {
                                    controller.switchLens()
                                }
                                if (!switched && !state.video.isActive) {
                                    viewModel.reportMessage("当前录像状态或镜头不支持切换")
                                }
                            },
                            enabled = !state.controlsLocked ||
                                state.video.phase == VideoRecordingPhase.RECORDING ||
                                state.video.phase == VideoRecordingPhase.PAUSED,
                            modifier = Modifier.size(52.dp).background(Color.White.copy(alpha = 0.14f), CircleShape),
                        ) {
                            Icon(Icons.Default.FlipCameraAndroid, "切换摄像头", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .offset(y = 8.dp)
                        .graphicsLayer { alpha = bottomExposureAlpha },
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.08f),
                ) {
                    Row(
                        Modifier.width(224.dp).padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("EV", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        CompactExposureSlider(
                            value = exposure,
                            minValue = minExposure,
                            maxValue = maxExposure,
                            vertical = false,
                            onValueChange = { value ->
                                exposure = if (liveBackendActive) liveController.setExposureCompensation(value)
                                else controller.setExposureCompensation(value)
                            },
                            onInteractionChange = { bottomExposureActive = it },
                            modifier = Modifier.weight(1f).height(24.dp),
                        )
                        Text(
                            if (exposure > 0) "+$exposure" else "$exposure",
                            color = Color.White.copy(alpha = if (exposureSupported) 0.9f else 0.4f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(22.dp),
                        )
                    }
                }
                if (state.pendingCaptureCount > 0) {
                    Text(
                        "待处理 ${state.pendingCaptureCount}/8",
                        color = if (state.captureQueueFull) Color(0xFFFFD34E) else Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (state.failedCaptureCount > 0) {
                    Text(
                        "${state.failedCaptureCount} 个任务无法恢复：${state.failedCaptureError.orEmpty()}",
                        color = Color(0xFFFF8A80),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (state.canRetry) TextButton(onClick = viewModel::retry) { Text("重试保存", color = Color.White) }
            }
        }
    }
}

@Composable
private fun CompactExposureSlider(
    value: Int,
    minValue: Int,
    maxValue: Int,
    vertical: Boolean,
    onValueChange: (Int) -> Unit,
    onInteractionChange: (Boolean) -> Unit = {},
    dragDistanceMultiplier: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val enabled = maxValue > minValue
    val fraction = if (enabled) {
        ((value - minValue).toFloat() / (maxValue - minValue)).coerceIn(0f, 1f)
    } else {
        0.5f
    }
    Canvas(
        modifier.pointerInput(minValue, maxValue, vertical, enabled, dragDistanceMultiplier) {
            if (!enabled) return@pointerInput
            fun update(position: Offset) {
                val rawFraction = if (vertical) {
                    1f - position.y / size.height
                } else {
                    position.x / size.width
                }
                val next = minValue + rawFraction.coerceIn(0f, 1f) * (maxValue - minValue)
                onValueChange(next.roundToInt())
            }
            var dragStartValue = currentValue
            var accumulatedDrag = 0f
            detectDragGestures(
                onDragStart = { position ->
                    onInteractionChange(true)
                    dragStartValue = currentValue
                    accumulatedDrag = 0f
                    if (dragDistanceMultiplier <= 1f) update(position)
                },
                onDrag = { change, dragAmount ->
                    if (dragDistanceMultiplier > 1f) {
                        accumulatedDrag += if (vertical) -dragAmount.y else dragAmount.x
                        val trackLength = if (vertical) size.height else size.width
                        onValueChange(
                            ExposureDragMapper.valueForDrag(
                                startValue = dragStartValue,
                                minValue = minValue,
                                maxValue = maxValue,
                                dragPixels = accumulatedDrag,
                                trackLengthPixels = trackLength.toFloat(),
                                distanceMultiplier = dragDistanceMultiplier,
                            ),
                        )
                    } else {
                        update(change.position)
                    }
                    change.consume()
                },
                onDragEnd = { onInteractionChange(false) },
                onDragCancel = { onInteractionChange(false) },
            )
        },
    ) {
        val trackColor = Color.White.copy(alpha = if (enabled) 0.25f else 0.12f)
        val activeColor = Color(0xFFFFD34E).copy(alpha = if (enabled) 0.92f else 0.35f)
        val stroke = 2.dp.toPx()
        val thumbRadius = 5.dp.toPx()
        if (vertical) {
            val x = size.width / 2f
            val thumbY = size.height * (1f - fraction)
            drawLine(trackColor, Offset(x, 0f), Offset(x, size.height), stroke, StrokeCap.Round)
            drawLine(activeColor, Offset(x, thumbY), Offset(x, size.height), stroke, StrokeCap.Round)
            drawCircle(activeColor, thumbRadius, Offset(x, thumbY))
        } else {
            val y = size.height / 2f
            val thumbX = size.width * fraction
            drawLine(trackColor, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            drawLine(activeColor, Offset(0f, y), Offset(thumbX, y), stroke, StrokeCap.Round)
            drawCircle(activeColor, thumbRadius, Offset(thumbX, y))
        }
    }
}

private fun cameraRotationFor(orientation: CameraOrientation): Pair<Int, Float> = when (orientation) {
    CameraOrientation.PORTRAIT -> AndroidSurface.ROTATION_0 to orientation.controlRotationDegrees
    CameraOrientation.LANDSCAPE_LEFT -> AndroidSurface.ROTATION_270 to orientation.controlRotationDegrees
    CameraOrientation.UPSIDE_DOWN -> AndroidSurface.ROTATION_180 to orientation.controlRotationDegrees
    CameraOrientation.LANDSCAPE_RIGHT -> AndroidSurface.ROTATION_90 to orientation.controlRotationDegrees
}

private fun zoomLabel(ratio: Float): String {
    val rounded = ratio.roundToInt()
    return if (abs(ratio - rounded) < 0.05f) "$rounded×" else "${(ratio * 10).roundToInt() / 10f}×"
}

private fun formatVideoDuration(durationNanos: Long): String {
    val totalSeconds = (durationNanos / 1_000_000_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

private fun flashModeLabel(mode: Int): String = when (mode) {
    ImageCapture.FLASH_MODE_AUTO -> "自动"
    ImageCapture.FLASH_MODE_ON -> "开启"
    else -> "关闭"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PermissionRequiredScreen(onBack: () -> Unit, onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.CameraAlt, null, Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("需要相机权限", style = MaterialTheme.typography.headlineSmall)
        Text("相机权限用于拍照；长按录像时还需要麦克风权限。App 不上传媒体，也不申请全盘存储权限。")
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) { Text("授予相机权限") }
        TextButton(onClick = onBack) { Text("返回") }
    }
}
