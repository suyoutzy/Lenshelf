package com.example.photocategorycamera.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BinaryContentVerifierTest {
    @Test
    fun acceptsIdenticalContentAcrossMultipleBuffers() {
        val content = ByteArray(150_000) { index -> (index % 251).toByte() }
        assertTrue(
            BinaryContentVerifier.sameContent(
                ByteArrayInputStream(content),
                ByteArrayInputStream(content.copyOf()),
            ),
        )
    }

    @Test
    fun rejectsDifferentBytes() {
        assertFalse(
            BinaryContentVerifier.sameContent(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                ByteArrayInputStream(byteArrayOf(1, 9, 3)),
            ),
        )
    }

    @Test
    fun rejectsDifferentLengths() {
        assertFalse(
            BinaryContentVerifier.sameContent(
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                ByteArrayInputStream(byteArrayOf(1, 2)),
            ),
        )
    }
}
