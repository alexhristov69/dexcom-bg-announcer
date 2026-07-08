package com.dexcom.bgannouncer.test

import com.dexcom.bgannouncer.data.SettingsRepository
import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.pipeline.GlucoseActionPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AdHocTestStep {
    IDLE,
    FETCHING,
    ANNOUNCING,
    FLASHING_BT,
    DONE,
    ERROR,
}

data class AdHocTestState(
    val step: AdHocTestStep = AdHocTestStep.IDLE,
    val message: String? = null,
    val lastCompletedAt: Long? = null,
    val cooldownRemainingSeconds: Int = 0,
)

@Singleton
class GlucoseTestRunner @Inject constructor(
    private val dexcomShareClient: DexcomShareClient,
    private val pipeline: GlucoseActionPipeline,
    private val settingsRepository: SettingsRepository,
) {
    private val _state = MutableStateFlow(AdHocTestState())
    val state: StateFlow<AdHocTestState> = _state.asStateFlow()

    private var lastRunAtMs: Long = 0L

    fun canRun(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cooldownMs = COOLDOWN_SECONDS * 1000L
        val elapsed = nowMs - lastRunAtMs
        return elapsed >= cooldownMs && _state.value.step != AdHocTestStep.FETCHING &&
            _state.value.step != AdHocTestStep.ANNOUNCING &&
            _state.value.step != AdHocTestStep.FLASHING_BT
    }

    fun cooldownRemainingSeconds(nowMs: Long = System.currentTimeMillis()): Int {
        val remaining = COOLDOWN_SECONDS - ((nowMs - lastRunAtMs) / 1000L).toInt()
        return remaining.coerceAtLeast(0)
    }

    suspend fun runAdHocTest(): Result<String> {
        if (!canRun()) {
            return Result.failure(IllegalStateException("Ad-hoc test is on cooldown or already running"))
        }

        val credentials = settingsRepository.getCredentials()
            ?: return Result.failure(IllegalStateException("Dexcom credentials are not configured"))

        val settings = settingsRepository.getSettings()
        lastRunAtMs = System.currentTimeMillis()

        return try {
            _state.value = AdHocTestState(step = AdHocTestStep.FETCHING, message = "Fetching reading…")

            val reading = dexcomShareClient.fetchLatestReading(credentials).getOrThrow()

            val result = pipeline.processReading(
                reading = reading,
                settings = settings,
                forceAnnounce = true,
                onStep = { stepMessage ->
                    _state.value = when {
                        stepMessage.contains("BT", ignoreCase = true) ->
                            AdHocTestState(step = AdHocTestStep.FLASHING_BT, message = stepMessage)
                        else ->
                            AdHocTestState(step = AdHocTestStep.ANNOUNCING, message = stepMessage)
                    }
                },
            )

            val summary = buildString {
                append("${reading.displayValue()} mg/dL ${reading.trend.label}")
                if (result.announced) append(" · announced")
                if (result.flashedBluetooth) append(" · BT flash")
                if (!result.announced && !settings.ttsEnabled) append(" · TTS skipped")
                if (!result.flashedBluetooth && settings.bluetoothArtEnabled) append(" · BT unavailable")
            }

            val completedAt = System.currentTimeMillis()
            settingsRepository.updateRuntimeStatus {
                copy(
                    lastAdHocTestTime = completedAt,
                    lastAdHocTestResult = summary,
                    lastError = null,
                )
            }

            _state.value = AdHocTestState(
                step = AdHocTestStep.DONE,
                message = summary,
                lastCompletedAt = completedAt,
            )
            Result.success(summary)
        } catch (error: Exception) {
            if (DexcomShareClient.isNoReadingsError(error)) {
                val result = pipeline.processUnavailableData(settings) { stepMessage ->
                    _state.value = when {
                        stepMessage.contains("BT", ignoreCase = true) ->
                            AdHocTestState(step = AdHocTestStep.FLASHING_BT, message = stepMessage)
                        else ->
                            AdHocTestState(step = AdHocTestStep.ANNOUNCING, message = stepMessage)
                    }
                }
                val summary = buildString {
                    append("Blood glucose data unavailable")
                    if (result.announced) append(" · announced")
                    if (result.flashedBluetooth) append(" · BT flash")
                    if (!result.announced && !settings.ttsEnabled) append(" · TTS skipped")
                    if (!result.flashedBluetooth && settings.bluetoothArtEnabled) append(" · BT unavailable")
                }
                val completedAt = System.currentTimeMillis()
                settingsRepository.updateRuntimeStatus {
                    copy(
                        lastAdHocTestTime = completedAt,
                        lastAdHocTestResult = summary,
                    )
                }
                _state.value = AdHocTestState(
                    step = AdHocTestStep.DONE,
                    message = summary,
                    lastCompletedAt = completedAt,
                )
                return Result.success(summary)
            }

            val message = error.message ?: "Ad-hoc test failed"
            settingsRepository.updateRuntimeStatus {
                copy(lastError = message)
            }
            _state.value = AdHocTestState(step = AdHocTestStep.ERROR, message = message)
            Result.failure(error)
        }
    }

    fun resetIdle() {
        if (_state.value.step == AdHocTestStep.DONE || _state.value.step == AdHocTestStep.ERROR) {
            _state.value = AdHocTestState(
                lastCompletedAt = _state.value.lastCompletedAt,
                cooldownRemainingSeconds = cooldownRemainingSeconds(),
            )
        }
    }

    companion object {
        const val COOLDOWN_SECONDS = 10
    }
}
