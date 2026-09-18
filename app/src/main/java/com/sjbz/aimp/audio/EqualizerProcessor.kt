package com.sjbz.aimp.audio

import com.sjbz.aimp.model.EqPreset
import java.util.Arrays

/**
 * 32-band ISO precision - UNIFICADO con SjbzDspProcessor.
 * Ya no duplica frecuencias, usa la misma fuente que GlobalAudioSessionManager.
 * API 100% compatible con tu proyecto actual.
 */
class EqualizerProcessor {

    companion object {
        const val BAND_COUNT = 32
        // FUENTE ÚNICA DE VERDAD - si SjbzDspProcessor cambia, todo cambia junto
        val BAND_FREQS: FloatArray get() = SjbzDspProcessor.BAND_FREQS
        val BAND_LABELS: Array<String> get() = SjbzDspProcessor.BAND_LABELS
        // Alias por compatibilidad con código viejo que usa ISO_FREQUENCIES
        val ISO_FREQUENCIES: FloatArray get() = BAND_FREQS
    }

    var isEnabled: Boolean = true

    @Volatile
    var preampDb: Float = 0.0f
        set(value) {
            val clamped = value.coerceIn(-12.0f, 12.0f)
            // Headroom anti-clip
            val maxGain = bandGains.maxOrNull()?: 0f
            field = if (maxGain + clamped > 12f) (12f - maxGain).coerceIn(-12f, 12f) else clamped
            // Propaga al DSP real si existe
            try { GlobalAudioSessionManager.getDspProcessorInstance().setPreamp(field) } catch (_: Exception) {}
        }

    private val bandGains = FloatArray(BAND_COUNT)

    init {
        applyPreset("Studio Master")
    }

    fun getBandGain(index: Int): Float {
        return if (index in 0 until BAND_COUNT) bandGains[index] else 0.0f
    }

    fun setBandGain(index: Int, gainDb: Float) {
        if (index in 0 until BAND_COUNT) {
            bandGains[index] = gainDb.coerceIn(-12.0f, 12.0f)
            // Propaga al DSP real para que suene en sistema
            try { GlobalAudioSessionManager.getDspProcessorInstance().setBandLevel(index, bandGains[index]) } catch (_: Exception) {}
        }
    }

    fun getBandGains(): FloatArray = bandGains.copyOf()

    fun setAllBands(gains: List<Float>) {
        for (i in 0 until minOf(gains.size, BAND_COUNT)) {
            setBandGain(i, gains[i])
        }
    }

    fun applyPreset(presetName: String) {
        Arrays.fill(bandGains, 0.0f)
        when (presetName) {
            "Studio Master", "Flat" -> {
                preampDb = 0.0f
            }
            "Bass", "Bass Boost" -> {
                preampDb = -1.5f
                val curve = floatArrayOf(6.0f, 6.0f, 5.8f, 5.5f, 5.0f, 4.5f, 4.0f, 3.2f, 2.5f, 1.5f, 0.8f, 0.0f, -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f, 1.0f, 1.0f, 1.5f, 1.5f, 2.0f, 1.8f, 1.5f, 1.0f, 0.5f, 0.0f)
                for (i in curve.indices) setBandGain(i, curve[i])
            }
            "Rock" -> {
                preampDb = -1.5f
                val curve = floatArrayOf(4.5f, 4.2f, 4.0f, 3.8f, 3.5f, 3.0f, 2.2f, 1.2f, 0.2f, -0.8f, -1.2f, -1.5f, -1.2f, -0.8f, -0.2f, 0.5f, 1.0f, 1.5f, 1.8f, 2.2f, 2.5f, 3.0f, 3.5f, 4.0f, 4.2f, 4.5f, 4.2f, 3.8f, 3.2f, 2.5f, 1.8f, 1.0f)
                for (i in curve.indices) setBandGain(i, curve[i])
            }
            "Vocal", "Vocal Boost" -> {
                preampDb = -1.0f
                val curve = floatArrayOf(-2.0f, -1.8f, -1.5f, -1.0f, -0.5f, 0.0f, 0.0f, 0.2f, 0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.2f, 3.8f, 4.2f, 4.5f, 4.2f, 3.8f, 3.2f, 2.5f, 1.8f, 1.2f, 0.5f, 0.0f, -0.5f, -1.0f, -1.2f, -1.5f, -2.0f)
                for (i in curve.indices) setBandGain(i, curve[i])
            }
            "Electronic" -> {
                preampDb = -2.0f
                val curve = floatArrayOf(6.5f, 6.2f, 5.8f, 5.5f, 5.0f, 4.5f, 3.5f, 2.0f, 0.5f, -0.5f, -1.0f, -1.2f, -1.0f, -0.5f, 0.0f, 0.0f, 0.2f, 0.5f, 0.8f, 1.2f, 1.8f, 2.5f, 3.5f, 4.5f, 5.2f, 5.5f, 5.2f, 4.8f, 4.0f, 3.2f, 2.5f, 1.8f)
                for (i in curve.indices) setBandGain(i, curve[i])
            }
            else -> { preampDb = 0.0f }
        }
    }

    fun toEqPreset(
        name: String,
        isCustom: Boolean = true,
        bassBoostEnabled: Boolean = true,
        bassBoostFreq: Float = 85.0f,
        bassBoostGain: Float = 4.0f,
        color: Int = try { EqPreset.generateRandomColor() } catch (_: Exception) { 0xFF00E5FF.toInt() }
    ): EqPreset {
        return EqPreset(
            name = name,
            preampDb = preampDb,
            bandGains = bandGains.toList(),
            isCustom = isCustom,
            bassBoostEnabled = bassBoostEnabled,
            bassBoostFreq = bassBoostFreq,
            bassBoostGain = bassBoostGain,
            color = color
        )
    }

    fun loadFromPreset(preset: EqPreset) {
        preampDb = preset.preampDb
        setAllBands(preset.bandGains)
    }
}
