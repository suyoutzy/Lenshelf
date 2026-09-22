package com.example.photocategorycamera.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.example.photocategorycamera.diagnostics.CaptureEventLog
import java.util.concurrent.atomic.AtomicBoolean

internal class LiveAudioEncoder(
    private val context: android.content.Context,
    private val buffer: LiveSampleBuffer,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    @Volatile var outputFormat: MediaFormat? = null
        private set
    @Volatile var sourceLabel: String = "uninitialized"
        private set
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private var encoder: MediaCodec? = null

    @SuppressLint("MissingPermission")
    fun start() {
        // UNPROCESSED was accurately timestamped on the target phone, but its raw level was too low
        // for ordinary Live playback. MIC restores the phone's normal capture gain. Its timing is
        // still derived from AudioRecord's BOOTTIME hardware timestamp, not callback arrival time.
        sourceLabel = "mic"
        val audioSource = MediaRecorder.AudioSource.MIC
        var sampleRate = 48_000
        for (candidate in listOf(48_000, 44_100)) {
            val minimum = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val audioRecord = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(candidate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(minimum * 4, 16_384)).build()
            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                recorder = audioRecord
                sampleRate = candidate
                break
            }
            audioRecord.release()
        }
        val input = checkNotNull(recorder) { "麦克风初始化失败" }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        encoder = codec
        codec.configure(
            MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
        )
        codec.start()
        input.startRecording()
        CaptureEventLog.record(
            context,
            "audio_started",
            values = mapOf("source" to sourceLabel, "sampleRate" to sampleRate),
        )
        running.set(true)
        thread = Thread({ encode(input, codec, sampleRate) }, "LenshelfLiveAudio").apply { start() }
    }

    private fun encode(input: AudioRecord, codec: MediaCodec, rate: Int) {
        try {
            val pcm = ByteArray(2048)
            val timestamp = AudioTimestamp()
            var anchorFrame: Long? = null
            var anchorNanos = 0L
            var framePosition = 0L
            var readsWithoutTimestamp = 0
            var anchorLogged = false
            val info = MediaCodec.BufferInfo()
            while (running.get()) {
                val count = input.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    check(!running.get()) { "麦克风读取失败：$count" }
                    break
                }
                if (input.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    anchorFrame = timestamp.framePosition
                    anchorNanos = timestamp.nanoTime
                    readsWithoutTimestamp = 0
                }
                if (anchorFrame != null) {
                    val index = codec.dequeueInputBuffer(10_000)
                    check(index >= 0) { "音频编码器输入阻塞" }
                    codec.getInputBuffer(index)!!.apply { clear(); put(pcm, 0, count) }
                    val ptsUs = AudioTimestampMapper.bufferStartUs(
                        hardwareFramePosition = anchorFrame,
                        hardwareTimestampNs = anchorNanos,
                        bufferStartFramePosition = framePosition,
                        sampleRate = rate,
                    )
                    if (!anchorLogged) {
                        anchorLogged = true
                        CaptureEventLog.record(
                            context,
                            "audio_timestamp_anchor",
                            values = mapOf(
                                "hardwareFramePosition" to anchorFrame,
                                "hardwareTimestampNs" to anchorNanos,
                                "bufferStartFramePosition" to framePosition,
                                "bufferFrames" to count / 2,
                                "mappedBufferStartUs" to ptsUs,
                            ),
                        )
                    }
                    codec.queueInputBuffer(index, 0, count, ptsUs, 0)
                } else check(++readsWithoutTimestamp < rate / 1024 * 3) { "3秒内未获得麦克风硬件时间戳" }
                framePosition += count / 2
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat = codec.outputFormat
                    else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val encoded = codec.getOutputBuffer(index)!!
                                encoded.position(info.offset); encoded.limit(info.offset + info.size)
                                val data = ByteArray(info.size); encoded.get(data)
                                buffer.append(LiveSampleBuffer.Track.AUDIO, info.presentationTimeUs, false, data)
                            }
                        } finally { codec.releaseOutputBuffer(index, false) }
                    } else break
                }
            }
        } catch (error: Exception) {
            if (running.get()) onFailure(error.message ?: "Live声音采集失败")
            outputFormat = null
        }
    }

    override fun close() {
        running.set(false)
        runCatching { recorder?.stop() }
        thread?.join(2_000)
        if (thread?.isAlive != true) {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { recorder?.release() }
        } else onFailure("Live声音线程未能及时停止")
    }
}
