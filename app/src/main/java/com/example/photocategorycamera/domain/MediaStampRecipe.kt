package com.example.photocategorycamera.domain

object PostCaptureNote {
    const val MAX_CHARACTERS = 60
    fun count(text: String): Int = text.codePointCount(0, text.length)
    fun validate(text: String): Result<String> = runCatching {
        require(count(text) <= MAX_CHARACTERS) { "备注最多60个字符" }
        require(text.none { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }) { "备注请填写为一段文字" }
        text
    }
}

data class MediaStampRecipe(val timeText: String? = null, val note: String = "", val version: Int = 2) {
    init { PostCaptureNote.validate(note).getOrThrow() }
    val hasContent: Boolean get() = !timeText.isNullOrBlank() || note.isNotBlank()
    val text: String get() = listOfNotNull(note.trim().takeIf { it.isNotEmpty() }, timeText?.takeIf { it.isNotBlank() }).joinToString("\n")
}
