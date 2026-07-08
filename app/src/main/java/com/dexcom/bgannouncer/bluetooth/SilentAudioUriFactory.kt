package com.dexcom.bgannouncer.bluetooth

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

object SilentAudioUriFactory {
    fun create(context: Context, durationMs: Int): Uri {
        val safeDurationMs = durationMs.coerceIn(100, 30_000)
        val file = File(context.cacheDir, "bg_silence_${safeDurationMs}ms.wav")
        if (!file.exists()) {
            writeSilentWav(file, safeDurationMs)
        }
        return Uri.fromFile(file)
    }

    private fun writeSilentWav(file: File, durationMs: Int) {
        val sampleRate = 44_100
        val channels = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val numSamples = sampleRate * durationMs / 1000
        val dataSize = numSamples * channels * bytesPerSample
        val totalDataLen = 36 + dataSize

        RandomAccessFile(file, "rw").use { wav ->
            wav.writeBytes("RIFF")
            wav.writeIntLE(totalDataLen)
            wav.writeBytes("WAVE")
            wav.writeBytes("fmt ")
            wav.writeIntLE(16)
            wav.writeShortLE(1)
            wav.writeShortLE(channels.toShort())
            wav.writeIntLE(sampleRate)
            wav.writeIntLE(sampleRate * channels * bytesPerSample)
            wav.writeShortLE((channels * bytesPerSample).toShort())
            wav.writeShortLE(bitsPerSample.toShort())
            wav.writeBytes("data")
            wav.writeIntLE(dataSize)
            val silence = ByteArray(4096)
            var remaining = dataSize
            while (remaining > 0) {
                val chunk = minOf(remaining, silence.size)
                wav.write(silence, 0, chunk)
                remaining -= chunk
            }
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                (value shr 8 and 0xFF).toByte(),
                (value shr 16 and 0xFF).toByte(),
                (value shr 24 and 0xFF).toByte(),
            ),
        )
    }

    private fun RandomAccessFile.writeShortLE(value: Short) {
        write(
            byteArrayOf(
                (value.toInt() and 0xFF).toByte(),
                (value.toInt() shr 8 and 0xFF).toByte(),
            ),
        )
    }
}
