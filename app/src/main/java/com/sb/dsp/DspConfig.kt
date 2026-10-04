package com.sb.dsp

data class DspConfig(
    val masterEnabled: Boolean = true,
    val sampleRate: Int = 48000,
    val channels: Int = 2,

    // 1. Equalizer Configuration & Strict Mutual Exclusion
    val eqEnabled: Boolean = true,
    val eqMode: EqMode = EqMode.BANDS_10,
    val gains10BandDb: FloatArray = FloatArray(10) { 0.0f },
    val gains20BandDb: FloatArray = FloatArray(20) { 0.0f },
    val gains32BandDb: FloatArray = FloatArray(32) { 0.0f },

    // 2. Pregain Configuration (-24.0 dB to +12.0 dB)
    val pregainEnabled: Boolean = false,
    val pregainDb: Float = 0.0f,

    // 3. Bass Boost Configuration
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Float = 0.0f,
    val bassBoostCenterFreq: Float = 80.0f,

    // 4. MDRC Configuration
    val mdrcEnabled: Boolean = false,
    val mdrcLowCrossoverHz: Float = 250.0f,
    val mdrcHighCrossoverHz: Float = 3500.0f,
    val mdrcLowBand: BandCompressorConfig = BandCompressorConfig(thresholdDb = -18f, ratio = 3.0f, attackMs = 20f, releaseMs = 120f, makeupGainDb = 0f),
    val mdrcMidBand: BandCompressorConfig = BandCompressorConfig(thresholdDb = -14f, ratio = 2.5f, attackMs = 15f, releaseMs = 80f, makeupGainDb = 0f),
    val mdrcHighBand: BandCompressorConfig = BandCompressorConfig(thresholdDb = -16f, ratio = 2.0f, attackMs = 5f, releaseMs = 50f, makeupGainDb = 0f),

    // 5. Tone Controls (3 Bandas: Graves, Medios, Agudos)
    val toneEnabled: Boolean = false,
    val bassToneDb: Float = 0.0f,
    val midToneDb: Float = 0.0f,
    val trebleToneDb: Float = 0.0f,

    // 6. Headroom Manager & Safety Limiter
    val headroomEnabled: Boolean = true,
    val headroomDb: Float = -1.0f,
    val autoGainEnabled: Boolean = false,
    val autoGainTargetRmsDb: Float = -14.0f,

    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Float = 0.0f
) {
    enum class EqMode(val bandCount: Int) {
        BANDS_10(10),
        BANDS_20(20),
        BANDS_32(32)
    }

    data class BandCompressorConfig(
        val thresholdDb: Float = -16.0f,
        val ratio: Float = 2.5f,
        val attackMs: Float = 10.0f,
        val releaseMs: Float = 100.0f,
        val makeupGainDb: Float = 0.0f
    )
}