package com.example.photocategorycamera.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TimeStampFormat(val label: String) {
    DATE("仅日期"), DATE_TIME("日期和时分"),
}

typealias PhotoDateStampFormat = TimeStampFormat

/** The resulting text is persisted at reservation, never formatted again during saving. */
object PhotoDateStamp {
    fun format(timestampMs: Long, zone: ZoneId, format: PhotoDateStampFormat): String =
        DateTimeFormatter.ofPattern(
            if (format == PhotoDateStampFormat.DATE) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm",
            Locale.ROOT,
        ).withZone(zone).format(Instant.ofEpochMilli(timestampMs))
}
