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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 60fps Precision Spectrum Analyzer View for SjbZ Studio.
 *
 * Connected directly to SjbzDspProcessor.fftListener (2048 mono PCM floats).
 * Renders 32 high-resolution frequency bins corresponding to the 32 ISO EQ bands
 * with smooth peak decay ballistics and Cyan studio glow.
 */
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

    // Normalized magnitudes (0.0 to 1.0)
    private val currentMagnitudes = FloatArray(BINS_COUNT)
    private val peakHoldMagnitudes = FloatArray(BINS_COUNT)
    private val rawBinAccumulators = FloatArray(BINS_COUNT)

    // Preallocated rendering objects (Zero-allocation during onDraw)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0F7FA") // Crisp bright highlight
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
    private var lastWidth = 0
    private var lastHeight = 0

    // Thread-safe lock for audio data updates
    private val lock = Any()

    init {
        setBackgroundColor(Color.parseColor("#0A0E17"))
    }

    /**
     * Called by SjbzDspProcessor.fftListener with 2048 mono float samples.
     * Computes fast logarithmic band energy across the 32 ISO frequency centers.
     */
    fun onAudioData(samples: FloatArray) {
        if (samples.isEmpty()) return

        val sampleCount = samples.size
        // Map 2048 samples into 32 frequency bins with logarithmic spacing
        synchronized(lock) {
            rawBinAccumulators.fill(0f)

            // Split 2048 samples into 32 logarithmic sub-bands
            val minIndex = 1
            val maxIndex = min(sampleCount / 2, 1024)

            for (b in 0 until BINS_COUNT) {
                // Logarithmic index range in FFT spectrum
                val startRatio = Math.pow(b.toDouble() / BINS_COUNT, 1.8)
                val endRatio = Math.pow((b + 1.0) / BINS_COUNT, 1.8)

                val startIdx = minIndex + ((maxIndex - minIndex) * startRatio).toInt()
                val endIdx = max(startIdx + 1, minIndex + ((maxIndex - minIndex) * endRatio).toInt())

                var sumSquares = 0f
                var count = 0
                for (i in startIdx until min(endIdx, sampleCount)) {
                    val s = samples[i]
                    sumSquares += s * s
                    count++
                }

                val rms = if (count > 0) sqrt(sumSquares / count) else 0f
                // Apply subtle frequency weighting (boost highs slightly for optical balance)
                val highWeight = 1.0f + (b.toFloat() / BINS_COUNT) * 1.5f
                rawBinAccumulators[b] = min(1.0f, rms * 4.5f * highWeight)
            }
        }

        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            lastWidth = w
            lastHeight = h
            // Electric Cyan to Deep Cyan gradient
            barGradient = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(
                    Color.parseColor("#00E5FF"), // Bright Cyan
                    Color.parseColor("#0284C7"), // Deep Cyan
                    Color.parseColor("#0F172A")  // Base Slate
                ),
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

        // 1. Draw subtle horizontal grid dB lines (-6dB, -12dB, -18dB)
        canvas.drawLine(0f, height * 0.25f, width, height * 0.25f, gridPaint)
        canvas.drawLine(0f, height * 0.50f, width, height * 0.50f, gridPaint)
        canvas.drawLine(0f, height * 0.75f, width, height * 0.75f, gridPaint)

        // 2. Read new bin values and update ballistics
        synchronized(lock) {
            for (i in 0 until BINS_COUNT) {
                val target = rawBinAccumulators[i]
                if (target > currentMagnitudes[i]) {
                    // Attack (rapid rise)
                    currentMagnitudes[i] = currentMagnitudes[i] + (target - currentMagnitudes[i]) * ATTACK_FACTOR
                } else {
                    // Decay (smooth exponential drop)
                    currentMagnitudes[i] = currentMagnitudes[i] * DECAY_FACTOR
                }

                // Peak hold meter
                if (currentMagnitudes[i] >= peakHoldMagnitudes[i]) {
                    peakHoldMagnitudes[i] = currentMagnitudes[i]
                } else {
                    peakHoldMagnitudes[i] = peakHoldMagnitudes[i] * PEAK_HOLD_DECAY
                }
            }
        }

        // 3. Render 32 bars with rounded corners and peak indicators
        val totalSpacing = width * 0.15f
        val barSpacing = totalSpacing / (BINS_COUNT + 1)
        val barWidth = (width - totalSpacing) / BINS_COUNT

        for (i in 0 until BINS_COUNT) {
            val left = barSpacing + i * (barWidth + barSpacing)
            val right = left + barWidth

            val mag = currentMagnitudes[i].coerceIn(0.02f, 1.0f)
            val barHeight = height * mag
            val top = height - barHeight
            val bottom = height

            barRect.set(left, top, right, bottom)
            canvas.drawRoundRect(barRect, 4f, 4f, barPaint)

            // Draw floating peak indicator line
            val peakMag = peakHoldMagnitudes[i].coerceIn(0.02f, 1.0f)
            val peakY = height - (height * peakMag)
            if (peakY < top - 2f) {
                canvas.drawLine(left, peakY, right, peakY, peakPaint)
            }
        }

        // Keep 60fps animation alive while magnitudes are decaying
        var stillActive = false
        for (i in 0 until BINS_COUNT) {
            if (currentMagnitudes[i] > 0.01f || peakHoldMagnitudes[i] > 0.01f) {
                stillActive = true
                break
            }
        }

        if (stillActive) {
            postInvalidateOnAnimation()
        }
    }
}
