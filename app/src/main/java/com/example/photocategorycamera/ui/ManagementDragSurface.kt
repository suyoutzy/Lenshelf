package com.example.photocategorycamera.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class DragRowBounds(val partition: DragPartition, val bounds: Rect)
private data class DragDropBounds(val partition: DragPartition, val beforeId: String?, val bounds: Rect)
internal data class DragLift(val id: String, val bounds: Rect, val grab: Offset)

/** Shared geometry and gesture transaction for category and folder management. Bounds use root pixels. */
@Stable
internal class ManagementDragState(private val scope: CoroutineScope) {
    private val controller = ManagementDragController()
    private val rows = mutableMapOf<String, DragRowBounds>()
    private val targets = mutableMapOf<String, DragDropBounds>()
    private val entries = mutableMapOf<String, Rect>()
    var world: DragWorld = DragWorld(emptyList(), emptyList())
        private set
    var displaySession by mutableStateOf<DragSession?>(null)
        private set
    var previewGroupId by mutableStateOf<String?>(null)
        private set
    var dragging by mutableStateOf(false)
        private set
    var submitting by mutableStateOf(false)
        private set
    var settling by mutableStateOf(false)
        private set
    val blocking: Boolean get() = dragging || submitting || settling
    var lift by mutableStateOf<DragLift?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var overlayPosition by mutableStateOf(Offset.Zero)
        private set
    var settleFraction by mutableFloatStateOf(0f)
        private set
    var fadeOnSettle by mutableStateOf(false)
        private set
    var rootOrigin = Offset.Zero
    var viewport: Rect = Rect.Zero
    var trayViewport: Rect = Rect.Zero
    var edgeSizePx = 56f
    var menuWidthPx = 52f
    private var acknowledgedTransfers = emptySet<String>()
    private var validDrop = false
    private var accepted = false
    private var pendingDrop: ManagementDrop? = null
    private var settleJob: Job? = null
    var onDrop: (ManagementDrop, (Boolean) -> Unit) -> Unit = { _, done -> done(false) }

    fun update(world: DragWorld, acknowledgedTransfers: Set<String> = emptySet()) {
        this.world = world
        this.acknowledgedTransfers = acknowledgedTransfers.toSet()
        if (dragging && !controller.refresh(world)) {
            cancel()
            return
        }
        val drop = pendingDrop
        if (accepted && drop != null) {
            val echoed = when (drop) {
                is ManagementDrop.Reorder -> world.records.filter { it.partition == drop.source.partition }.map { it.id } != drop.originalIds
                is ManagementDrop.Transfer -> drop.source.id in acknowledgedTransfers || world.records.any {
                    it.id == drop.source.id && it.partition.groupId == drop.targetGroupId
                }
                else -> true
            }
            if (echoed) {
                pendingDrop = null
                displaySession = null
                submitting = false
            }
        }
    }

    fun followSelectedGroup(id: String) {
        if (!blocking && pendingDrop == null) previewGroupId = id
    }

    fun row(id: String, partition: DragPartition, bounds: Rect) { rows[id] = DragRowBounds(partition, bounds) }
    fun removeRow(id: String) { rows.remove(id) }
    fun target(key: String, partition: DragPartition, before: String?, bounds: Rect) {
        targets[key] = DragDropBounds(partition, before, bounds)
    }
    fun removeTarget(key: String) { targets.remove(key) }
    fun entry(id: String, bounds: Rect) { entries[id] = bounds }
    fun removeEntry(id: String) { entries.remove(id) }

    fun start(localPoint: Offset) {
        if (blocking) return
        val point = localPoint + rootOrigin
        val row = rows.entries.firstOrNull { (_, value) ->
            value.bounds.contains(point) && point.x < value.bounds.right - menuWidthPx && viewport.contains(point)
        } ?: return
        if (!controller.start(row.key, world)) return
        lift = DragLift(row.key, row.value.bounds, point - row.value.bounds.topLeft)
        pointer = point
        overlayPosition = row.value.bounds.topLeft
        settleFraction = 0f
        fadeOnSettle = false
        dragging = true
        previewGroupId = controller.session!!.previewGroupId
        displaySession = controller.session
        processPointer()
    }

    fun move(localPoint: Offset) {
        if (!dragging) return
        pointer = localPoint + rootOrigin
        processPointer()
    }

