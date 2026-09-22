package com.example.photocategorycamera.domain

import java.io.InputStream

object BinaryContentVerifier {
    private const val BUFFER_SIZE = 64 * 1024

    fun sameContent(first: InputStream, second: InputStream): Boolean {
        val firstBuffer = ByteArray(BUFFER_SIZE)
        val secondBuffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val firstCount = first.readChunk(firstBuffer)
            val secondCount = second.readChunk(secondBuffer)
            if (firstCount != secondCount) return false
            if (firstCount == -1) return true
            for (index in 0 until firstCount) {
                if (firstBuffer[index] != secondBuffer[index]) return false
            }
        }
    }

    private fun InputStream.readChunk(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = read(buffer, total, buffer.size - total)
            if (count == -1) return if (total == 0) -1 else total
            if (count == 0) continue
            total += count
        }
        return total
    }
}
