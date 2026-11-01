package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class CategoryRefreshPlannerTest {
    private fun category(id: String, name: String, order: Int = 0) = Category(id, name, order, true, 1)

    @Test fun addsSortedDirectoriesAndRemovesOnlyMissingUnprotectedRecords() {
        val existing = listOf(category("keep", "Existing", 4), category("gone", "removed", 8), category("task", "pending", 9))
        val plan = CategoryRefreshPlanner.plan(listOf("zebra", "Existing", "Alpha", ".hidden", "Alpha", "bad/name"), existing, setOf("task"))
        assertEquals(listOf("Alpha", "zebra"), plan.namesToCreate)
        assertEquals(listOf("gone"), plan.idsToRemove)
        assertEquals(listOf("task"), plan.deferredIds)
        assertEquals(1, plan.retainedCount)
        assertEquals(3, plan.ignoredCount)
        assertEquals(listOf(4,8,9), existing.map { it.sortOrder })
    }

    @Test fun emptyDirectoryIsPresentAndExternalRenameProducesNewRecord() {
        val existing = listOf(category("empty", "empty"), category("renamed", "before"))
        val plan = CategoryRefreshPlanner.plan(listOf("empty", "after"), existing, emptySet())
        assertEquals(listOf("after"), plan.namesToCreate)
        assertEquals(listOf("renamed"), plan.idsToRemove)
        assertEquals(1, plan.retainedCount)
        assertEquals(emptyList<String>(), plan.deferredIds)
    }

    @Test fun externalWhitespaceNamesAreIgnoredRatherThanPointingAtANonexistentTrimmedPath() {
        val first = CategoryRefreshPlanner.plan(listOf("  Receipts  "), emptyList(), emptySet())
        assertEquals(emptyList<String>(), first.namesToCreate)
        assertEquals(1, first.ignoredCount)
        assertEquals(first, CategoryRefreshPlanner.plan(listOf("  Receipts  "), emptyList(), emptySet()))
        val renamed = CategoryRefreshPlanner.plan(listOf("  Receipts  "), listOf(category("old", "Receipts")), emptySet())
        assertEquals(listOf("old"), renamed.idsToRemove)
        assertTrue(renamed.namesToCreate.isEmpty())
    }

    @Test fun caseOnlyExternalRenameRemovesTheOldPathAndImportsTheExactNewPath() {
        val renamed = CategoryRefreshPlanner.plan(listOf("receipts"), listOf(category("old", "Receipts")), emptySet())
        assertEquals(listOf("old"), renamed.idsToRemove)
        assertEquals(listOf("receipts"), renamed.namesToCreate)
        assertEquals(0, renamed.retainedCount)
        val frozen = CategoryRefreshPlanner.plan(listOf("receipts"), listOf(category("old", "Receipts")), setOf("old"))
        assertEquals(listOf("old"), frozen.deferredIds)
        assertTrue(frozen.namesToCreate.isEmpty())
        assertTrue(frozen.idsToRemove.isEmpty())
    }

    @Test fun caseAmbiguousProviderNamesAbortReconciliation() {
        try {
            CategoryRefreshPlanner.plan(listOf("Receipts", "receipts"), listOf(category("old", "gone")), emptySet())
            fail("A case-ambiguous listing must not remove or import records")
        } catch (_: IllegalArgumentException) { }
    }
}
