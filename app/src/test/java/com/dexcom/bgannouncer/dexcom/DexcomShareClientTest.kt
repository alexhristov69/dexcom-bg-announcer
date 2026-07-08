package com.dexcom.bgannouncer.dexcom

import com.dexcom.bgannouncer.data.DexcomCredentials
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        server.enqueue(MockResponse().setBody("\"account-id-123\""))
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
    fun glucoseTrend_mapsKnownCodes() {
        assertEquals(GlucoseTrend.DOUBLE_UP, GlucoseTrend.fromCode(1))
        assertEquals(GlucoseTrend.FLAT, GlucoseTrend.fromCode(4))
        assertEquals(GlucoseTrend.NONE, GlucoseTrend.fromCode(99))
    }
}
