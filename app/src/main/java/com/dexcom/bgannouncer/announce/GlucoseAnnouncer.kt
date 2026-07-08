package com.dexcom.bgannouncer.announce

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class GlucoseAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var textToSpeech: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)

    init {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
                ttsReady.set(true)
            }
        }
    }

    suspend fun announce(reading: GlucoseReading, settings: AppSettings) {
        if (!settings.ttsEnabled) return
        speak(
            utterance = GlucoseSpeechFormatter.buildUtterance(reading, settings),
            settings = settings,
            utteranceId = "bg_${reading.dedupKey}",
        )
    }

    suspend fun announceUnavailable(settings: AppSettings) {
        if (!settings.ttsEnabled) return
        speak(
            utterance = GlucoseSpeechFormatter.unavailableUtterance(),
            settings = settings,
            utteranceId = "bg_unavailable_${System.currentTimeMillis()}",
        )
    }

    private suspend fun speak(utterance: String, settings: AppSettings, utteranceId: String) {
        val tts = awaitTts()

        val focusRequest = buildAudioFocusRequest()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }

        tts.setSpeechRate(settings.ttsSpeechRate)

        suspendCancellableCoroutine { continuation ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    abandonAudioFocus(focusRequest)
                    if (continuation.isActive) continuation.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    abandonAudioFocus(focusRequest)
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    abandonAudioFocus(focusRequest)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            })

            tts.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private suspend fun awaitTts(): TextToSpeech {
        if (ttsReady.get()) return textToSpeech!!
        return suspendCancellableCoroutine { continuation ->
            val handler = android.os.Handler(context.mainLooper)
            val poll = object : Runnable {
                override fun run() {
                    if (ttsReady.get()) {
                        continuation.resume(textToSpeech!!)
                    } else {
                        handler.postDelayed(this, 100)
                    }
                }
            }
            handler.post(poll)
        }
    }

    private fun buildAudioFocusRequest(): AudioFocusRequest {
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
    }

    private fun abandonAudioFocus(focusRequest: AudioFocusRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
