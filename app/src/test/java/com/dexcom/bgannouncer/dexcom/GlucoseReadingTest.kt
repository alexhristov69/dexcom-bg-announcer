package com.dexcom.bgannouncer.dexcom

import org.junit.Assert.assertEquals
import org.junit.Test

class GlucoseReadingTest {
    @Test
    fun dedupKey_usesTimestampAndValue() {
        val reading = GlucoseReading(
            valueMgDl = 120,
            trend = GlucoseTrend.FLAT,
            timestamp = java.time.Instant.ofEpochMilli(1_700_000_000_000L),
        )
        assertEquals("1700000000000_120", reading.dedupKey)
    }
}
