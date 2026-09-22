package com.example.photocategorycamera.camera

import android.animation.ValueAnimator
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.animation.DecelerateInterpolator
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.core.content.ContextCompat
import com.example.photocategorycamera.diagnostics.CaptureEventLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

data class LiveCaptureState(
    val ready: Boolean = false,
    val warming: Boolean = true,
    val pending: Int = 0,
    val audio: Boolean = false,
    val warning: String? = null,
)

/** Camera2 backend used only while Live is enabled. */
class LivePhotoController(
    private val context: Context,
    private val audioAllowed: Boolean,
    private val onState: (LiveCaptureState) -> Unit,
    private val onFocusTap: (Float, Float) -> Unit = { _, _ -> },
    private val onZoomChanged: (Float, Float, Float) -> Unit = { _, _, _ -> },
    private val onExposureChanged: (Int, Int, Int) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private val worker = HandlerThread("LenshelfLiveCamera").apply { start() }
    private val handler = Handler(worker.looper)
    private val main = ContextCompat.getMainExecutor(context)
    private val io = Executors.newSingleThreadExecutor()
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val buffer = LiveSampleBuffer()
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var videoCodec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var videoFormat: MediaFormat? = null
    private var audioEncoder: LiveAudioEncoder? = null
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var sensorOrientation = 0
    private var sensorRealtime = false
    private var targetRotation = Surface.ROTATION_0
    private var flashMode = androidx.camera.core.ImageCapture.FLASH_MODE_OFF
    private var zoomRatio = 1f
    private var minZoom = 1f
    private var maxZoom = 1f
    private var activeArray: Rect? = null
    private var focusRegion: MeteringRectangle? = null
    private var maxAfRegions = 0
    private var focusStartedNs: Long? = null
    private var exposure = 0
    private var minExposure = 0
    private var maxExposure = 0
    private var aeLockAvailable = false
    private var awbLockAvailable = false
    private var zoomAnimator: ValueAnimator? = null
    private var closed = false
    private var starting = false
    private var shouldRun = false
    private var state = LiveCaptureState()
    private val captures = LinkedHashMap<String, Pending>()
    private val unmatchedImages = HashMap<Long, ByteArray>()

    private class Pending(
        val id: String,
        val file: File,
        val onSaved: (File, String?) -> Unit,
        val onPhotoOnly: (File, String) -> Unit,
        val onError: (String) -> Unit,
        val orientation: Int,
        val acceptedNs: Long,
    ) {
        var sensorNs: Long? = null
        var jpegReady = false
        var finished = false
        var tailReady = false
        var exportAttempts = 0
        var forceShortTail = false
    }

    fun attach(view: TextureView) {
        textureView = view
        installGestures(view)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                shouldRun = true
                start()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                handler.post {
                    shouldRun = false
                    stopSession(shortenPending = true)
                }
                return true
            }
        }
        if (view.isAvailable) start()
    }

    fun isReady(): Boolean = state.ready

    fun capture(
        taskId: String,
        file: File,
        onSaved: (File, String?) -> Unit,
        onPhotoOnly: (File, String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val acceptedNs = android.os.SystemClock.elapsedRealtimeNanos()
        CaptureEventLog.record(context, "capture_accepted", taskId, acceptedNs)
        handler.post {
        val currentSession = session
        val reader = imageReader
        val currentDevice = device
        if (closed || currentSession == null || reader == null || currentDevice == null) {
            main.execute { onError("Live尚未就绪") }
            return@post
        }
        if (captures.size >= MAX_PENDING) {
            main.execute { onError("Live待处理任务已满") }
            return@post
        }
        try {
            val id = taskId
            captures[id] = Pending(id, file, onSaved, onPhotoOnly, onError, jpegOrientation(), acceptedNs)
            publish()
            // VIDEO_SNAPSHOT asks the HAL to preserve the active recording pipeline while it
            // produces a full-resolution JPEG. Keeping preview/video off this one-shot request
            // also prevents the still-tuned frame from being written into overlapping Live clips.
            val request = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure)
                focusRegion?.let { region ->
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                when (flashMode) {
                    androidx.camera.core.ImageCapture.FLASH_MODE_ON ->
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    androidx.camera.core.ImageCapture.FLASH_MODE_AUTO ->
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    else -> {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    }
                }
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                if (flashMode == androidx.camera.core.ImageCapture.FLASH_MODE_OFF) {
                    if (aeLockAvailable) set(CaptureRequest.CONTROL_AE_LOCK, true)
                    if (awbLockAvailable) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                setTag(id)
            }.build()
            currentSession.capture(request, captureCallback, handler)
            CaptureEventLog.record(context, "capture_submitted", id)
        } catch (error: Exception) {
            CaptureEventLog.record(context, "capture_submit_failed", taskId, values = mapOf("error" to error.message))
            main.execute { onError(error.message ?: "Live拍摄提交失败") }
        }
        }
    }

    fun setTargetRotation(rotation: Int) { handler.post { targetRotation = rotation } }
    fun setFlashMode(mode: Int) { handler.post { flashMode = mode; updateRepeating() } }
    fun setZoomRatio(ratio: Float): Float {
        val bounded = ratio.coerceIn(minZoom, maxZoom)
        handler.post {
            zoomAnimator?.cancel()
            zoomRatio = bounded
            updateRepeating()
            main.execute { onZoomChanged(zoomRatio, minZoom, maxZoom) }
        }
        return bounded
    }
    fun animateZoomRatio(ratio: Float) {
        handler.post {
            val target = ratio.coerceIn(minZoom, maxZoom)
            zoomAnimator?.cancel()
            zoomAnimator = ValueAnimator.ofFloat(zoomRatio, target).apply {
                duration = 260L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    zoomRatio = animator.animatedValue as Float
                    updateRepeating()
                    main.execute { onZoomChanged(zoomRatio, minZoom, maxZoom) }
                }
                start()
            }
        }
    }
    fun setExposureCompensation(value: Int): Int {
        val bounded = value.coerceIn(minExposure, maxExposure)
        handler.post {
            exposure = bounded
            updateRepeating()
            main.execute { onExposureChanged(exposure, minExposure, maxExposure) }
        }
        return bounded
    }
    fun switchLens() {
        handler.post {
            lensFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else CameraCharacteristics.LENS_FACING_BACK
            settlePendingCaptures {
                stopSession(shortenPending = false)
                startInternal()
            }
        }
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        handler.post {
            shouldRun = false
            settlePendingCaptures {
                stopSession(shortenPending = false)
                onStopped?.let { main.execute(it) }
            }
        }
    }

    fun pauseForLifecycle() {
        handler.post {
            shouldRun = false
            settlePendingCaptures { stopSession(shortenPending = false) }
        }
    }

    fun resumeForLifecycle() {
        handler.post {
            if (closed) return@post
            shouldRun = true
            startInternal()
        }
    }

    private fun start() = handler.post { startInternal() }

    @SuppressLint("MissingPermission")
    private fun startInternal() {
        if (Build.VERSION.SDK_INT < 33) {
            unavailable("Live需要Android 13及以上的时间同步接口，请使用普通拍照")
            return
        }
        if (closed || !shouldRun || starting || device != null) return
        val texture = textureView?.surfaceTexture ?: return
        starting = true
        updateState(ready = false, warming = true, warning = null)
        try {
            val cameraId = cameraManager.cameraIdList.first {
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensFacing
            }
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = checkNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val jpegSize = map.getOutputSizes(ImageFormat.JPEG).filter { size ->
                kotlin.math.abs(size.width.toDouble() / size.height - 4.0 / 3.0) < 0.02
            }.maxByOrNull { it.width.toLong() * it.height } ?: error("当前镜头没有4:3 JPEG输出")
            val encoderSizes = map.getOutputSizes(MediaCodec::class.java).toSet()
            val videoSize = when {
                Size(1440, 1080) in encoderSizes -> Size(1440, 1080)
                Size(960, 720) in encoderSizes -> Size(960, 720)
                else -> error("当前镜头不支持Live视频尺寸")
            }
            val fps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            check(fps.contains(Range(30, 30))) { "当前镜头不支持固定30fps Live" }
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            sensorRealtime = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            minZoom = zoomRange?.lower ?: 1f
            maxZoom = zoomRange?.upper ?: 1f
            zoomRatio = zoomRatio.coerceIn(minZoom, maxZoom)
            val exposureRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            minExposure = exposureRange?.lower ?: 0
            maxExposure = exposureRange?.upper ?: 0
            exposure = exposure.coerceIn(minExposure, maxExposure)
            aeLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
            awbLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
            main.execute {
                onZoomChanged(zoomRatio, minZoom, maxZoom)
                onExposureChanged(exposure, minExposure, maxExposure)
            }
            texture.setDefaultBufferSize(videoSize.width, videoSize.height)
            previewSurface = Surface(texture)
            imageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, MAX_PENDING).also { reader ->
                reader.setOnImageAvailableListener({ source -> receiveImage(source) }, handler)
            }
            videoCodec = MediaCodec.createEncoderByType("video/avc").also { codec ->
                codec.configure(
                    MediaFormat.createVideoFormat("video/avc", videoSize.width, videoSize.height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, if (videoSize.width == 1440) 8_000_000 else 4_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                codecSurface = codec.createInputSurface()
                codec.start()
            }
            cameraManager.openCamera(cameraId, cameraCallback, handler)
        } catch (error: Exception) {
            starting = false
            stopSession(shortenPending = true)
            updateState(warning = error.message ?: "Live初始化失败")
        }
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed || !shouldRun) { camera.close(); starting = false; return }
            device = camera
            try {
                val surfaces = listOf(previewSurface!!, codecSurface!!, imageReader!!.surface)
                val outputs = surfaces.map { surface ->
                    OutputConfiguration(surface).apply {
                        if (Build.VERSION.SDK_INT >= 33) setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR)
                    }
                }
                camera.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, { handler.post(it) }, sessionCallback),
                )
            } catch (error: Exception) { unavailable(error.message ?: "Live会话建立失败") }
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (camera !== device) return
            device = null
            recoverCamera("相机连接已断开")
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (camera !== device) return
            device = null
            recoverCamera("相机错误：$error")
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(value: CameraCaptureSession) {
            if (closed || !shouldRun || value.device !== device) { value.close(); return }
            session = value
            starting = false
            updateRepeating()
            if (audioAllowed && sensorRealtime &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                audioEncoder = LiveAudioEncoder(context, buffer) { warning -> handler.post { updateState(warning = "Live无声：$warning") } }
                runCatching { audioEncoder!!.start() }.onFailure {
                    audioEncoder?.close(); audioEncoder = null
                    updateState(warning = "Live无声：${it.message}")
                }
            } else updateState(warning = "Live无声：未授权麦克风或设备时间基准不兼容")
            handler.post(videoDrain)
        }
        override fun onConfigureFailed(value: CameraCaptureSession) {
            if (value.device !== device) { value.close(); return }
            unavailable("当前镜头不支持高分辨率照片与Live同时采集")
        }
    }

    private fun repeatingRequest(template: Int): CaptureRequest.Builder = device!!.createCaptureRequest(template).apply {
        addTarget(previewSurface!!); addTarget(codecSurface!!)
        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
        if (Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure)
        focusRegion?.let { region ->
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
        when (flashMode) {
            androidx.camera.core.ImageCapture.FLASH_MODE_ON -> set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            androidx.camera.core.ImageCapture.FLASH_MODE_AUTO -> set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
            else -> { set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON); set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF) }
        }
    }

    private fun updateRepeating() {
        val current = session ?: return
        runCatching { current.setRepeatingRequest(repeatingRequest(CameraDevice.TEMPLATE_RECORD).build(), captureCallback, handler) }
            .onFailure { unavailable(it.message ?: "Live预览更新失败") }
    }

    private fun focusAt(x: Float, y: Float, width: Int, height: Int) {
        handler.post {
            val bounds = activeArray ?: return@post
            val current = session ?: return@post
            if (width <= 0 || height <= 0) return@post
            if (maxAfRegions <= 0) {
                updateState(warning = "当前镜头不支持指定位置对焦")
                return@post
            }
            val (sensorX, sensorY) = FocusMeteringMapper.map(
                viewX = x / width,
                viewY = y / height,
                activeLeft = bounds.left,
                activeTop = bounds.top,
                activeWidth = bounds.width(),
                activeHeight = bounds.height(),
                zoomRatio = zoomRatio,
                relativeRotationDegrees = jpegOrientation(),
                mirrored = lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
            )
            val side = (minOf(bounds.width(), bounds.height()) / zoomRatio.coerceAtLeast(1f) * 0.08f)
                .toInt().coerceAtLeast(1)
            val left = (sensorX.toInt() - side / 2).coerceIn(bounds.left, bounds.right - side)
            val top = (sensorY.toInt() - side / 2).coerceIn(bounds.top, bounds.bottom - side)
            focusRegion = MeteringRectangle(left, top, side, side, MeteringRectangle.METERING_WEIGHT_MAX)
            runCatching {
                val cancel = repeatingRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }.build()
                val trigger = repeatingRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }.build()
                focusStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                CaptureEventLog.record(
                    context,
                    "focus_submitted",
                    values = mapOf("x" to sensorX, "y" to sensorY, "side" to side, "rotation" to jpegOrientation()),
                )
                current.captureBurst(listOf(cancel, trigger), captureCallback, handler)
                handler.postDelayed({
                    if (focusStartedNs != null) {
                        focusStartedNs = null
                        updateRepeating()
                    }
                }, 1_500)
            }.onFailure { updateState(warning = "Live对焦失败：${it.message}") }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installGestures(view: TextureView) {
        val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoomRatio(zoomRatio * detector.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                focusAt(event.x, event.y, view.width, view.height)
                val normalizedX = (event.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                val normalizedY = (event.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                main.execute { onFocusTap(normalizedX, normalizedY) }
                return true
            }
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                view.performClick()
                return true
            }
        })
        view.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            if (!scale.isInProgress) tap.onTouchEvent(event) else true
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val focusStart = focusStartedNs
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (focusStart != null && (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                focusStartedNs = null
                CaptureEventLog.record(
                    context,
                    "focus_completed",
                    values = mapOf(
                        "success" to (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED),
                        "latencyMs" to (android.os.SystemClock.elapsedRealtimeNanos() - focusStart) / 1_000_000.0,
                        "focusDistance" to result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    ),
                )
                handler.postDelayed({ updateRepeating() }, 100)
            }
            val pending = captures[request.tag as? String] ?: return
            pending.sensorNs = timestamp
            val callbackNs = android.os.SystemClock.elapsedRealtimeNanos()
            CaptureEventLog.record(
                context,
                "exposure_completed",
                pending.id,
                callbackNs,
                mapOf(
                    "sensorTimestampNs" to timestamp,
                    "acceptedToExposureMs" to if (sensorRealtime) (timestamp - pending.acceptedNs) / 1_000_000.0 else null,
                    "exposureTimeNs" to result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    "afState" to result.get(CaptureResult.CONTROL_AF_STATE),
                    "aeState" to result.get(CaptureResult.CONTROL_AE_STATE),
                    "awbState" to result.get(CaptureResult.CONTROL_AWB_STATE),
                ),
            )
            unmatchedImages.remove(timestamp)?.let { writeJpeg(pending, it) }
            handler.postDelayed({
                pending.tailReady = true
                export(pending, forced = false)
            }, POST_ROLL_MS)
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            val pending = captures.remove(request.tag as? String) ?: return
            CaptureEventLog.record(context, "capture_failed", pending.id, values = mapOf("reason" to failure.reason))
            main.execute { pending.onError("Live照片拍摄失败：${failure.reason}") }
            publish()
        }
    }

    private fun receiveImage(source: ImageReader) {
        val image = source.acquireNextImage() ?: return
        try {
            val timestamp = image.timestamp
            val bytes = ByteArray(image.planes[0].buffer.remaining())
            image.planes[0].buffer.get(bytes)
            val pending = captures.values.firstOrNull { it.sensorNs == timestamp }
            pending?.let { CaptureEventLog.record(context, "jpeg_received", it.id, values = mapOf("sensorTimestampNs" to timestamp)) }
            if (pending == null) unmatchedImages[timestamp] = bytes else writeJpeg(pending, bytes)
        } finally { image.close() }
    }

    private fun writeJpeg(pending: Pending, bytes: ByteArray) {
        io.execute {
            runCatching { pending.file.writeBytes(bytes) }
                .onSuccess {
                    CaptureEventLog.record(context, "jpeg_written", pending.id, values = mapOf("bytes" to bytes.size))
                    handler.post { pending.jpegReady = true; export(pending, forced = false) }
                }
                .onFailure { handler.post { failPending(pending, it.message ?: "Live照片写入失败") } }
        }
    }

    private val videoDrain = object : Runnable {
        override fun run() {
            if (closed || videoCodec == null) return
            try {
                val codec = videoCodec!!
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) videoFormat = codec.outputFormat
                    else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val encoded = codec.getOutputBuffer(index)!!
                                encoded.position(info.offset); encoded.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size); encoded.get(bytes)
                                buffer.append(
                                    LiveSampleBuffer.Track.VIDEO, info.presentationTimeUs,
                                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, bytes,
                                )
                            }
                        } finally { codec.releaseOutputBuffer(index, false) }
                    } else break
                }
                updateState(ready = true, warming = buffer.videoSpanUs() < 1_000_000)
                handler.postDelayed(this, 5)
            } catch (error: Exception) { unavailable(error.message ?: "Live视频编码失败") }
        }
    }

    private fun export(pending: Pending, forced: Boolean) {
        if (forced) pending.forceShortTail = true
        if (pending.finished || !pending.jpegReady || pending.sensorNs == null) return
        val useShortTail = forced || pending.forceShortTail
        if (!useShortTail && !pending.tailReady) return
        val photoUs = pending.sensorNs!! / 1_000
        val audioOutput = audioEncoder?.outputFormat
        val audioAdvanceUs = if (
            audioOutput != null && Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        ) XIAOMI_GALLERY_AUDIO_ADVANCE_US else 0L
        val snapshot = runCatching {
            buffer.snapshot(
                photoUs,
                audioAdvanceUs = audioAdvanceUs,
                requireFullTail = !useShortTail,
            )
        }.getOrElse { error ->
            if (useShortTail || ++pending.exportAttempts > 25) {
                photoOnly(pending, error.message ?: "Live动态不可用")
            } else {
                handler.postDelayed({ export(pending, forced = false) }, 20)
            }
            return
        }
        val videoOutput = videoFormat ?: run { snapshot.close(); return }
        pending.finished = true
        captures.remove(pending.id)
        publish()
        CaptureEventLog.record(
            context,
            "segment_frozen",
            pending.id,
            values = mapOf(
                "photoSensorUs" to photoUs,
                "segmentStartUs" to snapshot.originUs,
                "photoOffsetUs" to (photoUs - snapshot.originUs),
                "forcedShortTail" to useShortTail,
                "audio" to (audioOutput != null),
                "audioSource" to audioEncoder?.sourceLabel,
                "galleryAudioAdvanceUs" to audioAdvanceUs,
            ),
        )
        io.execute {
            snapshot.use {
                try {
                    val mp4 = File(pending.file.parentFile, "${pending.file.nameWithoutExtension}_${pending.id}.mp4")
                    writeMp4(mp4, snapshot, videoOutput, audioOutput, pending.orientation, audioAdvanceUs)
                    val presentationUs = photoUs - snapshot.originUs
                    val videoEnd = snapshot.samples.last { it.track == LiveSampleBuffer.Track.VIDEO }.ptsUs
                    val warning = if (videoEnd - photoUs < 966_666) "本张Live因切换操作缩短了尾段" else null
                    val candidate = File(pending.file.parentFile, "${pending.file.nameWithoutExtension}_${pending.id}_MP.jpg")
                    pending.file.copyTo(candidate, overwrite = false)
                    try {
                        MotionPhotoAssembler.assemble(candidate, mp4, presentationUs)
                        Files.move(candidate.toPath(), pending.file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        mp4.delete()
                        CaptureEventLog.record(
                            context,
                            "motion_assembled",
                            pending.id,
                            values = mapOf("bytes" to pending.file.length(), "presentationUs" to presentationUs),
                        )
                        main.execute { pending.onSaved(pending.file, warning) }
                    } catch (error: Exception) {
                        candidate.delete(); mp4.delete()
                        main.execute { pending.onPhotoOnly(pending.file, "照片已保留，Live未生成：${error.message}") }
                    }
                } catch (error: Exception) {
                    main.execute { pending.onPhotoOnly(pending.file, "照片已保留，Live未生成：${error.message}") }
                }
            }
        }
    }

    private fun writeMp4(
        file: File,
        snapshot: LiveSampleBuffer.Snapshot,
        video: MediaFormat,
        audio: MediaFormat?,
        orientation: Int,
        audioAdvanceUs: Long,
    ) {
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(orientation)
            val videoTrack = muxer.addTrack(video)
            val hasAudio = audio != null && snapshot.samples.any { it.track == LiveSampleBuffer.Track.AUDIO }
            val audioTrack = if (hasAudio) muxer.addTrack(audio!!) else -1
            muxer.start()
            snapshot.samples.forEach { sample ->
                val track = if (sample.track == LiveSampleBuffer.Track.VIDEO) videoTrack else audioTrack
                if (track >= 0) muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), MediaCodec.BufferInfo().apply {
                    val trackAdvanceUs = if (sample.track == LiveSampleBuffer.Track.AUDIO) audioAdvanceUs else 0L
                    set(0, sample.bytes.size, (sample.ptsUs - snapshot.originUs - trackAdvanceUs).coerceAtLeast(0L),
                        if (sample.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                })
            }
            muxer.stop()
        } finally { muxer.release() }
    }

    private fun photoOnly(pending: Pending, warning: String) {
        if (pending.finished || !pending.jpegReady) return
        pending.finished = true
        captures.remove(pending.id)
        publish()
        CaptureEventLog.record(context, "photo_only", pending.id, values = mapOf("warning" to warning))
        main.execute { pending.onPhotoOnly(pending.file, "照片已保存，Live未生成：$warning") }
    }

    private fun failPending(pending: Pending, message: String) {
        if (pending.finished) return
        pending.finished = true
        captures.remove(pending.id)
        publish()
        CaptureEventLog.record(context, "capture_failed", pending.id, values = mapOf("error" to message))
        main.execute { pending.onError(message) }
    }

    private fun jpegOrientation(): Int {
        val deviceDegrees = when (targetRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else (sensorOrientation - deviceDegrees + 360) % 360
    }

    private fun settlePendingCaptures(afterSettled: () -> Unit) {
        updateState(ready = false, warming = true)
        val deadline = android.os.SystemClock.elapsedRealtime() + CAPTURE_SETTLE_TIMEOUT_MS
        captures.values.toList().forEach { export(it, forced = true) }
        fun checkSettled() {
            captures.values.toList().forEach { export(it, forced = true) }
            if (captures.isEmpty()) {
                afterSettled()
            } else if (android.os.SystemClock.elapsedRealtime() < deadline) {
                handler.postDelayed(::checkSettled, 20)
            } else {
                captures.values.toList().forEach { pending ->
                    if (pending.jpegReady) photoOnly(pending, "切换时Live片段不可用")
                    else failPending(pending, "切换前照片采集未完成")
                }
                afterSettled()
            }
        }
        checkSettled()
    }

    private fun stopSession(shortenPending: Boolean) {
        handler.removeCallbacks(videoDrain)
        zoomAnimator?.cancel()
        zoomAnimator = null
        if (shortenPending) {
            captures.values.toList().forEach { export(it, forced = true) }
            captures.values.toList().forEach { pending ->
                if (pending.jpegReady) photoOnly(pending, "相机会话结束，Live片段不可用")
                else failPending(pending, "相机会话结束前照片采集未完成")
            }
        }
        runCatching { session?.stopRepeating() }; runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { imageReader?.close() }; imageReader = null
        audioEncoder?.close(); audioEncoder = null
        runCatching { videoCodec?.stop() }; runCatching { videoCodec?.release() }; videoCodec = null
        runCatching { codecSurface?.release() }; codecSurface = null
        runCatching { previewSurface?.release() }; previewSurface = null
        videoFormat = null
        focusRegion = null
        focusStartedNs = null
        buffer.clear()
        starting = false
        updateState(ready = false, warming = true)
    }

    private fun unavailable(message: String) {
        stopSession(shortenPending = true)
        updateState(warning = message)
    }

    private fun recoverCamera(message: String) {
        val restart = shouldRun
        stopSession(shortenPending = true)
        updateState(warning = if (restart) "$message，正在重新连接" else null)
        if (restart) handler.postDelayed({ startInternal() }, CAMERA_RETRY_DELAY_MS)
    }

    private fun updateState(
        ready: Boolean = state.ready,
        warming: Boolean = state.warming,
        warning: String? = state.warning,
    ) {
        state = LiveCaptureState(ready, warming, captures.size, audioEncoder?.outputFormat != null, warning)
        main.execute { onState(state) }
    }
    private fun publish() = updateState()

    override fun close() {
        handler.post {
            if (closed) return@post
            closed = true
            shouldRun = false
            stopSession(shortenPending = true)
            io.shutdown()
            worker.quitSafely()
        }
    }

    companion object {
        // Xiaomi Gallery's Motion Photo player adds roughly 320 ms of audio latency on the
        // target Xiaomi 17 Pro even though the extracted MP4 is synchronized in desktop editors.
        // Keep this final-container workaround device-scoped and retain extra audio at the tail.
        private const val XIAOMI_GALLERY_AUDIO_ADVANCE_US = 320_000L
        private const val MAX_PENDING = 8
        private const val POST_ROLL_MS = 1_100L
        private const val CAPTURE_SETTLE_TIMEOUT_MS = 1_000L
        private const val CAMERA_RETRY_DELAY_MS = 350L
    }
}
