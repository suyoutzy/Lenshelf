package com.example.photocategorycamera.document

import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.example.photocategorycamera.domain.DocumentOutlineStabilizer
import com.example.photocategorycamera.domain.DocumentQuad

@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
data class DocumentAnalysisResult(val quad: DocumentQuad?, val crop: Rect, val transform: OutputTransform, val analyzedAtMs: Long)

/** Local colour/edge detection, capped at 5 Hz; always returns the image to CameraX. */
@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
class DocumentAnalyzer(
    private val processor: DocumentProcessor,
    private val onResult: (DocumentAnalysisResult) -> Unit,
    private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastAnalysis = -200L
    private var errorReported = false
    private val stabilizer = DocumentOutlineStabilizer()
    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingCropRect = true
        isUsingRotationDegrees = false
    }

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysis < 200L) return
            lastAnalysis = now
            val crop = image.cropRect
            val step = ((maxOf(crop.width(), crop.height()) + 959) / 960).coerceAtLeast(1)
            val width = crop.width() / step
            val height = crop.height() / step
            if (width < 2 || height < 2) return
            val pixels = DocumentFramePixels.rgb(image.planes.map {
                DocumentFramePixels.Plane(it.buffer.duplicate(), it.rowStride, it.pixelStride)
            }, crop.left, crop.top, crop.width(), crop.height(), width, height)
            val candidate = processor.detectRgb(pixels, width, height, stabilizer.trackingHint)
            val quad = stabilizer.update(candidate, now)
            onResult(DocumentAnalysisResult(quad, Rect(crop), transformFactory.getOutputTransform(image), stabilizer.observedAtMs))
        } catch (error: Exception) {
            reportError(error)
        } catch (error: LinkageError) {
            reportError(error)
        } finally { image.close() }
    }

    private fun reportError(error: Throwable) {
        if (!errorReported) {
            errorReported = true
            onError(error.message ?: "实时识别暂不可用，拍后仍会识别；无边界时保留全图")
        }
    }
}
