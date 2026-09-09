package com.sjbz.aimp.audio

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Crossover filter manager and ATS2835P Hardware SoftClipper.
 * Uses smooth hyperbolic tangent (tanh) & polynomial curve to eliminate harsh digital squaring
 * and introduce warm musical harmonics when audio peaks exceed headroom.
 */
class CrossoverProcessor {

    // 4 crossover cutoffs defining the 5 bands
    val crossoverPointsHz = floatArrayOf(120f, 500f, 2000f, 8000f)

    var isSoftClipperEnabled: Boolean = true
    var driveFactor: Float = 1.15f
    var kneeStart: Float = 0.707f // ~ -3 dBFS knee point

    /**
     * ATS2835P hardware-modeled soft clipping transfer curve.
     * Takes an input audio sample normalized [-1.0, 1.0] and returns a smoothly saturated sample.
     */
    fun processSample(inputSample: Float): Float {
        if (!isSoftClipperEnabled) return inputSample.coerceIn(-1.0f, 1.0f)

        val x = inputSample * driveFactor
        val absX = abs(x)

        return if (absX < kneeStart) {
            // Linear region
            x
        } else {
            // Smooth polynomial / tanh saturation region
            val sign = if (x >= 0) 1.0f else -1.0f
            val compressed = kneeStart + (1.0f - kneeStart) * tanh((absX - kneeStart) / (1.0f - kneeStart))
            sign * compressed.coerceIn(0f, 0.999f)
        }
    }
}
