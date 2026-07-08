package com.dexcom.bgannouncer.bluetooth

import android.graphics.Bitmap
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LastBluetoothArtFlash(
    val bitmap: Bitmap,
    val valueMgDl: Int,
    val trendLabel: String,
    val flashedAtEpochMs: Long,
)

@Singleton
class LastBluetoothArtStore @Inject constructor() {
    private val _lastFlash = MutableStateFlow<LastBluetoothArtFlash?>(null)
    val lastFlash: StateFlow<LastBluetoothArtFlash?> = _lastFlash.asStateFlow()

    fun recordFlash(reading: GlucoseReading, artBitmap: Bitmap) {
        val copy = artBitmap.copy(artBitmap.config ?: Bitmap.Config.ARGB_8888, false)
        _lastFlash.value?.bitmap?.recycle()
        _lastFlash.value = LastBluetoothArtFlash(
            bitmap = copy,
            valueMgDl = reading.valueMgDl,
            trendLabel = reading.trend.label,
            flashedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
