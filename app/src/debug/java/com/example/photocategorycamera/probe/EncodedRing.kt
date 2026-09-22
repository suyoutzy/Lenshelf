package com.example.photocategorycamera.probe

/** Bounded encoded storage. Snapshots retain the actual packets, not copies per shutter. */
internal class EncodedRing(
    private val historyUs: Long = 4_000_000,
    private val capacityBytes: Long = 64L * 1024 * 1024,
) {
    enum class Track { VIDEO, AUDIO }
    class Packet(val track: Track, val ptsUs: Long, val keyFrame: Boolean, val bytes: ByteArray)
    inner class Snapshot internal constructor(val packets: List<Packet>, val originUs: Long) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(this@EncodedRing) {
            if (!closed) {
                closed = true
                packets.forEach(::unref)
            }
        }
    }

    private val ring = ArrayList<Packet>()
    private val references = HashMap<Packet, Int>()
    private var newestUs = Long.MIN_VALUE
    var retainedBytes: Long = 0
        private set
    var peakBytes: Long = 0
        private set

    @Synchronized
    fun append(track: Track, ptsUs: Long, keyFrame: Boolean, bytes: ByteArray) {
        require(ptsUs >= 0 && bytes.isNotEmpty())
        newestUs = maxOf(newestUs, ptsUs)
        val iterator = ring.iterator()
        while (iterator.hasNext()) {
            val packet = iterator.next()
            if (packet.ptsUs < newestUs - historyUs) {
                iterator.remove()
                unref(packet)
            }
        }
        check(retainedBytes + bytes.size <= capacityBytes) { "Encoded buffer budget exceeded" }
        val packet = Packet(track, ptsUs, keyFrame, bytes.copyOf())
        ring.add(packet)
        references[packet] = 1
        retainedBytes += bytes.size
        peakBytes = maxOf(peakBytes, retainedBytes)
    }

    /** No invented midpoint: selection and exported presentation time use the photograph time. */
    @Synchronized
    fun snapshot(photoUs: Long, beforeUs: Long = 1_000_000, afterUs: Long = 1_000_000): Snapshot {
        val keys = ring.filter { it.track == Track.VIDEO && it.keyFrame && it.ptsUs <= photoUs }
        val target = photoUs - beforeUs
        val start = keys.filter { it.ptsUs <= target }.maxByOrNull { it.ptsUs }
            ?: keys.minByOrNull { it.ptsUs }
            ?: error("No independently decodable video before photograph")
        check(start.ptsUs >= target - 500_000) { "Keyframe extends pre-roll beyond 0.5 seconds" }
        val selected = ring.filter { it.ptsUs in start.ptsUs..(photoUs + afterUs) }
            .sortedBy { it.ptsUs }
        val video = selected.filter { it.track == Track.VIDEO }
        check(video.last().ptsUs >= photoUs) { "Video does not contain photograph time" }
        selected.forEach { references[it] = references.getValue(it) + 1 }
        return Snapshot(selected, start.ptsUs)
    }

    @Synchronized
    fun clear() {
        ring.forEach(::unref)
        ring.clear()
        newestUs = Long.MIN_VALUE
    }

    private fun unref(packet: Packet) {
        val count = references.getValue(packet) - 1
        if (count == 0) {
            references.remove(packet)
            retainedBytes -= packet.bytes.size
        } else references[packet] = count
    }
}

internal object ProbeTiming {
    fun audioPtsUs(anchorFrame: Long, anchorNanos: Long, frame: Long, rate: Int): Long {
        require(rate > 0)
        return anchorNanos / 1_000 + (frame - anchorFrame) * 1_000_000 / rate
    }

    fun maxGapUs(times: List<Long>): Long = times.sorted().zipWithNext { a, b -> b - a }.maxOrNull() ?: 0

    fun nearestErrorUs(photoUs: Long, videoUs: List<Long>): Long? =
        videoUs.minOfOrNull { kotlin.math.abs(it - photoUs) }
}
