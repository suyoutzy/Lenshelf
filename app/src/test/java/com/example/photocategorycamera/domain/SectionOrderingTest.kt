package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class SectionOrderingTest {
    @Test fun `stale order is rejected even when snapshot has the same set`() {
        assertTrue(runCatching { SectionOrdering.validate(listOf("a", "b"), listOf("b", "a"), listOf("a", "b")) }.isFailure)
    }

    @Test fun `duplicates missing and foreign identifiers cannot be saved`() {
        val current = listOf("a", "b")
        listOf(listOf("a", "a"), listOf("a"), listOf("a", "c")).forEach { proposed ->
            assertTrue(runCatching { SectionOrdering.validate(current, current, proposed) }.isFailure)
        }
        assertTrue(runCatching { SectionOrdering.validate(listOf("a", "a"), listOf("a", "a"), listOf("a", "a")) }.isFailure)
        SectionOrdering.validate(current, current, listOf("b", "a"))
        SectionOrdering.validate(emptyList(), emptyList(), emptyList())
    }
}
