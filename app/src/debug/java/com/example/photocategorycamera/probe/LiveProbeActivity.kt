package com.example.photocategorycamera.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

/** Debug-only, manually/adb launched diagnostic screen. Never changes product settings. */
class LiveProbeActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var host: FrameLayout
    private lateinit var report: ProbeReport
    private var probe: Camera2LiveProbe? = null
    private var provider: ProcessCameraProvider? = null
    private var surface: Surface? = null
    private var started = false
    private var stopped = false
    private val preferences by lazy { getSharedPreferences("live_probe", MODE_PRIVATE) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startRun()
        else status.text = "需要相机权限才能验证"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "0.6 技术验证：主摄 1×、明亮场景。输出仅在应用私有目录。"; setPadding(16, 16, 16, 16) }
        layout.addView(status, LinearLayout.LayoutParams(-1, 180))
        host = FrameLayout(this)
        layout.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(Button(this).apply { text = "开始本次验证"; setOnClickListener { requestStart() } })
        layout.addView(Button(this).apply { text = "停止并返回"; setOnClickListener { finish() } })
        setContentView(layout)
        if (intent.getBooleanExtra("autorun", false)) requestStart()
    }

    private fun requestStart() {
        if (started) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else startRun()
    }

    private fun startRun() {
        if (started || isFinishing) return
        started = true
        stopped = false
        val mode = intent.getStringExtra("mode") ?: "baseline"
        report = ProbeReport(File(filesDir, "live-probe/${System.currentTimeMillis()}_$mode")) { message ->
            runOnUiThread { status.text = message }
        }
        report.event("mode", "name" to mode)
        if (mode == "baseline" || mode == "zsl") startBaseline(mode == "zsl") else startLive(mode)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startBaseline(zsl: Boolean) {
        val view = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        host.addView(view, FrameLayout.LayoutParams(-1, -1))
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (stopped) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                cameraProvider.unbindAll()
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                data class ThreeA(val af: Int?, val ae: Int?, val awb: Int?)
                val threeAByTimestamp = ConcurrentHashMap<Long, ThreeA>()
                @SuppressLint("UnsafeOptInUsageError")
                val previewBuilder = Preview.Builder().setResolutionSelector(selector).setTargetRotation(Surface.ROTATION_0)
                val threeACallback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                        threeAByTimestamp[timestamp] = ThreeA(
                            result.get(CaptureResult.CONTROL_AF_STATE),
                            result.get(CaptureResult.CONTROL_AE_STATE),
                            result.get(CaptureResult.CONTROL_AWB_STATE),
                        )
                        if (threeAByTimestamp.size > 180) {
                            threeAByTimestamp.keys.sorted().take(60).forEach(threeAByTimestamp::remove)
                        }
                    }
                }
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(threeACallback)
                val preview = previewBuilder.build()
                preview.surfaceProvider = view.surfaceProvider
                val image = ImageCapture.Builder().setResolutionSelector(selector)
                    .setCaptureMode(if (zsl) ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF).setTargetRotation(Surface.ROTATION_0).build()
                val useCases = UseCaseGroup.Builder().addUseCase(preview).addUseCase(image)
                    .setViewPort(ViewPort.Builder(Rational(3, 4), Surface.ROTATION_0).setScaleType(ViewPort.FILL_CENTER).build()).build()
                val camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCases)
                if (zsl && !camera.cameraInfo.isZslSupported) {
                    cameraProvider.unbindAll()
                    report.event("fatal", "message" to "ZSL unsupported; no automatic low-quality fallback tested")
                    return@addListener
                }
                val size = checkNotNull(image.resolutionInfo).resolution
                if (!zsl) preferences.edit().putInt("width", size.width).putInt("height", size.height).apply()
                val manager = getSystemService(CameraManager::class.java)
                val id = manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                val realtime = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                report.event("baseline_bound", "resolution" to size.toString(), "crop" to image.resolutionInfo?.cropRect.toString(),
                    "zsl" to zsl, "realtimeSensor" to realtime)
                val warmupMs = intent.getLongExtra("warmupMs", 2500L).coerceIn(1000L, 15_000L)
                val groups = intent.getIntExtra("groups", 1).coerceIn(1, 10)
                repeat(groups * 5) { index -> handler.postDelayed({
                    if (stopped) return@postDelayed
                    val requested = SystemClock.elapsedRealtimeNanos()
                    image.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(captured: ImageProxy) {
                            if (stopped || io.isShutdown) { captured.close(); return }
                            val received = SystemClock.elapsedRealtimeNanos()
                            val timestamp = captured.imageInfo.timestamp
                            val threeA = threeAByTimestamp[timestamp]
                            val width = captured.width; val height = captured.height
                            val data = try {
                                check(captured.format == android.graphics.ImageFormat.JPEG) { "Baseline output is not JPEG" }
                                ByteArray(captured.planes[0].buffer.remaining()).also { captured.planes[0].buffer.get(it) }
                            } finally { captured.close() }
                            io.execute {
                                try {
                                    val file = File(report.directory, "baseline_$index.jpg")
                                    file.writeBytes(data)
                                    val done = SystemClock.elapsedRealtimeNanos()
                                    val expected = MessageDigest.getInstance("SHA-256").digest(data)
                                    val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                                    check(expected.contentEquals(actual))
                                    report.event("baseline_photo", "id" to index, "width" to width, "height" to height,
                                        "requestedNs" to requested, "sensorNs" to timestamp,
                                        "af" to threeA?.af, "ae" to threeA?.ae, "awb" to threeA?.awb,
                                        "latencyMs" to if (realtime) (timestamp - requested) / 1e6 else null,
                                        "jpegReadyMs" to (received - requested) / 1e6,
                                        "privateFileDoneMs" to (done - requested) / 1e6,
                                        "privateReadbackMs" to (SystemClock.elapsedRealtimeNanos() - done) / 1e6,
                                        "note" to "Private JPEG output; SAF timing and final-file crop require separate product baseline")
                                } catch (error: Exception) { report.event("fatal", "message" to error.message) }
                            }
                        }
                        override fun onError(exception: ImageCaptureException) { report.event("capture_failure", "id" to index, "message" to exception.message) }
                    })
                }, warmupMs + (index / 5) * 5500L + (index % 5) * 500L) }
                handler.postDelayed({
                    if (!stopped) {
                        cameraProvider.unbindAll()
                        io.execute { report.event("files_complete", "path" to report.directory.absolutePath) }
                    }
                }, warmupMs + groups * 5500L + 7000)
            } catch (error: Exception) { report.event("fatal", "message" to error.message) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLive(mode: String) {
        val width = preferences.getInt("width", 0)
        val height = preferences.getInt("height", 0)
        if (width <= 0 || height <= 0) {
            report.event("fatal", "message" to "Run baseline first; never guess JPEG resolution")
            return
        }
        val hd = mode == "live720"
        val size = if (hd) Size(960, 720) else Size(1440, 1080)
        val texture = TextureView(this)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                value.setDefaultBufferSize(size.width, size.height)
                surface = Surface(value)
                probe = Camera2LiveProbe(this@LiveProbeActivity, surface!!, report,
                    Size(preferences.getInt("width", 0), preferences.getInt("height", 0)), size,
                    if (hd) 4_000_000 else 8_000_000,
                    intent.getIntExtra("groups", 10).coerceIn(1, 10),
                    if (intent.getBooleanExtra("soak", false)) 600_000 else 0) {
                    runOnUiThread { probe?.close(); probe = null }
                }.also { it.start() }
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                probe?.close(); probe = null
                return true
            }
        }
        host.addView(texture, FrameLayout.LayoutParams(-1, -1))
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        handler.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        probe?.close(); probe = null
        if (started) report.event("activity_stopped", "message" to "Acquisition stopped; incomplete test is not a pass")
    }

    override fun onDestroy() {
        super.onDestroy()
        surface?.release()
        io.shutdown()
    }
}
