package com.dexcom.bgannouncer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dexcom.bgannouncer.dexcom.DexcomRegion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class DexcomCredentials(
    val username: String,
    val password: String,
    val region: DexcomRegion,
)

data class AppSettings(
    val dexcomUsername: String = "",
    val dexcomPassword: String = "",
    val dexcomRegion: DexcomRegion = DexcomRegion.US,
    val monitoringEnabled: Boolean = false,
    val pollIntervalMinutes: Int = 5,
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsIncludeTrend: Boolean = true,
    val bluetoothArtEnabled: Boolean = true,
    val bluetoothFlashDurationSeconds: Int = 4,
    val lowThreshold: Int = 70,
    val highThreshold: Int = 180,
)

data class RuntimeStatus(
    val lastReadingValue: Int? = null,
    val lastReadingTrend: String? = null,
    val lastReadingTime: Long? = null,
    val lastPollTime: Long? = null,
    val nextPollTime: Long? = null,
    val isPolling: Boolean = false,
    val lastAdHocTestTime: Long? = null,
    val lastAdHocTestResult: String? = null,
    val lastError: String? = null,
    val serviceRunning: Boolean = false,
    val lastAnnouncedDedupKey: String? = null,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getSettings(): AppSettings {
        return AppSettings(
            dexcomUsername = prefs.getString(KEY_USERNAME, "").orEmpty(),
            dexcomPassword = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            dexcomRegion = DexcomRegion.entries.getOrElse(
                prefs.getInt(KEY_REGION, 0),
            ) { DexcomRegion.US },
            monitoringEnabled = prefs.getBoolean(KEY_MONITORING_ENABLED, false),
            pollIntervalMinutes = prefs.getInt(KEY_POLL_INTERVAL, 5).coerceIn(1, 30),
            ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, true),
            ttsSpeechRate = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f).coerceIn(0.5f, 2.0f),
            ttsIncludeTrend = prefs.getBoolean(KEY_TTS_INCLUDE_TREND, true),
            bluetoothArtEnabled = prefs.getBoolean(KEY_BT_ART_ENABLED, true),
            bluetoothFlashDurationSeconds = prefs.getInt(KEY_BT_FLASH_DURATION, 4).coerceIn(2, 10),
            lowThreshold = prefs.getInt(KEY_LOW_THRESHOLD, 70),
            highThreshold = prefs.getInt(KEY_HIGH_THRESHOLD, 180),
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_USERNAME, settings.dexcomUsername.trim())
            .putString(KEY_PASSWORD, settings.dexcomPassword)
            .putInt(KEY_REGION, settings.dexcomRegion.ordinal)
            .putBoolean(KEY_MONITORING_ENABLED, settings.monitoringEnabled)
            .putInt(KEY_POLL_INTERVAL, settings.pollIntervalMinutes.coerceIn(1, 30))
            .putBoolean(KEY_TTS_ENABLED, settings.ttsEnabled)
            .putFloat(KEY_TTS_SPEECH_RATE, settings.ttsSpeechRate.coerceIn(0.5f, 2.0f))
            .putBoolean(KEY_TTS_INCLUDE_TREND, settings.ttsIncludeTrend)
            .putBoolean(KEY_BT_ART_ENABLED, settings.bluetoothArtEnabled)
            .putInt(KEY_BT_FLASH_DURATION, settings.bluetoothFlashDurationSeconds.coerceIn(2, 10))
            .putInt(KEY_LOW_THRESHOLD, settings.lowThreshold)
            .putInt(KEY_HIGH_THRESHOLD, settings.highThreshold)
            .apply()
    }

    fun getCredentials(): DexcomCredentials? {
        val settings = getSettings()
        if (settings.dexcomUsername.isBlank() || settings.dexcomPassword.isBlank()) {
            return null
        }
        return DexcomCredentials(
            username = settings.dexcomUsername,
            password = settings.dexcomPassword,
            region = settings.dexcomRegion,
        )
    }

    fun isConfigured(): Boolean = getCredentials() != null

    fun getRuntimeStatus(): RuntimeStatus {
        return RuntimeStatus(
            lastReadingValue = prefs.getInt(KEY_LAST_READING_VALUE, -1).takeIf { it >= 0 },
            lastReadingTrend = prefs.getString(KEY_LAST_READING_TREND, null),
            lastReadingTime = prefs.getLong(KEY_LAST_READING_TIME, 0L).takeIf { it > 0L },
            lastPollTime = prefs.getLong(KEY_LAST_POLL_TIME, 0L).takeIf { it > 0L },
            nextPollTime = prefs.getLong(KEY_NEXT_POLL_TIME, 0L).takeIf { it > 0L },
            isPolling = prefs.getBoolean(KEY_IS_POLLING, false),
            lastAdHocTestTime = prefs.getLong(KEY_LAST_ADHOC_TIME, 0L).takeIf { it > 0L },
            lastAdHocTestResult = prefs.getString(KEY_LAST_ADHOC_RESULT, null),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            serviceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false),
            lastAnnouncedDedupKey = prefs.getString(KEY_LAST_ANNOUNCED_KEY, null),
        )
    }

    fun updateRuntimeStatus(update: RuntimeStatus.() -> RuntimeStatus) {
        val current = getRuntimeStatus()
        val next = current.update()
        prefs.edit()
            .apply {
                if (next.lastReadingValue != null) {
                    putInt(KEY_LAST_READING_VALUE, next.lastReadingValue)
                } else {
                    remove(KEY_LAST_READING_VALUE)
                }
                if (next.lastReadingTrend != null) {
                    putString(KEY_LAST_READING_TREND, next.lastReadingTrend)
                } else {
                    remove(KEY_LAST_READING_TREND)
                }
                if (next.lastReadingTime != null) {
                    putLong(KEY_LAST_READING_TIME, next.lastReadingTime)
                } else {
                    remove(KEY_LAST_READING_TIME)
                }
                if (next.lastPollTime != null) {
                    putLong(KEY_LAST_POLL_TIME, next.lastPollTime)
                } else {
                    remove(KEY_LAST_POLL_TIME)
                }
                if (next.nextPollTime != null) {
                    putLong(KEY_NEXT_POLL_TIME, next.nextPollTime)
                } else {
                    remove(KEY_NEXT_POLL_TIME)
                }
                putBoolean(KEY_IS_POLLING, next.isPolling)
                if (next.lastAdHocTestTime != null) {
                    putLong(KEY_LAST_ADHOC_TIME, next.lastAdHocTestTime)
                } else {
                    remove(KEY_LAST_ADHOC_TIME)
                }
                if (next.lastAdHocTestResult != null) {
                    putString(KEY_LAST_ADHOC_RESULT, next.lastAdHocTestResult)
                } else {
                    remove(KEY_LAST_ADHOC_RESULT)
                }
                if (next.lastError != null) {
                    putString(KEY_LAST_ERROR, next.lastError)
                } else {
                    remove(KEY_LAST_ERROR)
                }
                putBoolean(KEY_SERVICE_RUNNING, next.serviceRunning)
                if (next.lastAnnouncedDedupKey != null) {
                    putString(KEY_LAST_ANNOUNCED_KEY, next.lastAnnouncedDedupKey)
                } else {
                    remove(KEY_LAST_ANNOUNCED_KEY)
                }
            }
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "dexcom_bg_announcer_secure_prefs"
        private const val KEY_USERNAME = "dexcom_username"
        private const val KEY_PASSWORD = "dexcom_password"
        private const val KEY_REGION = "dexcom_region"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_POLL_INTERVAL = "poll_interval_minutes"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_INCLUDE_TREND = "tts_include_trend"
        private const val KEY_BT_ART_ENABLED = "bt_art_enabled"
        private const val KEY_BT_FLASH_DURATION = "bt_flash_duration_seconds"
        private const val KEY_LOW_THRESHOLD = "low_threshold"
        private const val KEY_HIGH_THRESHOLD = "high_threshold"
        private const val KEY_LAST_READING_VALUE = "last_reading_value"
        private const val KEY_LAST_READING_TREND = "last_reading_trend"
        private const val KEY_LAST_READING_TIME = "last_reading_time"
        private const val KEY_LAST_POLL_TIME = "last_poll_time"
        private const val KEY_NEXT_POLL_TIME = "next_poll_time"
        private const val KEY_IS_POLLING = "is_polling"
        private const val KEY_LAST_ADHOC_TIME = "last_adhoc_time"
        private const val KEY_LAST_ADHOC_RESULT = "last_adhoc_result"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_LAST_ANNOUNCED_KEY = "last_announced_dedup_key"
    }
}
