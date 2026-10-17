package com.example.photocategorycamera.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.OrientationEventListener
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoStable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.photocategorycamera.camera.CameraController
import com.example.photocategorycamera.camera.VideoRecordingCallbacks
import com.example.photocategorycamera.domain.Category
import com.example.photocategorycamera.domain.CaptureMode
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.CameraOrientation
import com.example.photocategorycamera.domain.CameraOrientationFilter
import com.example.photocategorycamera.domain.ExposureDragMapper
import com.example.photocategorycamera.domain.ZoomPresetSelector
import com.example.photocategorycamera.domain.VideoStabilizationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@SuppressLint("SourceLockedOrientationActivity")
@Composable
internal fun CameraScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    onSwitchCategory: (String) -> Unit,
    onOpenDirectory: (Category) -> Unit,
    gridEnabled: Boolean,
    onGridEnabledChanged: (Boolean) -> Unit,
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
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audioPermissionGranted = it
        viewModel.reportMessage(if (it) "麦克风已授权，请再次长按快门开始录像" else "录像需要麦克风权限，未开始录制")
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
        CameraController(context, videoStabilizationEnabled)
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
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var documentOutline by remember { mutableStateOf<FloatArray?>(null) }

    LaunchedEffect(state.captureMode) {
        controller.setDocumentMode(state.captureMode == CaptureMode.DOCUMENT)
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
                rotationTarget = visualRotation
            }
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        onDispose { orientationListener.disable() }
    }
    DisposableEffect(controller) { onDispose { viewModel.dismissDocumentPreview(); controller.release() } }
    DisposableEffect(lifecycleOwner, controller, state.captureMode) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    viewModel.dismissDocumentPreview()
                    if (controller.isVideoRunning()) {
                        viewModel.markVideoFinalizing()
                        controller.stopVideo()
                    } else if (state.captureMode == CaptureMode.DOCUMENT) {
                        controller.suspendCamera()
                    }
                }
                Lifecycle.Event.ON_START -> if (!controller.isVideoRunning()) controller.resumeCamera()
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
        if (state.directoryLocked) return
        if (state.captureMode == CaptureMode.DOCUMENT) {
            viewModel.beginDocumentCapture { reservation ->
                val boundary = controller.freezeDocumentBoundary()
                val projection = controller.freezeDocumentProjection()
                controller.takePhoto(reservation.id, reservation.file,
                    onSaved = { file -> viewModel.onDocumentCaptured(reservation.id, projection?.project(file), boundary?.mapToUpright(file)) },
                    onError = { viewModel.onDocumentFailed(reservation.id, it) })
            }
            return
        }
        viewModel.beginCapture { reservation ->
            val temp = reservation.tempFile
            controller.takePhoto(reservation.id, temp,
                    onSaved = { viewModel.saveCapturedPhoto(reservation.id) },
                    onError = { viewModel.onCaptureFailed(reservation.id, temp, it) })
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
        if (state.directoryLocked) return
        if (state.captureMode == CaptureMode.DOCUMENT) return
        if (!audioPermissionGranted) {
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
            beginRecording()
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
                                flashMode = controller.cycleFlash()
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
                        if (state.captureMode == CaptureMode.DOCUMENT) {
                            DocumentEffect.entries.forEach { effect ->
                                DropdownMenuItem(
                                    text = { Text("文档：" + effect.label + if (state.documentEffect == effect) " ✓" else "") },
                                    onClick = { viewModel.setDocumentEffect(effect); settingsMenu = false },
                                )
                            }
                        }
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
                                    onDocumentOutline = { documentOutline = it },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize().onSizeChanged { previewSize = it },
                    )

                state.documentDisplay?.let { display ->
                    DocumentCaptureOverlay(display) { viewModel.dismissDocumentPreview(display.preview.id) }
                }

                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .zIndex(2f)
                        .align(Alignment.TopCenter)
                        .padding(
                            top = 12.dp,
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

                if (state.captureMode == CaptureMode.DOCUMENT) {
                    documentOutline?.let { points ->
                        Canvas(Modifier.fillMaxSize()) {
                            for (index in 0..3) {
                                val next = (index + 1) % 4
                                drawLine(Color(0xFFFFD34E), Offset(points[index * 2], points[index * 2 + 1]),
                                    Offset(points[next * 2], points[next * 2 + 1]), strokeWidth = 2.dp.toPx())
                            }
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
                                    exposure = controller.setExposureCompensation(value)
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
                                        controller.animateZoomRatio(preset)
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
                if (!state.video.isActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CaptureMode.entries.forEach { mode ->
                            TextButton(onClick = { viewModel.setCaptureMode(mode) }, enabled = !state.controlsLocked) {
                                Text(if (mode == CaptureMode.PHOTO) "照片" else "文档",
                                    color = if (state.captureMode == mode) Color(0xFFFFD34E) else Color.White.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
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
                                    .semantics { contentDescription = if (state.captureMode == CaptureMode.DOCUMENT) "拍摄文档" else "拍照，长按录像" }
                                    .combinedClickable(
                                        enabled = !state.controlsLocked && !state.directoryLocked && !state.captureQueueFull && state.category != null,
                                        onClick = ::capturePhoto,
                                        onLongClick = if (state.captureMode == CaptureMode.PHOTO) ::startVideoFromLongPress else null,
                                    ),
                                shape = CircleShape,
                                color = if (state.controlsLocked || state.directoryLocked || state.captureQueueFull) Color.Gray else Color.White.copy(alpha = 0.88f),
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
                                val switched = if (
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
                                exposure = controller.setExposureCompensation(value)
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
                if (state.directoryLocked) {
                    Text("该分类正在变更目录，可切换到其他分类拍摄", color = Color(0xFFFFD34E), style = MaterialTheme.typography.labelSmall)
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
