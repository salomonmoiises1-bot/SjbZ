package com.sjbz.aimp.audio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sjbz.aimp.model.EqPreset
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class PresetManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "sjbz_dsp_pro"
        private const val KEY_PRESETS = "key_custom_presets"
        private const val KEY_ACTIVE_PRESET_NAME = "key_active_preset_name"

        val FACTORY_PRESET_NAMES = listOf(
            "Studio Master",
            "Flat",
            "Bass",
            "Rock",
            "Vocal",
            "Electronic",
            "Pop",
            "Jazz",
            "Classical",
            "Acoustic",
            "Metal"
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    val defaultPresetNames: List<String> get() = FACTORY_PRESET_NAMES

    fun getActivePresetName(): String {
        return prefs.getString(KEY_ACTIVE_PRESET_NAME, "Studio Master")?: "Studio Master"
    }

    fun setActivePresetName(name: String) {
        prefs.edit().putString(KEY_ACTIVE_PRESET_NAME, name).apply()
    }

    fun getFactoryPresets(): List<EqPreset> {
        val list = mutableListOf<EqPreset>()
        val eq = EqualizerProcessor()
        val factoryColors = mapOf(
            "Studio Master" to 0xFF00E5FF.toInt(),
            "Flat" to 0xFF38BDF8.toInt(),
            "Bass" to 0xFF00E676.toInt(),
            "Rock" to 0xFFFF1744.toInt(),
            "Vocal" to 0xFFFFB300.toInt(),
            "Electronic" to 0xFFD500F9.toInt(),
            "Pop" to 0xFF00B0FF.toInt(),
            "Jazz" to 0xFF7C4DFF.toInt(),
            "Classical" to 0xFF1DE9B6.toInt(),
            "Acoustic" to 0xFFFF9100.toInt(),
            "Metal" to 0xFFE040FB.toInt()
        )
        for (name in FACTORY_PRESET_NAMES) {
            try {
                eq.applyPreset(name)
                val color = factoryColors[name]?: EqPreset.generateRandomColor()
                val preset = eq.toEqPreset(
                    name = name,
                    isCustom = false,
                    bassBoostEnabled = (name == "Bass" || name == "Electronic" || name == "Rock"),
                    bassBoostFreq = 85f,
                    bassBoostGain = if (name == "Bass") 8.0f else if (name == "Electronic") 6.0f else 4.0f,
                    color = color
                )
                list.add(preset)
            } catch (_: Exception) {}
        }
        return list
    }

    fun getAllPresets(): List<EqPreset> {
        val list = mutableListOf<EqPreset>()
        list.addAll(getFactoryPresets())
        val customJson = prefs.getString(KEY_PRESETS, null)
        if (!customJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<EqPreset>>() {}.type
                val customList: List<EqPreset> = gson.fromJson(customJson, type)?: emptyList()
                for (p in customList) {
                    val validColor = if (p.color!= 0) p.color else EqPreset.generateRandomColor()
                    list.add(p.copy(color = validColor, isCustom = true))
                }
            } catch (_: Exception) {}
        }
        return list
    }

    fun saveCustomPreset(preset: EqPreset): Boolean {
        return try {
            val all = getAllPresets().filter { it.isCustom }.toMutableList()
            val existingIndex = all.indexOfFirst { it.name.equals(preset.name, ignoreCase = true) }
            val toSave = preset.copy(isCustom = true)
            if (existingIndex >= 0) all[existingIndex] = toSave else all.add(toSave)
            val json = gson.toJson(all)
            prefs.edit().putString(KEY_PRESETS, json).commit()
        } catch (_: Exception) { false }
    }

    fun deleteCustomPreset(presetName: String): Boolean {
        return try {
            val all = getAllPresets().filter { it.isCustom }.toMutableList()
            val removed = all.removeAll { it.name.equals(presetName, ignoreCase = true) }
            if (removed) {
                val json = gson.toJson(all)
                prefs.edit().putString(KEY_PRESETS, json).apply()
                true
            } else false
        } catch (_: Exception) { false }
    }

    fun exportPresetToFile(uri: Uri, preset: EqPreset): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os).use { writer -> gson.toJson(preset, writer) }
            }
            true
        } catch (_: Exception) { false }
    }

    fun importPresetFromFile(uri: Uri): EqPreset? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                InputStreamReader(ins).use { reader -> gson.fromJson(reader, EqPreset::class.java) }
            }
        } catch (_: Exception) { null }
    }
}
