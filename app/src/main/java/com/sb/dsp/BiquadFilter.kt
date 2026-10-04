package com.sb.dsp

import kotlin.math.*

class BiquadFilter {
    enum class Type {
        PEAKING_EQ, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, BAND_PASS
    }

    @PublishedApi internal var b0 = 1.0f; @PublishedApi internal var b1 = 0.0f; @PublishedApi internal var b2 = 0.0f
    @PublishedApi internal var a1 = 0.0f; @PublishedApi internal var a2 = 0.0f
    @PublishedApi internal var s1L = 0.0f; @PublishedApi internal var s2L = 0.0f
    private var s1R = 0.0f; private var s2R = 0.0f

    fun configure(type: Type, sampleRate: Float, centerFreq: Float, q: Float, gainDb: Float = 0.0f) {
        if (type == Type.PEAKING_EQ && abs(gainDb) < 0.02f) {
            b0 = 1.0f; b1 = 0.0f; b2 = 0.0f; a1 = 0.0f; a2 = 0.0f
            return
        }
        val safeFreq = centerFreq.coerceIn(10.0f, sampleRate * 0.49f)
        val omega = (2.0 * Math.PI * safeFreq / sampleRate).toFloat()
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)
        val alpha = sinOmega / (2.0f * q.coerceAtLeast(0.1f))
        val aLinear = 10.0f.pow(gainDb / 40.0f)

        var rawB0 = 1.0f; var rawB1 = 0.0f; var rawB2 = 0.0f
        var rawA0 = 1.0f; var rawA1 = 0.0f; var rawA2 = 0.0f

        when (type) {
            Type.PEAKING_EQ -> {
                rawB0 = 1.0f + alpha * aLinear
                rawB1 = -2.0f * cosOmega
                rawB2 = 1.0f - alpha * aLinear
                rawA0 = 1.0f + alpha / aLinear
                rawA1 = -2.0f * cosOmega
                rawA2 = 1.0f - alpha / aLinear
            }
            Type.LOW_SHELF -> {
                val sqrtA = sqrt(aLinear)
                val twoSqrtAAlpha = 2.0f * sqrtA * alpha
                rawB0 = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cosOmega + twoSqrtAAlpha)
                rawB1 = 2.0f * aLinear * ((aLinear - 1.0f) - (aLinear + 1.0f) * cosOmega)
                rawB2 = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cosOmega - twoSqrtAAlpha)
                rawA0 = (aLinear + 1.0f) + (aLinear - 1.0f) * cosOmega + twoSqrtAAlpha
                rawA1 = -2.0f * ((aLinear - 1.0f) + (aLinear + 1.0f) * cosOmega)
                rawA2 = (aLinear + 1.0f) - (aLinear - 1.0f) * cosOmega - twoSqrtAAlpha
            }
            Type.HIGH_SHELF -> {
                val sqrtA = sqrt(aLinear)
                val twoSqrtAAlpha = 2.0f * sqrtA * alpha
                rawB0 = aLinear * ((aLinear + 1.0f) + (aLinear - 1.0f) * cosOmega + twoSqrtAAlpha)
                rawB1 = -2.0f * aLinear * ((aLinear - 1.0f) + (aLinear + 1.0f) * cosOmega)
                rawB2 = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cosOmega - twoSqrtAAlpha)
                rawA0 = (aLinear + 1.0f) - (aLinear - 1.0f) * cosOmega + twoSqrtAAlpha
                rawA1 = 2.0f * ((aLinear - 1.0f) - (aLinear + 1.0f) * cosOmega)
                rawA2 = (aLinear + 1.0f) - (aLinear - 1.0f) * cosOmega - twoSqrtAAlpha
            }
            Type.LOW_PASS -> {
                rawB0 = (1.0f - cosOmega) / 2.0f; rawB1 = 1.0f - cosOmega; rawB2 = (1.0f - cosOmega) / 2.0f
                rawA0 = 1.0f + alpha; rawA1 = -2.0f * cosOmega; rawA2 = 1.0f - alpha
            }
            Type.HIGH_PASS -> {
                rawB0 = (1.0f + cosOmega) / 2.0f; rawB1 = -(1.0f + cosOmega); rawB2 = (1.0f + cosOmega) / 2.0f
                rawA0 = 1.0f + alpha; rawA1 = -2.0f * cosOmega; rawA2 = 1.0f - alpha
            }
            Type.BAND_PASS -> {
                rawB0 = alpha; rawB1 = 0.0f; rawB2 = -alpha
                rawA0 = 1.0f + alpha; rawA1 = -2.0f * cosOmega; rawA2 = 1.0f - alpha
            }
        }

        val invA0 = 1.0f / rawA0
        b0 = rawB0 * invA0; b1 = rawB1 * invA0; b2 = rawB2 * invA0
        a1 = rawA1 * invA0; a2 = rawA2 * invA0
    }

    inline fun processSampleMono(x: Float): Float {
        val y = b0 * x + s1L
        s1L = b1 * x - a1 * y + s2L
        s2L = b2 * x - a2 * y
        return y
    }

    fun processStereoInterleaved(buffer: FloatArray, frameCount: Int) {
        var idx = 0
        var i = 0
        while (i < frameCount) {
            val xL = buffer[idx]
            val yL = b0 * xL + s1L
            s1L = b1 * xL - a1 * yL + s2L
            s2L = b2 * xL - a2 * yL
            buffer[idx] = yL

            val xR = buffer[idx + 1]
            val yR = b0 * xR + s1R
            s1R = b1 * xR - a1 * yR + s2R
            s2R = b2 * xR - a2 * yR
            buffer[idx + 1] = yR

            idx += 2
            i++
        }
    }

    fun reset() {
        s1L = 0.0f; s2L = 0.0f; s1R = 0.0f; s2R = 0.0f
    }
}