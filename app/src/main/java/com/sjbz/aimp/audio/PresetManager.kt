package com.sjbz.aimp.audio

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sjbz.aimp.model.EqPreset

class PresetManager(context: Context) {

    private val prefs = context.getSharedPreferences("sjbz_presets_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun savePreset(preset: EqPreset) {
        val current = getAllPresets().toMutableList()
        current.removeAll { it.name == preset.name }
        current.add(preset)
        saveAll(current)
    }

    fun getAllPresets(): List<EqPreset> {
        val json = prefs.getString("custom_presets_list", null) ?: return emptyList()
        val type = object : TypeToken<List<EqPreset>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAll(list: List<EqPreset>) {
        val json = gson.toJson(list)
        prefs.edit().putString("custom_presets_list", json).apply()
    }
}
