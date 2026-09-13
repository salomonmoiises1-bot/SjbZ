package com.sjbz.aimp.audio

import android.media.audiofx.BassBoost
import android.util.Log

class BassBoostProcessor(var audioSessionId: Int = 0) {

    companion object {
        private const val TAG = "BassBoostProcessor"
        const val MAX_STRENGTH: Short = 700 // FIX: 1000 MUTEA, 700 ES EL MAX REAL
        const val DEFAULT_STRENGTH: Short = 400 // ~57% +6dB seguro
        const val DEFAULT_FREQ_HZ = 85
    }

    var isEnabled: Boolean = true
        set(value) {
            field = value
            updateNativeEffect()
        }

    var strength: Short = DEFAULT_STRENGTH
        set(value) {
            field = value.coerceIn(0.toShort(), MAX_STRENGTH)
            updateNativeEffect()
        }

    var centerFrequencyHz: Int = DEFAULT_FREQ_HZ
        set(value) {
            field = value
            // Acá no tocamos el efecto nativo, esto es solo para la UI
            // La frecuencia real la maneja el MDRC + EQ de 32 bandas
        }

    private var nativeBassBoost: BassBoost? = null

    init {
        if (audioSessionId > 0) {
            attachToSession(audioSessionId)
        }
    }

    fun attachToSession(sessionId: Int) {
        release()
        audioSessionId = sessionId
        if (sessionId <= 0) return

        try {
            nativeBassBoost = BassBoost(0, sessionId).apply {
                // Nunca habilitar con 0
                val safe = if (isEnabled) strength else 0.toShort()
                if (strengthSupported) {
                    setStrength(safe)
                }
                enabled = isEnabled && safe > 10
            }
            Log.i(TAG, "BassBoost ATTACHED session $sessionId strength=${strength}/700")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed BassBoost: ${t.message}")
            nativeBassBoost = null
        }
    }

    fun updateNativeEffect() {
        try {
            nativeBassBoost?.let { bb ->
                val safe = if (isEnabled) strength.coerceIn(0, MAX_STRENGTH) else 0.toShort()
                bb.enabled = isEnabled && safe > 10
                if (bb.strengthSupported) {
                    bb.setStrength(safe)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating: ${t.message}")
        }
    }

    fun getStrengthPercent(): Int {
        return (strength.toInt() * 100 / MAX_STRENGTH.toInt()).coerceIn(0, 100)
    }

    fun setStrengthPercent(percent: Int) {
        // 0-100% -> 0-700
        strength = ((percent.coerceIn(0, 100) * MAX_STRENGTH / 100)).toShort()
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
            nativeBassBoost?.enabled = false
            nativeBassBoost?.release()
        } catch (_: Throwable) {}
        nativeBassBoost = null
    }
}
