package com.dexcom.bgannouncer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.data.DexcomCredentials
import com.dexcom.bgannouncer.data.SettingsRepository
import com.dexcom.bgannouncer.dexcom.DexcomRegion
import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.service.CgmMonitorForegroundService
import com.dexcom.bgannouncer.test.AdHocTestStep
import com.dexcom.bgannouncer.test.GlucoseTestRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val username: String = "",
    val password: String = "",
    val region: DexcomRegion = DexcomRegion.US,
    val monitoringEnabled: Boolean = false,
    val pollIntervalMinutes: Int = 5,
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsIncludeTrend: Boolean = true,
    val bluetoothArtEnabled: Boolean = true,
    val bluetoothFlashDurationSeconds: Int = 4,
    val serviceRunning: Boolean = false,
    val lastReadingValue: Int? = null,
    val lastReadingTrend: String? = null,
    val lastPollTime: Long? = null,
    val lastAdHocTestTime: Long? = null,
    val lastAdHocTestResult: String? = null,
    val lastError: String? = null,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val adHocTestStep: AdHocTestStep = AdHocTestStep.IDLE,
    val adHocTestMessage: String? = null,
    val adHocCooldownSeconds: Int = 0,
) {
    val isConfigured: Boolean = username.isNotBlank() && password.isNotBlank()
    val adHocTestRunning: Boolean = adHocTestStep == AdHocTestStep.FETCHING ||
        adHocTestStep == AdHocTestStep.ANNOUNCING ||
        adHocTestStep == AdHocTestStep.FLASHING_BT
    val canRunAdHocTest: Boolean = isConfigured && !adHocTestRunning && adHocCooldownSeconds == 0 && !isBusy
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val dexcomShareClient: DexcomShareClient,
    private val glucoseTestRunner: GlucoseTestRunner,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null

    init {
        refreshFromRepository()
        viewModelScope.launch {
            glucoseTestRunner.state.collect { testState ->
                _uiState.update {
                    it.copy(
                        adHocTestStep = testState.step,
                        adHocTestMessage = testState.message,
                        lastAdHocTestTime = testState.lastCompletedAt ?: it.lastAdHocTestTime,
                        adHocCooldownSeconds = glucoseTestRunner.cooldownRemainingSeconds(),
                    )
                }
                if (testState.step == AdHocTestStep.DONE || testState.step == AdHocTestStep.ERROR) {
                    refreshFromRepository()
                    startCooldownTicker()
                }
            }
        }
        startCooldownTicker()
    }

    fun onUsernameChanged(value: String) = _uiState.update { it.copy(username = value) }
    fun onPasswordChanged(value: String) = _uiState.update { it.copy(password = value) }
    fun onRegionChanged(value: DexcomRegion) = _uiState.update { it.copy(region = value) }
    fun onPollIntervalChanged(value: Int) = _uiState.update { it.copy(pollIntervalMinutes = value) }
    fun onTtsEnabledChanged(value: Boolean) = _uiState.update { it.copy(ttsEnabled = value) }
    fun onTtsSpeechRateChanged(value: Float) = _uiState.update { it.copy(ttsSpeechRate = value) }
    fun onTtsIncludeTrendChanged(value: Boolean) = _uiState.update { it.copy(ttsIncludeTrend = value) }
    fun onBluetoothArtEnabledChanged(value: Boolean) = _uiState.update { it.copy(bluetoothArtEnabled = value) }
    fun onBluetoothFlashDurationChanged(value: Int) = _uiState.update { it.copy(bluetoothFlashDurationSeconds = value) }

    fun saveSettings() {
        val state = _uiState.value
        settingsRepository.saveSettings(state.toAppSettings(monitoringEnabled = state.monitoringEnabled))
        _uiState.update { it.copy(statusMessage = "Settings saved", errorMessage = null) }
    }

    fun testConnection() {
        viewModelScope.launch {
            saveSettings()
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            val credentials = currentCredentials()
            if (credentials == null) {
                _uiState.update { it.copy(isBusy = false, errorMessage = "Enter Dexcom credentials first") }
                return@launch
            }
            dexcomShareClient.invalidateSession()
            val result = dexcomShareClient.testConnection(credentials)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = result.fold({ "Connection successful" }, { null }),
                    errorMessage = result.fold({ null }, { error -> error.message }),
                )
            }
        }
    }

    fun runAdHocTest() {
        viewModelScope.launch {
            saveSettings()
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            val result = glucoseTestRunner.runAdHocTest()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = result.getOrNull(),
                    errorMessage = result.exceptionOrNull()?.message,
                )
            }
            glucoseTestRunner.resetIdle()
        }
    }

    fun toggleMonitoring() {
        val nextEnabled = !_uiState.value.monitoringEnabled
        val settings = _uiState.value.toAppSettings(monitoringEnabled = nextEnabled)
        settingsRepository.saveSettings(settings)
        if (nextEnabled) {
            CgmMonitorForegroundService.start(context)
        } else {
            CgmMonitorForegroundService.stop(context)
        }
        refreshFromRepository()
        _uiState.update {
            it.copy(
                monitoringEnabled = nextEnabled,
                statusMessage = if (nextEnabled) "Monitoring started" else "Monitoring stopped",
            )
        }
    }

    private fun refreshFromRepository() {
        val settings = settingsRepository.getSettings()
        val status = settingsRepository.getRuntimeStatus()
        _uiState.update {
            it.copy(
                username = settings.dexcomUsername,
                password = settings.dexcomPassword,
                region = settings.dexcomRegion,
                monitoringEnabled = settings.monitoringEnabled,
                pollIntervalMinutes = settings.pollIntervalMinutes,
                ttsEnabled = settings.ttsEnabled,
                ttsSpeechRate = settings.ttsSpeechRate,
                ttsIncludeTrend = settings.ttsIncludeTrend,
                bluetoothArtEnabled = settings.bluetoothArtEnabled,
                bluetoothFlashDurationSeconds = settings.bluetoothFlashDurationSeconds,
                serviceRunning = status.serviceRunning,
                lastReadingValue = status.lastReadingValue,
                lastReadingTrend = status.lastReadingTrend,
                lastPollTime = status.lastPollTime,
                lastAdHocTestTime = status.lastAdHocTestTime,
                lastAdHocTestResult = status.lastAdHocTestResult,
                lastError = status.lastError,
            )
        }
    }

    private fun currentCredentials(): DexcomCredentials? {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) return null
        return DexcomCredentials(
            username = state.username.trim(),
            password = state.password,
            region = state.region,
        )
    }

    private fun startCooldownTicker() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            while (isActive) {
                val remaining = glucoseTestRunner.cooldownRemainingSeconds()
                _uiState.update { it.copy(adHocCooldownSeconds = remaining) }
                delay(1_000)
            }
        }
    }

    private fun MainUiState.toAppSettings(monitoringEnabled: Boolean): AppSettings {
        return AppSettings(
            dexcomUsername = username,
            dexcomPassword = password,
            dexcomRegion = region,
            monitoringEnabled = monitoringEnabled,
            pollIntervalMinutes = pollIntervalMinutes,
            ttsEnabled = ttsEnabled,
            ttsSpeechRate = ttsSpeechRate,
            ttsIncludeTrend = ttsIncludeTrend,
            bluetoothArtEnabled = bluetoothArtEnabled,
            bluetoothFlashDurationSeconds = bluetoothFlashDurationSeconds,
        )
    }
}
