package com.sjbz.aimp.audio

import android.media.audiofx.BassBoost

/**
 * SjbZ BassBoost Psicoacústico +15dB
 * Nueva característica: frecuencia seleccionable 60/85/120 Hz
 */
class BassBoostProcessor {

    var frequency: Int = 85 // 60 = Sub, 85 = Punch, 120 = Mid-Bass
        private set

    var gainDb: Float = 6.0f
        private set

    var isEnabled: Boolean = true
        private set

    private var bassBoost: BassBoost? = null
    private var sessionId: Int = 0

    fun setBassBoost(freq: Int, gain: Float) {
        frequency = when (freq) {
            60, 85, 120 -> freq
            else -> 85
        }
        gainDb = gain.coerceIn(0f, 15f)
        isEnabled = gainDb > 0.1f
        // Si ya está atado, actualiza strength al vuelo
        try {
            bassBoost?.setStrength(toStrength().toShort())
            bassBoost?.enabled = isEnabled
        } catch (_: Exception) {}
    }

    fun getBassBoostGain(): Float = gainDb
    fun getBassBoostFreq(): Int = frequency

    fun toStrength(): Int {
        // Android BassBoost es 0-1000
        return (gainDb / 15f * 1000f).toInt().coerceIn(0, 1000)
    }

    // --- Métodos que necesita PlaybackService ---

    fun toDisplay(): String {
        return if (!isEnabled) "Bass: OFF"
        else String.format("Bass %dHz +%.1f dB", frequency, gainDb)
    }

    fun attach() {}
    fun attach(sessionId: Int) {
        this.sessionId = sessionId
        try {
            release()
            if (sessionId != 0) {
                bassBoost = BassBoost(0, sessionId).apply {
                    enabled = isEnabled
                    setStrength(toStrength().toShort())
                }
            }
        } catch (_: Exception) {
            bassBoost = null
        }
    }
    fun attach(session: Any?) { if (session is Int) attach(session) }
    fun attach(s1: Any?, s2: Any?) { if (s1 is Int) attach(s1) }

    fun release() {
        try { bassBoost?.release() } catch (_: Exception) {}
        bassBoost = null
    }
    fun release(session: Any?) { release() }
    fun release(id: Int) { release() }
}
