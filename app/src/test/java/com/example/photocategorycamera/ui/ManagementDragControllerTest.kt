package com.example.photocategorycamera.ui

import org.junit.Assert.*
import org.junit.Test

class ManagementDragControllerTest {
    private val source = DragPartition("home", true)
    private val target = DragPartition("work", true)
    private fun record(id: String, partition: DragPartition = source) = DragRecord(id, partition)
    private fun world() = DragWorld(listOf(record("a"), record("b"), record("c"),
        record("off", source.copy(active = false)), record("x", target), record("y", target)), listOf("home", "work", "empty"))

    @Test fun releaseCommitsOneImmutableSourceSnapshotAndExactOrder() {
        val records = world().records.toMutableList()
        val controller = ManagementDragController()
        assertTrue(controller.start("a", world().copy(records = records)))
        records.clear()
        assertEquals(listOf("a", "b", "c"), controller.session!!.originalIds)
        assertTrue(controller.place(source, null))
        assertEquals(ManagementDrop.Reorder(record("a"), listOf("a", "b", "c"), listOf("b", "c", "a")), controller.release(true))
        assertEquals(ManagementDrop.Cancel, controller.release(true))
    }

    @Test fun crossPartitionAndOutsideReleaseNeverCommit() {
        val controller = ManagementDragController()
        assertTrue(controller.start("a", world()))
        assertFalse(controller.place(source.copy(active = false), "off"))
        assertEquals(ManagementDrop.Cancel, controller.release(true))
        assertTrue(controller.start("a", world()))
        controller.place(source, "c")
        assertEquals(ManagementDrop.Cancel, controller.release(false))
    }

    @Test fun hoverRequiresContinuous600msAndEntryReleaseCancels() {
        val controller = ManagementDragController()
        controller.start("a", world())
        assertFalse(controller.hover("work", 0))
        assertFalse(controller.hover("work", 599))
        assertFalse(controller.hover(null, 600))
        assertFalse(controller.hover("work", 800))
        assertFalse(controller.hover("work", 1399))
        assertTrue(controller.hover("work", 1400))
        assertEquals("work", controller.session!!.previewGroupId)
        assertEquals(ManagementDrop.Cancel, controller.release(false))
    }

    @Test fun hoverCanReturnToSourceAndReorderWithoutTransfer() {
        val controller = ManagementDragController()
        controller.start("a", world())
        controller.hover("work", 0); controller.hover("work", 600)
        controller.hover("home", 601); controller.hover("home", 1201)
        controller.place(source, "c")
        assertEquals(ManagementDrop.Reorder(record("a"), listOf("a", "b", "c"), listOf("b", "a", "c")), controller.release(true))
    }

    @Test fun targetDropCarriesExactAnchorAndEmptySectionTail() {
        val controller = ManagementDragController()
        controller.start("a", world())
        controller.hover("work", 0); controller.hover("work", 600)
        controller.place(target, "y")
        assertEquals(ManagementDrop.Transfer(record("a"), "work", "y"), controller.release(true))
        controller.start("a", world())
        controller.hover("empty", 0); controller.hover("empty", 600)
        controller.place(DragPartition("empty"), null)
        assertEquals(ManagementDrop.Transfer(record("a"), "empty", null), controller.release(true))
    }

    @Test fun sourceMutationOrNewLockCancelsBeforeDrop() {
        val controller = ManagementDragController()
        controller.start("a", world())
        assertFalse(controller.refresh(world().copy(records = world().records.filterNot { it.id == "b" })))
        assertNull(controller.session)
        controller.start("a", world())
        assertFalse(controller.refresh(world().copy(lockedIds = setOf("a"))))
        assertEquals(ManagementDrop.Cancel, controller.release(true))
    }

    @Test fun lockedOrFixedSourceNeverStartsAndPendingOperationBlocksCrossGroup() {
        val controller = ManagementDragController()
        assertFalse(controller.start("a", world().copy(lockedIds = setOf("a"))))
        assertFalse(controller.start("a", world().copy(blockedGroups = setOf("home"))))
        assertFalse(controller.start("missing-fixed-home", world()))
        assertTrue(controller.start("a", world().copy(crossGroupEnabled = false)))
        controller.hover("work", 0)
        assertFalse(controller.hover("work", 1000))
        assertEquals("home", controller.session!!.previewGroupId)
    }

    @Test fun unchangedAndCancelledGesturesHaveNoMutation() {
        val controller = ManagementDragController()
        controller.start("b", world())
        controller.place(source, "c")
        assertEquals(ManagementDrop.Unchanged, controller.release(true))
        controller.start("a", world()); controller.place(source, null); controller.cancel()
        assertEquals(ManagementDrop.Cancel, controller.release(true))
    }
}
