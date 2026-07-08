package com.dexcom.bgannouncer.data

import com.dexcom.bgannouncer.dexcom.DexcomRegion
import javax.inject.Inject
import javax.inject.Singleton

data class ConnectionExchange(
    val label: String,
    val method: String,
    val url: String,
    val requestHeaders: String,
    val requestBody: String,
    val responseCode: Int?,
    val responseBody: String,
    val durationMs: Long,
    val success: Boolean,
    val error: String? = null,
)

data class ConnectionDiagnostics(
    val capturedAtEpochMs: Long,
    val region: DexcomRegion?,
    val usernameHint: String?,
    val authPath: String?,
    val exchanges: List<ConnectionExchange>,
    val outcome: String?,
) {
    fun toDisplayText(): String {
        if (exchanges.isEmpty()) {
            return "No connection attempts recorded yet. Run Test Connection or Run Ad-Hoc Test."
        }
        return buildString {
            appendLine("Captured: ${java.time.Instant.ofEpochMilli(capturedAtEpochMs)}")
            region?.let { appendLine("Region: ${it.name} (${it.host})") }
            usernameHint?.let { appendLine("Username: $it") }
            authPath?.let { appendLine("Auth path: $it") }
            outcome?.let { appendLine("Outcome: $it") }
            appendLine()
            exchanges.forEachIndexed { index, exchange ->
                appendLine("=== Exchange ${index + 1}: ${exchange.label} ===")
                appendLine("${exchange.method} ${exchange.url}")
                appendLine("Duration: ${exchange.durationMs} ms")
                appendLine("Success: ${exchange.success}")
                exchange.error?.let { appendLine("Error: $it") }
                appendLine()
                appendLine("Request headers:")
                appendLine(exchange.requestHeaders)
                appendLine()
                appendLine("Request body:")
                appendLine(exchange.requestBody.ifBlank { "(empty)" })
                appendLine()
                appendLine("Response (${exchange.responseCode ?: "n/a"}):")
                appendLine(exchange.responseBody.ifBlank { "(empty)" })
                appendLine()
            }
        }.trimEnd()
    }
}

@Singleton
class ConnectionDiagnosticsRepository @Inject constructor() {
    @Volatile
    private var snapshot: ConnectionDiagnostics = emptySnapshot()

    private var sessionRegion: DexcomRegion? = null
    private var sessionUsernameHint: String? = null
    private var sessionAuthPath: String? = null
    private var sessionOutcome: String? = null
    private val sessionExchanges = mutableListOf<ConnectionExchange>()

    fun beginSession(region: DexcomRegion, username: String) {
        synchronized(this) {
            sessionRegion = region
            sessionUsernameHint = redactUsername(username)
            sessionAuthPath = null
            sessionOutcome = null
            sessionExchanges.clear()
        }
    }

    fun setAuthPath(path: String) {
        synchronized(this) {
            sessionAuthPath = path
        }
    }

    fun recordExchange(exchange: ConnectionExchange) {
        synchronized(this) {
            sessionExchanges.add(exchange)
        }
    }

    fun completeSession(outcome: String) {
        synchronized(this) {
            sessionOutcome = outcome
            snapshot = ConnectionDiagnostics(
                capturedAtEpochMs = System.currentTimeMillis(),
                region = sessionRegion,
                usernameHint = sessionUsernameHint,
                authPath = sessionAuthPath,
                exchanges = sessionExchanges.toList(),
                outcome = outcome,
            )
        }
    }

    fun failSession(outcome: String) {
        completeSession(outcome)
    }

    fun getSnapshot(): ConnectionDiagnostics = snapshot

    fun clear() {
        synchronized(this) {
            snapshot = emptySnapshot()
            sessionRegion = null
            sessionUsernameHint = null
            sessionAuthPath = null
            sessionOutcome = null
            sessionExchanges.clear()
        }
    }

    companion object {
        fun emptySnapshot(): ConnectionDiagnostics {
            return ConnectionDiagnostics(
                capturedAtEpochMs = 0L,
                region = null,
                usernameHint = null,
                authPath = null,
                exchanges = emptyList(),
                outcome = null,
            )
        }

        fun redactUsername(username: String): String {
            val trimmed = username.trim()
            return when {
                trimmed.length <= 4 -> "***"
                trimmed.contains("@") -> {
                    val parts = trimmed.split("@", limit = 2)
                    "${parts[0].take(2)}***@${parts.getOrElse(1) { "?" }}"
                }
                trimmed.startsWith("+") && trimmed.length > 6 ->
                    "${trimmed.take(4)}***${trimmed.takeLast(2)}"
                else -> "${trimmed.take(2)}***${trimmed.takeLast(2)}"
            }
        }

        fun redactSecrets(body: String): String {
            return body
                .replace(Regex(""""password"\s*:\s*"[^"]*"""", RegexOption.IGNORE_CASE), """"password":"***"""")
                .replace(Regex(""""sessionId"\s*:\s*"[^"]*"""", RegexOption.IGNORE_CASE), """"sessionId":"***"""")
        }
    }
}
