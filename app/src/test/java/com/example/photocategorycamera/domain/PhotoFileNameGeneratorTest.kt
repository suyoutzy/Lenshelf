package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFileNameGeneratorTest {
    @Test
    fun producesExpectedTimestampName() {
        val name = PhotoFileNameGenerator.baseName(0L)
        assertTrue(name.matches(Regex("IMG_\\d{8}_\\d{6}_000\\.jpg")))
    }

    @Test
    fun addsSuffixBeforeExtension() {
        assertEquals("IMG_20260101_120000_000_3.jpg", PhotoFileNameGenerator.candidate("IMG_20260101_120000_000.jpg", 3))
    }

    @Test
    fun motionPhotoNameEndsWithMpMarker() {
        val name = PhotoFileNameGenerator.baseName(0L, motionPhoto = true)
        assertTrue(name.matches(Regex("IMG_\\d{8}_\\d{6}_000_MP\\.jpg")))
    }

    @Test
    fun videoNameUsesVidPrefixAndMp4Extension() {
        val name = PhotoFileNameGenerator.baseName(MediaKind.VIDEO, 0L)
        assertTrue(name.matches(Regex("VID_\\d{8}_\\d{6}_000\\.mp4")))
    }

    @Test
    fun videoCollisionSuffixStaysBeforeExtension() {
        assertEquals(
            "VID_20260101_120000_000_2.mp4",
            PhotoFileNameGenerator.candidate("VID_20260101_120000_000.mp4", 2),
        )
    }

    @Test
    fun zeroSuffixKeepsOriginalName() {
        assertEquals("photo.jpg", PhotoFileNameGenerator.candidate("photo.jpg", 0))
    }
}
