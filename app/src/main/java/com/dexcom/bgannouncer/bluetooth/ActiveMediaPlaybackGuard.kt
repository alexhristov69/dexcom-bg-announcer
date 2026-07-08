package com.dexcom.bgannouncer.bluetooth

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveMediaPlaybackGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeMediaSessionRegistry: ActiveMediaSessionRegistry,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var interruptedPackageName: String? = null
    private var shouldResume = false
    private var audioFocusRequest: AudioFocusRequest? = null

    suspend fun suppressActivePlayback() {
        withContext(Dispatchers.Main) {
            activeMediaSessionRegistry.refresh()
            val controller = activeMediaSessionRegistry.getPlayingController()
            interruptedPackageName = controller?.packageName
            shouldResume = controller?.isActivelyPlaying() == true
            if (shouldResume) {
                controller?.transportControls?.pause()
            }
        }
        requestAudioFocus()
        delay(SETTLE_DELAY_MS)
    }

    suspend fun restoreActivePlayback() {
        delay(RESTORE_DELAY_MS)
        abandonAudioFocus()
        delay(SETTLE_DELAY_MS)
        if (!shouldResume) {
            interruptedPackageName = null
            return
        }

        withContext(Dispatchers.Main) {
            activeMediaSessionRegistry.refresh()
            val controller = activeMediaSessionRegistry.getControllerForPackage(interruptedPackageName)
                ?: activeMediaSessionRegistry.getActiveController()
            controller?.transportControls?.play()
            interruptedPackageName = null
            shouldResume = false
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val SETTLE_DELAY_MS = 350L
        private const val RESTORE_DELAY_MS = 500L
    }
}

private fun MediaController.isActivelyPlaying(): Boolean {
    return when (playbackState?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        -> true
        else -> false
    }
}
