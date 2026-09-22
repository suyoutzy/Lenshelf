package com.example.photocategorycamera.camera

/** Encoded audio/video history shared by overlapping Live captures. */
internal class LiveSampleBuffer(
    private val historyUs: Long = 4_000_000,
    private val capacityBytes: Long = 64L * 1024 * 1024,
) {
    enum class Track { VIDEO, AUDIO }
    class Sample(val track: Track, val ptsUs: Long, val keyFrame: Boolean, val bytes: ByteArray)
    inner class Snapshot internal constructor(val samples: List<Sample>, val originUs: Long) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(this@LiveSampleBuffer) {
            if (!closed) {
                closed = true
                samples.forEach(::release)
            }
        }
    }

    private val history = ArrayList<Sample>()
    private val references = HashMap<Sample, Int>()
    private var newestUs = Long.MIN_VALUE
    var retainedBytes: Long = 0
        private set

    @Synchronized
    fun videoSpanUs(): Long {
        val video = history.filter { it.track == Track.VIDEO }
        return if (video.size < 2) 0L else video.last().ptsUs - video.first().ptsUs
    }

    @Synchronized
    fun append(track: Track, ptsUs: Long, keyFrame: Boolean, data: ByteArray) {
        require(ptsUs >= 0L && data.isNotEmpty())
        newestUs = maxOf(newestUs, ptsUs)
        val iterator = history.iterator()
        while (iterator.hasNext()) {
            val sample = iterator.next()
            if (sample.ptsUs < newestUs - historyUs) {
                iterator.remove()
                release(sample)
            }
        }
        check(retainedBytes + data.size <= capacityBytes) { "Live编码缓冲已满" }
        val sample = Sample(track, ptsUs, keyFrame, data.copyOf())
        history.add(sample)
        references[sample] = 1
        retainedBytes += data.size
    }

    @Synchronized
    fun snapshot(
        photoUs: Long,
        beforeUs: Long = 1_000_000,
        afterUs: Long = 1_000_000,
        audioAdvanceUs: Long = 0L,
        requireFullTail: Boolean = true,
    ): Snapshot {
        require(audioAdvanceUs >= 0L)
        val targetUs = photoUs - beforeUs
        val keyframes = history.filter { it.track == Track.VIDEO && it.keyFrame && it.ptsUs <= photoUs }
        val start = keyframes.filter { it.ptsUs <= targetUs }.maxByOrNull { it.ptsUs }
            ?: keyframes.minByOrNull { it.ptsUs }
            ?: error("Live缓冲中没有可独立播放的起始帧")
        check(start.ptsUs >= targetUs - 500_000) { "Live关键帧间隔超过允许范围" }
        val videoEndUs = photoUs + afterUs
        val audioStartUs = start.ptsUs + audioAdvanceUs
        val audioEndUs = videoEndUs + audioAdvanceUs
        val selected = history.filter { sample ->
            when (sample.track) {
                Track.VIDEO -> sample.ptsUs in start.ptsUs..videoEndUs
                Track.AUDIO -> sample.ptsUs in audioStartUs..audioEndUs
            }
        }.sortedBy { it.ptsUs }
        val videoSamples = selected.filter { it.track == Track.VIDEO }
        check(videoSamples.any { it.ptsUs >= photoUs }) { "Live动态部分未覆盖照片时刻" }
        if (videoSamples.size >= 10) {
            check(videoSamples.zipWithNext().all { (left, right) -> right.ptsUs - left.ptsUs <= 100_000 }) {
                "Live视频在照片窗口内发生停顿"
            }
        }
        if (requireFullTail) {
            val capturedVideoEndUs = videoSamples.lastOrNull()?.ptsUs ?: Long.MIN_VALUE
            check(capturedVideoEndUs >= videoEndUs - 33_334) { "Live动态部分尚未取得完整尾段" }
            if (audioAdvanceUs > 0L && history.any { it.track == Track.AUDIO }) {
                val capturedAudioEndUs = selected.lastOrNull { it.track == Track.AUDIO }?.ptsUs ?: Long.MIN_VALUE
                check(capturedAudioEndUs >= audioEndUs - 25_000) { "Live声音尚未取得兼容尾段" }
            }
        }
        selected.forEach { references[it] = references.getValue(it) + 1 }
        return Snapshot(selected, start.ptsUs)
    }

    @Synchronized
    fun clear() {
        history.forEach(::release)
        history.clear()
        newestUs = Long.MIN_VALUE
    }

    private fun release(sample: Sample) {
        val count = references.getValue(sample) - 1
        if (count == 0) {
            references.remove(sample)
            retainedBytes -= sample.bytes.size
        } else references[sample] = count
    }
}