    /** Called every frame as well as every pointer event: edge scrolling also moves hover targets. */
    fun processPointer(nowMillis: Long? = null) {
        if (!dragging) return
        val current = controller.session ?: return
        val trayHit = trayViewport.contains(pointer)
        val entry = if (trayHit) entries.entries.firstOrNull { it.value.contains(pointer) }?.key else null
        // Pointer events have no frame timestamp. Mixing a synthetic zero with the
        // monotonic frame clock would complete the first dwell immediately.
        if (entry == null) controller.hover(null, 0L)
        if (nowMillis != null && controller.hover(entry, nowMillis)) {
            previewGroupId = controller.session!!.previewGroupId
            displaySession = controller.session
        }
        if (trayHit || !viewport.contains(pointer)) {
            validDrop = false
            return
        }
        val session = controller.session ?: return
        val partition = DragPartition(session.previewGroupId, session.source.partition.active)
        val hitRow = rows.entries.firstOrNull { it.value.bounds.contains(pointer) }
        validDrop = when {
            hitRow != null && hitRow.value.partition != partition -> false
            hitRow?.key == current.source.id -> session.hasValidPlacement
            hitRow != null -> {
                val candidates = world.records.filter { it.partition == partition && it.id != session.source.id }
                val row = hitRow.value.bounds
                // A small dead band around the midpoint avoids oscillation during placement animation.
                val midpoint = row.center.y
                val before = if (pointer.y < midpoint - 5f) hitRow.key
                else if (pointer.y > midpoint + 5f) candidates.getOrNull(candidates.indexOfFirst { it.id == hitRow.key } + 1)?.id
                else session.beforeId
                controller.place(partition, before)
            }
            else -> {
                val target = targets.values.firstOrNull { it.bounds.contains(pointer) }
                if (target != null) controller.place(target.partition, target.beforeId) else false
            }
        }
        displaySession = controller.session
    }

    fun orderedIds(partition: DragPartition, actual: List<String>): List<String> {
        val current = displaySession ?: return actual
        if (partition.groupId != current.previewGroupId || partition.active != current.source.partition.active || !current.hasValidPlacement) return actual
        val result = actual.filterNot { it == current.source.id }.toMutableList()
        val index = current.beforeId?.let(result::indexOf)?.takeIf { it >= 0 } ?: result.size
        result.add(index, current.source.id)
        return result
    }

    fun end() {
        if (!dragging) return
        processPointer()
        val sourceSession = displaySession
        val result = controller.release(validDrop)
        dragging = false
        if (result == ManagementDrop.Cancel) {
            restore(sourceSession)
            return
        }
        settleToRow()
        if (result == ManagementDrop.Unchanged) {
            displaySession = null
            return
        }
        submitting = true
        accepted = false
        pendingDrop = result
        onDrop(result) { success ->
            // Completion may arrive after leaving the screen. It only changes local presentation.
            if (pendingDrop != result) return@onDrop
            if (success) {
                accepted = true
                update(world, acknowledgedTransfers)
            } else {
                pendingDrop = null
                submitting = false
                restore(sourceSession)
            }
        }
    }

    fun cancel() {
        if (!dragging) return
        val current = displaySession
        controller.cancel()
        dragging = false
        restore(current)
    }

    private fun restore(session: DragSession?) {
        previewGroupId = session?.source?.partition?.groupId ?: previewGroupId
        displaySession = null
        settleToRow()
    }

    private fun settleToRow() {
        settleJob?.cancel()
        val lifted = lift ?: return
        val from = pointer - lifted.grab
        overlayPosition = from
        settling = true
        settleFraction = 0f
        settleJob = scope.launch {
            // Let a restored source group or the new insertion slot finish its layout first.
            withFrameNanos { }
            val destination = rows[lifted.id]?.takeIf { it.bounds.overlaps(viewport) }?.bounds?.topLeft
            fadeOnSettle = destination == null
            val to = destination ?: from
            val fraction = Animatable(0f)
            fraction.animateTo(1f, tween(180)) {
                settleFraction = value
                overlayPosition = from + (to - from) * value
            }
            settling = false
            lift = null
        }
    }

    fun dispose() {
        controller.cancel()
        dragging = false
        settleJob?.cancel()
        lift = null
    }

