package com.sb.dsp

import android.content.Context

object DspConfigStore {
    private const val PREFS = "sb_dsp_config"
    private fun p(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isServiceActive(context: Context) = p(context).getBoolean("serviceActive", false)
    fun setServiceActive(context: Context, active: Boolean) { p(context).edit().putBoolean("serviceActive", active).apply() }

    fun load(context: Context): DspConfig {
        val prefs = p(context); val base = DspConfig()
        fun array(key:String,n:Int)=FloatArray(n){i->prefs.getFloat("${key}_$i",0f)}
        fun band(k:String,b:DspConfig.BandCompressorConfig)=DspConfig.BandCompressorConfig(
            prefs.getFloat("${k}_threshold",b.thresholdDb),prefs.getFloat("${k}_ratio",b.ratio),prefs.getFloat("${k}_attack",b.attackMs),prefs.getFloat("${k}_release",b.releaseMs),prefs.getFloat("${k}_makeup",b.makeupGainDb))
        return base.copy(
            masterEnabled=prefs.getBoolean("masterEnabled",base.masterEnabled),
            eqEnabled=prefs.getBoolean("eqEnabled",base.eqEnabled),
            eqMode=runCatching{DspConfig.EqMode.valueOf(prefs.getString("eqMode",base.eqMode.name)!!)}.getOrDefault(base.eqMode),
            gains10BandDb=array("eq10",10),gains20BandDb=array("eq20",20),gains32BandDb=array("eq32",32),
            pregainEnabled=prefs.getBoolean("pregainEnabled",base.pregainEnabled),pregainDb=prefs.getFloat("pregainDb",base.pregainDb),
            bassBoostEnabled=prefs.getBoolean("bassBoostEnabled",base.bassBoostEnabled),bassBoostStrength=prefs.getFloat("bassBoostStrength",base.bassBoostStrength),bassBoostCenterFreq=prefs.getFloat("bassBoostCenterFreq",base.bassBoostCenterFreq),
            mdrcEnabled=prefs.getBoolean("mdrcEnabled",base.mdrcEnabled),mdrcLowCrossoverHz=prefs.getFloat("mdrcLowCrossoverHz",base.mdrcLowCrossoverHz),mdrcMidCrossoverHz=prefs.getFloat("mdrcMidCrossoverHz",base.mdrcMidCrossoverHz),mdrcHighCrossoverHz=prefs.getFloat("mdrcHighCrossoverHz",base.mdrcHighCrossoverHz),
            mdrcLowBand=band("mdrcLow",base.mdrcLowBand),mdrcMidBand=band("mdrcMid",base.mdrcMidBand),mdrcHighBand=band("mdrcHigh",base.mdrcHighBand),mdrcUltraBand=band("mdrcUltra",base.mdrcUltraBand),
            toneEnabled=prefs.getBoolean("toneEnabled",base.toneEnabled),bassToneDb=prefs.getFloat("bassToneDb",base.bassToneDb),midToneDb=prefs.getFloat("midToneDb",base.midToneDb),trebleToneDb=prefs.getFloat("trebleToneDb",base.trebleToneDb),
            headroomEnabled=prefs.getBoolean("headroomEnabled",base.headroomEnabled),headroomDb=prefs.getFloat("headroomDb",base.headroomDb),
            limiterThresholdDb=prefs.getFloat("limiterThresholdDb",base.limiterThresholdDb),limiterRatio=prefs.getFloat("limiterRatio",base.limiterRatio),limiterAttackMs=prefs.getFloat("limiterAttackMs",base.limiterAttackMs),limiterReleaseMs=prefs.getFloat("limiterReleaseMs",base.limiterReleaseMs),limiterPostGainDb=prefs.getFloat("limiterPostGainDb",base.limiterPostGainDb),
            autoGainEnabled=prefs.getBoolean("autoGainEnabled",base.autoGainEnabled),autoGainTargetRmsDb=prefs.getFloat("autoGainTargetRmsDb",base.autoGainTargetRmsDb),
            masterGainDb=prefs.getFloat("masterGainDb",base.masterGainDb),balance=prefs.getFloat("balance",base.balance),virtualizerEnabled=prefs.getBoolean("virtualizerEnabled",base.virtualizerEnabled),virtualizerStrength=prefs.getFloat("virtualizerStrength",base.virtualizerStrength)
        ).validate()
    }

    fun save(context: Context, config: DspConfig) {
        val c=config.validate(); val e=p(context).edit().putBoolean("masterEnabled",c.masterEnabled).putBoolean("eqEnabled",c.eqEnabled).putString("eqMode",c.eqMode.name)
            .putBoolean("pregainEnabled",c.pregainEnabled).putFloat("pregainDb",c.pregainDb).putBoolean("bassBoostEnabled",c.bassBoostEnabled).putFloat("bassBoostStrength",c.bassBoostStrength).putFloat("bassBoostCenterFreq",c.bassBoostCenterFreq)
            .putBoolean("mdrcEnabled",c.mdrcEnabled).putFloat("mdrcLowCrossoverHz",c.mdrcLowCrossoverHz).putFloat("mdrcMidCrossoverHz",c.mdrcMidCrossoverHz).putFloat("mdrcHighCrossoverHz",c.mdrcHighCrossoverHz)
            .putBoolean("toneEnabled",c.toneEnabled).putFloat("bassToneDb",c.bassToneDb).putFloat("midToneDb",c.midToneDb).putFloat("trebleToneDb",c.trebleToneDb)
            .putBoolean("headroomEnabled",c.headroomEnabled).putFloat("headroomDb",c.headroomDb).putFloat("limiterThresholdDb",c.limiterThresholdDb).putFloat("limiterRatio",c.limiterRatio).putFloat("limiterAttackMs",c.limiterAttackMs).putFloat("limiterReleaseMs",c.limiterReleaseMs).putFloat("limiterPostGainDb",c.limiterPostGainDb)
            .putBoolean("autoGainEnabled",c.autoGainEnabled).putFloat("autoGainTargetRmsDb",c.autoGainTargetRmsDb).putFloat("masterGainDb",c.masterGainDb).putFloat("balance",c.balance).putBoolean("virtualizerEnabled",c.virtualizerEnabled).putFloat("virtualizerStrength",c.virtualizerStrength)
        c.gains10BandDb.forEachIndexed{i,v->e.putFloat("eq10_$i",v)}; c.gains20BandDb.forEachIndexed{i,v->e.putFloat("eq20_$i",v)}; c.gains32BandDb.forEachIndexed{i,v->e.putFloat("eq32_$i",v)}
        fun put(k:String,b:DspConfig.BandCompressorConfig){e.putFloat("${k}_threshold",b.thresholdDb).putFloat("${k}_ratio",b.ratio).putFloat("${k}_attack",b.attackMs).putFloat("${k}_release",b.releaseMs).putFloat("${k}_makeup",b.makeupGainDb)}
        put("mdrcLow",c.mdrcLowBand);put("mdrcMid",c.mdrcMidBand);put("mdrcHigh",c.mdrcHighBand);put("mdrcUltra",c.mdrcUltraBand);e.apply()
    }
}
