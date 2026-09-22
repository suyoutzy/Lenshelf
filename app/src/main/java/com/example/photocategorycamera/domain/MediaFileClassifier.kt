package com.example.photocategorycamera.domain

/** Count Motion Photo JPEGs once, and ignore sidecars, hidden files and folders. */
object MediaFileClassifier {
    private val extensions = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif", "bmp", "dng",
        "mp4", "m4v", "mov", "3gp", "3gpp", "mkv", "webm", "avi", "mpeg", "mpg",
    )

    fun isMedia(name: String, mimeType: String?): Boolean {
        if (name.startsWith(".") || mimeType == "vnd.android.document/directory") return false
        val mime = mimeType?.lowercase(java.util.Locale.ROOT)
        return mime?.startsWith("image/") == true || mime?.startsWith("video/") == true ||
            name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in extensions
    }
}
