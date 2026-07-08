package com.dexcom.bgannouncer.announce

import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.dexcom.GlucoseReading

object GlucoseSpeechFormatter {
    fun buildUtterance(reading: GlucoseReading, settings: AppSettings): String {
        val valuePart = "Blood glucose ${reading.valueMgDl} milligrams per deciliter"
        return if (settings.ttsIncludeTrend) {
            "$valuePart, ${reading.trend.spokenPhrase}"
        } else {
            valuePart
        }
    }

    fun unavailableUtterance(): String = UNAVAILABLE_DISPLAY_TEXT

    fun unavailableDisplayText(): String = UNAVAILABLE_DISPLAY_TEXT

    const val UNAVAILABLE_DISPLAY_TEXT = "Blood glucose data is unavailable"
    const val UNAVAILABLE_ART_TITLE = "No data"
    const val UNAVAILABLE_ART_SUBTITLE = "Unavailable"
}
