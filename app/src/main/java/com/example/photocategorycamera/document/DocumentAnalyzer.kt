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
data class DocumentAnalysisResult(val quad: DocumentQuad?, val crop: Rect, val transform: OutputTransform)

/** Reads only luminance, caps analysis at 5 Hz and always returns the image to CameraX. */
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
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            val pixels = ByteArray(width * height)
            for (row in 0 until height) {
                val offset = base + (crop.top + row * step) * plane.rowStride + crop.left * plane.pixelStride
                for (column in 0 until width) pixels[row * width + column] = buffer.get(offset + column * step * plane.pixelStride)
            }
            val quad = stabilizer.update(processor.detectLuminance(pixels, width, height))
            onResult(DocumentAnalysisResult(quad, Rect(crop), transformFactory.getOutputTransform(image)))
        } catch (error: Exception) {
            reportError(error)
        } catch (error: LinkageError) {
            reportError(error)
        } finally { image.close() }
    }

    private fun reportError(error: Throwable) {
        if (!errorReported) {
            errorReported = true
            onError(error.message ?: "实时识别暂不可用，仍可拍摄并手动调整")
        }
    }
}
