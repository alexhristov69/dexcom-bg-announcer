package com.dexcom.bgannouncer.dexcom

import java.time.Instant

enum class DexcomRegion(val host: String) {
    US("share2.dexcom.com"),
    OUS("shareous1.dexcom.com"),
    JAPAN("share.dexcom.jp"),
}

enum class GlucoseTrend(val code: Int, val label: String, val spokenPhrase: String) {
    NONE(0, "—", "unknown trend"),
    DOUBLE_UP(1, "⇈", "rising quickly"),
    SINGLE_UP(2, "↑", "rising"),
    FORTY_FIVE_UP(3, "↗", "rising slightly"),
    FLAT(4, "→", "steady"),
    FORTY_FIVE_DOWN(5, "↘", "falling slightly"),
    SINGLE_DOWN(6, "↓", "falling"),
    DOUBLE_DOWN(7, "⇊", "falling quickly"),
    NOT_COMPUTABLE(8, "?", "trend not available"),
    RATE_OUT_OF_RANGE(9, "!", "rate out of range"),
    ;

    companion object {
        fun fromCode(code: Int): GlucoseTrend {
            return entries.firstOrNull { it.code == code } ?: NONE
        }

        fun fromName(name: String): GlucoseTrend {
            val normalized = name.trim().lowercase().replace("_", "").replace(" ", "")
            return when (normalized) {
                "none" -> NONE
                "doubleup" -> DOUBLE_UP
                "singleup" -> SINGLE_UP
                "fortyfiveup" -> FORTY_FIVE_UP
                "flat" -> FLAT
                "fortyfivedown" -> FORTY_FIVE_DOWN
                "singledown" -> SINGLE_DOWN
                "doubledown" -> DOUBLE_DOWN
                "notcomputable" -> NOT_COMPUTABLE
                "rateoutofrange" -> RATE_OUT_OF_RANGE
                else -> entries.firstOrNull { it.name.replace("_", "").lowercase() == normalized } ?: NONE
            }
        }
    }
}

data class GlucoseReading(
    val valueMgDl: Int,
    val trend: GlucoseTrend,
    val timestamp: Instant,
    val status: String? = null,
) {
    val dedupKey: String = "${timestamp.toEpochMilli()}_$valueMgDl"

    fun displayValue(): String = valueMgDl.toString()
}
