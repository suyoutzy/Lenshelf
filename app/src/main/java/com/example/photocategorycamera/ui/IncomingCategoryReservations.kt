package com.example.photocategorycamera.ui

import com.example.photocategorycamera.domain.Category

internal data class IncomingCategoryReservation(val operation: DirectoryOperationUiState, val category: Category)

internal fun retainIncomingCategoryReservations(
    previous: List<IncomingCategoryReservation>,
    operations: List<DirectoryOperationUiState>,
    categories: List<Category>,
): List<IncomingCategoryReservation> {
    val retained = previous.associateBy { it.category.id }.toMutableMap()
    operations.filter { it.kind == "TRANSFER_CATEGORY" && it.targetGroupId != null }.forEach { operation ->
        val category = categories.firstOrNull { it.id == operation.categoryId }
            ?: retained[operation.categoryId]?.category
        if (category != null) retained[category.id] = IncomingCategoryReservation(operation, category)
    }
    // Independent Room flows may clear the operation before emitting the committed category.
    // Only that category echo can retire its presentation; elapsed time is not a commit signal.
    return retained.values.filter { reservation -> categories.none {
        it.id == reservation.category.id && it.groupId == reservation.operation.targetGroupId
    } }
}
