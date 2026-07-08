package com.dexcom.bgannouncer.pipeline

import com.dexcom.bgannouncer.announce.GlucoseAnnouncer
import com.dexcom.bgannouncer.art.GlucoseArtGenerator
import com.dexcom.bgannouncer.bluetooth.BluetoothArtFlashController
import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.data.SettingsRepository
import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import javax.inject.Inject
import javax.inject.Singleton

data class PipelineResult(
    val reading: GlucoseReading,
    val announced: Boolean,
    val flashedBluetooth: Boolean,
    val skippedAsDuplicate: Boolean,
)

data class UnavailableDataResult(
    val announced: Boolean,
    val flashedBluetooth: Boolean,
)

@Singleton
class GlucoseActionPipeline @Inject constructor(
    private val announcer: GlucoseAnnouncer,
    private val artGenerator: GlucoseArtGenerator,
    private val bluetoothArtFlashController: BluetoothArtFlashController,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun processReading(
        reading: GlucoseReading,
        settings: AppSettings,
        forceAnnounce: Boolean,
        onStep: (String) -> Unit = {},
    ): PipelineResult {
        val status = settingsRepository.getRuntimeStatus()
        val isDuplicate = status.lastAnnouncedDedupKey == reading.dedupKey
        if (!forceAnnounce && isDuplicate) {
            return PipelineResult(
                reading = reading,
                announced = false,
                flashedBluetooth = false,
                skippedAsDuplicate = true,
            )
        }

        var announced = false
        var flashed = false

        if (settings.ttsEnabled) {
            onStep("Announcing…")
            announcer.announce(reading, settings)
            announced = true
        }

        if (settings.bluetoothArtEnabled) {
            onStep("Flashing BT art…")
            val art = artGenerator.generate(reading, settings)
            flashed = runCatching {
                bluetoothArtFlashController.flashArt(
                    reading = reading,
                    artBitmap = art.primary,
                    durationSeconds = settings.bluetoothFlashDurationSeconds,
                )
            }.getOrDefault(false)
        }

        settingsRepository.updateRuntimeStatus {
            copy(
                lastReadingValue = reading.valueMgDl,
                lastReadingTrend = reading.trend.label,
                lastReadingTime = reading.timestamp.toEpochMilli(),
                lastAnnouncedDedupKey = reading.dedupKey,
                lastError = null,
            )
        }

        return PipelineResult(
            reading = reading,
            announced = announced,
            flashedBluetooth = flashed,
            skippedAsDuplicate = false,
        )
    }

    suspend fun processUnavailableData(
        settings: AppSettings,
        onStep: (String) -> Unit = {},
    ): UnavailableDataResult {
        var announced = false
        var flashed = false

        if (settings.ttsEnabled) {
            onStep("Announcing…")
            announcer.announceUnavailable(settings)
            announced = true
        }

        if (settings.bluetoothArtEnabled) {
            onStep("Flashing BT art…")
            val art = artGenerator.generateUnavailable()
            flashed = runCatching {
                bluetoothArtFlashController.flashUnavailableArt(
                    artBitmap = art.primary,
                    durationSeconds = settings.bluetoothFlashDurationSeconds,
                )
            }.getOrDefault(false)
        }

        settingsRepository.updateRuntimeStatus {
            copy(
                lastReadingValue = null,
                lastReadingTrend = null,
                lastReadingTime = null,
                lastError = DexcomShareClient.NO_READINGS_MESSAGE,
            )
        }

        return UnavailableDataResult(
            announced = announced,
            flashedBluetooth = flashed,
        )
    }
}
