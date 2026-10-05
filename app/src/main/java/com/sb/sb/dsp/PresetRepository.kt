package com.sb.dsp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Complete DSP preset storage.
 *
 * A motor preset contains every user-facing DSP parameter: master/bypass,
 * EQ 10/20/32 curves, pre-gain, bass boost, MDRC crossovers + all four
 * compressor bands, tone, limiter/headroom, AutoGain, master gain, balance
 * and virtualizer. Hardware-dependent sample rate/channel count are never
 * persisted as preset parameters.
 */
class PresetRepository(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("sb_presets", Context.MODE_PRIVATE)

    data class PresetInfo(val name: String, val factory: Boolean)

    private val factoryNames = listOf(
        "Plano SB", "Rock", "Pop", "Vocal", "Electronic", "Acoustic", "Cinema", "Noche"
    )

    init {
        ensureFactoryPresets()
    }

    fun names(): List<String> = (factoryNames + customNames()).distinct()

    fun presetInfos(): List<PresetInfo> = names().map { PresetInfo(it, it in factoryNames) }

    fun selectedPresetName(): String = prefs.getString("selected_preset", "Plano SB") ?: "Plano SB"

    fun setSelectedPresetName(name: String) {
        if (name in names()) prefs.edit().putString("selected_preset", name).apply()
    }

    fun customNames(): List<String> = readNames("names")

    fun eqNames(): List<String> = readNames("eq_names")

    fun saveEq(name: String, c: DspConfig) {
        val n = name.trim()
        if (n.isEmpty()) return
        val names = eqNames().toMutableList().apply { if (!contains(n)) add(n) }
        val o = JSONObject().put("eqMode", c.eqMode.name)
            .put("eq10", JSONArray(c.gains10BandDb.toList()))
            .put("eq20", JSONArray(c.gains20BandDb.toList()))
            .put("eq32", JSONArray(c.gains32BandDb.toList()))
        prefs.edit()
            .putString("eq_names", JSONArray(names).toString())
            .putString("eq_preset_$n", o.toString())
            .apply()
    }

    fun loadEq(name: String, fallback: DspConfig): DspConfig = runCatching {
        val o = JSONObject(prefs.getString("eq_preset_$name", "{}")!!)
        fallback.copy(
            eqMode = runCatching {
                DspConfig.EqMode.valueOf(o.optString("eqMode", fallback.eqMode.name))
            }.getOrDefault(fallback.eqMode),
            gains10BandDb = readArray(o, "eq10", fallback.gains10BandDb),
            gains20BandDb = readArray(o, "eq20", fallback.gains20BandDb),
            gains32BandDb = readArray(o, "eq32", fallback.gains32BandDb)
        ).validate()
    }.getOrDefault(fallback)

    fun save(name: String, c: DspConfig) {
        val n = name.trim()
        if (n.isEmpty()) return
        val clean = c.validate()
        val names = customNames().toMutableList().apply { if (!contains(n)) add(n) }
        prefs.edit()
            .putString("names", JSONArray(names).toString())
            .putString("preset_$n", encode(clean).toString())
            .apply()
    }

    fun load(name: String, fallback: DspConfig): DspConfig = runCatching {
        val json = factoryPreset(name) ?: JSONObject(prefs.getString("preset_$name", "{}")!!)
        decode(json, fallback).validate()
    }.getOrDefault(fallback)

    fun isFactory(name: String): Boolean = name in factoryNames

    fun deleteCustom(name: String) {
        if (isFactory(name)) return
        val names = customNames().filterNot { it == name }
        prefs.edit().putString("names", JSONArray(names).toString()).remove("preset_$name").apply()
    }

    fun renameCustom(oldName: String, newName: String) {
        if (isFactory(oldName)) return
        val n = newName.trim()
        if (n.isEmpty() || n == oldName || isFactory(n)) return
        val config = load(oldName, DspConfig())
        deleteCustom(oldName)
        save(n, config)
    }

    private fun readNames(key: String): List<String> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun encode(c: DspConfig): JSONObject {
        val o = JSONObject()
        o.put("masterEnabled", c.masterEnabled)
            .put("eqEnabled", c.eqEnabled)
            .put("eqMode", c.eqMode.name)
            .put("pregainEnabled", c.pregainEnabled)
            .put("pregainDb", c.pregainDb)
            .put("bassBoostEnabled", c.bassBoostEnabled)
            .put("bassBoostStrength", c.bassBoostStrength)
            .put("bassBoostFrequencyHz", c.bassBoostFrequencyHz)
            .put("mdrcEnabled", c.mdrcEnabled)
            .put("mdrcLowCrossoverHz", c.mdrcLowCrossoverHz)
            .put("mdrcMidCrossoverHz", c.mdrcMidCrossoverHz)
            .put("mdrcHighCrossoverHz", c.mdrcHighCrossoverHz)
            .put("toneEnabled", c.toneEnabled)
            .put("bassToneDb", c.bassToneDb)
            .put("midToneDb", c.midToneDb)
            .put("trebleToneDb", c.trebleToneDb)
            .put("limiterEnabled", c.limiterEnabled)
            .put("headroomDb", c.headroomDb)
            .put("limiterThresholdDb", c.limiterThresholdDb)
            .put("limiterRatio", c.limiterRatio)
            .put("limiterAttackMs", c.limiterAttackMs)
            .put("limiterReleaseMs", c.limiterReleaseMs)
            .put("limiterPostGainDb", c.limiterPostGainDb)
            .put("autoGainEnabled", c.autoGainEnabled)
            .put("autoGainTargetRmsDb", c.autoGainTargetRmsDb)
            .put("masterGainDb", c.masterGainDb)
            .put("balance", c.balance)
            .put("virtualizerEnabled", c.virtualizerEnabled)
            .put("virtualizerStrength", c.virtualizerStrength)
            .put("eq10", JSONArray(c.gains10BandDb.toList()))
            .put("eq20", JSONArray(c.gains20BandDb.toList()))
            .put("eq32", JSONArray(c.gains32BandDb.toList()))
        putBand(o, "low", c.mdrcLowBand)
        putBand(o, "mid", c.mdrcMidBand)
        putBand(o, "high", c.mdrcHighBand)
        putBand(o, "ultra", c.mdrcUltraBand)
        return o
    }

    private fun putBand(o: JSONObject, key: String, b: DspConfig.BandCompressorConfig) {
        o.put("${key}T", b.thresholdDb)
            .put("${key}R", b.ratio)
            .put("${key}A", b.attackMs)
            .put("${key}Rel", b.releaseMs)
            .put("${key}M", b.makeupGainDb)
    }

    private fun decode(o: JSONObject, fallback: DspConfig): DspConfig {
        fun f(key: String, current: Float) = o.optDouble(key, current.toDouble()).toFloat()
        fun b(key: String, current: DspConfig.BandCompressorConfig) = DspConfig.BandCompressorConfig(
            f("${key}T", current.thresholdDb),
            f("${key}R", current.ratio),
            f("${key}A", current.attackMs),
            f("${key}Rel", current.releaseMs),
            f("${key}M", current.makeupGainDb)
        )
        return fallback.copy(
            masterEnabled = o.optBoolean("masterEnabled", fallback.masterEnabled),
            eqEnabled = o.optBoolean("eqEnabled", fallback.eqEnabled),
            eqMode = runCatching { DspConfig.EqMode.valueOf(o.optString("eqMode", fallback.eqMode.name)) }.getOrDefault(fallback.eqMode),
            gains10BandDb = readArray(o, "eq10", fallback.gains10BandDb),
            gains20BandDb = readArray(o, "eq20", fallback.gains20BandDb),
            gains32BandDb = readArray(o, "eq32", fallback.gains32BandDb),
            pregainEnabled = o.optBoolean("pregainEnabled", fallback.pregainEnabled),
            pregainDb = f("pregainDb", fallback.pregainDb),
            bassBoostEnabled = o.optBoolean("bassBoostEnabled", fallback.bassBoostEnabled),
            bassBoostStrength = f("bassBoostStrength", fallback.bassBoostStrength),
            bassBoostFrequencyHz = f("bassBoostFrequencyHz", fallback.bassBoostFrequencyHz),
            mdrcEnabled = o.optBoolean("mdrcEnabled", fallback.mdrcEnabled),
            mdrcLowCrossoverHz = f("mdrcLowCrossoverHz", fallback.mdrcLowCrossoverHz),
            mdrcMidCrossoverHz = f("mdrcMidCrossoverHz", fallback.mdrcMidCrossoverHz),
            mdrcHighCrossoverHz = f("mdrcHighCrossoverHz", fallback.mdrcHighCrossoverHz),
            mdrcLowBand = b("low", fallback.mdrcLowBand),
            mdrcMidBand = b("mid", fallback.mdrcMidBand),
            mdrcHighBand = b("high", fallback.mdrcHighBand),
            mdrcUltraBand = b("ultra", fallback.mdrcUltraBand),
            toneEnabled = o.optBoolean("toneEnabled", fallback.toneEnabled),
            bassToneDb = f("bassToneDb", fallback.bassToneDb),
            midToneDb = f("midToneDb", fallback.midToneDb),
            trebleToneDb = f("trebleToneDb", fallback.trebleToneDb),
            limiterEnabled = o.optBoolean("limiterEnabled", fallback.limiterEnabled),
            headroomDb = f("headroomDb", fallback.headroomDb),
            limiterThresholdDb = f("limiterThresholdDb", fallback.limiterThresholdDb),
            limiterRatio = f("limiterRatio", fallback.limiterRatio),
            limiterAttackMs = f("limiterAttackMs", fallback.limiterAttackMs),
            limiterReleaseMs = f("limiterReleaseMs", fallback.limiterReleaseMs),
            limiterPostGainDb = f("limiterPostGainDb", fallback.limiterPostGainDb),
            autoGainEnabled = o.optBoolean("autoGainEnabled", fallback.autoGainEnabled),
            autoGainTargetRmsDb = f("autoGainTargetRmsDb", fallback.autoGainTargetRmsDb),
            masterGainDb = f("masterGainDb", fallback.masterGainDb),
            balance = f("balance", fallback.balance),
            virtualizerEnabled = o.optBoolean("virtualizerEnabled", fallback.virtualizerEnabled),
            virtualizerStrength = f("virtualizerStrength", fallback.virtualizerStrength)
        )
    }

    private fun readArray(o: JSONObject, key: String, source: FloatArray): FloatArray = FloatArray(source.size) { i ->
        o.optJSONArray(key)?.optDouble(i, source.getOrElse(i) { 0f }.toDouble())?.toFloat()
            ?: source.getOrElse(i) { 0f }
    }

    /** Factory presets are immutable and recreated from deterministic definitions. */
    private fun factoryPreset(name: String): JSONObject? {
        val base = DspConfig()
        fun curve(values: FloatArray, gain: Float = 1f) = FloatArray(values.size) { i -> values[i] * gain }
        fun configFor(vararg eq32: Float): DspConfig {
            val g32 = FloatArray(32)
            eq32.forEachIndexed { i, v -> if (i < g32.size) g32[i] = v }
            return base.copy(gains32BandDb = g32).validate()
        }
        val c = when (name) {
            "Plano SB" -> base
            "Rock" -> base.copy(
                eqMode = DspConfig.EqMode.BANDS_32,
                gains32BandDb = floatArrayOf(3f,3f,2.5f,2f,2f,1.5f,1f,0f,0f,0.5f,1f,1f,1f,1.5f,2f,2f,2.5f,2f,1f,0f,-1f,-1f,-0.5f,0.5f,1.5f,2f,2f,1.5f,0.5f,0f,0f,-0.5f),
                toneEnabled = true, bassToneDb = 1.5f, midToneDb = 0.5f, trebleToneDb = 1.5f,
                limiterEnabled = true, limiterThresholdDb = -2.5f, limiterRatio = 8f
            )
            "Pop" -> base.copy(
                gains32BandDb = floatArrayOf(2f,2f,2f,1.5f,1.5f,1f,0.5f,0f,0f,0.5f,0.5f,0.5f,0f,0.5f,1f,1.5f,2f,2f,1.5f,1f,0.5f,0.5f,1f,1.5f,2f,2.5f,3f,3f,2.5f,2f,1.5f,1f),
                toneEnabled = true, bassToneDb = 1f, midToneDb = 0.5f, trebleToneDb = 1.5f
            )
            "Vocal" -> base.copy(
                gains32BandDb = floatArrayOf(-2f,-2f,-1.5f,-1f,-0.5f,0f,0f,0.5f,0.5f,1f,1f,1.5f,2f,2.5f,3f,3f,2.5f,2f,1.5f,1f,0.5f,0f,0f,0.5f,1f,1.5f,1f,0.5f,0f,-0.5f,-1f,-1.5f),
                toneEnabled = true, bassToneDb = -1f, midToneDb = 2f, trebleToneDb = 1f
            )
            "Electronic" -> base.copy(
                gains32BandDb = floatArrayOf(4f,4f,3.5f,3f,3f,2.5f,2f,1f,1f,1.5f,1f,0.5f,0f,0.5f,1f,1f,0.5f,0f,0f,0.5f,1f,1.5f,2f,2.5f,3f,3.5f,4f,4f,3.5f,3f,2.5f,2f),
                bassBoostEnabled = true, bassBoostStrength = 0.18f, bassBoostFrequencyHz = 75f,
                limiterEnabled = true, limiterThresholdDb = -3f, limiterRatio = 10f
            )
            "Acoustic" -> base.copy(
                gains32BandDb = floatArrayOf(1f,1f,0.5f,0f,0f,0f,0f,0.5f,1f,1.5f,1.5f,1f,0.5f,0.5f,1f,1.5f,1.5f,1f,0.5f,0f,0f,0.5f,1f,1.5f,2f,2f,1.5f,1f,0.5f,0f,0f,0f),
                toneEnabled = true, bassToneDb = 0.5f, midToneDb = 1f, trebleToneDb = 1f
            )
            "Cinema" -> base.copy(
                gains32BandDb = floatArrayOf(3f,3f,2.5f,2f,2f,1.5f,1f,0.5f,0f,0f,0f,0.5f,1f,1.5f,1.5f,1f,0.5f,0f,0f,0.5f,1f,1.5f,2f,2.5f,3f,3.5f,4f,4f,3.5f,3f,2f,1f),
                mdrcEnabled = true, mdrcLowCrossoverHz = 120f, mdrcMidCrossoverHz = 800f, mdrcHighCrossoverHz = 4200f,
                limiterEnabled = true, limiterThresholdDb = -3f, limiterRatio = 8f
            )
            "Noche" -> base.copy(
                gains32BandDb = floatArrayOf(1f,1f,0.5f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,-0.5f,-0.5f,-1f,-1f,-1.5f,-2f,-2f,-2.5f,-3f,-3f,-3.5f,-4f),
                autoGainEnabled = true, autoGainTargetRmsDb = -18f,
                limiterEnabled = true, limiterThresholdDb = -4f, limiterRatio = 12f, limiterAttackMs = 2f, limiterReleaseMs = 120f
            )
            else -> return null
        }
        val tuned = c.copy(
            gains10BandDb = remapCurve(c.gains32BandDb, DspConfig.FREQUENCIES_32, DspConfig.FREQUENCIES_10),
            gains20BandDb = remapCurve(c.gains32BandDb, DspConfig.FREQUENCIES_32, DspConfig.FREQUENCIES_20)
        ).validate()
        return encode(tuned)
    }

    private fun remapCurve(source: FloatArray, sourceFreq: FloatArray, targetFreq: FloatArray): FloatArray =
        FloatArray(targetFreq.size) { i ->
            val f = targetFreq[i]
            var best = 0
            var bestDistance = Float.POSITIVE_INFINITY
            for (j in sourceFreq.indices) {
                val d = kotlin.math.abs(kotlin.math.ln(f / sourceFreq[j]))
                if (d < bestDistance) {
                    bestDistance = d
                    best = j
                }
            }
            source.getOrElse(best) { 0f }
        }

    private fun ensureFactoryPresets() {
        // Factory presets are virtual; this migration only removes obsolete
        // factory names from the custom list so they cannot be accidentally
        // shadowed by an old user preset with the same name.
        val clean = customNames().filterNot { it in factoryNames }
        if (clean.size != customNames().size) {
            prefs.edit().putString("names", JSONArray(clean).toString()).apply()
        }
    }
}
