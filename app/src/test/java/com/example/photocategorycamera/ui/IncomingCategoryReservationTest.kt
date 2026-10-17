package com.example.photocategorycamera.ui

import com.example.photocategorycamera.domain.Category
import org.junit.Assert.*
import org.junit.Test

class IncomingCategoryReservationTest {
    private val source = Category("a", "样品", 1, false, 1)
    private val operation = DirectoryOperationUiState("op", "TRANSFER_CATEGORY", "常用/样品", "实验/样品", "COPY", null,
        source.id, "home", "work", "anchor")

    @Test fun backgroundReservationSurvivesOperationClearUntilTargetCategoryArrives() {
        val pending = retainIncomingCategoryReservations(emptyList(), listOf(operation), listOf(source))
        assertEquals(1, pending.size)
        val gap = retainIncomingCategoryReservations(pending, emptyList(), listOf(source))
        assertEquals(pending, gap)
        assertEquals("anchor", gap.single().operation.targetBeforeCategoryId)
        assertFalse(gap.single().category.isActive)
        val real = source.copy(groupId = "work", groupName = "实验")
        assertTrue(retainIncomingCategoryReservations(gap, emptyList(), listOf(real)).isEmpty())
    }

    @Test fun realRowAlwaysWinsWhenCategoryFlowArrivesFirst() {
        val pending = retainIncomingCategoryReservations(emptyList(), listOf(operation), listOf(source))
        val real = source.copy(groupId = "work", groupName = "实验")
        assertTrue(retainIncomingCategoryReservations(pending, listOf(operation), listOf(real)).isEmpty())
    }

    @Test fun latestErrorAndSourceSnapshotSurviveMissingOperationAndCategoryEmission() {
        val pending = retainIncomingCategoryReservations(emptyList(), listOf(operation), listOf(source))
        val failed = retainIncomingCategoryReservations(pending, listOf(operation.copy(error = "读写失败")), listOf(source))
        val gap = retainIncomingCategoryReservations(failed, emptyList(), emptyList())
        assertEquals("读写失败", gap.single().operation.error)
        assertEquals(source, gap.single().category)
    }
}
