package com.dexcom.bgannouncer.announce

import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import java.time.Duration
import java.time.Instant

object GlucoseSpeechFormatter {
    fun buildUtterance(
        reading: GlucoseReading,
        settings: AppSettings,
        now: Instant = Instant.now(),
    ): String {
        val valuePart = "Blood glucose ${reading.valueMgDl} milligrams per deciliter"
        val agePart = formatReadingAge(reading.timestamp, now)
        return if (settings.ttsIncludeTrend) {
            "$valuePart, taken $agePart, ${reading.trend.spokenPhrase}"
        } else {
            "$valuePart, taken $agePart"
        }
    }

    fun formatReadingAge(timestamp: Instant, now: Instant = Instant.now()): String {
        val minutes = Duration.between(timestamp, now).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "1 minute ago"
            minutes < 60 -> "$minutes minutes ago"
            else -> {
                val hours = minutes / 60
                if (hours == 1L) "1 hour ago" else "$hours hours ago"
            }
        }
    }

    fun unavailableUtterance(): String = UNAVAILABLE_DISPLAY_TEXT

    fun unavailableDisplayText(): String = UNAVAILABLE_DISPLAY_TEXT

    const val UNAVAILABLE_DISPLAY_TEXT = "Blood glucose data is unavailable"
    const val UNAVAILABLE_ART_TITLE = "No data"
    const val UNAVAILABLE_ART_SUBTITLE = "Unavailable"
}
