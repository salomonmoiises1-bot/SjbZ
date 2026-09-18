package com.sjbz.aimp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class AudioSpectrumVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val BINS_COUNT = 32
        private const val ATTACK_FACTOR = 0.65f
        private const val DECAY_FACTOR = 0.88f
        private const val PEAK_HOLD_DECAY = 0.94f
    }

    private val currentMagnitudes = FloatArray(BINS_COUNT)
    private val peakHoldMagnitudes = FloatArray(BINS_COUNT)
    private val rawBinAccumulators = FloatArray(BINS_COUNT)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0F7FA")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2B3C")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val barRect = RectF()
    private var barGradient: LinearGradient? = null
    private val lock = Any()
    private var isMockRunning = false

    private val mockRunnable = object : Runnable {
        override fun run() {
            if (!isMockRunning) return
            val mockSamples = FloatArray(128) { (Math.random() * 0.4 - 0.2).toFloat() }
            onAudioData(mockSamples)
            postDelayed(this, 50)
        }
    }

    init { setBackgroundColor(Color.parseColor("#0A0E17")) }

    // === FIX PARA COMPILAR: alias que pide EqStudioActivity ===
    fun updateFft(samples: FloatArray) {
        onAudioData(samples)
    }
    fun setFftData(samples: FloatArray) {
        onAudioData(samples)
    }
    // ==========================================================

    fun startMockVisualizer() {
        if (!isMockRunning) {
            isMockRunning = true
            post(mockRunnable)
        }
    }

    fun stopMockVisualizer() {
        isMockRunning = false
        removeCallbacks(mockRunnable)
    }

    fun onAudioData(samples: FloatArray) {
        if (samples.isEmpty()) return
        val sampleCount = samples.size
        synchronized(lock) {
            rawBinAccumulators.fill(0f)
            val minIndex = 1
            val maxIndex = min(sampleCount / 2, 1024)
            for (b in 0 until BINS_COUNT) {
                val startRatio = Math.pow(b.toDouble() / BINS_COUNT, 1.8)
                val endRatio = Math.pow((b + 1.0) / BINS_COUNT, 1.8)
                val startIdx = minIndex + ((maxIndex - minIndex) * startRatio).toInt()
                val endIdx = maxOf(startIdx + 1, minIndex + ((maxIndex - minIndex) * endRatio).toInt())
                var sumSquares = 0f
                var count = 0
                for (i in startIdx until min(endIdx, sampleCount)) {
                    val s = samples[i]
                    sumSquares += s * s
                    count++
                }
                val rms = if (count > 0) sqrt(sumSquares / count) else 0f
                val highWeight = 1.0f + (b.toFloat() / BINS_COUNT) * 1.5f
                rawBinAccumulators[b] = min(1.0f, rms * 4.5f * highWeight)
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            barGradient = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#0284C7"), Color.parseColor("#0F172A")),
                floatArrayOf(0.0f, 0.7f, 1.0f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = barGradient
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        canvas.drawLine(0f, height * 0.25f, width, height * 0.25f, gridPaint)
        canvas.drawLine(0f, height * 0.50f, width, height * 0.50f, gridPaint)
        canvas.drawLine(0f, height * 0.75f, width, height * 0.75f, gridPaint)

        synchronized(lock) {
            for (i in 0 until BINS_COUNT) {
                val target = rawBinAccumulators[i]
                if (target > currentMagnitudes[i]) {
                    currentMagnitudes[i] = currentMagnitudes[i] + (target - currentMagnitudes[i]) * ATTACK_FACTOR
                } else {
                    currentMagnitudes[i] = currentMagnitudes[i] * DECAY_FACTOR
                }
                if (currentMagnitudes[i] >= peakHoldMagnitudes[i]) {
                    peakHoldMagnitudes[i] = currentMagnitudes[i]
                } else {
                    peakHoldMagnitudes[i] = peakHoldMagnitudes[i] * PEAK_HOLD_DECAY
                }
            }
        }

        val totalSpacing = width * 0.15f
        val barSpacing = totalSpacing / (BINS_COUNT + 1)
        val barWidth = (width - totalSpacing) / BINS_COUNT

        for (i in 0 until BINS_COUNT) {
            val left = barSpacing + i * (barWidth + barSpacing)
            val right = left + barWidth
            val mag = currentMagnitudes[i].coerceIn(0.02f, 1.0f)
            val barHeight = height * mag
            barRect.set(left, height - barHeight, right, height)
            canvas.drawRoundRect(barRect, 4f, 4f, barPaint)
            val peakMag = peakHoldMagnitudes[i].coerceIn(0.02f, 1.0f)
            val peakY = height - (height * peakMag)
            if (peakY < height - barHeight - 2f) {
                canvas.drawLine(left, peakY, right, peakY, peakPaint)
            }
        }

        var stillActive = false
        for (i in 0 until BINS_COUNT) {
            if (currentMagnitudes[i] > 0.01f || peakHoldMagnitudes[i] > 0.01f) { stillActive = true; break }
        }
        if (stillActive) postInvalidateOnAnimation()
    }
}
