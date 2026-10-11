package com.example.photocategorycamera.document

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DocumentFramePixelsTest {
    @Test fun paddedPlanesAndBufferOffsetsMatchCompactPlanes() {
        fun plane(width: Int, height: Int, value: (Int, Int) -> Int, padded: Boolean): DocumentFramePixels.Plane {
            val stride = if (padded) width * 2 + 7 else width
            val pixelStride = if (padded) 2 else 1
            val buffer = ByteBuffer.allocate(3 + stride * height)
            for (y in 0 until height) for (x in 0 until width) buffer.put(3 + y * stride + x * pixelStride, value(x, y).toByte())
            buffer.position(3)
            return DocumentFramePixels.Plane(buffer, stride, pixelStride)
        }
        fun planes(padded: Boolean) = listOf(
            plane(8, 6, { x, y -> 30 + x * 10 + y * 5 }, padded),
            plane(4, 3, { x, _ -> 110 + x }, padded),
            plane(4, 3, { _, y -> 130 + y }, padded),
        )
        assertArrayEquals(DocumentFramePixels.rgb(planes(false), 1, 1, 6, 4, 4, 3),
            DocumentFramePixels.rgb(planes(true), 1, 1, 6, 4, 4, 3))
    }

    @Test fun subsamplingIncludesTheBottomRightOfTheActualCrop() {
        val y = ByteBuffer.wrap(ByteArray(8 * 6) { 16 })
        y.put(4 * 8 + 6, 235.toByte())
        val neutral = ByteBuffer.wrap(ByteArray(4 * 3) { 128.toByte() })
        val rgb = DocumentFramePixels.rgb(listOf(DocumentFramePixels.Plane(y, 8, 1),
            DocumentFramePixels.Plane(neutral, 4, 1), DocumentFramePixels.Plane(neutral, 4, 1)), 1, 1, 6, 4, 3, 2)
        assertEquals(0, rgb[0].toInt() and 255)
        assertEquals(255, rgb[rgb.size - 1].toInt() and 255)
    }
}
