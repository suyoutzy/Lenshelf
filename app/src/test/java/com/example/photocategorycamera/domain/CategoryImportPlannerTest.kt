package com.example.photocategorycamera.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryImportPlannerTest {
    private fun category(name: String, active: Boolean, id: String = name) =
        Category(id, name, 0, active, 0)

    @Test fun createsNewAndSkipsExistingRegardlessOfActiveState() {
        val plan = CategoryImportPlanner.plan(
            directoryNames = listOf("旅行", "工作记录", "票据", "旅行"),
            existing = listOf(category("工作记录", true), category("票据", false)),
        )

        assertEquals(listOf("旅行"), plan.namesToCreate)
        assertEquals(2, plan.existingCount)
        assertEquals(1, plan.ignoredCount)
    }

    @Test fun ignoresHiddenAndInvalidDirectoryNames() {
        val plan = CategoryImportPlanner.plan(
            directoryNames = listOf(".cache", "错误/名称", "a".repeat(41), " 正常分类 "),
            existing = emptyList(),
        )

        assertEquals(listOf("正常分类"), plan.namesToCreate)
        assertEquals(3, plan.ignoredCount)
    }

    @Test fun leavesInactiveExistingCategoryInactive() {
        val plan = CategoryImportPlanner.plan(
            directoryNames = listOf("TRAVEL"),
            existing = listOf(category("Travel", false)),
        )

        assertEquals(emptyList<String>(), plan.namesToCreate)
        assertEquals(1, plan.existingCount)
    }
}
