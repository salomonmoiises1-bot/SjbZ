package com.sjbz.aimp.audio

import com.sjbz.aimp.model.EqPreset
import java.util.Arrays

/**
 * 32-band ISO precision audio equalizer for SjbZ.
 * Center frequencies from 20 Hz to 20,000 Hz with Preamp control (-12dB to +12dB).
 */
class EqualizerProcessor {

    companion object {
        const val BAND_COUNT = 32

        val ISO_FREQUENCIES = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f,
            125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
            800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f,
            5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 18000f, 20000f
        )

        val BAND_LABELS = arrayOf(
            "20", "25", "31.5", "40", "50", "63", "80", "100",
            "125", "160", "200", "250", "315", "400", "500", "630",
            "800", "1k", "1.25k", "1.6k", "2k", "2.5k", "3.15k", "4k",
            "5k", "6.3k", "8k", "10k", "12.5k", "16k", "18k", "20k"
        )
    }

    var isEnabled: Boolean = true
    var preampDb: Float = 0.0f
        set(value) {
            field = value.coerceIn(-12.0f, 12.0f)
        }

    private val bandGains = FloatArray(BAND_COUNT)

    init {
        applyPreset("ATS-2835P Master")
    }

    fun getBandGain(index: Int): Float {
        return if (index in 0 until BAND_COUNT) bandGains[index] else 0.0f
    }

    fun setBandGain(index: Int, gainDb: Float) {
        if (index in 0 until BAND_COUNT) {
            bandGains[index] = gainDb.coerceIn(-12.0f, 12.0f)
        }
    }

    fun getBandGains(): FloatArray {
        return bandGains.copyOf()
    }

    fun setAllBands(gains: List<Float>) {
        for (i in 0 until minOf(gains.size, BAND_COUNT)) {
            bandGains[i] = gains[i].coerceIn(-12.0f, 12.0f)
        }
    }

    fun applyPreset(presetName: String) {
        Arrays.fill(bandGains, 0.0f)
        when (presetName) {
            "Harman Kardon Target", "Harman Kardon (Harman Target)", "Harman Kardon", "Harman Target" -> {
                // Official Harman Target acoustic curve with sub-bass shelf and pinna ear-gain peak
                preampDb = -2.5f
                val curve = floatArrayOf(
                    6.5f, 6.5f, 6.2f, 6.0f, 5.5f, 4.8f, 4.0f, 3.0f,
                    1.8f, 0.8f, 0.0f, -0.2f, -0.3f, 0.0f, 0.0f, 0.0f,
                    0.3f, 0.8f, 1.5f, 2.5f, 4.0f, 5.2f, 6.0f, 5.0f,
                    3.5f, 1.5f, 0.5f, 0.0f, -0.5f, -1.0f, -1.5f, -2.0f
                )
                System.arraycopy(curve, 0, bandGains, 0, minOf(curve.size, BAND_COUNT))
            }
            "Flat", "Flat / Studio Direct" -> {
                preampDb = 0f
            }
            "ATS-2835P Master" -> {
                // Actions Semiconductor ATS-2835P calibrated acoustic curve
                preampDb = -0.8f
                val curve = floatArrayOf(
                    5.5f, 5.0f, 4.5f, 4.0f, 3.5f, 2.5f, 1.5f, 0.5f,
                    -0.5f, -1.0f, -1.2f, -1.0f, -0.5f, 0.0f, 0.5f, 0.8f,
                    1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.2f, 2.5f, 2.8f,
                    3.0f, 3.2f, 3.5f, 3.8f, 3.5f, 3.0f, 2.0f, 1.0f
                )
                System.arraycopy(curve, 0, bandGains, 0, minOf(curve.size, BAND_COUNT))
            }
            "Bass Boost" -> {
                preampDb = -1.5f
                val curve = floatArrayOf(
                    6.0f, 5.5f, 5.0f, 4.5f, 4.0f, 3.5f, 2.5f, 1.5f,
                    0.5f, 0.0f, -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.5f, 0.5f, 1.0f, 1.0f, 1.5f, 1.5f,
                    2.0f, 2.0f, 2.0f, 1.5f, 1.0f, 0.5f, 0.0f, 0.0f
                )
                System.arraycopy(curve, 0, bandGains, 0, minOf(curve.size, BAND_COUNT))
            }
            "Club / Bass Extreme" -> {
                preampDb = -3.0f
                val curve = floatArrayOf(
                    7.5f, 7.0f, 6.5f, 6.0f, 5.0f, 4.0f, 3.0f, 2.0f,
                    1.0f, 0.5f, 0.0f, -0.5f, -0.5f, 0.0f, 0.5f, 1.0f,
                    1.5f, 1.8f, 2.0f, 2.2f, 2.5f, 3.0f, 3.5f, 3.5f,
                    3.0f, 2.5f, 2.0f, 1.5f, 1.0f, 0.5f, 0.0f, 0.0f
                )
                System.arraycopy(curve, 0, bandGains, 0, minOf(curve.size, BAND_COUNT))
            }
            "V-Shape", "V-Shape (Punchy / EDM)" -> {
                preampDb = -2.0f
                val curve = floatArrayOf(
                    5.5f, 5.0f, 4.5f, 4.0f, 3.5f, 2.5f, 1.5f, 0.5f,
                    -0.5f, -1.5f, -2.5f, -3.0f, -3.0f, -2.5f, -2.0f, -1.0f,
                    0.0f, 0.5f, 1.0f, 1.5f, 2.5f, 3.5f, 4.5f, 5.0f,
                    5.5f, 5.0f, 4.0f, 3.0f, 2.0f, 1.5f, 1.0f, 0.5f
                )
                System.arraycopy(curve, 0, bandGains, 0, minOf(curve.size, BAND_COUNT))
            }
            "Rock" -> {
                preampDb = -1.0f
                val curve = floatArrayOf(
                    4.5f, 4.0f, 3.5f, 3.0f, 2.5f, 1.5f, 0.5f, -0.5f,
                    -1.0f, -1.5f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f,
                    1.8f, 2.0f, 2.2f, 2.5f, 3.0f, 3.2f, 3.5f, 3.8f,
                    4.0f, 4.2f, 4.5f, 4.2f, 3.5f, 2.5f, 1.5f, 0.5f
                )
                System.arraycopy(curve, 0, bandGains, 0, minOf(curve.size, BAND_COUNT))
            }
            "Acoustic & Vocal Clarity", "Vocal Clear" -> {
                preampDb = -1.0f
                val curve = floatArrayOf(
                    0.0f, 0.5f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f, 0.5f,
                    0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f,
                    4.0f, 3.8f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.0f,
                    0.5f, 0.0f, 0.0f, -0.5f, -1.0f, -1.5f, -2.0f, -2.5f
                )
                System.arraycopy(curve, 0, bandGains, 0, minOf(curve.size, BAND_COUNT))
            }
        }
    }

    fun toEqPreset(name: String, isCustom: Boolean = true, mdrcSettings: com.sjbz.aimp.model.MDRCSettings = com.sjbz.aimp.model.MDRCSettings()): EqPreset {
        return EqPreset(
            name = name,
            preampDb = preampDb,
            bandGains = bandGains.toList(),
            isCustom = isCustom,
            mdrcSettings = mdrcSettings
        )
    }

    fun loadFromPreset(preset: EqPreset) {
        preampDb = preset.preampDb
        setAllBands(preset.bandGains)
    }
}
