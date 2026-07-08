package com.dexcom.bgannouncer.pipeline

import com.dexcom.bgannouncer.announce.GlucoseAnnouncer
import com.dexcom.bgannouncer.art.GlucoseArtGenerator
import com.dexcom.bgannouncer.bluetooth.ActiveMediaPlaybackGuard
import com.dexcom.bgannouncer.bluetooth.BluetoothArtFlashController
import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.data.SettingsRepository
import com.dexcom.bgannouncer.dexcom.DexcomShareClient
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val activeMediaPlaybackGuard: ActiveMediaPlaybackGuard,
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

        val art = if (settings.bluetoothArtEnabled) {
            artGenerator.generate(reading, settings)
        } else {
            null
        }

        runAnnounceAndFlash(
            settings = settings,
            onStep = onStep,
            announce = {
                announcer.announce(reading, settings)
                announced = true
            },
            flash = { skipPlaybackGuard ->
                val bitmap = art?.primary ?: return@runAnnounceAndFlash false
                val result = runCatching {
                    bluetoothArtFlashController.flashArt(
                        reading = reading,
                        artBitmap = bitmap,
                        durationSeconds = settings.bluetoothFlashDurationSeconds,
                        skipPlaybackGuard = skipPlaybackGuard,
                    )
                }.getOrDefault(false)
                flashed = result
                result
            },
        )

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

        val art = if (settings.bluetoothArtEnabled) {
            artGenerator.generateUnavailable()
        } else {
            null
        }

        runAnnounceAndFlash(
            settings = settings,
            onStep = onStep,
            announce = {
                announcer.announceUnavailable(settings)
                announced = true
            },
            flash = { skipPlaybackGuard ->
                val bitmap = art?.primary ?: return@runAnnounceAndFlash false
                val result = runCatching {
                    bluetoothArtFlashController.flashUnavailableArt(
                        artBitmap = bitmap,
                        durationSeconds = settings.bluetoothFlashDurationSeconds,
                        skipPlaybackGuard = skipPlaybackGuard,
                    )
                }.getOrDefault(false)
                flashed = result
                result
            },
        )

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

    private suspend fun runAnnounceAndFlash(
        settings: AppSettings,
        onStep: (String) -> Unit,
        announce: suspend () -> Unit,
        flash: suspend (skipPlaybackGuard: Boolean) -> Boolean,
    ) {
        val ttsEnabled = settings.ttsEnabled
        val btEnabled = settings.bluetoothArtEnabled
        if (!ttsEnabled && !btEnabled) return

        val runConcurrently = ttsEnabled && btEnabled
        val managePlaybackExternally = runConcurrently && btEnabled

        if (managePlaybackExternally) {
            activeMediaPlaybackGuard.suppressActivePlayback()
        }

        try {
            if (runConcurrently) {
                onStep("Announcing and flashing BT art…")
                coroutineScope {
                    val announceJob = async {
                        runCatching { announce() }
                    }
                    val flashJob = async {
                        runCatching { flash(true) }
                    }
                    announceJob.await()
                    flashJob.await()
                }
            } else {
                if (ttsEnabled) {
                    onStep("Announcing…")
                    runCatching { announce() }
                }
                if (btEnabled) {
                    onStep("Flashing BT art…")
                    runCatching { flash(false) }
                }
            }
        } finally {
            if (managePlaybackExternally) {
                activeMediaPlaybackGuard.restoreActivePlayback()
            }
        }
    }
}
