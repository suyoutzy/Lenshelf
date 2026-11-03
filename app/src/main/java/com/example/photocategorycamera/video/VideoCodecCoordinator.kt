package com.example.photocategorycamera.video

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VideoExportDeferredException : IOException("录像优先，视频处理稍后继续")

/** Recording admission cancels and joins the export child, including its codec-release finally block. */
class VideoCodecCoordinator {
    private val ownership = Mutex()
    private var activeExport: Deferred<*>? = null
    private var recording: RecordingLease? = null
    private val recordingState = MutableStateFlow(false)
    val recordingReserved: Boolean get() = recordingState.value

    class RecordingLease internal constructor(private val owner: VideoCodecCoordinator) {
        suspend fun release() = owner.releaseRecording(this)
    }

    suspend fun acquireRecording(): RecordingLease {
        val lease = RecordingLease(this)
        val export = ownership.withLock {
            check(recording == null) { "另一项录像正在准备中" }
            recording = lease
            recordingState.value = true
            activeExport
        }
        try {
            export?.cancelAndJoin()
            return lease
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                export?.cancelAndJoin()
                releaseRecording(lease)
            }
            throw error
        }
    }

    private suspend fun releaseRecording(lease: RecordingLease) = ownership.withLock {
        if (recording === lease) { recording = null; recordingState.value = false }
    }

    suspend fun <T> export(work: suspend () -> T): T = supervisorScope {
        val child = async(start = CoroutineStart.LAZY) { work() }
        ownership.withLock {
            if (recording != null) { child.cancel(); throw VideoExportDeferredException() }
            if (activeExport != null) { child.cancel(); throw VideoExportDeferredException() }
            activeExport = child
        }
        child.start()
        try { child.await() }
        catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw VideoExportDeferredException()
        } finally {
            withContext(NonCancellable) {
                ownership.withLock { if (activeExport === child) activeExport = null }
            }
        }
    }
}
