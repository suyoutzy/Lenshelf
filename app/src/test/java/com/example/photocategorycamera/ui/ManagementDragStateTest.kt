package com.example.photocategorycamera.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

/** Real gesture state and geometry; the cancelled scope suppresses only settle rendering. */
class ManagementDragStateTest {
    private val home = DragPartition("home")
    private val work = DragPartition("work")
    private val world = DragWorld(listOf(DragRecord("a", home), DragRecord("b", home),
        DragRecord("x", work)), listOf("home", "work"))
    private fun state() = ManagementDragState(CoroutineScope(Job().apply { cancel() })).apply {
        update(this@ManagementDragStateTest.world)
        viewport = Rect(0f, 0f, 300f, 500f)
        trayViewport = Rect(0f, 500f, 300f, 600f)
        row("a", home, Rect(0f, 20f, 300f, 100f))
        row("b", home, Rect(0f, 110f, 300f, 190f))
        target("tail", home, null, Rect(0f, 200f, 300f, 250f))
        entry("work", Rect(150f, 500f, 300f, 600f))
    }

    @Test fun firstFrameCannotShortenContinuousHoverDwell() {
        val state = state()
        state.start(Offset(30f, 50f))
        state.move(Offset(200f, 550f))
        state.processPointer(100_000)
        assertEquals("home", state.previewGroupId)
        state.processPointer(100_599)
        assertEquals("home", state.previewGroupId)
        state.processPointer(100_600)
        assertEquals("work", state.previewGroupId)
    }

    @Test fun reservationObservedBeforeCompletionDoesNotLeaveUiSubmitting() {
        val state = state()
        var completion: ((Boolean) -> Unit)? = null
        state.onDrop = { _, done -> completion = done }
        state.start(Offset(30f, 50f))
        state.processPointer(1_000)
        state.move(Offset(200f, 550f))
        state.processPointer(1_001)
        state.processPointer(1_601)
        state.removeRow("a"); state.removeRow("b"); state.removeTarget("tail")
        state.target("target-tail", work, null, Rect(0f, 100f, 300f, 200f))
        state.move(Offset(30f, 150f))
        state.end()
        assertTrue(state.submitting)
        state.update(world, setOf("a"))
        completion!!(true)
        assertFalse(state.submitting)
        assertNull(state.displaySession)
    }

    @Test fun releaseCallsPersistenceOnceAndPreservesPlacementUntilEcho() {
        val state = state()
        var calls = 0
        state.onDrop = { _, done -> calls++; done(true) }
        state.start(Offset(30f, 50f)); state.move(Offset(30f, 230f))
        assertEquals(0, calls)
        state.end(); state.end()
        assertEquals(1, calls)
        assertEquals(listOf("b", "a"), state.orderedIds(home, listOf("a", "b")))
        assertTrue(state.submitting)
        state.update(world.copy(records = listOf(world.records[1], world.records[0], world.records[2])))
        assertFalse(state.submitting)
    }

    @Test fun menuAndOutsideOrWrongStateReleasesNeverPersist() {
        val state = state()
        var calls = 0
        state.onDrop = { _, _ -> calls++ }
        state.start(Offset(280f, 50f))
        assertFalse(state.dragging)
        state.start(Offset(30f, 50f))
        state.move(Offset(350f, 230f)); state.end()
        assertEquals(0, calls)
    }

    @Test fun stationaryPointerReevaluatesAfterListGeometryMoves() {
        val state = state()
        state.row("b", home, Rect(0f, 420f, 300f, 500f))
        state.start(Offset(30f, 50f)); state.move(Offset(30f, 450f))
        assertEquals("b", state.displaySession!!.beforeId)
        assertTrue(state.verticalScrollVelocity() > 0f)
        state.row("b", home, Rect(0f, 380f, 300f, 460f))
        state.processPointer(10_000)
        assertNull(state.displaySession!!.beforeId)
        assertEquals(Offset(30f, 450f), state.pointer)
        state.move(Offset(290f, 550f))
        assertTrue(state.horizontalScrollVelocity() > 0f)
        assertEquals(0f, state.verticalScrollVelocity())
    }
}
