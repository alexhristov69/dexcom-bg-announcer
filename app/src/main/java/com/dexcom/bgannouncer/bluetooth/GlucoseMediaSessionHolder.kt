package com.dexcom.bgannouncer.bluetooth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dexcom.bgannouncer.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BluetoothFlashRequest(
    val title: String,
    val artist: String,
    val album: String,
    val artBitmap: Bitmap,
    val durationSeconds: Int,
)

@Singleton
class GlucoseMediaSessionHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var mediaSession: MediaSession? = null

    @Volatile
    private var currentArtwork: Bitmap? = null

    @Volatile
    private var pendingFlash: BluetoothFlashRequest? = null

    @Volatile
    private var flashResult: CompletableDeferred<Boolean>? = null

    fun initializeInService(service: MediaSessionService): MediaSession {
        player?.let { return mediaSession!! }

        val exoPlayer = ExoPlayer.Builder(service)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ false,
            )
            .build()
            .apply {
                volume = 0f
                playWhenReady = false
            }
        val session = MediaSession.Builder(service, exoPlayer)
            .setSessionActivity(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(service, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setBitmapLoader(SyncBitmapLoader { currentArtwork })
            .build()

        player = exoPlayer
        mediaSession = session
        return session
    }

    fun getSession(): MediaSession? = mediaSession

    fun prepareFlash(request: BluetoothFlashRequest): CompletableDeferred<Boolean> {
        pendingFlash = request
        val deferred = CompletableDeferred<Boolean>()
        flashResult = deferred
        return deferred
    }

    suspend fun executePendingFlash(): Boolean = mutex.withLock {
        val request = pendingFlash ?: return@withLock false
        pendingFlash = null
        val exoPlayer = player ?: return@withLock completeFlash(false)

        currentArtwork = request.artBitmap
        try {
            val artworkBytes = request.artBitmap.toPngBytes()
            val metadata = MediaMetadata.Builder()
                .setTitle(request.title)
                .setArtist(request.artist)
                .setAlbumTitle(request.album)
                .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId("bg_flash_${System.nanoTime()}")
                .setUri(SilentAudioUriFactory.create(context, request.durationSeconds * 1000))
                .setMediaMetadata(metadata)
                .build()

            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

            delay(request.durationSeconds * 1000L)

            exoPlayer.playWhenReady = false
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            true
        } catch (_: Exception) {
            false
        } finally {
            currentArtwork = null
        }
    }

    fun finishFlash(success: Boolean) {
        teardownActivePlayback()
        completeFlash(success)
    }

    fun teardownActivePlayback() {
        player?.apply {
            playWhenReady = false
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        currentArtwork = null
        pendingFlash = null
        flashResult?.complete(false)
        flashResult = null
    }

    private fun completeFlash(success: Boolean): Boolean {
        flashResult?.complete(success)
        flashResult = null
        return success
    }

    private fun Bitmap.toPngBytes(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
