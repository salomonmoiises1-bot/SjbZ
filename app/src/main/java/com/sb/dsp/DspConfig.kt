package com.sb.dsp

data class DspConfig(
    val masterEnabled: Boolean = true,
    val sampleRate: Int = 48000,
    val channels: Int = 2,

    val eqEnabled: Boolean = true,
    val eqMode: EqMode = EqMode.BANDS_32,
    val gains10BandDb: FloatArray = FloatArray(10),
    val gains20BandDb: FloatArray = FloatArray(20),
    val gains32BandDb: FloatArray = FloatArray(32),

    val pregainEnabled: Boolean = true,
    val pregainDb: Float = 0.0f,

    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Float = 0.0f,
    val bassBoostCenterFreq: Float = 80.0f,

    val mdrcEnabled: Boolean = false,
    val mdrcLowCrossoverHz: Float = 160.0f,
    val mdrcMidCrossoverHz: Float = 800.0f,
    val mdrcHighCrossoverHz: Float = 4000.0f,
    val mdrcLowBand: BandCompressorConfig = BandCompressorConfig(-18f, 3f, 20f, 120f, 0f),
    val mdrcMidBand: BandCompressorConfig = BandCompressorConfig(-14f, 2.5f, 15f, 80f, 0f),
    val mdrcHighBand: BandCompressorConfig = BandCompressorConfig(-16f, 2f, 8f, 60f, 0f),
    val mdrcUltraBand: BandCompressorConfig = BandCompressorConfig(-18f, 2f, 5f, 50f, 0f),

    val toneEnabled: Boolean = false,
    val bassToneDb: Float = 0.0f,
    val midToneDb: Float = 0.0f,
    val trebleToneDb: Float = 0.0f,

    val headroomEnabled: Boolean = true,
    val headroomDb: Float = -1.0f,
    val limiterThresholdDb: Float = -2.0f,
    val limiterRatio: Float = 10.0f,
    val limiterAttackMs: Float = 1.0f,
    val limiterReleaseMs: Float = 60.0f,
    val limiterPostGainDb: Float = 0.0f,

    val autoGainEnabled: Boolean = false,
    val autoGainTargetRmsDb: Float = -14.0f,

    val masterGainDb: Float = 0.0f,
    val balance: Float = 0.0f,

    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Float = 0.0f
) {
    enum class EqMode(val bandCount: Int) { BANDS_10(10), BANDS_20(20), BANDS_32(32) }

    data class BandCompressorConfig(
        val thresholdDb: Float = -16f,
        val ratio: Float = 2.5f,
        val attackMs: Float = 10f,
        val releaseMs: Float = 100f,
        val makeupGainDb: Float = 0f
    )

    fun activeGains(): FloatArray = when (eqMode) {
        EqMode.BANDS_10 -> gains10BandDb
        EqMode.BANDS_20 -> gains20BandDb
        EqMode.BANDS_32 -> gains32BandDb
    }

    fun validate(): DspConfig {
        fun cleanArray(source: FloatArray, count: Int) =
            FloatArray(count) { i -> source.getOrElse(i) { 0f }.coerceIn(-24f, 24f) }
        val sr = sampleRate.coerceIn(8000, 192000)
        val low = mdrcLowCrossoverHz.coerceIn(20f, sr * 0.49f)
        val mid = mdrcMidCrossoverHz.coerceIn(low, sr * 0.49f)
        val high = mdrcHighCrossoverHz.coerceIn(mid, sr * 0.49f)
        fun band(b: BandCompressorConfig) = b.copy(
            thresholdDb = b.thresholdDb.coerceIn(-60f, 0f),
            ratio = b.ratio.coerceIn(1f, 50f),
            attackMs = b.attackMs.coerceIn(0.01f, 500f),
            releaseMs = b.releaseMs.coerceIn(1f, 5000f),
            makeupGainDb = b.makeupGainDb.coerceIn(-30f, 30f)
        )
        return copy(
            sampleRate = sr,
            channels = channels.coerceIn(1, 2),
            pregainDb = pregainDb.coerceIn(-24f, 12f),
            bassBoostStrength = bassBoostStrength.coerceIn(0f, 1f),
            bassBoostCenterFreq = bassBoostCenterFreq.coerceIn(30f, 160f),
            mdrcLowCrossoverHz = low,
            mdrcMidCrossoverHz = mid,
            mdrcHighCrossoverHz = high,
            mdrcLowBand = band(mdrcLowBand), mdrcMidBand = band(mdrcMidBand),
            mdrcHighBand = band(mdrcHighBand), mdrcUltraBand = band(mdrcUltraBand),
            headroomDb = headroomDb.coerceIn(-12f, 0f),
            limiterThresholdDb = limiterThresholdDb.coerceIn(-30f, 0f),
            limiterRatio = limiterRatio.coerceIn(1f, 50f),
            limiterAttackMs = limiterAttackMs.coerceIn(0.01f, 100f),
            limiterReleaseMs = limiterReleaseMs.coerceIn(1f, 500f),
            limiterPostGainDb = limiterPostGainDb.coerceIn(-12f, 12f),
            autoGainTargetRmsDb = autoGainTargetRmsDb.coerceIn(-60f, 0f),
            masterGainDb = masterGainDb.coerceIn(-60f, 12f),
            balance = balance.coerceIn(-1f, 1f),
            gains10BandDb = cleanArray(gains10BandDb, 10),
            gains20BandDb = cleanArray(gains20BandDb, 20),
            gains32BandDb = cleanArray(gains32BandDb, 32)
        )
    }
}
