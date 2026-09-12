package com.sjbz.aimp.audio

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

    fun setBassBoost(freq: Int, gain: Float) {
        frequency = when (freq) {
            60, 85, 120 -> freq
            else -> 85
        }
        gainDb = gain.coerceIn(0f, 15f)
        isEnabled = gainDb > 0.1f
    }

    fun getBassBoostGain(): Float = gainDb
    fun getBassBoostFreq(): Int = frequency

    fun toStrength(): Int {
        // Android BassBoost es 0-1000
        return (gainDb / 15f * 1000f).toInt().coerceIn(0, 1000)
    }
}
