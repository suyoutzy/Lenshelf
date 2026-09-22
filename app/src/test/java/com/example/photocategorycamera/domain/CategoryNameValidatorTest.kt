package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryNameValidatorTest {
    @Test
    fun trimsAValidName() {
        val result = CategoryNameValidator.validate("  工作记录  ")
        assertTrue(result.isValid)
        assertEquals("工作记录", result.normalizedName)
    }

    @Test
    fun rejectsBlankName() {
        assertFalse(CategoryNameValidator.validate("   ").isValid)
    }

    @Test
    fun rejectsPathSeparatorsAndControlCharacters() {
        listOf("工作/记录", "工作\\记录", "工作\n记录").forEach {
            assertFalse("Expected invalid: $it", CategoryNameValidator.validate(it).isValid)
        }
    }

    @Test
    fun rejectsNamesLongerThanFortyCharacters() {
        assertFalse(CategoryNameValidator.validate("a".repeat(41)).isValid)
        assertTrue(CategoryNameValidator.validate("a".repeat(40)).isValid)
    }
}
