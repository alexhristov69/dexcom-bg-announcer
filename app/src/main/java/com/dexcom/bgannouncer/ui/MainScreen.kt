package com.dexcom.bgannouncer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dexcom.bgannouncer.dexcom.DexcomRegion
import com.dexcom.bgannouncer.test.AdHocTestStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

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

        HorizontalDivider()

        OutlinedTextField(
            value = uiState.username,
            onValueChange = viewModel::onUsernameChanged,
            label = { Text("Dexcom Share username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = uiState.password,
            onValueChange = viewModel::onPasswordChanged,
            label = { Text("Dexcom Share password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
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

        ToggleRow("Flash Bluetooth cover art", uiState.bluetoothArtEnabled, viewModel::onBluetoothArtEnabledChanged)
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

        uiState.statusMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary)
        }
        uiState.errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(uiState: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text("Service: ${if (uiState.serviceRunning) "running" else "stopped"}")
            Text(
                "Last reading: ${
                    uiState.lastReadingValue?.let { "$it mg/dL ${uiState.lastReadingTrend.orEmpty()}" } ?: "—"
                }",
            )
            Text("Last poll: ${formatTime(uiState.lastPollTime)}")
            Text("Last ad-hoc test: ${formatTime(uiState.lastAdHocTestTime)}")
            uiState.lastAdHocTestResult?.let { Text("Ad-hoc result: $it") }
            uiState.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
            if (uiState.adHocTestStep != AdHocTestStep.IDLE && uiState.adHocTestStep != AdHocTestStep.DONE) {
                Text("Test step: ${uiState.adHocTestMessage ?: uiState.adHocTestStep.name}")
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

private fun formatTime(epochMs: Long?): String {
    if (epochMs == null) return "—"
    return TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a")
