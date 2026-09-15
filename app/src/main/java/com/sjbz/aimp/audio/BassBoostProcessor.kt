package com.sjbz.aimp.audio

import android.media.audiofx.BassBoost
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2

/**
 * Bass Boost Processor para SjbZ / ATS2835P.
 *
 * Motor principal: DSP sintetizado vía DynamicsProcessing PreEq (funciona 100% en Android 12+).
 * Motor secundario: legacy BassBoost donde el HAL lo permite.
 * Curva psicoacústica plana en sub-graves para que las 32 bandas (20-125Hz) respondan.
 */
class BassBoostProcessor(var audioSessionId: Int = 0) {

    companion object {
        private const val TAG = "BassBoostProcessor"
        const val MAX_STRENGTH: Short = 1000
        const val DEFAULT_STRENGTH: Short = 600 // ~60% (+7.2 dB)
        const val DEFAULT_FREQ_HZ = 60
        const val MIN_FREQ_HZ = 20
        const val MAX_FREQ_HZ = 150
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
            field = value.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
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
     * Ganancia en dB para una frecuencia dada.
     * Shelf plano hacia abajo (20Hz mantiene 85%+), roll-off suave hacia arriba
     * para no colorear voces. Así las 8 bandas bajas (25-125) sí suenan.
     */
    fun getBoostGainForFrequency(freqHz: Float): Float {
        if (!isEnabled || strength <= 0) return 0.0f
        val maxGainDb = (strength.toFloat() / MAX_STRENGTH.toFloat()) * 12.0f
        val f0 = centerFrequencyHz.toFloat()
        if (freqHz <= 0f || f0 <= 0f) return 0.0f

        val octDiff = abs(log2((freqHz / f0).toDouble())).toFloat()

        val factor = if (freqHz <= f0) {
            // PARCHE 32 bandas: de 0.70 -> 0.85 mínimo, de 0.12 -> 0.08 pendiente
            (1.0f - (octDiff * 0.08f)).coerceIn(0.85f, 1.0f)
        } else {
            // Roll-off más abierto para no matar 100-200Hz
            exp(-(octDiff * octDiff) / 1.2f).coerceIn(0.0f, 1.0f)
        }
        return (maxGainDb * factor).coerceIn(0.0f, 14.0f)
    }

    /**
     * Curva completa de 32 bandas lista para inyectar al DynamicsProcessing.
     */
    fun getFull32BandCurve(isoFreqs: FloatArray): FloatArray {
        return FloatArray(isoFreqs.size) { i -> getBoostGainForFrequency(isoFreqs[i]) }
    }

    fun attachToSession(sessionId: Int) {
        release()
        audioSessionId = sessionId
        if (sessionId <= 0) {
            Log.i(TAG, "Session $sessionId: bypass nativo, solo DSP (Android 12+ safe)")
            return
        }
        try {
            nativeBassBoost = BassBoost(0, sessionId).apply {
                enabled = this@BassBoostProcessor.isEnabled
                if (strengthSupported) {
                    setStrength(this@BassBoostProcessor.strength)
                }
            }
            Log.i(TAG, "BassBoost nativo en sesión $sessionId strength=$strength")
        } catch (t: Throwable) {
            Log.w(TAG, "BassBoost nativo no disponible: ${t.message}, usando DSP")
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
            Log.w(TAG, "Error actualizando BassBoost nativo: ${t.message}")
        }
    }

    fun getStrengthPercent(): Int = (strength.toInt() / 10).coerceIn(0, 100)
    fun setStrengthPercent(percent: Int) {
        strength = (percent.coerceIn(0, 100) * 10).toShort()
    }

    fun getStrengthDb(): Float = (strength.toFloat() / MAX_STRENGTH.toFloat()) * 15.0f
    fun setStrengthDb(db: Float) {
        val clamped = db.coerceIn(0.0f, 15.0f)
        strength = ((clamped / 15.0f) * MAX_STRENGTH.toFloat()).toInt().toShort()
    }

    fun isNativeActive(): Boolean = nativeBassBoost!= null

    fun release() {
        try { nativeBassBoost?.release() } catch (_: Throwable) {}
        nativeBassBoost = null
    }
}
