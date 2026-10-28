package com.example.photocategorycamera.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PhotoDateStampTest {
    @Test fun formatsLocalDateAndOptionalTwentyFourHourTime() {
        val instant = Instant.parse("2026-10-28T06:35:00Z").toEpochMilli()
        assertEquals("2026-10-28", PhotoDateStamp.format(instant, ZoneId.of("Asia/Shanghai"), PhotoDateStampFormat.DATE))
        assertEquals("2026-10-28 14:35", PhotoDateStamp.format(instant, ZoneId.of("Asia/Shanghai"), PhotoDateStampFormat.DATE_TIME))
    }

    @Test fun timeZoneAndMidnightAreResolvedAtCapture() {
        val instant = Instant.parse("2026-10-28T16:01:00Z").toEpochMilli()
        assertEquals("2026-10-29", PhotoDateStamp.format(instant, ZoneId.of("Asia/Shanghai"), PhotoDateStampFormat.DATE))
        assertEquals("2026-10-28", PhotoDateStamp.format(instant, ZoneId.of("America/New_York"), PhotoDateStampFormat.DATE))
    }
}
