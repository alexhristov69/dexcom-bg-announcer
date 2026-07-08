package com.dexcom.bgannouncer.dexcom

import com.dexcom.bgannouncer.data.DexcomCredentials
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

class DexcomShareException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class DexcomShareClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val baseUrlProvider: DexcomBaseUrlProvider = DexcomBaseUrlProvider(),
) {
    private var cachedSessionId: String? = null
    private var cachedRegion: DexcomRegion? = null

    suspend fun testConnection(credentials: DexcomCredentials): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                authenticate(credentials, forceRefresh = true)
            }
        }
    }

    suspend fun fetchLatestReading(credentials: DexcomCredentials): Result<GlucoseReading> {
        return runCatching {
            withContext(Dispatchers.IO) {
                val sessionId = authenticate(credentials, forceRefresh = false)
                val records = readLatestGlucose(credentials.region, sessionId)
                val record = records.firstOrNull()
                    ?: throw DexcomShareException("No glucose readings returned from Dexcom Share")
                parseRecord(record)
            }
        }
    }

    fun invalidateSession() {
        cachedSessionId = null
        cachedRegion = null
    }

    private fun authenticate(credentials: DexcomCredentials, forceRefresh: Boolean): String {
        if (!forceRefresh && cachedSessionId != null && cachedRegion == credentials.region) {
            return cachedSessionId!!
        }

        val baseUrl = baseUrl(credentials.region)
        val accountId = postForString(
            url = "$baseUrl/ShareWebServices/Services/General/AuthenticatePublisherAccount",
            body = json.encodeToString(
                ShareAuthRequest.serializer(),
                ShareAuthRequest(
                    accountName = credentials.username,
                    password = credentials.password,
                    applicationId = APPLICATION_ID,
                ),
            ),
        ).trim('"')

        if (accountId.isBlank()) {
            throw DexcomShareException("Dexcom Share authentication failed")
        }

        val sessionId = postForString(
            url = "$baseUrl/ShareWebServices/Services/General/LoginPublisherAccountById",
            body = json.encodeToString(
                ShareLoginRequest.serializer(),
                ShareLoginRequest(
                    accountId = accountId,
                    password = credentials.password,
                    applicationId = APPLICATION_ID,
                ),
            ),
        ).trim('"')

        if (sessionId.isBlank()) {
            throw DexcomShareException("Dexcom Share login failed")
        }

        cachedSessionId = sessionId
        cachedRegion = credentials.region
        return sessionId
    }

    private fun readLatestGlucose(region: DexcomRegion, sessionId: String): List<ShareGlucoseRecord> {
        val baseUrl = baseUrl(region)
        val url =
            "$baseUrl/ShareWebServices/Services/Publisher/ReadPublisherLatestGlucoseValues" +
                "?sessionId=$sessionId&minutes=10&maxCount=1"
        val responseBody = postForString(url = url, body = "")
        return json.decodeFromString(responseBody)
    }

    private fun postForString(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DexcomShareException(
                    "Dexcom Share request failed (${response.code}): ${responseBody.take(200)}",
                )
            }
            if (responseBody.isBlank()) {
                throw DexcomShareException("Dexcom Share returned an empty response")
            }
            return responseBody
        }
    }

    private fun parseRecord(record: ShareGlucoseRecord): GlucoseReading {
        val timestamp = parseDexcomDate(record.systemTime ?: record.displayTime)
        return GlucoseReading(
            valueMgDl = record.value,
            trend = GlucoseTrend.fromCode(record.trend ?: GlucoseTrend.NONE.code),
            timestamp = timestamp,
        )
    }

    private fun parseDexcomDate(raw: String?): Instant {
        if (raw.isNullOrBlank()) return Instant.now()
        val matcher = DATE_PATTERN.matcher(raw)
        if (!matcher.find()) return Instant.now()
        val millis = matcher.group(1)?.toLongOrNull() ?: return Instant.now()
        return Instant.ofEpochMilli(millis)
    }

    private fun baseUrl(region: DexcomRegion): String = baseUrlProvider.baseUrl(region)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val DATE_PATTERN = Pattern.compile("/Date\\((-?\\d+)")
        const val APPLICATION_ID = "d89443d2-327c-4a6f-89e5-496e03e46930"
    }
}

open class DexcomBaseUrlProvider {
    open fun baseUrl(region: DexcomRegion): String = "https://${region.host}"
}

@Singleton
class DexcomClientProvider @Inject constructor() {
    fun createClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun createJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
