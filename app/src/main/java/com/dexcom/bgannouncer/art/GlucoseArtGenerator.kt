package com.dexcom.bgannouncer.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.dexcom.bgannouncer.data.AppSettings
import com.dexcom.bgannouncer.dexcom.GlucoseReading
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class GlucoseArt(
    val primary: Bitmap,
    val thumbnail: Bitmap,
)

@Singleton
class GlucoseArtGenerator @Inject constructor() {
    fun generate(reading: GlucoseReading, settings: AppSettings): GlucoseArt {
        val primary = render(reading, settings, PRIMARY_SIZE)
        val thumbnail = render(reading, settings, THUMBNAIL_SIZE)
        return GlucoseArt(primary = primary, thumbnail = thumbnail)
    }

    fun generateUnavailable(): GlucoseArt {
        val primary = renderUnavailable(PRIMARY_SIZE)
        val thumbnail = renderUnavailable(THUMBNAIL_SIZE)
        return GlucoseArt(primary = primary, thumbnail = thumbnail)
    }

    private fun renderUnavailable(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#455A64"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.16f
            isFakeBoldText = true
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.08f
        }

        canvas.drawText("No data", size / 2f, size * 0.46f, titlePaint)
        canvas.drawText("Unavailable", size / 2f, size * 0.58f, subtitlePaint)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = size * 0.015f
        }
        val inset = size * 0.06f
        canvas.drawRoundRect(
            RectF(inset, inset, size - inset, size - inset),
            size * 0.08f,
            size * 0.08f,
            accent,
        )

        return bitmap
    }

    private fun render(reading: GlucoseReading, settings: AppSettings, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundColor = backgroundColorFor(reading, settings)
        canvas.drawColor(backgroundColor)

        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.28f
            isFakeBoldText = true
        }
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.08f
        }
        val trendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.14f
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.06f
        }

        val centerY = size * 0.42f
        canvas.drawText(reading.displayValue(), size / 2f, centerY, valuePaint)
        canvas.drawText("mg/dL", size / 2f, centerY + size * 0.12f, unitPaint)
        canvas.drawText(reading.trend.label, size / 2f, size * 0.72f, trendPaint)

        val formattedTime = TIME_FORMATTER.format(reading.timestamp.atZone(ZoneId.systemDefault()))
        canvas.drawText(formattedTime, size / 2f, size * 0.86f, timePaint)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = size * 0.015f
        }
        val inset = size * 0.06f
        canvas.drawRoundRect(
            RectF(inset, inset, size - inset, size - inset),
            size * 0.08f,
            size * 0.08f,
            accent,
        )

        return bitmap
    }

    private fun backgroundColorFor(reading: GlucoseReading, settings: AppSettings): Int {
        return when {
            reading.valueMgDl < settings.lowThreshold -> Color.parseColor("#C62828")
            reading.valueMgDl > settings.highThreshold -> Color.parseColor("#EF6C00")
            else -> Color.parseColor("#2E7D32")
        }
    }

    companion object {
        const val PRIMARY_SIZE = 512
        const val THUMBNAIL_SIZE = 200
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")
    }
}
