package com.dexcom.bgannouncer.bluetooth

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.dexcom.bgannouncer.announce.GlucoseSpeechFormatter
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothArtFlashController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionHolder: GlucoseMediaSessionHolder,
    private val activeMediaSessionRegistry: ActiveMediaSessionRegistry,
    private val activeMediaPlaybackGuard: ActiveMediaPlaybackGuard,
    private val lastBluetoothArtStore: LastBluetoothArtStore,
) {
    suspend fun flashArt(
        reading: GlucoseReading,
        artBitmap: Bitmap,
        durationSeconds: Int,
    ): Boolean {
        val activeMedia = activeMediaSessionRegistry.getActiveMediaInfo()
        return flash(
            artBitmap = artBitmap,
            title = "${reading.displayValue()} mg/dL",
            artist = reading.trend.label,
            album = activeMedia?.title ?: "Glucose reading",
            durationSeconds = durationSeconds,
            onRecorded = { lastBluetoothArtStore.recordFlash(reading, artBitmap) },
        )
    }

    suspend fun flashUnavailableArt(
        artBitmap: Bitmap,
        durationSeconds: Int,
    ): Boolean {
        return flash(
            artBitmap = artBitmap,
            title = GlucoseSpeechFormatter.UNAVAILABLE_ART_TITLE,
            artist = GlucoseSpeechFormatter.UNAVAILABLE_ART_SUBTITLE,
            album = GlucoseSpeechFormatter.unavailableDisplayText(),
            durationSeconds = durationSeconds,
            onRecorded = { lastBluetoothArtStore.recordUnavailableFlash(artBitmap) },
        )
    }

    private suspend fun flash(
        artBitmap: Bitmap,
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
        onRecorded: () -> Unit,
    ): Boolean {
        onRecorded()

        val connected = runCatching {
            BluetoothAudioConnectivity.isLikelyConnected(context)
        }.getOrDefault(false)
        if (!connected) {
            return false
        }

        val request = BluetoothFlashRequest(
            title = title,
            artist = artist,
            album = album,
            artBitmap = artBitmap,
            durationSeconds = durationSeconds,
        )
        val result = sessionHolder.prepareFlash(request)

        activeMediaPlaybackGuard.suppressActivePlayback()
        return try {
            withContext(Dispatchers.Main) {
                context.startForegroundService(
                    Intent(context, GlucoseMediaSessionService::class.java).apply {
                        action = GlucoseMediaSessionService.ACTION_FLASH
                    },
                )
            }
            withTimeout(FLASH_TIMEOUT_MS) { result.await() }
        } catch (_: Exception) {
            false
        } finally {
            activeMediaPlaybackGuard.restoreActivePlayback()
        }
    }

    companion object {
        private const val FLASH_TIMEOUT_MS = 30_000L
    }
}
