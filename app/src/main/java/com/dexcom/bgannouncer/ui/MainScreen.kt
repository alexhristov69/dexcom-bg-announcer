package com.dexcom.bgannouncer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dexcom.bgannouncer.announce.GlucoseSpeechFormatter
import com.dexcom.bgannouncer.bluetooth.BluetoothPermissionHelper
import com.dexcom.bgannouncer.dexcom.DexcomRegion
import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.data.WorkflowPhase
import com.dexcom.bgannouncer.data.WorkflowSource
import com.dexcom.bgannouncer.test.AdHocTestStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }
    var bluetoothConnectGranted by remember {
        mutableStateOf(BluetoothPermissionHelper.hasConnectPermission(context))
    }
    var pendingBluetoothArtEnable by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        bluetoothConnectGranted = granted
        if (granted && pendingBluetoothArtEnable) {
            pendingBluetoothArtEnable = false
            viewModel.onBluetoothArtEnabledChanged(true)
        } else {
            pendingBluetoothArtEnable = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Dexcom BG Announcer", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Informational use only. Not for treatment decisions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(uiState)
        LastBluetoothArtCard(uiState)
        ConnectionDiagnosticsCard(
            uiState = uiState,
            onClear = viewModel::clearConnectionDiagnostics,
        )

        Button(
            onClick = { viewModel.runAdHocTest() },
            enabled = uiState.canRunAdHocTest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.adHocTestRunning) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                )
            }
            Text(
                when {
                    uiState.adHocTestRunning -> uiState.adHocTestMessage ?: "Running test…"
                    uiState.adHocCooldownSeconds > 0 -> "Run Ad-Hoc Test (${uiState.adHocCooldownSeconds}s)"
                    else -> "Run Ad-Hoc Test"
                },
            )
        }

        OutlinedButton(
            onClick = { viewModel.runTestBroadcast() },
            enabled = uiState.canTestBroadcast,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test Broadcast")
        }

        HorizontalDivider()

        OutlinedTextField(
            value = uiState.username,
            onValueChange = viewModel::onUsernameChanged,
            label = { Text("Dexcom Share username") },
            supportingText = {
                Text("Email, phone (+1…), or account UUID from uam1.dexcom.com")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = uiState.password,
            onValueChange = viewModel::onPasswordChanged,
            label = { Text("Dexcom Share password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) "Hide" else "Show")
                }
            },
        )

        RegionSelector(
            selected = uiState.region,
            onSelected = viewModel::onRegionChanged,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.testConnection() },
                enabled = !uiState.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Test Connection")
            }
            Button(
                onClick = { viewModel.saveSettings() },
                enabled = !uiState.isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Save")
            }
        }

        HorizontalDivider()

        Text("Schedule", style = MaterialTheme.typography.titleMedium)
        Text("Poll every ${uiState.pollIntervalMinutes} minutes")
        Slider(
            value = uiState.pollIntervalMinutes.toFloat(),
            onValueChange = { viewModel.onPollIntervalChanged(it.toInt()) },
            valueRange = 1f..30f,
            steps = 28,
        )

        ToggleRow("Enable TTS announcements", uiState.ttsEnabled, viewModel::onTtsEnabledChanged)
        if (uiState.ttsEnabled) {
            Text("Speech rate: ${"%.1f".format(uiState.ttsSpeechRate)}")
            Slider(
                value = uiState.ttsSpeechRate,
                onValueChange = viewModel::onTtsSpeechRateChanged,
                valueRange = 0.5f..2.0f,
            )
            ToggleRow("Include trend in speech", uiState.ttsIncludeTrend, viewModel::onTtsIncludeTrendChanged)
        }

        ToggleRow(
            label = "Flash Bluetooth cover art",
            checked = uiState.bluetoothArtEnabled,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    viewModel.onBluetoothArtEnabledChanged(false)
                    return@ToggleRow
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !bluetoothConnectGranted) {
                    pendingBluetoothArtEnable = true
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    viewModel.onBluetoothArtEnabledChanged(true)
                }
            },
        )
        if (uiState.bluetoothArtEnabled) {
            Text("Flash duration: ${uiState.bluetoothFlashDurationSeconds} seconds")
            Slider(
                value = uiState.bluetoothFlashDurationSeconds.toFloat(),
                onValueChange = { viewModel.onBluetoothFlashDurationChanged(it.toInt()) },
                valueRange = 2f..10f,
                steps = 7,
            )
        }

        HorizontalDivider()

        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            OutlinedButton(
                onClick = {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                },
                enabled = !bluetoothConnectGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (bluetoothConnectGranted) {
                        "Bluetooth permission granted"
                    } else {
                        "Grant Bluetooth permission (required for cover art)"
                    },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(
                onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant notification permission")
            }
        }
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open notification access (for active music detection)")
        }
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Battery optimization settings")
        }

        HorizontalDivider()

        Button(
            onClick = { viewModel.toggleMonitoring() },
            enabled = uiState.isConfigured && !uiState.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (uiState.monitoringEnabled) "Stop Monitoring" else "Start Monitoring")
        }

        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                uiState.statusMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
                uiState.errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(uiState: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text("Service: ${if (uiState.serviceRunning) "running" else "stopped"}")
                Text("Monitor workflow: ${formatMonitorWorkflow(uiState)}")
                Text("Test broadcast: ${formatTestBroadcastState(uiState)}")
                Text(
                    "Last reading: ${
                        uiState.lastReadingValue?.let { "$it mg/dL ${uiState.lastReadingTrend.orEmpty()}" } ?: "—"
                    }",
                )
                Text("Last poll: ${formatTime(uiState.lastPollTime)}")
                Text(formatNextPollCountdown(uiState))
                Text("Last ad-hoc test: ${formatTime(uiState.lastAdHocTestTime)}")
                uiState.lastAdHocTestResult?.let { Text("Ad-hoc result: $it") }
                uiState.lastError?.let { error ->
                    Text(
                        formatLastError(error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (uiState.adHocTestStep != AdHocTestStep.IDLE && uiState.adHocTestStep != AdHocTestStep.DONE) {
                    Text("Test step: ${uiState.adHocTestMessage ?: uiState.adHocTestStep.name}")
                }
            }
        }
    }
}

