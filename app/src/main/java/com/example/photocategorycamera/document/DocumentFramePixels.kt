package com.example.photocategorycamera.document

import java.nio.ByteBuffer

/** Sample the actual crop, including both endpoints; respect independent Y/U/V padding and offsets. */
internal object DocumentFramePixels {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int) {
        private val origin = buffer.position()
        fun at(x: Int, y: Int): Int = buffer.get(origin + y * rowStride + x * pixelStride).toInt() and 255
    }

    fun rgb(planes: List<Plane>, left: Int, top: Int, cropWidth: Int, cropHeight: Int, width: Int, height: Int): ByteArray {
        require(planes.size == 3 && width > 1 && height > 1 && cropWidth > 1 && cropHeight > 1)
        val output = ByteArray(width * height * 3)
        for (row in 0 until height) {
            val y = top + row * (cropHeight - 1) / (height - 1)
            for (column in 0 until width) {
                val x = left + column * (cropWidth - 1) / (width - 1)
                val luminance = (planes[0].at(x, y) - 16).coerceAtLeast(0)
                val u = planes[1].at(x / 2, y / 2) - 128
                val v = planes[2].at(x / 2, y / 2) - 128
                val offset = (row * width + column) * 3
                output[offset] = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255).toByte()
                output[offset + 1] = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255).toByte()
                output[offset + 2] = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255).toByte()
            }
        }
        return output
    }
}
