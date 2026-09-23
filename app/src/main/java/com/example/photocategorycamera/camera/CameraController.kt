package com.example.photocategorycamera.camera

import android.animation.ValueAnimator
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.animation.DecelerateInterpolator
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.AspectRatio
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.view.PreviewView
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.example.photocategorycamera.domain.VideoStabilizationPolicy
import com.example.photocategorycamera.domain.VideoStabilizationStatus
import com.example.photocategorycamera.diagnostics.CaptureEventLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class VideoRecordingCallbacks(
    val onStarted: (String, VideoStabilizationStatus, String?) -> Unit,
    val onStatus: (Long) -> Unit,
    val onPaused: () -> Unit,
    val onResumed: () -> Unit,
    val onFinalized: (File, String?) -> Unit,
    val onError: (File, String) -> Unit,
)

@SuppressLint("UnsafeOptInUsageError")
class CameraController(
    private val context: Context,
    initialMotionPhotoEnabled: Boolean = false,
    initialVideoStabilizationEnabled: Boolean = true,
) {
    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var onFocusTap: (Float, Float) -> Unit = { _, _ -> }
    private var onZoomChanged: (Float, Float, Float) -> Unit = { _, _, _ -> }
    private var onExposureChanged: (Int, Int, Int) -> Unit = { _, _, _ -> }
    private var targetRotation = Surface.ROTATION_0
    private var zoomAnimator: ValueAnimator? = null
    private var observedZoomState: LiveData<ZoomState>? = null
    private var motionPhotoEnabled = initialMotionPhotoEnabled
    private var videoStabilizationEnabled = initialVideoStabilizationEnabled
    private var activeVideoStabilizationStatus = VideoStabilizationStatus.DISABLED_BY_USER
    private var activeRecording: Recording? = null
    private var standaloneVideoRunning = false
    private var standaloneVideoPaused = false
    private var standaloneLensSwitching = false
    private var standaloneCaptureRotation = Surface.ROTATION_0
    private var liveCaptureRunning = false
    private var cameraEnabled = true
    private var onCameraReady: (() -> Unit)? = null
    private var sensorTimestampRealtime = false
    private val ordinaryCaptures = ArrayDeque<OrdinaryCapture>()
    private var released = false
    private var onError: (String) -> Unit = {}
    private var onMotionPhotoChanged: (Boolean) -> Unit = {}
    private var onVideoStabilizationCapabilityChanged: (Boolean) -> Unit = {}
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private data class OrdinaryCapture(
        val taskId: String,
        val acceptedNs: Long,
        var sensorNs: Long? = null,
    )

    private val stillCaptureEvents = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) != CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) return
            val pending = synchronized(ordinaryCaptures) { ordinaryCaptures.firstOrNull { it.sensorNs == null } } ?: return
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            pending.sensorNs = timestamp
            CaptureEventLog.record(
                context,
                "ordinary_exposure_completed",
                pending.taskId,
                values = mapOf(
                    "sensorTimestampNs" to timestamp,
                    "acceptedToExposureMs" to if (sensorTimestampRealtime) (timestamp - pending.acceptedNs) / 1_000_000.0 else null,
                    "exposureTimeNs" to result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    "afState" to result.get(CaptureResult.CONTROL_AF_STATE),
                    "aeState" to result.get(CaptureResult.CONTROL_AE_STATE),
                    "awbState" to result.get(CaptureResult.CONTROL_AWB_STATE),
                ),
            )
        }
    }

    fun attach(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
        onError: (String) -> Unit,
        onFocusTap: (Float, Float) -> Unit = { _, _ -> },
        onZoomChanged: (Float, Float, Float) -> Unit = { _, _, _ -> },
        onExposureChanged: (Int, Int, Int) -> Unit = { _, _, _ -> },
        onMotionPhotoChanged: (Boolean) -> Unit = {},
        onVideoStabilizationCapabilityChanged: (Boolean) -> Unit = {},
    ) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        this.onFocusTap = onFocusTap
        this.onZoomChanged = onZoomChanged
        this.onExposureChanged = onExposureChanged
        this.onError = onError
        this.onMotionPhotoChanged = onMotionPhotoChanged
        this.onVideoStabilizationCapabilityChanged = onVideoStabilizationCapabilityChanged
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        installGestures(previewView)
        targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                if (cameraEnabled) {
                    if (previewView.isLaidOut) bindWithMotionFallback()
                    else previewView.doOnLayout { if (cameraEnabled) bindWithMotionFallback() }
                }
            }.onFailure { onError(it.message ?: "相机初始化失败") }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindPhotoMode() {
        val owner = lifecycleOwner ?: return
        val view = previewView ?: return
        val cameraProvider = provider ?: return
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also { it.surfaceProvider = view.surfaceProvider }
        val imageCaptureBuilder = ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setTargetRotation(targetRotation)
        Camera2Interop.Extender(imageCaptureBuilder).setSessionCaptureCallback(stillCaptureEvents)
        imageCapture = imageCaptureBuilder.build()
        videoCapture = if (motionPhotoEnabled) {
            val qualitySelector = QualitySelector.from(
                Quality.HD,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
            )
            val recorder = Recorder.Builder()
                .setAspectRatio(AspectRatio.RATIO_4_3)
                .setQualitySelector(qualitySelector)
                .build()
            VideoCapture.Builder(recorder)
                .setTargetRotation(targetRotation)
                .build()
        } else {
            null
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(imageCapture!!)
            .apply { videoCapture?.let(::addUseCase) }
            // Photo geometry must not inherit the still-animating 9:16 video view.
            .setViewPort(
                androidx.camera.core.ViewPort.Builder(android.util.Rational(3, 4), Surface.ROTATION_0)
                    .setScaleType(androidx.camera.core.ViewPort.FILL_CENTER)
                    .build(),
            )
            .build()
        observedZoomState?.removeObservers(owner)
        observedZoomState = null
        cameraProvider.unbindAll()
        val boundCamera = cameraProvider.bindToLifecycle(owner, selector, useCaseGroup)
        camera = boundCamera
        val cameraManager = context.getSystemService(CameraManager::class.java)
        sensorTimestampRealtime = runCatching {
            val cameraId = cameraManager.cameraIdList.first { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lensFacing
            }
            cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        }.getOrDefault(false)
        val zoomState = boundCamera.cameraInfo.zoomState
        observedZoomState = zoomState
        zoomState.observe(owner) { zoom ->
            onZoomChanged(zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio)
        }
        zoomState.value?.let { zoom ->
            onZoomChanged(zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio)
        }
        boundCamera.cameraInfo.exposureState.let { exposure ->
            val range = exposure.exposureCompensationRange
            onExposureChanged(exposure.exposureCompensationIndex, range.lower, range.upper)
        }
        runCatching { notifyVideoStabilizationCapability(boundCamera.cameraInfo) }
            .onFailure { onVideoStabilizationCapabilityChanged(false) }
        onCameraReady?.also { callback ->
            onCameraReady = null
            mainHandler.post(callback)
        }
    }

    private fun bindWithMotionFallback() {
        if (!cameraEnabled || released) return
        runCatching(::bindPhotoMode).onFailure { firstError ->
            if (!motionPhotoEnabled) throw firstError
            motionPhotoEnabled = false
            onMotionPhotoChanged(false)
            runCatching(::bindPhotoMode)
                .onSuccess { onError("当前镜头不支持同时拍照和录制，Live图已关闭") }
                .onFailure { throw it }
        }
    }

    fun takePhoto(taskId: String, file: File, onSaved: (File) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture ?: run {
            onError("相机尚未就绪")
            return
        }
        val pending = OrdinaryCapture(taskId, SystemClock.elapsedRealtimeNanos())
        synchronized(ordinaryCaptures) { ordinaryCaptures.addLast(pending) }
        CaptureEventLog.record(context, "ordinary_capture_accepted", taskId, pending.acceptedNs)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    synchronized(ordinaryCaptures) { ordinaryCaptures.remove(pending) }
                    CaptureEventLog.record(context, "ordinary_jpeg_completed", taskId, values = mapOf("bytes" to file.length()))
                    onSaved(file)
                }
                override fun onError(exception: ImageCaptureException) {
                    synchronized(ordinaryCaptures) { ordinaryCaptures.remove(pending) }
                    CaptureEventLog.record(context, "ordinary_capture_failed", taskId, values = mapOf("error" to exception.message))
                    onError(exception.message ?: "拍摄失败")
                }
            },
        )
    }

    fun takeMotionPhoto(file: File, onSaved: (File) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture
        val recorder = videoCapture?.output
        if (!motionPhotoEnabled || capture == null || recorder == null) {
            onError("Live图尚未就绪")
            return
        }
        if (liveCaptureRunning) {
            onError("上一张Live图仍在处理中")
            return
        }
        liveCaptureRunning = true
        val captureRotation = targetRotation
        capture.targetRotation = captureRotation
        videoCapture?.targetRotation = captureRotation
        val videoFile = File(file.parentFile, "${file.nameWithoutExtension}_motion.mp4")
        var jpegSaved = false
        var jpegError: String? = null
        var videoFinalized = false
        var videoError: String? = null
        var completionStarted = false
        var latestDurationUs = 0L
        var latestDurationObservedAtNanos = 0L
        var recordingStartedAtNanos = 0L
        var presentationTimestampUs = 0L
        val completionDeadline = SystemClock.elapsedRealtime() + LIVE_COMPLETION_TIMEOUT_MS
        lateinit var tryComplete: () -> Unit
        fun fail(message: String) {
            if (completionStarted) return
            completionStarted = true
            videoFile.delete()
            file.delete()
            liveCaptureRunning = false
            restoreRotationAfterMotionCapture()
            onError(message)
        }
        tryComplete = completion@{
            if (released) {
                videoFile.delete()
                file.delete()
                return@completion
            }
            if (completionStarted || !videoFinalized) return@completion
            videoError?.let {
                fail(it)
                return@completion
            }
            jpegError?.let {
                fail(it)
                return@completion
            }
            if (!jpegSaved) {
                if (SystemClock.elapsedRealtime() >= completionDeadline) {
                    fail("Live图静态照片处理超时")
                } else {
                    mainHandler.postDelayed(tryComplete, 100L)
                }
                return@completion
            }
            completionStarted = true
            ioExecutor.execute {
                runCatching {
                    val boundedPresentationTimestampUs = if (latestDurationUs > 0L) {
                        presentationTimestampUs.coerceIn(0L, latestDurationUs)
                    } else {
                        presentationTimestampUs.coerceAtLeast(0L)
                    }
                    MotionPhotoAssembler.assemble(file, videoFile, boundedPresentationTimestampUs)
                }.onSuccess {
                    videoFile.delete()
                    mainHandler.post {
                        liveCaptureRunning = false
                        restoreRotationAfterMotionCapture()
                        onSaved(file)
                    }
                }.onFailure { error ->
                    videoFile.delete()
                    file.delete()
                    mainHandler.post {
                        liveCaptureRunning = false
                        restoreRotationAfterMotionCapture()
                        onError(error.message ?: "Live图封装失败")
                    }
                }
            }
        }
        val outputOptions = FileOutputOptions.Builder(videoFile).build()
        val recording = recorder.prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordingStartedAtNanos = SystemClock.elapsedRealtimeNanos()
                        latestDurationUs = event.recordingStats.recordedDurationNanos / 1_000L
                        latestDurationObservedAtNanos = recordingStartedAtNanos
                    }
                    is VideoRecordEvent.Status -> {
                        latestDurationUs = event.recordingStats.recordedDurationNanos / 1_000L
                        latestDurationObservedAtNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    is VideoRecordEvent.Finalize -> {
                        latestDurationUs = event.recordingStats.recordedDurationNanos / 1_000L
                        activeRecording = null
                        if (released) {
                            videoFile.delete()
                            file.delete()
                            return@start
                        }
                        videoFinalized = true
                        if (event.hasError()) videoError = "Live图视频片段录制失败（${event.error}）"
                        tryComplete()
                    }
                }
            }
        activeRecording = recording
        val shutterRequestedAtNanos = SystemClock.elapsedRealtimeNanos()
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    presentationTimestampUs = when {
                        recordingStartedAtNanos > 0L ->
                            (shutterRequestedAtNanos - recordingStartedAtNanos).coerceAtLeast(0L) / 1_000L
                        latestDurationObservedAtNanos > 0L -> latestDurationUs
                        else -> 0L
                    }
                    jpegSaved = true
                    tryComplete()
                }

                override fun onError(exception: ImageCaptureException) {
                    jpegError = exception.message ?: "Live图静态照片拍摄失败"
                    activeRecording?.stop()
                    tryComplete()
                }
            },
        )
        mainHandler.postDelayed({ activeRecording?.stop() }, LIVE_TOTAL_DURATION_MS)
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun startVideo(
        file: File,
        fileSizeLimitBytes: Long,
        callbacks: VideoRecordingCallbacks,
    ) {
        if (standaloneVideoRunning || liveCaptureRunning || activeRecording != null) {
            callbacks.onError(file, "相机正在处理上一项拍摄")
            return
        }
        val owner = lifecycleOwner
        val view = previewView
        val cameraProvider = provider
        if (owner == null || view == null || cameraProvider == null) {
            callbacks.onError(file, "相机尚未就绪")
            return
        }
        standaloneVideoRunning = true
        standaloneVideoPaused = false
        standaloneCaptureRotation = targetRotation
        val requestedStatus = runCatching {
            resolveVideoStabilizationStatus(cameraProvider, lensFacing)
        }.getOrDefault(VideoStabilizationStatus.UNSUPPORTED)
        var actualStatus = requestedStatus
        var stabilizationWarning: String? = when (requestedStatus) {
            VideoStabilizationStatus.UNSUPPORTED -> "当前镜头不支持同步防抖，本次已关闭"
            else -> null
        }
        val recorder = runCatching {
            if (requestedStatus == VideoStabilizationStatus.ACTIVE) {
                runCatching {
                    bindStandaloneVideo(owner, view, cameraProvider, stabilizationEnabled = true)
                }.getOrElse {
                    actualStatus = VideoStabilizationPolicy.afterStabilizedBindingFailure(requestedStatus)
                    stabilizationWarning = "同步防抖组合绑定失败，本次已关闭"
                    bindStandaloneVideo(owner, view, cameraProvider, stabilizationEnabled = false)
                }
            } else {
                bindStandaloneVideo(owner, view, cameraProvider, stabilizationEnabled = false)
            }
            videoCapture?.output ?: error("录像组件未就绪")
        }.getOrElse { error ->
            standaloneVideoRunning = false
            restorePhotoModeAfterVideo()
            callbacks.onError(file, error.message ?: "录像模式初始化失败")
            return
        }
        activeVideoStabilizationStatus = actualStatus
        val outputOptions = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(MAX_VIDEO_DURATION_MS)
            .setFileSizeLimit(fileSizeLimitBytes)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            standaloneVideoRunning = false
            restorePhotoModeAfterVideo()
            callbacks.onError(file, "麦克风权限已失效，录像未开始")
            return
        }
        val pendingRecording = runCatching {
            recorder.prepareRecording(context, outputOptions)
                .withAudioEnabled()
                .asPersistentRecording()
        }.getOrElse { error ->
            standaloneVideoRunning = false
            restorePhotoModeAfterVideo()
            callbacks.onError(file, error.message ?: "无法启用麦克风录像")
            return
        }
        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> callbacks.onStarted(
                    currentVideoQualityLabel(),
                    actualStatus,
                    stabilizationWarning,
                )
                is VideoRecordEvent.Status -> callbacks.onStatus(event.recordingStats.recordedDurationNanos)
                is VideoRecordEvent.Pause -> {
                    standaloneVideoPaused = true
                    callbacks.onPaused()
                }
                is VideoRecordEvent.Resume -> {
                    standaloneVideoPaused = false
                    callbacks.onResumed()
                }
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    standaloneVideoRunning = false
                    standaloneVideoPaused = false
                    standaloneLensSwitching = false
                    activeVideoStabilizationStatus = VideoStabilizationStatus.DISABLED_BY_USER
                    val warning = when (event.error) {
                        VideoRecordEvent.Finalize.ERROR_NONE -> null
                        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "已达到10分钟上限"
                        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "已达到安全存储上限"
                        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "相机进入后台，录像已自动结束"
                        else -> "录像因相机错误提前结束（${event.error}）"
                    }
                    restorePhotoModeAfterVideo()
                    if (file.isFile && file.length() > 0L) {
                        callbacks.onFinalized(file, warning)
                    } else {
                        callbacks.onError(file, warning ?: "录像未生成有效文件")
                    }
                }
            }
        }
    }

    private fun bindStandaloneVideo(
        owner: LifecycleOwner,
        view: PreviewView,
        cameraProvider: ProcessCameraProvider,
        reuseVideoCapture: Boolean = false,
        stabilizationEnabled: Boolean = false,
    ) {
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(Surface.ROTATION_0)
            .setTargetFrameRate(VIDEO_FRAME_RATE)
            .setPreviewStabilizationEnabled(stabilizationEnabled)
            .build()
            .also { it.surfaceProvider = view.surfaceProvider }
        val capture = if (reuseVideoCapture) {
            requireNotNull(videoCapture) { "录像组件已经释放" }
        } else {
            val recorder = Recorder.Builder()
                .setAspectRatio(AspectRatio.RATIO_16_9)
                .setQualitySelector(
                    QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD)),
                )
                .build()
            VideoCapture.Builder(recorder)
                .setTargetRotation(standaloneCaptureRotation)
                .setTargetFrameRate(VIDEO_FRAME_RATE)
                .setVideoStabilizationEnabled(stabilizationEnabled)
                .build()
                .also { videoCapture = it }
        }
        imageCapture = null
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        observedZoomState?.removeObservers(owner)
        observedZoomState = null
        cameraProvider.unbindAll()
        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .build()
        val boundCamera = cameraProvider.bindToLifecycle(owner, selector, useCaseGroup)
        camera = boundCamera
        observeCameraState(owner, boundCamera)
    }

    private fun observeCameraState(owner: LifecycleOwner, boundCamera: Camera) {
        val zoomState = boundCamera.cameraInfo.zoomState
        observedZoomState = zoomState
        zoomState.observe(owner) { zoom -> onZoomChanged(zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio) }
        zoomState.value?.let { zoom -> onZoomChanged(zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio) }
        boundCamera.cameraInfo.exposureState.let { exposure ->
            val range = exposure.exposureCompensationRange
            onExposureChanged(exposure.exposureCompensationIndex, range.lower, range.upper)
        }
    }

    private fun currentVideoQualityLabel(): String {
        return "1080p"
    }

    private fun restorePhotoModeAfterVideo() {
        runCatching(::bindWithMotionFallback).onFailure {
            onError(it.message ?: "录像结束后恢复拍照模式失败")
        }
    }

    fun pauseVideo() {
        if (!standaloneVideoRunning || standaloneVideoPaused) return
        activeRecording?.pause()
    }

    fun resumeVideo() {
        if (!standaloneVideoRunning || !standaloneVideoPaused) return
        activeRecording?.resume()
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun switchLensDuringVideo(): Boolean {
        if (!standaloneVideoRunning || standaloneLensSwitching) {
            onError("当前录像状态暂不能切换镜头")
            return false
        }
        val owner = lifecycleOwner ?: run { onError("相机尚未就绪"); return false }
        val view = previewView ?: run { onError("相机尚未就绪"); return false }
        val cameraProvider = provider ?: run { onError("相机尚未就绪"); return false }
        val previousLens = lensFacing
        val nextLens = if (previousLens == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val selector = CameraSelector.Builder().requireLensFacing(nextLens).build()
        if (!cameraProvider.hasCamera(selector)) {
            onError("设备没有可切换的目标镜头")
            return false
        }
        val targetCapability = runCatching {
            stabilizationCapability(cameraProvider.getCameraInfo(selector))
        }.getOrDefault(StabilizationCapability(false, emptyList()))
        if (!VideoStabilizationPolicy.canSwitchLensDuringRecording(
                activeVideoStabilizationStatus,
                targetCapability.supported,
                targetCapability.frameRateRanges,
            )
        ) {
            onError("目标镜头不支持同步防抖；关闭防抖后可切换")
            return false
        }
        standaloneLensSwitching = true
        val wasPaused = standaloneVideoPaused
        if (!wasPaused) activeRecording?.pause()
        zoomAnimator?.cancel()
        lensFacing = nextLens
        return runCatching {
            bindStandaloneVideo(
                owner,
                view,
                cameraProvider,
                reuseVideoCapture = true,
                stabilizationEnabled = activeVideoStabilizationStatus == VideoStabilizationStatus.ACTIVE,
            )
            true
        }.getOrElse { error ->
            lensFacing = previousLens
            runCatching {
                bindStandaloneVideo(
                    owner,
                    view,
                    cameraProvider,
                    reuseVideoCapture = true,
                    stabilizationEnabled = activeVideoStabilizationStatus == VideoStabilizationStatus.ACTIVE,
                )
            }
                .onFailure { activeRecording?.stop() }
            onError(error.message ?: "录像中切换摄像头失败")
            false
        }.also {
            if (!wasPaused && standaloneVideoRunning) activeRecording?.resume()
            standaloneLensSwitching = false
        }
    }

    fun stopVideo() {
        if (!standaloneVideoRunning) return
        activeRecording?.stop()
    }

    fun isVideoRunning(): Boolean = standaloneVideoRunning

    fun setVideoStabilizationEnabled(enabled: Boolean) {
        if (!standaloneVideoRunning) videoStabilizationEnabled = enabled
    }

    fun setMotionPhotoEnabled(enabled: Boolean): Boolean {
        if (standaloneVideoRunning || liveCaptureRunning || motionPhotoEnabled == enabled) return motionPhotoEnabled
        activeRecording?.stop()
        activeRecording = null
        motionPhotoEnabled = enabled
        return runCatching {
            bindWithMotionFallback()
            onMotionPhotoChanged(motionPhotoEnabled)
            motionPhotoEnabled
        }.getOrElse {
            motionPhotoEnabled = false
            onMotionPhotoChanged(false)
            onError(it.message ?: "Live图初始化失败")
            false
        }
    }

    fun isMotionPhotoEnabled(): Boolean = motionPhotoEnabled

    /** Releases CameraX while the Camera2 Live backend owns the physical camera. */
    fun suspendCamera() {
        cameraEnabled = false
        zoomAnimator?.cancel()
        provider?.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
    }

    /** Rebinds CameraX after leaving Live mode. */
    fun resumeCamera(onReady: (() -> Unit)? = null) {
        if (released) return
        if (cameraEnabled && camera != null) {
            onReady?.let { callback -> mainHandler.post(callback) }
            return
        }
        this.onCameraReady = onReady
        cameraEnabled = true
        bindWithMotionFallback()
    }

    fun switchLens(): Boolean {
        if (standaloneVideoRunning) return false
        val newLens = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val available = provider?.hasCamera(CameraSelector.Builder().requireLensFacing(newLens).build()) == true
        if (available) {
            zoomAnimator?.cancel()
            lensFacing = newLens
            bindWithMotionFallback()
        }
        return available
    }

    private data class StabilizationCapability(
        val supported: Boolean,
        val frameRateRanges: List<IntRange>,
    )

    private fun stabilizationCapability(cameraInfo: CameraInfo): StabilizationCapability =
        StabilizationCapability(
            supported = Recorder.getVideoCapabilities(cameraInfo).isStabilizationSupported,
            frameRateRanges = cameraInfo.supportedFrameRateRanges.map { it.lower..it.upper },
        )

    @SuppressLint("UnsafeOptInUsageError")
    private fun resolveVideoStabilizationStatus(
        cameraProvider: ProcessCameraProvider,
        facing: Int,
    ): VideoStabilizationStatus {
        if (!videoStabilizationEnabled) return VideoStabilizationStatus.DISABLED_BY_USER
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        val capability = stabilizationCapability(cameraProvider.getCameraInfo(selector))
        return VideoStabilizationPolicy.resolveRequestedStatus(
            videoStabilizationEnabled,
            capability.supported,
            capability.frameRateRanges,
        )
    }

    private fun notifyVideoStabilizationCapability(cameraInfo: CameraInfo) {
        val capability = stabilizationCapability(cameraInfo)
        onVideoStabilizationCapabilityChanged(
            capability.supported && capability.frameRateRanges.any { 30 in it },
        )
    }

    fun cycleFlash(): Int {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        return flashMode
    }

    fun adjustExposure(delta: Int): Int {
        val currentCamera = camera ?: return 0
        val exposure = currentCamera.cameraInfo.exposureState
        if (!exposure.isExposureCompensationSupported) return 0
        return setExposureCompensation(exposure.exposureCompensationIndex + delta)
    }

    fun setExposureCompensation(index: Int): Int {
        val currentCamera = camera ?: return 0
        val state = currentCamera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return 0
        val range = state.exposureCompensationRange
        val next = index.coerceIn(range.lower, range.upper)
        currentCamera.cameraControl.setExposureCompensationIndex(next)
        onExposureChanged(next, range.lower, range.upper)
        return next
    }

    fun setZoomRatio(ratio: Float) {
        val currentCamera = camera ?: return
        val zoom = currentCamera.cameraInfo.zoomState.value ?: return
        currentCamera.cameraControl.setZoomRatio(ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio))
    }

    fun animateZoomRatio(ratio: Float) {
        val currentCamera = camera ?: return
        val zoom = currentCamera.cameraInfo.zoomState.value ?: return
        val target = ratio.coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(zoom.zoomRatio, target).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                camera?.cameraControl?.setZoomRatio(animator.animatedValue as Float)
            }
            start()
        }
    }

    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        if (liveCaptureRunning || standaloneVideoRunning) return
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    private fun restoreRotationAfterMotionCapture() {
        imageCapture?.targetRotation = targetRotation
        videoCapture?.targetRotation = targetRotation
    }

    fun release() {
        released = true
        zoomAnimator?.cancel()
        zoomAnimator = null
        activeRecording?.stop()
        activeRecording = null
        mainHandler.removeCallbacksAndMessages(null)
        lifecycleOwner?.let { owner -> observedZoomState?.removeObservers(owner) }
        observedZoomState = null
        provider?.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
        ioExecutor.shutdown()
    }

    private fun installGestures(view: PreviewView) {
        var scalingGesture = false
        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scalingGesture = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentCamera = camera ?: return false
                val zoomState = currentCamera.cameraInfo.zoomState.value ?: return false
                val target = (zoomState.zoomRatio * detector.scaleFactor)
                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                currentCamera.cameraControl.setZoomRatio(target)
                return true
            }
        })
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) zoomAnimator?.cancel()
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scalingGesture) {
                val point = view.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
                onFocusTap(event.x, event.y)
                view.performClick()
            }
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                scalingGesture = false
            }
            true
        }
    }

    private companion object {
        val VIDEO_FRAME_RATE = android.util.Range(30, 30)
        const val LIVE_TOTAL_DURATION_MS = 2_100L
        const val LIVE_COMPLETION_TIMEOUT_MS = 7_000L
        const val MAX_VIDEO_DURATION_MS = 10L * 60L * 1_000L
    }
}
