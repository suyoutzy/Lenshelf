package com.example.photocategorycamera.document

import android.graphics.Bitmap
import com.example.photocategorycamera.domain.DocumentQuad

/** Small, ephemeral display copies; never the files owned by the durable saving pipeline. */
data class DocumentCapturePreview(val id: String, val source: Bitmap, val result: Bitmap, val quad: DocumentQuad) {
    fun recycleUnpublished() { source.recycle(); result.recycle() }
}
