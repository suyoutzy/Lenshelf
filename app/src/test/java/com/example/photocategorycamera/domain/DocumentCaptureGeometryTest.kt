package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class DocumentCaptureGeometryTest {
    @Test fun onlyRecentAnalysisFramesCanBeFrozenAtTheShutter() {
        assertTrue(DocumentCaptureGeometry.isFresh(1000, 1000))
        assertTrue(DocumentCaptureGeometry.isFresh(1000, 1500))
        assertFalse(DocumentCaptureGeometry.isFresh(1000, 1501))
        assertFalse(DocumentCaptureGeometry.isFresh(1001, 1000))
    }

    @Test fun rotatedAndMirroredPixelCornersBecomeClockwiseUprightQuads() {
        val points = listOf(DocumentPoint(0.1f, 0.2f), DocumentPoint(0.8f, 0.15f),
            DocumentPoint(0.9f, 0.7f), DocumentPoint(0.2f, 0.85f))
        for (turns in 0..3) for (mirrored in listOf(false, true)) {
            val transformed = points.map {
                DocumentCoordinates.rotate(if (mirrored) DocumentPoint(1f - it.x, it.y) else it, turns)
            }
            val pixels = transformed.flatMap { listOf(it.x * 800, it.y * 600) }.toFloatArray()
            val normalized = DocumentCaptureGeometry.normalizedQuad(pixels, 800, 600)!!
            assertTrue(normalized.isValid)
            assertEquals(transformed.minOf { it.x + it.y }, normalized.points.first().let { it.x + it.y }, 0.001f)
            transformed.forEach { point ->
                assertTrue(normalized.points.any { kotlin.math.abs(it.x - point.x) < 0.001f && kotlin.math.abs(it.y - point.y) < 0.001f })
            }
        }
    }

    @Test fun invalidTransformsCannotSilentlyTurnIntoAClampedCrop() {
        assertNull(DocumentCaptureGeometry.normalizedQuad(FloatArray(6), 400, 300))
        assertNull(DocumentCaptureGeometry.normalizedQuad(FloatArray(8), 400, 300))
        assertNull(DocumentCaptureGeometry.normalizedQuad(floatArrayOf(Float.NaN, 0f, 400f, 0f, 400f, 300f, 0f, 300f), 400, 300))
        assertNull(DocumentCaptureGeometry.normalizedQuad(floatArrayOf(-20f, 0f, 400f, 0f, 400f, 300f, 0f, 300f), 400, 300))
        assertNull(DocumentCaptureGeometry.normalizedQuad(FloatArray(8), 0, 300))
    }

    @Test fun subpixelBoundaryRoundingIsAcceptedWithoutCroppingAwayThePage() {
        assertEquals(DocumentQuad.FULL_IMAGE, DocumentCaptureGeometry.normalizedQuad(
            floatArrayOf(-0.1f, -0.1f, 400.1f, 0f, 400f, 300.1f, 0f, 300f), 400, 300))
    }
}
