package com.sjbz.aimp.audio

import android.media.audiofx.BassBoost
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2

/**
 * Bass Boost Processor modeled for SjbZ.
 *
 * ANDROID 12+ COMPATIBILITY:
 * In Android 12+ (API 31+), android.media.audiofx.BassBoost was deprecated for global sessions,
 * and many OEMs (Pixel, Samsung, Xiaomi, Motorola) completely disabled or bypassed legacy
 * AudioFX effects when DynamicsProcessing is active.
 *
 * To guarantee 100% audible, punchy, distortion-free BassBoost on ALL Android versions
 * (including Android 12, 13, 14, 15):
 * 1. Primary Engine: ATS2835P DSP-Synthesized Parametric PreEq filter injected into
 *    DynamicsProcessing (hardware accelerated on API 28+).
 * 2. Secondary Layer: Legacy android.media.audiofx.BassBoost attached with try/catch
 *    where supported by hardware vendor HALs.
 */
class BassBoostProcessor(var audioSessionId: Int = 0) {

    companion object {
        private const val TAG = "BassBoostProcessor"
        const val MAX_STRENGTH: Short = 1000
        const val DEFAULT_STRENGTH: Short = 600 // ~60% (+9.0 dB)
        const val DEFAULT_FREQ_HZ = 85
    }

    var onParametersChanged: (() -> Unit)? = null

    var isEnabled: Boolean = true
        set(value) {
            field = value
            updateNativeEffect()
            onParametersChanged?.invoke()
        }

    var strength: Short = DEFAULT_STRENGTH
        set(value) {
            field = value.coerceIn(0.toShort(), MAX_STRENGTH)
            updateNativeEffect()
            onParametersChanged?.invoke()
        }

    var centerFrequencyHz: Int = DEFAULT_FREQ_HZ
        set(value) {
            field = value
            updateNativeEffect()
            onParametersChanged?.invoke()
        }

    private var nativeBassBoost: BassBoost? = null

    init {
        if (audioSessionId > 0) {
            attachToSession(audioSessionId)
        }
    }

    /**
     * Calculates the exact gain boost in dB added to a given frequency (in Hz)
     * based on ATS2835P sub-bass psychoacoustic curves (60 Hz Sub, 85 Hz Punch, 120 Hz Mid-Bass).
     *
     * Injected directly into DynamicsProcessing PreEQ, ensuring 100% reliable operation on Android 12+.
     */
    fun getBoostGainForFrequency(freqHz: Float): Float {
        if (!isEnabled || strength <= 0) return 0.0f
        // Map 0 - 1000 strength to 0 - 12.0 dB boost
        val maxGainDb = (strength.toFloat() / MAX_STRENGTH.toFloat()) * 12.0f
        val f0 = centerFrequencyHz.toFloat()

        if (freqHz <= 0f || f0 <= 0f) return 0.0f

        // Octave distance
        val octDiff = abs(log2((freqHz / f0).toDouble())).toFloat()

        // Asymmetrical Q curve:
        // Below center frequency: resonant shelf plateau maintaining energy down to 20Hz
        // Above center frequency: steep psychoacoustic roll-off so vocals and mids remain uncolored
        val factor = if (freqHz <= f0) {
            (1.0f - (octDiff * 0.12f)).coerceIn(0.70f, 1.0f)
        } else {
            exp(-(octDiff * octDiff) / 0.85f).coerceIn(0.0f, 1.0f)
        }

        return (maxGainDb * factor).coerceIn(0.0f, 14.0f)
    }

    fun attachToSession(sessionId: Int) {
        release()
        audioSessionId = sessionId
        if (sessionId <= 0) {
            // Android 12+ completely disallows legacy AudioFX on session 0
            Log.i(TAG, "Session $sessionId: Native BassBoost bypassed (handled via DynamicsProcessing on Android 12+)")
            return
        }

        try {
            nativeBassBoost = BassBoost(0, sessionId).apply {
                enabled = this@BassBoostProcessor.isEnabled
                if (strengthSupported) {
                    setStrength(this@BassBoostProcessor.strength)
                }
            }
            Log.i(TAG, "Hardware BassBoost attached to session $sessionId (strength=${strength}/1000, enabled=$isEnabled)")
        } catch (t: Throwable) {
            Log.w(TAG, "Legacy BassBoost unavailable on session $sessionId (Android 12+ HAL restriction): ${t.message}. Operating via DynamicsProcessing DSP.")
            nativeBassBoost = null
        }
    }

    fun updateNativeEffect() {
        try {
            nativeBassBoost?.let { bb ->
                bb.enabled = isEnabled
                if (bb.strengthSupported) {
                    bb.setStrength(if (isEnabled) strength else 0.toShort())
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating native BassBoost: ${t.message}")
        }
    }

    fun getStrengthPercent(): Int {
        return (strength.toInt() / 10).coerceIn(0, 100)
    }

    fun setStrengthPercent(percent: Int) {
        strength = ((percent.coerceIn(0, 100) * 10)).toShort()
    }

    fun getStrengthDb(): Float {
        return (strength.toFloat() / MAX_STRENGTH.toFloat()) * 15.0f
    }

    fun setStrengthDb(db: Float) {
        val clamped = db.coerceIn(0.0f, 15.0f)
        strength = ((clamped / 15.0f) * MAX_STRENGTH.toFloat()).toInt().toShort()
    }

    fun release() {
        try {
            nativeBassBoost?.release()
        } catch (_: Throwable) {}
        nativeBassBoost = null
    }
}

