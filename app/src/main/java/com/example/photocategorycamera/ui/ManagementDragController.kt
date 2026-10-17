package com.example.photocategorycamera.ui

internal data class DragPartition(val groupId: String, val active: Boolean = true)
internal data class DragRecord(val id: String, val partition: DragPartition, val revision: String = id)
internal data class DragWorld(
    val records: List<DragRecord>,
    val groups: List<String>,
    val lockedIds: Set<String> = emptySet(),
    val blockedGroups: Set<String> = emptySet(),
    val busy: Boolean = false,
    val crossGroupEnabled: Boolean = true,
)
internal data class DragSession(
    val source: DragRecord,
    val originalIds: List<String>,
    val previewGroupId: String,
    val beforeId: String?,
    val hasValidPlacement: Boolean,
)
internal sealed interface ManagementDrop {
    data object Cancel : ManagementDrop
    data object Unchanged : ManagementDrop
    data class Reorder(val source: DragRecord, val originalIds: List<String>, val proposedIds: List<String>) : ManagementDrop
    data class Transfer(val source: DragRecord, val targetGroupId: String, val beforeId: String?) : ManagementDrop
}

/** Gesture transaction only: persistence is called by the screen after release. */
internal class ManagementDragController {
    var session: DragSession? = null
        private set
    private var originalWorld: DragWorld? = null
    private var world: DragWorld? = null
    private var hoveredGroup: String? = null
    private var hoverStartedAt = 0L

    fun start(id: String, world: DragWorld): Boolean {
        if (session != null || world.busy) return false
        val source = world.records.firstOrNull { it.id == id } ?: return false
        if (id in world.lockedIds || source.partition.groupId in world.blockedGroups) return false
        val snapshot = world.copy(records = world.records.toList(), groups = world.groups.toList(),
            lockedIds = world.lockedIds.toSet(), blockedGroups = world.blockedGroups.toSet())
        this.world = snapshot
        originalWorld = snapshot
        val ids = snapshot.records.filter { it.partition == source.partition }.map { it.id }
        session = DragSession(source, ids, source.partition.groupId, ids.getOrNull(ids.indexOf(id) + 1), true)
        hoveredGroup = null
        return true
    }

    /** A gesture may never overwrite a new source snapshot or a newly locked row. */
    fun refresh(world: DragWorld): Boolean {
        val current = session ?: return false
        val original = originalWorld ?: return false
        val relevantGroups = setOf(current.source.partition.groupId, current.previewGroupId)
        val before = original.records.filter { it.partition.groupId in relevantGroups }
        val after = world.records.filter { it.partition.groupId in relevantGroups }
        val relevantIds = before.map { it.id }.toSet()
        if (world.busy || before != after || world.groups != original.groups ||
            world.blockedGroups.any { it in relevantGroups } ||
            world.lockedIds.intersect(relevantIds) != original.lockedIds.intersect(relevantIds) ||
            (current.previewGroupId != current.source.partition.groupId && !world.crossGroupEnabled)) {
            cancel()
            return false
        }
        this.world = world.copy(records = world.records.toList(), groups = world.groups.toList(),
            lockedIds = world.lockedIds.toSet(), blockedGroups = world.blockedGroups.toSet())
        return true
    }

    /** Only navigation occurs at the dwell boundary. Releasing on an entry is never a drop. */
    fun hover(groupId: String?, nowMillis: Long): Boolean {
        val current = session ?: return false
        val data = world ?: return false
        val eligible = groupId?.takeIf { it in data.groups && it !in data.blockedGroups &&
            (it == current.source.partition.groupId || data.crossGroupEnabled) }
        if (eligible == null || eligible == current.previewGroupId) {
            hoveredGroup = null
            return false
        }
        if (hoveredGroup != eligible) {
            hoveredGroup = eligible
            hoverStartedAt = nowMillis
            return false
        }
        if (nowMillis - hoverStartedAt < 600L) return false
        session = current.copy(previewGroupId = eligible, beforeId = null, hasValidPlacement = false)
        hoveredGroup = null
        return true
    }

    fun place(partition: DragPartition, beforeId: String?): Boolean {
        val current = session ?: return false
        val data = world ?: return false
        val valid = partition.groupId == current.previewGroupId && partition.active == current.source.partition.active &&
            partition.groupId !in data.blockedGroups && (beforeId == null || data.records.any {
                it.id == beforeId && it.id != current.source.id && it.partition == partition
            })
        session = if (valid) current.copy(beforeId = beforeId, hasValidPlacement = true)
        else current.copy(hasValidPlacement = false)
        return valid
    }

    fun release(insideSection: Boolean): ManagementDrop {
        val current = session
        val result = if (current == null || !insideSection || !current.hasValidPlacement) ManagementDrop.Cancel
        else if (current.previewGroupId != current.source.partition.groupId) {
            ManagementDrop.Transfer(current.source, current.previewGroupId, current.beforeId)
        } else {
            val proposed = current.originalIds.filterNot { it == current.source.id }.toMutableList()
            val insertion = current.beforeId?.let(proposed::indexOf)?.takeIf { it >= 0 } ?: proposed.size
            proposed.add(insertion, current.source.id)
            if (proposed == current.originalIds) ManagementDrop.Unchanged
            else ManagementDrop.Reorder(current.source, current.originalIds, proposed.toList())
        }
        cancel()
        return result
    }

    fun cancel() {
        session = null
        originalWorld = null
        world = null
        hoveredGroup = null
    }
}
