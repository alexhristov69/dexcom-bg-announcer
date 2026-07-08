package com.dexcom.bgannouncer.bluetooth

import android.graphics.Bitmap
import com.dexcom.bgannouncer.announce.GlucoseSpeechFormatter
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LastBluetoothArtFlash(
    val bitmap: Bitmap,
    val caption: String,
    val flashedAtEpochMs: Long,
)

@Singleton
class LastBluetoothArtStore @Inject constructor() {
    private val _lastFlash = MutableStateFlow<LastBluetoothArtFlash?>(null)
    val lastFlash: StateFlow<LastBluetoothArtFlash?> = _lastFlash.asStateFlow()

    fun recordFlash(reading: GlucoseReading, artBitmap: Bitmap) {
        recordFlash(
            artBitmap = artBitmap,
            caption = "${reading.valueMgDl} mg/dL ${reading.trend.label}",
        )
    }

    fun recordUnavailableFlash(artBitmap: Bitmap) {
        recordFlash(
            artBitmap = artBitmap,
            caption = GlucoseSpeechFormatter.unavailableDisplayText(),
        )
    }

    private fun recordFlash(artBitmap: Bitmap, caption: String) {
        val copy = artBitmap.copy(artBitmap.config ?: Bitmap.Config.ARGB_8888, false)
        _lastFlash.value?.bitmap?.recycle()
        _lastFlash.value = LastBluetoothArtFlash(
            bitmap = copy,
            caption = caption,
            flashedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
