package com.sb.dsp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PresetRepository(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("sb_presets", Context.MODE_PRIVATE)
    fun names(): List<String> = runCatching { JSONArray(prefs.getString("names", "[]")!!).let { a -> (0 until a.length()).map(a::getString) } }.getOrDefault(emptyList())

    fun save(name: String, c: DspConfig) {
        val n=name.trim(); if(n.isEmpty()) return
        val names=names().toMutableList().apply{if(!contains(n))add(n)}; val o=JSONObject()
        o.put("masterEnabled",c.masterEnabled).put("eqEnabled",c.eqEnabled).put("eqMode",c.eqMode.name)
            .put("pregainEnabled",c.pregainEnabled).put("pregainDb",c.pregainDb).put("bassBoostEnabled",c.bassBoostEnabled).put("bassBoostStrength",c.bassBoostStrength)
            .put("mdrcEnabled",c.mdrcEnabled).put("mdrcLowCrossoverHz",c.mdrcLowCrossoverHz).put("mdrcMidCrossoverHz",c.mdrcMidCrossoverHz).put("mdrcHighCrossoverHz",c.mdrcHighCrossoverHz)
            .put("toneEnabled",c.toneEnabled).put("bassToneDb",c.bassToneDb).put("midToneDb",c.midToneDb).put("trebleToneDb",c.trebleToneDb)
            .put("limiterEnabled",c.limiterEnabled).put("headroomDb",c.headroomDb).put("limiterThresholdDb",c.limiterThresholdDb).put("limiterRatio",c.limiterRatio).put("limiterAttackMs",c.limiterAttackMs).put("limiterReleaseMs",c.limiterReleaseMs).put("limiterPostGainDb",c.limiterPostGainDb)
            .put("autoGainEnabled",c.autoGainEnabled).put("autoGainTargetRmsDb",c.autoGainTargetRmsDb).put("masterGainDb",c.masterGainDb).put("balance",c.balance).put("virtualizerEnabled",c.virtualizerEnabled).put("virtualizerStrength",c.virtualizerStrength)
        o.put("eq10",JSONArray(c.gains10BandDb.toList())).put("eq20",JSONArray(c.gains20BandDb.toList())).put("eq32",JSONArray(c.gains32BandDb.toList()))
        fun putBand(k:String,b:DspConfig.BandCompressorConfig){o.put("${k}T",b.thresholdDb).put("${k}R",b.ratio).put("${k}A",b.attackMs).put("${k}Rel",b.releaseMs).put("${k}M",b.makeupGainDb)}
        putBand("low",c.mdrcLowBand);putBand("mid",c.mdrcMidBand);putBand("high",c.mdrcHighBand);putBand("ultra",c.mdrcUltraBand)
        prefs.edit().putString("names",JSONArray(names).toString()).putString("preset_$n",o.toString()).apply()
    }

    fun load(name:String,fallback:DspConfig):DspConfig=runCatching{
        val o=JSONObject(prefs.getString("preset_$name","{}")!!)
        fun arr(k:String,n:Int)=FloatArray(n){i->o.optJSONArray(k)?.optDouble(i,0.0)?.toFloat()?:0f}
        fun band(k:String,b:DspConfig.BandCompressorConfig)=DspConfig.BandCompressorConfig(o.optDouble("${k}T",b.thresholdDb.toDouble()).toFloat(),o.optDouble("${k}R",b.ratio.toDouble()).toFloat(),o.optDouble("${k}A",b.attackMs.toDouble()).toFloat(),o.optDouble("${k}Rel",b.releaseMs.toDouble()).toFloat(),o.optDouble("${k}M",b.makeupGainDb.toDouble()).toFloat())
        fallback.copy(masterEnabled=o.optBoolean("masterEnabled",fallback.masterEnabled),eqEnabled=o.optBoolean("eqEnabled",fallback.eqEnabled),eqMode=runCatching{DspConfig.EqMode.valueOf(o.optString("eqMode",fallback.eqMode.name))}.getOrDefault(fallback.eqMode),
            gains10BandDb=arr("eq10",10),gains20BandDb=arr("eq20",20),gains32BandDb=arr("eq32",32),pregainEnabled=o.optBoolean("pregainEnabled",fallback.pregainEnabled),pregainDb=o.optDouble("pregainDb",fallback.pregainDb.toDouble()).toFloat(),bassBoostEnabled=o.optBoolean("bassBoostEnabled",fallback.bassBoostEnabled),bassBoostStrength=o.optDouble("bassBoostStrength",fallback.bassBoostStrength.toDouble()).toFloat(),
            mdrcEnabled=o.optBoolean("mdrcEnabled",fallback.mdrcEnabled),mdrcLowCrossoverHz=o.optDouble("mdrcLowCrossoverHz",fallback.mdrcLowCrossoverHz.toDouble()).toFloat(),mdrcMidCrossoverHz=o.optDouble("mdrcMidCrossoverHz",fallback.mdrcMidCrossoverHz.toDouble()).toFloat(),mdrcHighCrossoverHz=o.optDouble("mdrcHighCrossoverHz",fallback.mdrcHighCrossoverHz.toDouble()).toFloat(),mdrcLowBand=band("low",fallback.mdrcLowBand),mdrcMidBand=band("mid",fallback.mdrcMidBand),mdrcHighBand=band("high",fallback.mdrcHighBand),mdrcUltraBand=band("ultra",fallback.mdrcUltraBand),
            toneEnabled=o.optBoolean("toneEnabled",fallback.toneEnabled),bassToneDb=o.optDouble("bassToneDb",fallback.bassToneDb.toDouble()).toFloat(),midToneDb=o.optDouble("midToneDb",fallback.midToneDb.toDouble()).toFloat(),trebleToneDb=o.optDouble("trebleToneDb",fallback.trebleToneDb.toDouble()).toFloat(),limiterEnabled=o.optBoolean("limiterEnabled",o.optBoolean("headroomEnabled",fallback.limiterEnabled)),headroomDb=o.optDouble("headroomDb",fallback.headroomDb.toDouble()).toFloat(),limiterThresholdDb=o.optDouble("limiterThresholdDb",fallback.limiterThresholdDb.toDouble()).toFloat(),limiterRatio=o.optDouble("limiterRatio",fallback.limiterRatio.toDouble()).toFloat(),limiterAttackMs=o.optDouble("limiterAttackMs",fallback.limiterAttackMs.toDouble()).toFloat(),limiterReleaseMs=o.optDouble("limiterReleaseMs",fallback.limiterReleaseMs.toDouble()).toFloat(),limiterPostGainDb=o.optDouble("limiterPostGainDb",fallback.limiterPostGainDb.toDouble()).toFloat(),autoGainEnabled=o.optBoolean("autoGainEnabled",fallback.autoGainEnabled),autoGainTargetRmsDb=o.optDouble("autoGainTargetRmsDb",fallback.autoGainTargetRmsDb.toDouble()).toFloat(),masterGainDb=o.optDouble("masterGainDb",fallback.masterGainDb.toDouble()).toFloat(),balance=o.optDouble("balance",fallback.balance.toDouble()).toFloat(),virtualizerEnabled=o.optBoolean("virtualizerEnabled",fallback.virtualizerEnabled),virtualizerStrength=o.optDouble("virtualizerStrength",fallback.virtualizerStrength.toDouble()).toFloat()).validate()
    }.getOrDefault(fallback)
}
