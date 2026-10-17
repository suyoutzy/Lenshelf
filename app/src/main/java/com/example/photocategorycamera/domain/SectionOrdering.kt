package com.example.photocategorycamera.domain

/** Validates the complete ordered snapshot before any persistent order is changed. */
object SectionOrdering {
    fun validate(currentIds: List<String>, expectedIds: List<String>, orderedIds: List<String>) {
        check(currentIds == expectedIds) { "列表已变化，请重新排序" }
        require(orderedIds.size == currentIds.size && orderedIds.distinct().size == orderedIds.size &&
            currentIds.distinct().size == currentIds.size && orderedIds.toSet() == currentIds.toSet()) {
            "排序必须包含当前分区的全部项目，且不能重复"
        }
    }
}
