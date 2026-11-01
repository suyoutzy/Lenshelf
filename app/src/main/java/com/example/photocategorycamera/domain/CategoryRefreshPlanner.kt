package com.example.photocategorycamera.domain

import java.util.Locale

data class CategoryRefreshPlan(
    val namesToCreate: List<String>,
    val idsToRemove: List<String>,
    val deferredIds: List<String>,
    val retainedCount: Int,
    val ignoredCount: Int,
)

/** Called only with a complete directory listing; never infers absence from a failed read. */
object CategoryRefreshPlanner {
    fun plan(names: List<String>, existing: List<Category>, protectedIds: Set<String>): CategoryRefreshPlan {
        val present = names.toSet()
        require(present.groupBy { it.lowercase(Locale.ROOT) }.values.none { it.size > 1 }) {
            "存在仅大小写不同的同名目录，请先处理名称冲突后刷新"
        }
        val missing = existing.filter { it.name !in present }
        val removed = missing.filterNot { it.id in protectedIds }.map { it.id }
        // Provider names are paths. Never turn a padded external name into another directory.
        val importable = names.filter { name ->
            val validation = CategoryNameValidator.validate(name)
            validation.isValid && validation.normalizedName == name && !name.startsWith('.')
        }
        val imported = CategoryImportPlanner.plan(importable, existing.filterNot { it.id in removed })
        return CategoryRefreshPlan(imported.namesToCreate, removed,
            missing.filter { it.id in protectedIds }.map { it.id },
            existing.size - missing.size, names.size - importable.size + imported.ignoredCount)
    }
}
