package com.dexcom.bgannouncer.announce

import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import com.dexcom.bgannouncer.dexcom.GlucoseTrend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GlucoseSpeechFormatterTest {
    private val reading = GlucoseReading(
        valueMgDl = 112,
        trend = GlucoseTrend.FLAT,
        timestamp = Instant.parse("2025-01-01T12:00:00Z"),
    )

    @Test
    fun buildUtterance_includesTrendWhenEnabled() {
        val now = Instant.parse("2025-01-01T12:05:00Z")
        val text = GlucoseSpeechFormatter.buildUtterance(reading, AppSettings(ttsIncludeTrend = true), now)
        assertTrue(text.contains("112"))
        assertTrue(text.contains("steady"))
        assertTrue(text.contains("5 minutes ago"))
    }

    @Test
    fun buildUtterance_omitsTrendWhenDisabled() {
        val now = Instant.parse("2025-01-01T12:05:00Z")
        val text = GlucoseSpeechFormatter.buildUtterance(reading, AppSettings(ttsIncludeTrend = false), now)
        assertTrue(text.contains("112"))
        assertFalse(text.contains("steady"))
        assertTrue(text.contains("5 minutes ago"))
    }

    @Test
    fun formatReadingAge_usesSingularMinute() {
        val timestamp = Instant.parse("2025-01-01T12:00:00Z")
        val now = Instant.parse("2025-01-01T12:01:00Z")
        assertTrue(GlucoseSpeechFormatter.formatReadingAge(timestamp, now).contains("1 minute ago"))
    }

    @Test
    fun formatReadingAge_usesJustNowForRecentReading() {
        val timestamp = Instant.parse("2025-01-01T12:00:00Z")
        val now = Instant.parse("2025-01-01T12:00:30Z")
        assertTrue(GlucoseSpeechFormatter.formatReadingAge(timestamp, now).contains("just now"))
    }

    @Test
    fun unavailableUtterance_describesMissingData() {
        assertTrue(GlucoseSpeechFormatter.unavailableUtterance().contains("unavailable"))
    }
}
