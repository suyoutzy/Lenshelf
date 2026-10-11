package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class DocumentPreviewGeometryTest {
    @Test fun preservesAspectRatioAndFitsWithoutStretching() {
        val points = DocumentPreviewGeometry.flatCorners(800, 400, 300f, 400f)
        assertEquals(listOf(DocumentPoint(0f, 125f), DocumentPoint(300f, 125f),
            DocumentPoint(300f, 275f), DocumentPoint(0f, 275f)), points)
    }

    @Test fun everyRotationAndMirrorUnfoldsWithoutCrossingOrCollapsing() {
        for (turns in 0..3) for (mirrored in listOf(false, true)) {
            val reference = DocumentQuad.FULL_IMAGE.points.map {
                val p = DocumentCoordinates.rotate(it, turns)
                DocumentPoint((if (mirrored) 1 - p.x else p.x) * 240 + 30, p.y * 320 + 40)
            }
            val flat = DocumentPreviewGeometry.flatCorners(300, 400, 300f, 400f, reference)
            fun cross(points: List<DocumentPoint>, index: Int): Float {
                val a = points[index]; val b = points[(index + 1) % 4]; val c = points[(index + 2) % 4]
                return (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            }
            for (step in 0..10) {
                val frame = DocumentPreviewGeometry.interpolate(reference, flat, step / 10f)
                for (index in 0..3) assertTrue(cross(reference, index) * cross(frame, index) > 0)
            }
        }
    }

    @Test fun interpolationHasExactEndpointsAndClampsTime() {
        val from = DocumentQuad.FULL_IMAGE.points
        val to = from.map { DocumentPoint(it.x * 0.8f + 0.1f, it.y * 0.8f + 0.1f) }
        assertEquals(from, DocumentPreviewGeometry.interpolate(from, to, -1f))
        assertEquals(to, DocumentPreviewGeometry.interpolate(from, to, 2f))
        assertEquals(DocumentPoint(0.05f, 0.05f), DocumentPreviewGeometry.interpolate(from, to, 0.5f).first())
    }
}
