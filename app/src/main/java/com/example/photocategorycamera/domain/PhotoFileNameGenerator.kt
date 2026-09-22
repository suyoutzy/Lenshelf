package com.example.photocategorycamera.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoFileNameGenerator {
    private val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    @Synchronized
    fun baseName(
        nowMillis: Long = System.currentTimeMillis(),
        motionPhoto: Boolean = false,
    ): String = "IMG_${formatter.format(Date(nowMillis))}${if (motionPhoto) "_MP" else ""}.jpg"

    @Synchronized
    fun baseName(
        mediaKind: MediaKind,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = when (mediaKind) {
        MediaKind.PHOTO -> baseName(nowMillis)
        MediaKind.MOTION_PHOTO -> baseName(nowMillis, motionPhoto = true)
        MediaKind.VIDEO -> "VID_${formatter.format(Date(nowMillis))}.mp4"
    }

    fun candidate(baseName: String, suffix: Int): String {
        if (suffix <= 0) return baseName
        val extensionIndex = baseName.lastIndexOf('.')
        return if (extensionIndex > 0) {
            "${baseName.substring(0, extensionIndex)}_$suffix${baseName.substring(extensionIndex)}"
        } else {
            "${baseName}_$suffix"
        }
    }
}
