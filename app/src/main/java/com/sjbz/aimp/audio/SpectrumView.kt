package com.sjbz.aimp.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint().apply { color = Color.parseColor("#0A1420") }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1E3A4A")
        strokeWidth = 1f
        alpha = 120
    }
    private val spectrumPaint = Paint().apply {
        color = Color.parseColor("#00D4FF")
        style = Paint.Style.FILL
        isAntiAlias = true
        alpha = 180
    }
    private val linePaint = Paint().apply {
        color = Color.parseColor("#7DF9FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.parseColor("#7DF9FF")
        textSize = 32f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val peakPaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        alpha = 100
    }

    private var fftData = FloatArray(128) { 0f }
    private var peakData = FloatArray(128) { 0f }
    private var peakValue = 0f
    private var peakHoldTime = 0L

    fun updateSpectrum(magnitudes: FloatArray) {
        val n = min(fftData.size, magnitudes.size)
        System.arraycopy(magnitudes, 0, fftData, 0, n)
        for (i in 0 until n) {
            if (magnitudes[i] > peakData[i]) peakData[i] = magnitudes[i]
            else peakData[i] *= 0.98f
        }
        val max = magnitudes.maxOrNull()?: 0f
        if (max > peakValue) {
            peakValue = max
            peakHoldTime = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - peakHoldTime > 1500) {
            peakValue *= 0.99f
        }
        invalidate()
    }

    fun setIdle() {
        for (i in fftData.indices) {
            fftData[i] *= 0.9f
            peakData[i] *= 0.95f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // grid LCD
        for (x in 0..10) {
            val gx = x * w / 10
            canvas.drawLine(gx, 0f, gx, h, gridPaint)
        }
        for (y in 0..4) {
            val gy = y * h / 4
            canvas.drawLine(0f, gy, w, gy, gridPaint)
        }

        val n = 96
        val path = Path()
        val peakPath = Path()
        path.moveTo(0f, h)
        peakPath.moveTo(0f, h)

        for (i in 0 until n) {
            val idx = (i * fftData.size / n).coerceIn(fftData.indices)
            val level = fftData[idx].coerceIn(0f, 1f)
            val px = i * w / n
            val py = h - level * h * 0.75f - h*0.1f

            if (i == 0) {
                path.lineTo(px, py)
                peakPath.lineTo(px, h - peakData[idx].coerceIn(0f,1f) * h * 0.75f - h*0.1f)
            } else {
                path.lineTo(px, py)
                peakPath.lineTo(px, h - peakData[idx].coerceIn(0f,1f) * h * 0.75f - h*0.1f)
            }
        }
        path.lineTo(w, h)
        path.close()

        // relleno
        canvas.drawPath(path, spectrumPaint)

        // línea superior
        val linePath = Path()
        for (i in 0 until n) {
            val idx = (i * fftData.size / n).coerceIn(fftData.indices)
            val level = fftData[idx].coerceIn(0f, 1f)
            val px = i * w / n
            val py = h - level * h * 0.75f - h*0.1f
            if (i == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
        }
        canvas.drawPath(linePath, linePaint)

        // textos estilo LCD
        val db = (-30 + peakValue * 30).toInt().coerceIn(-60, 0)
        canvas.drawText("PEAK HOLD", w - 220f, 40f, textPaint)
        canvas.drawText("PEAK: $db dB", 20f, h - 20f, textPaint)
        canvas.drawText("HOLD", w - 120f, h - 20f, textPaint)
    }
}
