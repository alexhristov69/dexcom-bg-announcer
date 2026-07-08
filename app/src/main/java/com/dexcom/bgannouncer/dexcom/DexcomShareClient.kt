package com.dexcom.bgannouncer.dexcom

import com.dexcom.bgannouncer.data.ConnectionDiagnosticsRepository
import com.dexcom.bgannouncer.data.ConnectionExchange
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
    private val diagnosticsRepository: ConnectionDiagnosticsRepository,
    private val baseUrlProvider: DexcomBaseUrlProvider,
) {
    private var cachedSessionId: String? = null
    private var cachedRegion: DexcomRegion? = null

    suspend fun testConnection(credentials: DexcomCredentials): Result<Unit> {
        return runCatching {
            withContext(Dispatchers.IO) {
                authenticate(credentials, forceRefresh = true)
            }
            Unit
        }.onSuccess {
            diagnosticsRepository.completeSession("Connection successful")
        }.onFailure { error ->
            diagnosticsRepository.failSession(error.message ?: "Connection failed")
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
        }.onSuccess { reading ->
            diagnosticsRepository.completeSession(
                "Fetched reading: ${reading.valueMgDl} mg/dL ${reading.trend.label}",
            )
        }.onFailure { error ->
            diagnosticsRepository.failSession(error.message ?: "Fetch failed")
        }
    }

    fun getDiagnostics() = diagnosticsRepository.getSnapshot()

    fun clearDiagnostics() {
        diagnosticsRepository.clear()
    }

    fun invalidateSession() {
        cachedSessionId = null
        cachedRegion = null
    }

    private fun authenticate(credentials: DexcomCredentials, forceRefresh: Boolean): String {
        if (!forceRefresh && cachedSessionId != null && cachedRegion == credentials.region) {
            return cachedSessionId!!
        }

        diagnosticsRepository.beginSession(credentials.region, credentials.username)

        val baseUrl = baseUrl(credentials.region)
        val applicationId = applicationId(credentials.region)
        val username = credentials.username.trim()

        val sessionId = when {
            isUuid(username) -> {
                diagnosticsRepository.setAuthPath("LoginPublisherAccountById (account UUID)")
                loginByAccountId(
                    baseUrl = baseUrl,
                    accountId = username,
                    password = credentials.password,
                    applicationId = applicationId,
                    label = "Login by account ID",
                )
            }
            else -> {
                val byName = loginByAccountName(
                    baseUrl = baseUrl,
                    accountName = username,
                    password = credentials.password,
                    applicationId = applicationId,
                )
                if (byName != null) {
                    byName
                } else {
                    diagnosticsRepository.setAuthPath("AuthenticatePublisherAccount + LoginPublisherAccountById")
                    val accountId = authenticateAccount(
                        baseUrl = baseUrl,
                        accountName = username,
                        password = credentials.password,
                        applicationId = applicationId,
                    )
                    loginByAccountId(
                        baseUrl = baseUrl,
                        accountId = accountId,
                        password = credentials.password,
                        applicationId = applicationId,
                        label = "Login by account ID (two-step)",
                    )
                }
            }
        }

        cachedSessionId = sessionId
        cachedRegion = credentials.region
        return sessionId
    }

    private fun loginByAccountName(
        baseUrl: String,
        accountName: String,
        password: String,
        applicationId: String,
    ): String? {
        return runCatching {
            diagnosticsRepository.setAuthPath("LoginPublisherAccountByName")
            val sessionId = postForString(
                label = "Login by account name",
                url = "$baseUrl/ShareWebServices/Services/General/LoginPublisherAccountByName",
                body = json.encodeToString(
                    ShareLoginByNameRequest.serializer(),
                    ShareLoginByNameRequest(
                        accountName = accountName,
                        password = password,
                        applicationId = applicationId,
                    ),
                ),
            ).trim('"')
            validateSessionId(sessionId)
            sessionId
        }.getOrNull()
    }

    private fun authenticateAccount(
        baseUrl: String,
        accountName: String,
        password: String,
        applicationId: String,
    ): String {
        val accountId = postForString(
            label = "Authenticate publisher account",
            url = "$baseUrl/ShareWebServices/Services/General/AuthenticatePublisherAccount",
            body = json.encodeToString(
                ShareAuthRequest.serializer(),
                ShareAuthRequest(
                    accountName = accountName,
                    password = password,
                    applicationId = applicationId,
                ),
            ),
        ).trim('"')
        if (accountId.isBlank() || accountId == DEFAULT_UUID) {
            throw DexcomShareException("Dexcom Share authentication failed")
        }
        return accountId
    }

    private fun loginByAccountId(
        baseUrl: String,
        accountId: String,
        password: String,
        applicationId: String,
        label: String = "Login by account ID",
    ): String {
        val sessionId = postForString(
            label = label,
            url = "$baseUrl/ShareWebServices/Services/General/LoginPublisherAccountById",
            body = json.encodeToString(
                ShareLoginRequest.serializer(),
                ShareLoginRequest(
                    accountId = accountId,
                    password = password,
                    applicationId = applicationId,
                ),
            ),
        ).trim('"')
        validateSessionId(sessionId)
        return sessionId
    }

    private fun validateSessionId(sessionId: String) {
        if (sessionId.isBlank() || sessionId == DEFAULT_UUID) {
            throw DexcomShareException("Dexcom Share login failed")
        }
    }

    private fun readLatestGlucose(region: DexcomRegion, sessionId: String): List<ShareGlucoseRecord> {
        val baseUrl = baseUrl(region)
        val url =
            "$baseUrl/ShareWebServices/Services/Publisher/ReadPublisherLatestGlucoseValues" +
                "?sessionId=$sessionId&minutes=10&maxCount=1"
        val responseBody = postForString(
            label = "Read latest glucose values",
            url = url,
            body = "{}",
        )
        return json.decodeFromString(responseBody)
    }

    private fun postForString(label: String, url: String, body: String): String {
        val redactedUrl = url.replace(Regex("sessionId=[^&]+"), "sessionId=***")
        val requestHeaders = buildString {
            appendLine("Content-Type: application/json")
            appendLine("Accept: application/json")
            appendLine("Accept-Encoding: application/json")
        }.trimEnd()
        val redactedBody = ConnectionDiagnosticsRepository.redactSecrets(body)

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "application/json")
            .build()

        val startedAt = System.currentTimeMillis()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val durationMs = System.currentTimeMillis() - startedAt
                val dexcomError = parseDexcomErrorOrNull(responseBody)

                diagnosticsRepository.recordExchange(
                    ConnectionExchange(
                        label = label,
                        method = "POST",
                        url = redactedUrl,
                        requestHeaders = requestHeaders,
                        requestBody = redactedBody,
                        responseCode = response.code,
                        responseBody = responseBody,
                        durationMs = durationMs,
                        success = response.isSuccessful && dexcomError == null,
                        error = dexcomError,
                    ),
                )

                if (dexcomError != null) {
                    throw DexcomShareException(dexcomError)
                }
                if (!response.isSuccessful) {
                    throw DexcomShareException(
                        "Dexcom Share request failed (${response.code}): ${responseBody.take(200)}",
                    )
                }
                if (responseBody.isBlank()) {
                    throw DexcomShareException("Dexcom Share returned an empty response")
                }
                responseBody
            }
        } catch (error: Exception) {
            if (error is DexcomShareException) throw error
            val durationMs = System.currentTimeMillis() - startedAt
            diagnosticsRepository.recordExchange(
                ConnectionExchange(
                    label = label,
                    method = "POST",
                    url = redactedUrl,
                    requestHeaders = requestHeaders,
                    requestBody = redactedBody,
                    responseCode = null,
                    responseBody = "",
                    durationMs = durationMs,
                    success = false,
                    error = error.message,
                ),
            )
            throw error
        }
    }

    private fun parseDexcomErrorOrNull(responseBody: String): String? {
        if (!responseBody.trimStart().startsWith("{")) return null
        val error = runCatching {
            json.decodeFromString<ShareErrorResponse>(responseBody)
        }.getOrNull() ?: return null
        if (error.code.isNullOrBlank()) return null
        return buildString {
            append(error.code)
            if (!error.message.isNullOrBlank()) {
                append(": ")
                append(error.message)
            }
        }
    }

    private fun applicationId(region: DexcomRegion): String = when (region) {
        DexcomRegion.US, DexcomRegion.OUS -> APPLICATION_ID_US
        DexcomRegion.JAPAN -> APPLICATION_ID_JP
    }

    private fun isUuid(value: String): Boolean {
        return UUID_PATTERN.matches(value.trim())
    }

    private fun parseRecord(record: ShareGlucoseRecord): GlucoseReading {
        val timestamp = parseDexcomDate(record.systemTime ?: record.displayTime)
        return GlucoseReading(
            valueMgDl = record.value,
            trend = record.trend ?: GlucoseTrend.NONE,
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
        private val DATE_PATTERN = Pattern.compile("Date\\((-?\\d+)")
        private val UUID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
        private const val DEFAULT_UUID = "00000000-0000-0000-0000-000000000000"
        private const val APPLICATION_ID_US = "d89443d2-327c-4a6f-89e5-496bbb0317db"
        private const val APPLICATION_ID_JP = "d8665ade-9673-4e27-9ff6-92db4ce13d13"
    }
}

open class DexcomBaseUrlProvider @Inject constructor() {
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
