package com.sjbz.aimp.audio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sjbz.aimp.model.EqPreset
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Manages Equalizer + MDRC presets using SharedPreferences and JSON (Gson).
 * Supports saving, loading, deleting, and exporting/importing .sjbz preset files.
 */
class PresetManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "sjbz_eq_presets_prefs"
        private const val KEY_PRESETS = "key_custom_presets"
        private const val KEY_ACTIVE_PRESET_NAME = "key_active_preset_name"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    val defaultPresetNames = listOf(
        "ATS-2835P Master",
        "Harman Kardon Target",
        "Flat",
        "Bass Boost",
        "V-Shape",
        "Rock",
        "Vocal Clear"
    )

    fun getActivePresetName(): String {
        return prefs.getString(KEY_ACTIVE_PRESET_NAME, "ATS-2835P Master") ?: "ATS-2835P Master"
    }

    fun setActivePresetName(name: String) {
        prefs.edit().putString(KEY_ACTIVE_PRESET_NAME, name).apply()
    }

    fun getAllPresets(): List<EqPreset> {
        val list = mutableListOf<EqPreset>()

        // 1. Built-in presets
        for (name in defaultPresetNames) {
            val processor = EqualizerProcessor().apply { applyPreset(name) }
            list.add(processor.toEqPreset(name, isCustom = false))
        }

        // 2. Custom user presets from SharedPreferences
        val customJson = prefs.getString(KEY_PRESETS, null)
        if (!customJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<EqPreset>>() {}.type
                val customList: List<EqPreset> = gson.fromJson(customJson, type)
                list.addAll(customList)
            } catch (_: Exception) {}
        }

        return list
    }

    fun saveCustomPreset(preset: EqPreset): Boolean {
        val currentCustom = getCustomPresets().toMutableList()
        currentCustom.removeAll { it.name.equals(preset.name, ignoreCase = true) }
        currentCustom.add(preset.copy(isCustom = true))

        val json = gson.toJson(currentCustom)
        prefs.edit().putString(KEY_PRESETS, json).apply()
        setActivePresetName(preset.name)
        return true
    }

    fun deletePreset(name: String): Boolean {
        if (defaultPresetNames.contains(name)) return false // cannot delete built-in presets
        val currentCustom = getCustomPresets().toMutableList()
        val removed = currentCustom.removeAll { it.name.equals(name, ignoreCase = true) }
        if (removed) {
            val json = gson.toJson(currentCustom)
            prefs.edit().putString(KEY_PRESETS, json).apply()
            setActivePresetName("ATS-2835P Master")
        }
        return removed
    }

    fun getCustomPresets(): List<EqPreset> {
        val json = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<EqPreset>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun exportPresetToSjbz(preset: EqPreset, outputUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(outputUri)?.use { outStream ->
                OutputStreamWriter(outStream).use { writer ->
                    gson.toJson(preset, writer)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importPresetFromSjbz(inputUri: Uri): EqPreset? {
        return try {
            context.contentResolver.openInputStream(inputUri)?.use { inStream ->
                InputStreamReader(inStream).use { reader ->
                    val preset: EqPreset = gson.fromJson(reader, EqPreset::class.java)
                    saveCustomPreset(preset)
                    preset
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
