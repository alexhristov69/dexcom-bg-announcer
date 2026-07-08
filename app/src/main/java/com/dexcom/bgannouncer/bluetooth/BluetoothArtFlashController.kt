package com.dexcom.bgannouncer.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.dexcom.bgannouncer.announce.GlucoseSpeechFormatter
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothArtFlashController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionHolder: GlucoseMediaSessionHolder,
    private val activeMediaSessionRegistry: ActiveMediaSessionRegistry,
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
        if (!isBluetoothAudioConnected()) return false

        val session = sessionHolder.ensureSession()

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artBitmap)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artBitmap)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 60_000L)
            .build()

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE,
            )
            .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
            .build()

        session.isActive = true
        session.setMetadata(metadata)
        session.setPlaybackState(playbackState)
        onRecorded()

        delay(durationSeconds * 1000L)

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                .build(),
        )
        session.setMetadata(MediaMetadataCompat.Builder().build())
        session.isActive = false
        return true
    }

    private fun isBluetoothAudioConnected(): Boolean {
        if (!BluetoothPermissionHelper.hasConnectPermission(context)) return false
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adapter.isEnabled) return false
        @Suppress("MissingPermission")
        val connected = adapter.getProfileConnectionState(BluetoothProfile.A2DP) ==
            BluetoothProfile.STATE_CONNECTED
        return connected
    }
}

@Singleton
class GlucoseMediaSessionHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var mediaSession: MediaSessionCompat? = null

    fun ensureSession(): MediaSessionCompat {
        return mediaSession ?: synchronized(this) {
            mediaSession ?: MediaSessionCompat(context, SESSION_TAG).also { session ->
                session.setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
                )
                mediaSession = session
            }
        }
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
    }

    companion object {
        private const val SESSION_TAG = "DexcomBgAnnouncer"
    }
}
