package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class DocumentPageSelectionTest {
    private fun page(left: Float, top: Float, right: Float, bottom: Float) = DocumentQuad(listOf(
        DocumentPoint(left, top), DocumentPoint(right, top), DocumentPoint(right, bottom), DocumentPoint(left, bottom),
    ))

    @Test fun equallySizedPageNearestTheCenterWins() {
        val central = page(0.3f, 0.2f, 0.7f, 0.8f)
        val side = page(0.01f, 0.2f, 0.41f, 0.8f)
        assertEquals(central, DocumentPageSelection.select(listOf(side, central), null, { it }, DocumentPageSelection::centrality))
    }

    @Test fun aMatchingPageWinsOverAChangingNeighbourScore() {
        val original = page(0.1f, 0.1f, 0.5f, 0.9f)
        val matching = page(0.12f, 0.1f, 0.52f, 0.9f)
        val neighbour = page(0.5f, 0.1f, 0.95f, 0.9f)
        assertEquals(matching, DocumentPageSelection.select(listOf(neighbour, matching), original, { it }) {
            if (it == neighbour) 10f else 1f
        })
    }

    @Test fun aRemovedPageAllowsACentralReplacementAndEmptyCandidatesReturnNull() {
        val original = page(0.01f, 0.1f, 0.3f, 0.9f)
        val replacement = page(0.4f, 0.1f, 0.9f, 0.9f)
        assertEquals(replacement, DocumentPageSelection.select(listOf(replacement), original, { it }, DocumentPageSelection::centrality))
        assertNull(DocumentPageSelection.select(emptyList<DocumentQuad>(), original, { it }, DocumentPageSelection::centrality))
    }
}
