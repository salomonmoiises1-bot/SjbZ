package com.sb.dsp

import android.content.Context

/** Persistent store for the complete user-facing DSP configuration. */
object DspConfigStore {
    private const val PREFS = "sb_dsp_config"

    private fun p(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isServiceActive(context: Context): Boolean = p(context).getBoolean("serviceActive", false)

    fun setServiceActive(context: Context, active: Boolean) { p(context).edit().putBoolean("serviceActive", active).apply() }

    fun load(context: Context): DspConfig {
        val prefs = p(context)
        val base = DspConfig()
        fun array(key: String, count: Int) = FloatArray(count) { i -> prefs.getFloat("${key}_$i", 0f) }
        fun band(prefix: String, fallback: DspConfig.BandCompressorConfig) = DspConfig.BandCompressorConfig(
            prefs.getFloat("${prefix}_threshold", fallback.thresholdDb),
            prefs.getFloat("${prefix}_ratio", fallback.ratio),
            prefs.getFloat("${prefix}_attack", fallback.attackMs),
            prefs.getFloat("${prefix}_release", fallback.releaseMs),
            prefs.getFloat("${prefix}_makeup", fallback.makeupGainDb)
        )
        return base.copy(
            masterEnabled = prefs.getBoolean("masterEnabled", base.masterEnabled),
            eqEnabled = prefs.getBoolean("eqEnabled", base.eqEnabled),
            gains10BandDb = array("eq10", 10),
            gains20BandDb = array("eq20", 20),
            gains32BandDb = array("eq32", 32),
            pregainEnabled = prefs.getBoolean("pregainEnabled", base.pregainEnabled),
            pregainDb = prefs.getFloat("pregainDb", base.pregainDb),
            bassBoostEnabled = prefs.getBoolean("bassBoostEnabled", base.bassBoostEnabled),
            bassBoostStrength = prefs.getFloat("bassBoostStrength", base.bassBoostStrength),
            bassBoostCenterFreq = prefs.getFloat("bassBoostCenterFreq", base.bassBoostCenterFreq),
            mdrcEnabled = prefs.getBoolean("mdrcEnabled", base.mdrcEnabled),
            mdrcLowCrossoverHz = prefs.getFloat("mdrcLowCrossoverHz", base.mdrcLowCrossoverHz),
            mdrcMidCrossoverHz = prefs.getFloat("mdrcMidCrossoverHz", base.mdrcMidCrossoverHz),
            mdrcHighCrossoverHz = prefs.getFloat("mdrcHighCrossoverHz", base.mdrcHighCrossoverHz),
            mdrcLowBand = band("mdrcLow", base.mdrcLowBand),
            mdrcMidBand = band("mdrcMid", base.mdrcMidBand),
            mdrcHighBand = band("mdrcHigh", base.mdrcHighBand),
            toneEnabled = prefs.getBoolean("toneEnabled", base.toneEnabled),
            bassToneDb = prefs.getFloat("bassToneDb", base.bassToneDb),
            midToneDb = prefs.getFloat("midToneDb", base.midToneDb),
            trebleToneDb = prefs.getFloat("trebleToneDb", base.trebleToneDb),
            headroomEnabled = prefs.getBoolean("headroomEnabled", base.headroomEnabled),
            headroomDb = prefs.getFloat("headroomDb", base.headroomDb),
            autoGainEnabled = prefs.getBoolean("autoGainEnabled", base.autoGainEnabled),
            autoGainTargetRmsDb = prefs.getFloat("autoGainTargetRmsDb", base.autoGainTargetRmsDb),
            masterGainDb = prefs.getFloat("masterGainDb", base.masterGainDb),
            balance = prefs.getFloat("balance", base.balance),
            virtualizerEnabled = prefs.getBoolean("virtualizerEnabled", base.virtualizerEnabled),
            virtualizerStrength = prefs.getFloat("virtualizerStrength", base.virtualizerStrength)
        ).validate()
    }

    fun save(context: Context, config: DspConfig) {
        val c = config.validate()
        val e = p(context).edit()
            .putBoolean("masterEnabled", c.masterEnabled)
            .putBoolean("eqEnabled", c.eqEnabled)
            .putBoolean("pregainEnabled", c.pregainEnabled)
            .putFloat("pregainDb", c.pregainDb)
            .putBoolean("bassBoostEnabled", c.bassBoostEnabled)
            .putFloat("bassBoostStrength", c.bassBoostStrength)
            .putFloat("bassBoostCenterFreq", c.bassBoostCenterFreq)
            .putBoolean("mdrcEnabled", c.mdrcEnabled)
            .putFloat("mdrcLowCrossoverHz", c.mdrcLowCrossoverHz)
            .putFloat("mdrcMidCrossoverHz", c.mdrcMidCrossoverHz)
            .putFloat("mdrcHighCrossoverHz", c.mdrcHighCrossoverHz)
            .putBoolean("toneEnabled", c.toneEnabled)
            .putFloat("bassToneDb", c.bassToneDb)
            .putFloat("midToneDb", c.midToneDb)
            .putFloat("trebleToneDb", c.trebleToneDb)
            .putBoolean("headroomEnabled", c.headroomEnabled)
            .putFloat("headroomDb", c.headroomDb)
            .putBoolean("autoGainEnabled", c.autoGainEnabled)
            .putFloat("autoGainTargetRmsDb", c.autoGainTargetRmsDb)
            .putFloat("masterGainDb", c.masterGainDb)
            .putFloat("balance", c.balance)
            .putBoolean("virtualizerEnabled", c.virtualizerEnabled)
            .putFloat("virtualizerStrength", c.virtualizerStrength)
        c.gains10BandDb.forEachIndexed { i, v -> e.putFloat("eq10_$i", v) }
        c.gains20BandDb.forEachIndexed { i, v -> e.putFloat("eq20_$i", v) }
        c.gains32BandDb.forEachIndexed { i, v -> e.putFloat("eq32_$i", v) }
        fun putBand(prefix: String, b: DspConfig.BandCompressorConfig) {
            e.putFloat("${prefix}_threshold", b.thresholdDb)
                .putFloat("${prefix}_ratio", b.ratio)
                .putFloat("${prefix}_attack", b.attackMs)
                .putFloat("${prefix}_release", b.releaseMs)
                .putFloat("${prefix}_makeup", b.makeupGainDb)
        }
        putBand("mdrcLow", c.mdrcLowBand)
        putBand("mdrcMid", c.mdrcMidBand)
        putBand("mdrcHigh", c.mdrcHighBand)
        e.apply()
    }
}
