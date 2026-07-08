package com.dexcom.bgannouncer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dexcom.bgannouncer.bluetooth.LastBluetoothArtFlash
import com.dexcom.bgannouncer.bluetooth.LastBluetoothArtStore
import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.data.ConnectionDiagnostics
import com.dexcom.bgannouncer.data.ConnectionDiagnosticsRepository
import com.dexcom.bgannouncer.data.DexcomCredentials
import com.dexcom.bgannouncer.data.MonitorWorkflowRepository
import com.dexcom.bgannouncer.data.RuntimeStatus
import com.dexcom.bgannouncer.data.SettingsRepository
import com.dexcom.bgannouncer.data.WorkflowPhase
import com.dexcom.bgannouncer.data.WorkflowSource
import com.dexcom.bgannouncer.data.WorkflowState
import com.dexcom.bgannouncer.data.toGlucoseReading
import com.dexcom.bgannouncer.dexcom.DexcomRegion
import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.pipeline.GlucoseActionPipeline
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
    val nextPollCountdownSeconds: Int? = null,
    val isPolling: Boolean = false,
    val lastAdHocTestTime: Long? = null,
    val lastAdHocTestResult: String? = null,
    val lastError: String? = null,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val adHocTestStep: AdHocTestStep = AdHocTestStep.IDLE,
    val adHocTestMessage: String? = null,
    val adHocCooldownSeconds: Int = 0,
    val connectionDiagnostics: ConnectionDiagnostics = ConnectionDiagnosticsRepository.emptySnapshot(),
    val lastBluetoothArtFlash: LastBluetoothArtFlash? = null,
    val workflowState: WorkflowState = WorkflowState(),
) {
    val isConfigured: Boolean = username.isNotBlank() && password.isNotBlank()
    val adHocTestRunning: Boolean = adHocTestStep == AdHocTestStep.FETCHING ||
        adHocTestStep == AdHocTestStep.ANNOUNCING ||
        adHocTestStep == AdHocTestStep.FLASHING_BT
    val canRunAdHocTest: Boolean = isConfigured && !adHocTestRunning && adHocCooldownSeconds == 0 && !isBusy
    val canTestBroadcast: Boolean =
        (lastReadingValue != null || DexcomShareClient.isNoReadingsMessage(lastError)) &&
            !adHocTestRunning && !isBusy && !workflowState.isTestBroadcastActive
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val dexcomShareClient: DexcomShareClient,
    private val glucoseTestRunner: GlucoseTestRunner,
    private val pipeline: GlucoseActionPipeline,
    private val lastBluetoothArtStore: LastBluetoothArtStore,
    private val workflowRepository: MonitorWorkflowRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var statusTickerJob: Job? = null

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
                    startStatusTicker()
                }
            }
        }
        startStatusTicker()
        viewModelScope.launch {
            lastBluetoothArtStore.lastFlash.collect { flash ->
                _uiState.update { it.copy(lastBluetoothArtFlash = flash) }
            }
        }
        viewModelScope.launch {
            workflowRepository.state.collect { workflow ->
                _uiState.update { it.copy(workflowState = workflow) }
            }
        }
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
                    connectionDiagnostics = dexcomShareClient.getDiagnostics(),
                )
            }
        }
    }

    fun clearConnectionDiagnostics() {
        dexcomShareClient.clearDiagnostics()
        _uiState.update {
            it.copy(connectionDiagnostics = ConnectionDiagnosticsRepository.emptySnapshot())
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
                    connectionDiagnostics = dexcomShareClient.getDiagnostics(),
                )
            }
            glucoseTestRunner.resetIdle()
        }
    }

    fun runTestBroadcast() {
        viewModelScope.launch {
            saveSettings()
            _uiState.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            val settings = settingsRepository.getSettings()
            val status = settingsRepository.getRuntimeStatus()
            workflowRepository.setActive(
                phase = WorkflowPhase.ANNOUNCING,
                message = "Starting test broadcast",
                source = WorkflowSource.TEST_BROADCAST,
            )

            val summary = when {
                status.lastReadingValue != null -> {
                    val reading = status.toGlucoseReading()
                        ?: run {
                            workflowRepository.completeTestBroadcast("No last reading to broadcast")
                            _uiState.update {
                                it.copy(isBusy = false, errorMessage = "No last reading to broadcast")
                            }
                            return@launch
                        }
                    val result = pipeline.processReading(
                        reading = reading,
                        settings = settings,
                        forceAnnounce = true,
                        onStep = { message ->
                            workflowRepository.updateFromStep(message, WorkflowSource.TEST_BROADCAST)
                        },
                    )
                    buildBroadcastSummary(
                        headline = "${reading.displayValue()} mg/dL ${reading.trend.label}",
                        announced = result.announced,
                        flashedBluetooth = result.flashedBluetooth,
                        settings = settings,
                    )
                }
                DexcomShareClient.isNoReadingsMessage(status.lastError) -> {
                    workflowRepository.setActive(
                        phase = WorkflowPhase.HANDLING_UNAVAILABLE,
                        message = "Broadcasting unavailable data",
                        source = WorkflowSource.TEST_BROADCAST,
                    )
                    val result = pipeline.processUnavailableData(settings) { message ->
                        workflowRepository.updateFromStep(message, WorkflowSource.TEST_BROADCAST)
                    }
                    buildBroadcastSummary(
                        headline = "Blood glucose data unavailable",
                        announced = result.announced,
                        flashedBluetooth = result.flashedBluetooth,
                        settings = settings,
                    )
                }
                else -> {
                    workflowRepository.completeTestBroadcast("No last reading to broadcast")
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = "No last reading to broadcast")
                    }
                    return@launch
                }
            }

            workflowRepository.completeTestBroadcast(summary)
            refreshFromRepository()
            _uiState.update {
                it.copy(isBusy = false, statusMessage = "Test broadcast: $summary")
            }
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
        refreshStatus()
        _uiState.update {
            it.copy(
                monitoringEnabled = nextEnabled,
                statusMessage = if (nextEnabled) "Monitoring started" else "Monitoring stopped",
            )
        }
    }

    fun refreshStatus() {
        refreshFromRepository()
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
                isPolling = status.isPolling,
                nextPollCountdownSeconds = computeNextPollCountdown(status),
                lastAdHocTestTime = status.lastAdHocTestTime,
                lastAdHocTestResult = status.lastAdHocTestResult,
                lastError = status.lastError,
                connectionDiagnostics = dexcomShareClient.getDiagnostics(),
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

    private fun startStatusTicker() {
        statusTickerJob?.cancel()
        statusTickerJob = viewModelScope.launch {
            while (isActive) {
                val status = settingsRepository.getRuntimeStatus()
                _uiState.update {
                    it.copy(
                        adHocCooldownSeconds = glucoseTestRunner.cooldownRemainingSeconds(),
                        serviceRunning = status.serviceRunning,
                        lastReadingValue = status.lastReadingValue,
                        lastReadingTrend = status.lastReadingTrend,
                        lastPollTime = status.lastPollTime,
                        isPolling = status.isPolling,
                        lastAdHocTestTime = status.lastAdHocTestTime,
                        lastAdHocTestResult = status.lastAdHocTestResult,
                        lastError = status.lastError,
                        nextPollCountdownSeconds = computeNextPollCountdown(status),
                        connectionDiagnostics = if (status.serviceRunning) {
                            dexcomShareClient.getDiagnostics()
                        } else {
                            it.connectionDiagnostics
                        },
                    )
                }
                delay(1_000)
            }
        }
    }

    private fun computeNextPollCountdown(status: RuntimeStatus): Int? {
        if (!status.serviceRunning) return null
        if (status.isPolling) return 0
        val nextPollTime = status.nextPollTime ?: return null
        val remainingMs = nextPollTime - System.currentTimeMillis()
        return (remainingMs / 1_000L).toInt().coerceAtLeast(0)
    }

    private fun buildBroadcastSummary(
        headline: String,
        announced: Boolean,
        flashedBluetooth: Boolean,
        settings: AppSettings,
    ): String {
        return buildString {
            append(headline)
            if (announced) append(" · announced")
            if (flashedBluetooth) append(" · BT flash")
            if (!announced && !settings.ttsEnabled) append(" · TTS skipped")
            if (!flashedBluetooth && settings.bluetoothArtEnabled) append(" · BT unavailable")
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
