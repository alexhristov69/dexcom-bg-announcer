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
        val text = GlucoseSpeechFormatter.buildUtterance(reading, AppSettings(ttsIncludeTrend = true))
        assertTrue(text.contains("112"))
        assertTrue(text.contains("steady"))
    }

    @Test
    fun buildUtterance_omitsTrendWhenDisabled() {
        val text = GlucoseSpeechFormatter.buildUtterance(reading, AppSettings(ttsIncludeTrend = false))
        assertTrue(text.contains("112"))
        assertFalse(text.contains("steady"))
    }
}