@Composable
private fun LastBluetoothArtCard(uiState: MainUiState) {
    var expanded by remember { mutableStateOf(false) }
    val lastFlash = uiState.lastBluetoothArtFlash

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Last Bluetooth art flash", style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "▾" else "▸")
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lastFlash == null) {
                        Text(
                            "No Bluetooth cover art has been sent yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "${lastFlash.caption} · ${formatTime(lastFlash.flashedAtEpochMs)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Image(
                            bitmap = lastFlash.bitmap.asImageBitmap(),
                            contentDescription = "Last Bluetooth cover art: ${lastFlash.caption}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionDiagnosticsCard(
    uiState: MainUiState,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val diagnosticsText = uiState.connectionDiagnostics.toDisplayText()
    val hasDiagnostics = uiState.connectionDiagnostics.exchanges.isNotEmpty()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Connection diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text(if (expanded) "▾" else "▸")
                }
                if (hasDiagnostics) {
                    TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        text = diagnosticsText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun RegionSelector(selected: DexcomRegion, onSelected: (DexcomRegion) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DexcomRegion.entries.forEach { region ->
            val label = when (region) {
                DexcomRegion.US -> "US"
                DexcomRegion.OUS -> "OUS"
                DexcomRegion.JAPAN -> "Japan"
            }
            OutlinedButton(
                onClick = { onSelected(region) },
                enabled = selected != region,
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatMonitorWorkflow(uiState: MainUiState): String {
    if (!uiState.serviceRunning) {
        return "Idle (monitoring stopped)"
    }

    val workflow = uiState.workflowState
    if (workflow.source == WorkflowSource.MONITORING && workflow.phase != WorkflowPhase.IDLE) {
        return formatWorkflowDetail(workflow.phase, workflow.message, uiState.nextPollCountdownSeconds)
    }
    if (uiState.isPolling) {
        return "Polling Dexcom Share"
    }
    if (workflow.phase == WorkflowPhase.WAITING_FOR_NEXT_POLL) {
        return formatWorkflowDetail(
            phase = workflow.phase,
            message = workflow.message,
            countdownSeconds = uiState.nextPollCountdownSeconds,
        )
    }
    return "Idle"
}

private fun formatTestBroadcastState(uiState: MainUiState): String {
    val workflow = uiState.workflowState
    if (workflow.isTestBroadcastActive) {
        return formatWorkflowDetail(workflow.phase, workflow.message, null)
    }
    val summary = workflow.lastTestBroadcastSummary
    if (summary != null) {
        return "Last: $summary (${formatTime(workflow.lastTestBroadcastTime)})"
    }
    return "—"
}

private fun formatWorkflowDetail(
    phase: WorkflowPhase,
    message: String?,
    countdownSeconds: Int?,
): String {
    val base = message ?: when (phase) {
        WorkflowPhase.FETCHING_READING -> "Polling Dexcom Share"
        WorkflowPhase.ANNOUNCING -> "Announcing glucose reading"
        WorkflowPhase.FLASHING_BT -> "Flashing Bluetooth cover art"
        WorkflowPhase.HANDLING_UNAVAILABLE -> "Broadcasting unavailable data"
        WorkflowPhase.WAITING_FOR_NEXT_POLL -> "Waiting for next scheduled poll"
        WorkflowPhase.IDLE -> "Idle"
    }
    return if (phase == WorkflowPhase.WAITING_FOR_NEXT_POLL && countdownSeconds != null && countdownSeconds > 0) {
        "$base · ${formatCountdown(countdownSeconds)} remaining"
    } else {
        base
    }
}

private fun formatLastError(error: String): String {
    val message = if (DexcomShareClient.isNoReadingsMessage(error)) {
        GlucoseSpeechFormatter.unavailableDisplayText()
    } else {
        error
    }
    return "Last error: $message"
}

private fun formatTime(epochMs: Long?): String {
    if (epochMs == null) return "—"
    return TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

private fun formatNextPollCountdown(uiState: MainUiState): String {
    if (!uiState.monitoringEnabled || !uiState.serviceRunning) {
        return "Next poll: —"
    }
    if (uiState.isPolling) {
        return "Next poll: polling Dexcom now…"
    }
    val countdown = uiState.nextPollCountdownSeconds
    return when (countdown) {
        null -> "Next poll: starting…"
        0 -> "Next poll: due now"
        else -> "Next poll in: ${formatCountdown(countdown)}"
    }
}

private fun formatCountdown(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes}m ${seconds.toString().padStart(2, '0')}s"
    } else {
        "${seconds}s"
    }
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a")
