package com.example.photocategorycamera.domain

object XiaomiFileManagerPath {
    fun primaryStorageSegments(documentId: String): List<String>? {
        val separator = documentId.indexOf(':')
        if (separator <= 0 || !documentId.substring(0, separator).equals("primary", ignoreCase = true)) {
            return null
        }
        val relativePath = documentId.substring(separator + 1)
        if (relativePath.isBlank()) return null
        return relativePath.split('/').filter(String::isNotBlank)
    }
}
