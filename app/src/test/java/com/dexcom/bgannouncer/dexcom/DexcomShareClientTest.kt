package com.dexcom.bgannouncer.dexcom

import com.dexcom.bgannouncer.data.ConnectionDiagnosticsRepository
import com.dexcom.bgannouncer.data.DexcomCredentials
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DexcomShareClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DexcomShareClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        client = DexcomShareClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            diagnosticsRepository = ConnectionDiagnosticsRepository(),
            baseUrlProvider = object : DexcomBaseUrlProvider() {
                override fun baseUrl(region: DexcomRegion): String = baseUrl
            },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchLatestReading_parsesDexcomShareResponse() {
        server.enqueue(MockResponse().setBody("\"session-id-456\""))
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "Value": 112,
                    "Trend": 4,
                    "ST": "/Date(1700000000000)/"
                  }
                ]
                """.trimIndent(),
            ),
        )

        val result = runBlocking {
            client.fetchLatestReading(
                DexcomCredentials(
                    username = "user@example.com",
                    password = "secret",
                    region = DexcomRegion.US,
                ),
            )
        }

        assertTrue(result.isSuccess)
        val reading = result.getOrThrow()
        assertEquals(112, reading.valueMgDl)
        assertEquals(GlucoseTrend.FLAT, reading.trend)
    }

    @Test
    fun fetchLatestReading_parsesStringTrendAndDateFormat() {
        server.enqueue(MockResponse().setBody("\"session-id-456\""))
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "WT": "Date(1783532461869)",
                    "ST": "Date(1783532461869)",
                    "DT": "Date(1783532461869-0700)",
                    "Value": 137,
                    "Trend": "Flat"
                  }
                ]
                """.trimIndent(),
            ),
        )

        val result = runBlocking {
            client.fetchLatestReading(
                DexcomCredentials(
                    username = "user@example.com",
                    password = "secret",
                    region = DexcomRegion.US,
                ),
            )
        }

        assertTrue(result.isSuccess)
        val reading = result.getOrThrow()
        assertEquals(137, reading.valueMgDl)
        assertEquals(GlucoseTrend.FLAT, reading.trend)
        assertEquals(1783532461869L, reading.timestamp.toEpochMilli())
    }

    @Test
    fun glucoseTrend_mapsKnownCodes() {
        assertEquals(GlucoseTrend.DOUBLE_UP, GlucoseTrend.fromCode(1))
        assertEquals(GlucoseTrend.FLAT, GlucoseTrend.fromCode(4))
        assertEquals(GlucoseTrend.NONE, GlucoseTrend.fromCode(99))
    }

    @Test
    fun glucoseTrend_mapsKnownNames() {
        assertEquals(GlucoseTrend.FLAT, GlucoseTrend.fromName("Flat"))
        assertEquals(GlucoseTrend.DOUBLE_UP, GlucoseTrend.fromName("DoubleUp"))
        assertEquals(GlucoseTrend.SINGLE_DOWN, GlucoseTrend.fromName("SingleDown"))
    }

    @Test
    fun isNoReadingsError_matchesEmptyDexcomResponse() {
        val error = DexcomShareException(DexcomShareClient.NO_READINGS_MESSAGE)
        assertTrue(DexcomShareClient.isNoReadingsError(error))
        assertFalse(DexcomShareClient.isNoReadingsError(Exception("Polling failed")))
    }
}
