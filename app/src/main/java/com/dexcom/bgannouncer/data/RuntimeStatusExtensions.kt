package com.dexcom.bgannouncer.data

import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import com.dexcom.bgannouncer.dexcom.GlucoseTrend
import java.time.Instant

fun RuntimeStatus.toGlucoseReading(): GlucoseReading? {
    val value = lastReadingValue ?: return null
    val timestamp = lastReadingTime?.let { Instant.ofEpochMilli(it) } ?: Instant.now()
    val trend = GlucoseTrend.entries.firstOrNull { it.label == lastReadingTrend } ?: GlucoseTrend.NONE
    return GlucoseReading(
        valueMgDl = value,
        trend = trend,
        timestamp = timestamp,
    )
}

fun RuntimeStatus.canTestBroadcast(): Boolean {
    return lastReadingValue != null || DexcomShareClient.isNoReadingsMessage(lastError)
}
