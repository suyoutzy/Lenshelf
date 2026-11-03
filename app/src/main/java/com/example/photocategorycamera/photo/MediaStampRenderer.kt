package com.example.photocategorycamera.photo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Display-coordinate layout shared by JPEG, video overlay, and the small note preview. */
object MediaStampRenderer {
    fun draw(canvas: Canvas, width: Float, height: Float, text: String) {
        if (text.isBlank()) return
        val edge = minOf(width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = edge * .025f
            textAlign = Paint.Align.RIGHT
            strokeJoin = Paint.Join.ROUND
            strokeWidth = maxOf(1f, edge * .001f)
        }
        val lines = text.lines().flatMap { logical ->
            val wrapped = mutableListOf<String>()
            var remaining = logical
            while (remaining.isNotEmpty()) {
                var count = paint.breakText(remaining, true, width * .80f, null).coerceAtLeast(1)
                if (count < remaining.length && remaining[count].isLowSurrogate() && remaining[count - 1].isHighSurrogate()) {
                    count = if (count == 1) 2 else count - 1
                }
                wrapped += remaining.take(count)
                remaining = remaining.drop(count)
            }
            wrapped
        }
        val x = width - edge * .03f
        var y = height - edge * .03f - paint.fontMetrics.descent
        val lineHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 1.15f
        for (line in lines.asReversed()) {
            paint.style = Paint.Style.STROKE; paint.color = Color.BLACK
            canvas.drawText(line, x, y, paint)
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            canvas.drawText(line, x, y, paint)
            y -= lineHeight
        }
    }
}
