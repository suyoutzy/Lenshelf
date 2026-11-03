package com.example.photocategorycamera.video

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VideoCodecCoordinatorTest {
    @Test fun recorderWaitsForExportCleanupAndPreventsAnotherExport() = runBlocking {
        withTimeout(5000) {
            val codec = VideoCodecCoordinator()
            val began = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val export = async { runCatching {
                codec.export {
                    try { began.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                }
            } }
            began.await()
            val recording = async { codec.acquireRecording() }
            cleaning.await()
            assertFalse(recording.isCompleted)
            release.complete(Unit)
            val lease = recording.await()
            assertTrue(export.await().exceptionOrNull() is VideoExportDeferredException)
            var encoded = false
            assertTrue(runCatching { codec.export { encoded = true } }.isFailure)
            assertFalse(encoded)
            lease.release()
            assertEquals(42,codec.export { 42 })
        }
    }

    @Test fun cancelingPreparationStillWaitsForTheCodecBeforeReleasingItsReservation() = runBlocking {
        withTimeout(5000) {
            val codec = VideoCodecCoordinator()
            val began = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val export = async { runCatching { codec.export {
                try { began.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
            } } }
            began.await()
            val recording = async { codec.acquireRecording() }
            cleaning.await()
            recording.cancel()
            assertTrue(codec.recordingReserved)
            release.complete(Unit)
            recording.join()
            export.await()
            assertFalse(codec.recordingReserved)
            assertEquals("ready",codec.export { "ready" })
        }
    }
    @Test fun aSecondExportIsDeferredWithoutFailingOrCancelingTheFirst() = runBlocking {
        withTimeout(5000) {
            val codec=VideoCodecCoordinator()
            val started=CompletableDeferred<Unit>()
            val finish=CompletableDeferred<Unit>()
            val first=async { codec.export { started.complete(Unit);finish.await();"first" } }
            started.await()
            val second=async { runCatching { codec.export { "second" } } }
            val result=withTimeout(2000) { second.await() }
            assertTrue(result.exceptionOrNull() is VideoExportDeferredException)
            finish.complete(Unit)
            assertEquals("first",first.await())
        }
    }

}
