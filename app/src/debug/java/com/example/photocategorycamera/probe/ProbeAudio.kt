package com.example.photocategorycamera.probe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** Audio timestamps are BOOTTIME, used only with a REALTIME camera sensor. */
internal class ProbeAudio(private val ring: EncodedRing, private val warning: (String) -> Unit) : AutoCloseable {
    @Volatile var format: MediaFormat? = null
        private set
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private var encoder: MediaCodec? = null

    @SuppressLint("MissingPermission")
    fun start() {
        var rate = 48_000
        for (candidate in listOf(48_000, 44_100)) {
            val minimum = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(candidate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 4, 16_384)).build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                recorder = record
                rate = candidate
                break
            }
            record.release()
        }
        val input = checkNotNull(recorder) { "AudioRecord initialization failed" }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        encoder = codec
        val config = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }
        codec.configure(config, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        input.startRecording()
        running.set(true)
        thread = Thread({
            try {
                val pcm = ByteArray(2048)
                val anchor = AudioTimestamp()
                var anchorFrame: Long? = null
                var anchorNanos = 0L
                var frame = 0L
                var reads = 0
                val info = MediaCodec.BufferInfo()
                while (running.get()) {
                    val count = input.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) {
                        check(!running.get()) { "AudioRecord read failed: $count" }
                        break
                    }
                    if (anchorFrame == null && input.getTimestamp(anchor, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                        anchorFrame = anchor.framePosition
                        anchorNanos = anchor.nanoTime
                    }
                    if (anchorFrame != null) {
                        val index = codec.dequeueInputBuffer(10_000)
                        check(index >= 0) { "Audio encoder input starved" }
                        codec.getInputBuffer(index)!!.apply { clear(); put(pcm, 0, count) }
                        val pts = ProbeTiming.audioPtsUs(anchorFrame, anchorNanos, frame, rate)
                        check(pts >= 0) { "Invalid audio clock" }
                        codec.queueInputBuffer(index, 0, count, pts, 0)
                    } else check(++reads < rate / 1024 * 3) { "Audio timestamp unavailable for 3 seconds" }
                    frame += count / 2
                    while (true) {
                        val index = codec.dequeueOutputBuffer(info, 0)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            format = codec.outputFormat
                        } else if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val buffer = codec.getOutputBuffer(index)!!
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    val data = ByteArray(info.size); buffer.get(data)
                                    ring.append(EncodedRing.Track.AUDIO, info.presentationTimeUs, false, data)
                                }
                            } finally { codec.releaseOutputBuffer(index, false) }
                        } else break
                    }
                }
            } catch (error: Exception) {
                if (running.get()) warning("Live 无声：${error.message}")
                format = null
            }
        }, "LiveProbeAudio").apply { start() }
    }

    override fun close() {
        running.set(false)
        runCatching { recorder?.stop() }
        thread?.join(2000)
        // A stopped AudioRecord unblocks read before codec ownership returns here.
        if (thread?.isAlive != true) {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { recorder?.release() }
        } else warning("Audio worker did not stop; resources retained until process exit")
    }
}
