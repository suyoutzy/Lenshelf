package com.example.photocategorycamera.ui

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.photocategorycamera.document.DocumentCapturePreview
import com.example.photocategorycamera.domain.DocumentPoint
import com.example.photocategorycamera.domain.DocumentPreviewGeometry
import kotlinx.coroutines.delay

data class DocumentDisplay(val preview: DocumentCapturePreview, val sourceProjection: FloatArray?)

/** No pointer handlers: this transient overlay never blocks another shutter press. */
@Composable
internal fun DocumentCaptureOverlay(display: DocumentDisplay, onFinished: () -> Unit) {
    val unfolding = remember(display.preview.id) { Animatable(0f) }
    val opacity = remember(display.preview.id) { Animatable(1f) }
    LaunchedEffect(display.preview.id) {
        unfolding.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        delay(420)
        opacity.animateTo(0f, tween(160))
        onFinished()
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    Canvas(Modifier.fillMaxSize().zIndex(1f).semantics { contentDescription = "文档成片快速预览" }) {
        val preview = display.preview
        val projection = display.sourceProjection
        val originalCorners = if (projection != null && projection.size == 8 && projection.all { it.isFinite() }) {
            (0..3).map { DocumentPoint(projection[it * 2] * size.width, projection[it * 2 + 1] * size.height) }
        } else DocumentPreviewGeometry.flatCorners(preview.source.width, preview.source.height, size.width, size.height)
        fun bitmapCorners(width: Int, height: Int) = floatArrayOf(0f, 0f, width.toFloat(), 0f,
            width.toFloat(), height.toFloat(), 0f, height.toFloat())
        fun coordinates(points: List<DocumentPoint>) = points.flatMap { listOf(it.x, it.y) }.toFloatArray()
        val originalMatrix = Matrix().apply { setPolyToPoly(bitmapCorners(preview.source.width, preview.source.height), 0,
            coordinates(originalCorners), 0, 4) }
        val cropCorners = preview.quad.points.flatMap { listOf(it.x * preview.source.width, it.y * preview.source.height) }.toFloatArray()
        originalMatrix.mapPoints(cropCorners)
        val start = (0..3).map { DocumentPoint(cropCorners[it * 2], cropCorners[it * 2 + 1]) }
        val end = DocumentPreviewGeometry.flatCorners(preview.result.width, preview.result.height, size.width, size.height, originalCorners)
        val current = DocumentPreviewGeometry.interpolate(start, end, unfolding.value)
        val resultMatrix = Matrix().apply { setPolyToPoly(bitmapCorners(preview.result.width, preview.result.height), 0,
            coordinates(current), 0, 4) }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val layer = native.saveLayerAlpha(0f, 0f, size.width, size.height, (255 * opacity.value).toInt())
            native.drawColor(android.graphics.Color.BLACK)
            paint.alpha = (255 * (1f - unfolding.value)).toInt()
            native.drawBitmap(preview.source, originalMatrix, paint)
            paint.alpha = 255
            native.drawBitmap(preview.result, resultMatrix, paint)
            native.restoreToCount(layer)
        }
    }
}
