package com.example.photocategorycamera.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.*
import android.os.Handler
import android.os.Build
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.photocategorycamera.camera.MotionPhotoAssembler
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Stage-one experiment only. Product integration is gated on its actual device results. */
internal class Camera2LiveProbe(
    private val context: Context,
    private val preview: Surface,
    private val report: ProbeReport,
    private val baseline: Size,
    private val videoSize: Size,
    private val bitRate: Int,
    private val groups: Int,
    private val soakMs: Long = 0,
    private val completed: () -> Unit,
) : AutoCloseable {
    private val worker = HandlerThread("Camera2LiveProbe").apply { start() }
    private val handler = Handler(worker.looper)
    private val io = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val ring = EncodedRing()
    private val manager = context.getSystemService(CameraManager::class.java)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var format: MediaFormat? = null
    private var audio: ProbeAudio? = null
    private var realtime = false
    private var orientation = 0
    private var finishing = false
    private var configured = false
    private val videoTimes = ArrayList<Long>()
    private val keyTimes = ArrayList<Long>()
    private val sensorTimes = ArrayList<Long>()
    private val shots = LinkedHashMap<Int, Shot>()
    private val unmatchedJpeg = HashMap<Long, File>()

    private class Shot(val id: Int, val requestedNs: Long, val dueNs: Long) {
        var sensorNs: Long? = null
        var jpeg: File? = null
        var jpegDoneNs: Long? = null
        var exported = false
        var failed = false
    }

    @SuppressLint("MissingPermission")
    fun start() = handler.post {
        try {
            check(Build.VERSION.SDK_INT >= 33) { "Live验证探针需要Android 13及以上的时间同步接口" }
            val id = manager.cameraIdList.first { manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            val properties = manager.getCameraCharacteristics(id)
            val map = checkNotNull(properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            check(map.getOutputSizes(ImageFormat.JPEG).contains(baseline)) { "Baseline JPEG size unavailable: $baseline" }
            check(map.getOutputSizes(MediaCodec::class.java).contains(videoSize)) { "Encoder stream size unsupported: $videoSize" }
            realtime = properties.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            orientation = properties.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val ranges = properties.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            check(ranges.contains(Range(30, 30))) { "Fixed 30fps AE range unavailable" }
            report.event("capabilities", "cameraId" to id, "jpeg" to baseline.toString(), "video" to videoSize.toString(),
                "realtimeSensor" to realtime, "jpegStallNs" to map.getOutputStallDuration(ImageFormat.JPEG, baseline),
                "jpegMinFrameNs" to map.getOutputMinFrameDuration(ImageFormat.JPEG, baseline),
                "hardwareLevel" to properties.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
            jpegReader = ImageReader.newInstance(baseline.width, baseline.height, ImageFormat.JPEG, 4).also { reader ->
                reader.setOnImageAvailableListener({ source ->
                    val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                    try {
                        val timestamp = image.timestamp
                        val data = ByteArray(image.planes[0].buffer.remaining())
                        image.planes[0].buffer.get(data)
                        val file = File(report.directory, "sensor_$timestamp.jpg")
                        val received = SystemClock.elapsedRealtimeNanos()
                        io.execute {
                            try {
                                file.writeBytes(data)
                                handler.post {
                                    val shot = shots.values.firstOrNull { it.sensorNs == timestamp }
                                    if (shot == null) unmatchedJpeg[timestamp] = file else {
                                        shot.jpeg = file; shot.jpegDoneNs = received
                                    }
                                    report.event("jpeg", "sensorNs" to timestamp, "receivedNs" to received,
                                        "fileDoneNs" to SystemClock.elapsedRealtimeNanos(), "bytes" to data.size)
                                }
                            } catch (error: Exception) { handler.post { fail("JPEG write: ${error.message}") } }
                        }
                    } finally { image.close() }
                }, handler)
            }
            codec = MediaCodec.createEncoderByType("video/avc").also { encoder ->
                val config = MediaFormat.createVideoFormat("video/avc", videoSize.width, videoSize.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                encoder.configure(config, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codecSurface = encoder.createInputSurface()
                encoder.start()
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed.get()) { camera.close(); return }
                    device = camera
                    try {
                        val outputs = listOf(preview, codecSurface!!, jpegReader!!.surface).map { surface ->
                            OutputConfiguration(surface).apply {
                                if (Build.VERSION.SDK_INT >= 33) setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR)
                            }
                        }
                        camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                            outputs, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(value: CameraCaptureSession) {
                                    if (closed.get()) { value.close(); return }
                                    session = value
                                    try {
                                        val request = baseRequest(CameraDevice.TEMPLATE_RECORD).build()
                                        value.setRepeatingRequest(request, captureCallback, handler)
                                        if (realtime && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            audio = ProbeAudio(ring) { text -> handler.post { report.event("audio_warning", "message" to text) } }
                                            runCatching { audio!!.start() }.onFailure {
                                                report.event("audio_warning", "message" to it.message)
                                                audio?.close(); audio = null
                                            }
                                        } else report.event("audio_warning", "message" to "Live 无声：麦克风未授权或传感器时基不可验证")
                                        configured = true
                                        handler.post(drain)
                                        scheduleShots()
                                    } catch (error: Exception) { fail(error.message ?: "Start failed") }
                                }
                                override fun onConfigureFailed(value: CameraCaptureSession) { fail("Camera2 stream combination rejected") }
                            }))
                    } catch (error: Exception) { fail(error.message ?: "Session creation failed") }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); fail("Camera disconnected") }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); fail("Camera error $error") }
            }, handler)
        } catch (error: Exception) { fail(error.message ?: "Probe initialization failed") }
        handler.postDelayed({ if (!configured && !closed.get()) fail("Camera session timed out") }, 10_000)
    }

    private fun baseRequest(template: Int): CaptureRequest.Builder = device!!.createCaptureRequest(template).apply {
        addTarget(preview)
        addTarget(codecSurface!!)
        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
        if (Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (closed.get()) return
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            sensorTimes.add(timestamp / 1000)
            val shot = shots[request.tag as? Int] ?: return
            shot.sensorNs = timestamp
            shot.jpeg = unmatchedJpeg.remove(timestamp)
            report.event("exposure", "id" to shot.id, "requestedNs" to shot.requestedNs,
                "sensorNs" to timestamp, "latencyMs" to if (realtime) (timestamp - shot.requestedNs) / 1e6 else null,
                "exposureNs" to result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                "iso" to result.get(CaptureResult.SENSOR_SENSITIVITY), "af" to result.get(CaptureResult.CONTROL_AF_STATE))
            handler.postDelayed({ export(shot) }, 1300)
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (closed.get()) return
            shots[request.tag as? Int]?.failed = true
            report.event("capture_failure", "id" to request.tag, "reason" to failure.reason)
        }
    }

    private fun scheduleShots() {
        report.event("ready", "groups" to groups, "warmupMs" to 2500, "soakMs" to soakMs)
        val epoch = SystemClock.elapsedRealtimeNanos() + (2500 + soakMs) * 1_000_000
        repeat(groups * 5) { index ->
            val delay = 2500 + soakMs + (index / 5) * 5500L + (index % 5) * 500L
            val due = epoch + ((index / 5) * 5500L + (index % 5) * 500L) * 1_000_000
            handler.postDelayed({
                if (closed.get() || finishing) return@postDelayed
                try {
                    val shot = Shot(index, SystemClock.elapsedRealtimeNanos(), due)
                    shots[index] = shot
                    val request = baseRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(jpegReader!!.surface)
                        set(CaptureRequest.JPEG_ORIENTATION, orientation)
                        set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                        setTag(index)
                    }.build()
                    session!!.capture(request, captureCallback, handler)
                    report.event("shutter", "id" to index, "schedulingDelayMs" to (shot.requestedNs - due) / 1e6)
                } catch (error: Exception) { fail(error.message ?: "Capture submission failed") }
            }, delay)
        }
        handler.postDelayed({ finish() }, 2500 + soakMs + groups * 5500L + 5000)
    }

    private val drain = object : Runnable {
        override fun run() {
            if (closed.get() || finishing) return
            try {
                val encoder = codec ?: return
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = encoder.outputFormat
                    else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = encoder.getOutputBuffer(index)!!
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val data = ByteArray(info.size); buffer.get(data)
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                ring.append(EncodedRing.Track.VIDEO, info.presentationTimeUs, key, data)
                                videoTimes.add(info.presentationTimeUs)
                                if (key) keyTimes.add(info.presentationTimeUs)
                            }
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    } else break
                }
                handler.postDelayed(this, 5)
            } catch (error: Exception) { fail(error.message ?: "Video encoder failed") }
        }
    }

    private fun export(shot: Shot) {
        if (closed.get() || finishing || shot.exported || shot.failed) return
        val photoUs = (shot.sensorNs ?: return) / 1000
        val jpeg = shot.jpeg
        if (jpeg == null) {
            if (SystemClock.elapsedRealtimeNanos() - shot.requestedNs < 8_000_000_000) {
                handler.postDelayed({ export(shot) }, 100)
            } else { shot.failed = true; report.event("export_failure", "id" to shot.id, "message" to "JPEG timeout") }
            return
        }
        try {
            val snapshot = ring.snapshot(photoUs)
            val videoFormat = checkNotNull(format)
            val audioFormat = audio?.format
            shot.exported = true
            io.execute {
                snapshot.use {
                    try {
                        val video = File(report.directory, "shot_${shot.id}.mp4")
                        val muxer = MediaMuxer(video.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        try {
                            muxer.setOrientationHint(orientation)
                            val v = muxer.addTrack(videoFormat)
                            val hasAudio = audioFormat != null && snapshot.packets.any { it.track == EncodedRing.Track.AUDIO }
                            val a = if (hasAudio) muxer.addTrack(audioFormat!!) else -1
                            muxer.start()
                            snapshot.packets.forEach { packet ->
                                val track = if (packet.track == EncodedRing.Track.VIDEO) v else a
                                if (track >= 0) muxer.writeSampleData(track, ByteBuffer.wrap(packet.bytes), MediaCodec.BufferInfo().apply {
                                    set(0, packet.bytes.size, packet.ptsUs - snapshot.originUs,
                                        if (packet.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                                })
                            }
                            muxer.stop()
                        } finally { muxer.release() }
                        verifyVideo(video)
                        // The source JPEG is never replaced, even if assembly or playback validation fails.
                        val output = File(report.directory, "shot_${shot.id}_MP.jpg")
                        jpeg.copyTo(output, overwrite = false)
                        MotionPhotoAssembler.assemble(output, video, photoUs - snapshot.originUs)
                        val pts = snapshot.packets.filter { it.track == EncodedRing.Track.VIDEO }.map { it.ptsUs }
                        report.event("export", "id" to shot.id, "photoOffsetUs" to photoUs - snapshot.originUs,
                            "postUs" to pts.last() - photoUs, "nearestFrameErrorUs" to ProbeTiming.nearestErrorUs(photoUs, pts),
                            "maxFrameGapUs" to ProbeTiming.maxGapUs(pts), "audio" to (audioFormat != null),
                            "file" to output.name)
                    } catch (error: Exception) {
                        handler.post { shot.failed = true }
                        report.event("export_failure", "id" to shot.id, "message" to error.message)
                    }
                }
            }
        } catch (error: Exception) {
            shot.failed = true
            report.event("export_failure", "id" to shot.id, "message" to error.message)
        }
    }

    private fun verifyVideo(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("No MP4 video track")
            extractor.selectTrack(track)
            check(extractor.sampleTime >= 0 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "MP4 does not start at sync sample" }
            var count = 0
            do { count++ } while (extractor.advance())
            check(count > 1) { "MP4 contains fewer than two frames" }
        } finally { extractor.release() }
    }

    private fun finish() {
        if (finishing || closed.get()) return
        finishing = true
        val latencies = shots.values.mapNotNull { shot -> shot.sensorNs?.let { (it - shot.requestedNs) / 1e6 } }
        report.event("capture_summary", "requested" to shots.size, "jpegCount" to shots.values.count { it.jpeg != null },
            "latencyComparable" to realtime, "latencyMs" to JSONArray(latencies),
            "maxFrameGapUs" to ProbeTiming.maxGapUs(videoTimes), "maxKeyGapUs" to ProbeTiming.maxGapUs(keyTimes),
            "peakEncodedBytes" to ring.peakBytes, "result" to "REQUIRES_REVIEW_NOT_AUTO_PASS")
        val times = org.json.JSONObject().put("videoPtsUs", JSONArray(videoTimes))
            .put("sensorPtsUs", JSONArray(sensorTimes)).put("keyPtsUs", JSONArray(keyTimes))
        io.execute {
            File(report.directory, "timestamps.json").writeText(times.toString())
            report.event("files_complete", "path" to report.directory.absolutePath)
            completed()
        }
    }

    private fun fail(message: String) {
        if (closed.get()) return
        report.event("fatal", "message" to message)
        finish()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            handler.removeCallbacksAndMessages(null)
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { jpegReader?.close() }
            audio?.close()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { codecSurface?.release() }
            ring.clear()
            io.shutdown()
            worker.quitSafely()
        }
    }
}
