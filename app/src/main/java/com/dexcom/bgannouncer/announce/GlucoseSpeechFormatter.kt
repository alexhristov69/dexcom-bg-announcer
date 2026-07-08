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
}
