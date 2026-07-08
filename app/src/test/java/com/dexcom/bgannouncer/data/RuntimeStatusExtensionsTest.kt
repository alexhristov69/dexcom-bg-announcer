package com.dexcom.bgannouncer.data

import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import com.dexcom.bgannouncer.dexcom.GlucoseTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RuntimeStatusExtensionsTest {
    @Test
    fun toGlucoseReading_reconstructsFromStoredFields() {
        val reading = RuntimeStatus(
            lastReadingValue = 120,
            lastReadingTrend = "→",
            lastReadingTime = 1_700_000_000_000L,
        ).toGlucoseReading()

        assertNotNull(reading)
        assertEquals(120, reading!!.valueMgDl)
        assertEquals(GlucoseTrend.FLAT, reading.trend)
        assertEquals(1_700_000_000_000L, reading.timestamp.toEpochMilli())
    }

    @Test
    fun toGlucoseReading_returnsNullWithoutValue() {
        assertNull(RuntimeStatus(lastError = DexcomShareClient.NO_READINGS_MESSAGE).toGlucoseReading())
    }

    @Test
    fun canTestBroadcast_allowsReadingOrUnavailableState() {
        assertTrue(RuntimeStatus(lastReadingValue = 100).canTestBroadcast())
        assertTrue(RuntimeStatus(lastError = DexcomShareClient.NO_READINGS_MESSAGE).canTestBroadcast())
        assertFalse(RuntimeStatus().canTestBroadcast())
    }
}
