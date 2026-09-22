package com.example.photocategorycamera.domain

import java.util.Locale

data class CategoryImportPlan(
    val namesToCreate: List<String>,
    val existingCount: Int,
    val ignoredCount: Int,
)

/** Plans a non-destructive import from the authorized root's direct child directories. */
object CategoryImportPlanner {
    fun plan(directoryNames: List<String>, existing: List<Category>): CategoryImportPlan {
        val existingByName = existing.associateBy { key(it.name) }
        val seen = mutableSetOf<String>()
        val namesToCreate = mutableListOf<String>()
        var existingCount = 0
        var ignoredCount = 0

        directoryNames.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { rawName ->
            val validation = CategoryNameValidator.validate(rawName)
            val normalized = validation.normalizedName
            val normalizedKey = key(normalized)
            if (!validation.isValid || normalized.startsWith('.') || !seen.add(normalizedKey)) {
                ignoredCount++
                return@forEach
            }
            val category = existingByName[normalizedKey]
            if (category == null) namesToCreate += normalized else existingCount++
        }
        return CategoryImportPlan(namesToCreate, existingCount, ignoredCount)
    }

    private fun key(name: String) = name.lowercase(Locale.ROOT)
}
