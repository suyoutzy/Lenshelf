package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class DocumentModelsTest {
    @Test fun fullImageIsConvexAndRoundTrips() {
        val quad = DocumentQuad.FULL_IMAGE
        assertTrue(quad.isValid)
        assertEquals(1f, quad.area, 0.0001f)
        assertEquals(quad, DocumentQuad.decode(quad.encode()))
    }

    @Test fun crossedAndDegenerateCornersAreRejected() {
        val p = DocumentQuad.FULL_IMAGE.points
        assertFalse(DocumentQuad(listOf(p[0], p[2], p[1], p[3])).isValid)
        assertFalse(DocumentQuad(List(4) { p[0] }).isValid)
        assertNull(DocumentQuad.decode("NaN,0;1,0;1,1;0,1"))
        assertNull(DocumentQuad.decode("0,0;1,0;1,1"))
        assertNull(DocumentQuad.decode("invalid"))
    }

    @Test fun draggingCannotCrossTheOppositeEdge() {
        val original = DocumentQuad.FULL_IMAGE
        assertEquals(original, original.move(0, DocumentPoint(1f, 1f)))
        val clamped = original.move(0, DocumentPoint(-2f, -2f))
        assertEquals(original, clamped)
        assertTrue(original.move(0, DocumentPoint(0.1f, 0.1f)).isValid)
    }

    @Test fun outputCapsResolutionWithoutUpscaling() {
        val quad = DocumentQuad.FULL_IMAGE
        assertEquals(399 to 299, quad.outputSize(400, 300))
        val (w, h) = quad.outputSize(8000, 6000)
        assertEquals(4096, w)
        assertTrue(h <= 4096)
        val crop = DocumentQuad(listOf(DocumentPoint(0.2f, 0.2f), DocumentPoint(0.8f, 0.2f),
            DocumentPoint(0.8f, 0.8f), DocumentPoint(0.2f, 0.8f)))
        assertTrue(crop.outputSize(400, 300).first < 400)
    }

    @Test fun allFourCoordinateRotationsAreCorrect() {
        val point = DocumentPoint(0.2f, 0.7f)
        val expected = listOf(DocumentPoint(0.2f, 0.7f), DocumentPoint(0.3f, 0.2f),
            DocumentPoint(0.8f, 0.3f), DocumentPoint(0.7f, 0.8f))
        for (turn in 0..3) {
            val actual = DocumentCoordinates.rotate(point, turn)
            assertEquals(expected[turn].x, actual.x, 0.0001f)
            assertEquals(expected[turn].y, actual.y, 0.0001f)
        }
        assertEquals(point, DocumentCoordinates.rotate(point, 4))
    }

    @Test fun analysisCoordinatesAreRelativeToCropOrigin() {
        assertArrayEquals(floatArrayOf(0f, 0f, 640f, 0f, 640f, 480f, 0f, 480f),
            DocumentQuad.FULL_IMAGE.cropCoordinates(640, 480), 0.001f)
        val smaller = DocumentQuad(listOf(DocumentPoint(0.1f, 0.2f), DocumentPoint(0.9f, 0.2f),
            DocumentPoint(0.9f, 0.8f), DocumentPoint(0.1f, 0.8f)))
        assertEquals(64f, smaller.cropCoordinates(640, 480)[0], 0.001f)
        assertEquals(96f, smaller.cropCoordinates(640, 480)[1], 0.001f)
    }

    @Test fun oneMissDoesNotRefreshTheOutlineAndRepeatedMissesClearIt() {
        val stabilizer = DocumentOutlineStabilizer()
        assertNull(stabilizer.update(DocumentQuad.FULL_IMAGE, 1000))
        assertNotNull(stabilizer.update(DocumentQuad.FULL_IMAGE, 1200))
        assertNotNull(stabilizer.update(null, 1400))
        assertEquals(1200L, stabilizer.observedAtMs)
        assertNull(stabilizer.update(null, 1600))
        assertNull(stabilizer.update(DocumentQuad.FULL_IMAGE))
    }

    @Test fun distantCandidateMustStabilizeAgain() {
        val stabilizer = DocumentOutlineStabilizer()
        stabilizer.update(DocumentQuad.FULL_IMAGE)
        stabilizer.update(DocumentQuad.FULL_IMAGE)
        val smaller = DocumentQuad(listOf(DocumentPoint(0.2f, 0.2f), DocumentPoint(0.8f, 0.2f),
            DocumentPoint(0.8f, 0.8f), DocumentPoint(0.2f, 0.8f)))
        assertNull(stabilizer.update(smaller))
        assertNull(stabilizer.update(smaller))
        val stable = stabilizer.update(smaller)!!
        smaller.points.zip(stable.points).forEach { (a, b) ->
            assertEquals(a.x, b.x, 0.0001f); assertEquals(a.y, b.y, 0.0001f)
        }
    }

    @Test fun oneNeighbouringPageCannotStealTheStableTarget() {
        val stabilizer = DocumentOutlineStabilizer()
        val page = DocumentQuad.FULL_IMAGE
        val neighbour = page.move(0, DocumentPoint(0.2f, 0.2f))
        stabilizer.update(page, 1000)
        stabilizer.update(page, 1200)
        assertNull(stabilizer.update(neighbour, 1400))
        assertEquals(page, stabilizer.trackingHint)
        assertEquals(page, stabilizer.update(page, 1600))
    }
}
