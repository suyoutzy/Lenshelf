package com.example.photocategorycamera.domain

import org.junit.Assert.*
import org.junit.Test

class MediaStampRecipeTest {
    @Test fun noteOnlyRecipeHasNoBlankTimestampLine() {
        assertEquals("设备已检查", MediaStampRecipe(note = "设备已检查").text)
        assertTrue(MediaStampRecipe(note = "设备已检查").hasContent)
        assertEquals("设备已检查\n2026-11-03 10:00", MediaStampRecipe("2026-11-03 10:00", "设备已检查").text)
        assertFalse(MediaStampRecipe().hasContent)
    }

    @Test fun sixtySupplementaryCharactersAreAcceptedWithoutSplittingSurrogatePairs() {
        assertEquals(60, PostCaptureNote.count("😀".repeat(60)))
        assertTrue(PostCaptureNote.validate("😀".repeat(60)).isSuccess)
        assertTrue(PostCaptureNote.validate("😀".repeat(61)).isFailure)
        assertTrue(PostCaptureNote.validate("第一行\n第二行").isFailure)
    }
}
