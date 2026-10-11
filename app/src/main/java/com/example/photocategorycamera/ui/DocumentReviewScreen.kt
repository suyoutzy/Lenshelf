package com.example.photocategorycamera.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.photocategorycamera.data.DocumentDraftEntity
import com.example.photocategorycamera.domain.DocumentEffect
import com.example.photocategorycamera.domain.DocumentPoint
import com.example.photocategorycamera.domain.DocumentQuad
import kotlin.math.hypot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentReviewScreen(
    viewModel: DocumentViewModel,
    onBack: () -> Unit,
    onRetake: (DocumentDraftEntity) -> Unit,
    onQueued: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft
    var adjustCorners by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler { viewModel.leave(onBack) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("丢弃文档草稿？") },
            text = { Text("只删除这张尚未归档的文档及其临时原图，不影响分类文件夹中的照片。") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discard(onBack) }) { Text("丢弃") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("取消") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("确认文档")
                    Text(draft?.categoryName ?: "待确认草稿", style = MaterialTheme.typography.labelMedium)
                } },
                navigationIcon = { IconButton(onClick = { viewModel.leave(onBack) }, enabled = !state.saving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回首页，保留草稿")
                } },
                actions = { TextButton(onClick = { confirmDiscard = true }, enabled = draft != null && !state.saving) { Text("丢弃") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("确认保存前不会归档；返回后可从首页继续。", style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF181818)), contentAlignment = Alignment.Center) {
                if (adjustCorners && draft != null && state.sourcePreview != null) {
                    DocumentCornerEditor(state.sourcePreview!!, draft.quad, !state.saving) { viewModel.edit(quad = it) }
                } else {
                    state.processedPreview?.let {
                        Image(it.asImageBitmap(), "矫正后的文档预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
                if (state.processing || state.saving) CircularProgressIndicator(Modifier.size(36.dp))
                if (!state.processing && state.sourcePreview == null) Text("原图不可用，可重拍或丢弃", color = Color.White)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DocumentEffect.entries.forEach { effect ->
                    FilterChip(selected = draft?.effect == effect, enabled = draft != null && !state.saving,
                        onClick = { viewModel.edit(effect = effect) }, label = { Text(effect.label) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { adjustCorners = !adjustCorners }, enabled = state.sourcePreview != null && !state.saving) {
                    Text(if (adjustCorners) "查看效果" else "调整四角")
                }
                TextButton(onClick = { draft?.let { viewModel.edit(quarterTurns = it.quarterTurns + 1) } },
                    enabled = draft != null && !state.saving) { Text("旋转 90°") }
                TextButton(onClick = { draft?.let { viewModel.leave { onRetake(it) } } }, enabled = draft != null && !state.saving) { Text("重拍") }
            }
            if (adjustCorners) Text("拖动四个圆点调整范围；旋转应用于矫正结果。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { viewModel.save(onQueued) },
                enabled = draft != null && state.processedPreview != null && !state.processing && !state.saving,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(if (state.saving) "正在生成并提交…" else "确认保存")
            }
        }
    }
}

@Composable
private fun DocumentCornerEditor(bitmap: Bitmap, quad: DocumentQuad, enabled: Boolean, onChange: (DocumentQuad) -> Unit) {
    var localQuad by remember(bitmap, quad) { mutableStateOf(quad) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val touchRadius = with(LocalDensity.current) { 48.dp.toPx() }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "文档四角调整区域" }.onSizeChanged { canvasSize = it }
        .pointerInput(bitmap, canvasSize, enabled, quad) {
            if (!enabled || canvasSize.width == 0 || canvasSize.height == 0) return@pointerInput
            val scale = minOf(canvasSize.width.toFloat() / bitmap.width, canvasSize.height.toFloat() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = (canvasSize.width - w) / 2
            val top = (canvasSize.height - h) / 2
            var selected: Int? = null
            detectDragGestures(
                onDragStart = { touch ->
                    selected = localQuad.points.indices.minByOrNull { index ->
                        val p = localQuad.points[index]
                        hypot(touch.x - left - p.x * w, touch.y - top - p.y * h)
                    }?.takeIf { index ->
                        val p = localQuad.points[index]
                        hypot(touch.x - left - p.x * w, touch.y - top - p.y * h) <= touchRadius
                    }
                },
                onDragEnd = { if (selected != null) onChange(localQuad); selected = null },
                onDragCancel = { selected = null; localQuad = quad },
            ) { change, delta ->
                selected?.let { index ->
                    change.consume()
                    val point = localQuad.points[index]
                    localQuad = localQuad.move(index, DocumentPoint(point.x + delta.x / w, point.y + delta.y / h))
                }
            }
        }) {
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawImage(image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
        val points = localQuad.points.map { Offset(left + it.x * w, top + it.y * h) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, Color(0xFFFFD34E), style = Stroke(2.dp.toPx()))
        points.forEach { drawCircle(Color(0xFFFFD34E), 8.dp.toPx(), it); drawCircle(Color.Black, 4.dp.toPx(), it) }
    }
}