    fun verticalScrollVelocity(): Float = if (!dragging || trayViewport.contains(pointer) || pointer.x !in viewport.left..viewport.right) 0f
        else edgeVelocity(pointer.y, viewport.top, viewport.bottom)
    fun horizontalScrollVelocity(): Float = if (!dragging || !trayViewport.contains(pointer)) 0f
        else edgeVelocity(pointer.x, trayViewport.left, trayViewport.right)
    private fun edgeVelocity(position: Float, start: Float, end: Float): Float = when {
        position < start || position > end -> 0f
        position < start + edgeSizePx -> -900f * ((start + edgeSizePx - position) / edgeSizePx).coerceIn(0f, 1f)
        position > end - edgeSizePx -> 900f * ((position - end + edgeSizePx) / edgeSizePx).coerceIn(0f, 1f)
        else -> 0f
    }
}

@Composable
internal fun rememberManagementDragState(): ManagementDragState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ManagementDragState(scope) }
}

@Composable
internal fun ManagementDragSurface(
    state: ManagementDragState,
    listState: LazyListState,
    trayState: LazyListState? = null,
    modifier: Modifier = Modifier,
    liftedContent: @Composable (String) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    SideEffect {
        state.edgeSizePx = with(density) { 56.dp.toPx() }
        // Both card rows have a 48 dp IconButton and 4 dp trailing padding.
        state.menuWidthPx = with(density) { 52.dp.toPx() }
    }
    BackHandler(state.dragging) { state.cancel() }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) state.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.dispose() }
    }
    LaunchedEffect(state.dragging) {
        if (!state.dragging) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (state.dragging) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
            previous = now
            state.processPointer(now / 1_000_000)
            val dy = state.verticalScrollVelocity() * seconds
            val dx = state.horizontalScrollVelocity() * seconds
            if (dy != 0f) listState.scrollBy(dy)
            if (dx != 0f) trayState?.scrollBy(dx)
            state.processPointer(now / 1_000_000)
        }
    }
    Box(modifier.testTag("management-drag-root")
        .onGloballyPositioned { state.rootOrigin = it.boundsInRoot().topLeft }
        .pointerInput(state) {
            detectDragGesturesAfterLongPress(
                onDragStart = state::start, onDragEnd = state::end, onDragCancel = state::cancel,
                onDrag = { change, _ -> if (state.dragging) { change.consume(); state.move(change.position) } },
            )
        }) {
        content()
        state.lift?.let { lift ->
            val position = (if (state.dragging) state.pointer - lift.grab else state.overlayPosition) - state.rootOrigin
            val scale = remember(lift) { Animatable(1f) }
            LaunchedEffect(lift, state.dragging) {
                scale.animateTo(if (state.dragging) 1.03f else 1f, tween(180))
            }
            Box(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .width(with(density) { lift.bounds.width.toDp() }).height(with(density) { lift.bounds.height.toDp() })
                .zIndex(10f).graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value
                    transformOrigin = TransformOrigin((lift.grab.x / lift.bounds.width).coerceIn(0f, 1f),
                        (lift.grab.y / lift.bounds.height).coerceIn(0f, 1f))
                    shadowElevation = with(density) { 8.dp.toPx() }
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    alpha = if (state.fadeOnSettle) 1f - state.settleFraction else 1f
                }) { liftedContent(lift.id) }
        }
    }
}

@Composable
internal fun Modifier.dragItem(state: ManagementDragState, id: String, partition: DragPartition): Modifier {
    DisposableEffect(state, id) { onDispose { state.removeRow(id) } }
    return testTag("drag-item-$id").onGloballyPositioned { state.row(id, partition, it.boundsInRoot()) }
}

@Composable
internal fun Modifier.dragDropArea(state: ManagementDragState, key: String, partition: DragPartition, before: String?): Modifier {
    DisposableEffect(state, key) { onDispose { state.removeTarget(key) } }
    return testTag(key).onGloballyPositioned { state.target(key, partition, before, it.boundsInRoot()) }
}

@Composable
internal fun Modifier.dragGroupEntry(state: ManagementDragState, id: String): Modifier {
    DisposableEffect(state, id) { onDispose { state.removeEntry(id) } }
    return testTag("drag-group-$id").onGloballyPositioned { state.entry(id, it.boundsInRoot()) }
}
