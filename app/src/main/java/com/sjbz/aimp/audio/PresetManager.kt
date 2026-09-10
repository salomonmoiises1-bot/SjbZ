package com.sjbz.aimp.audio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sjbz.aimp.model.EqPreset
import com.sjbz.aimp.model.MDRCSettings
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Manages Equalizer + MDRC presets using SharedPreferences and JSON (Gson).
 * Features 7 factory presets calibrated for the Actions ATS2835P audio chip,
 * each with a distinct 32-band curve, MDRC profile, and color coding.
 * Supports saving, loading, deleting, and exporting/importing .sjbz preset files.
 */
class PresetManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "sjbz_eq_presets_prefs"
        private const val KEY_PRESETS = "key_custom_presets"
        private const val KEY_ACTIVE_PRESET_NAME = "key_active_preset_name"

        val FACTORY_PRESET_NAMES = listOf(
            "ATS-2835P Master",
            "Bass Boost",
            "Acoustic",
            "Vocal Boost",
            "Flat",
            "Rock",
            "Electronic"
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    val defaultPresetNames: List<String> get() = FACTORY_PRESET_NAMES

    fun getActivePresetName(): String {
        return prefs.getString(KEY_ACTIVE_PRESET_NAME, "ATS-2835P Master") ?: "ATS-2835P Master"
    }

    fun setActivePresetName(name: String) {
        prefs.edit().putString(KEY_ACTIVE_PRESET_NAME, name).apply()
    }

    fun getFactoryPresets(): List<EqPreset> {
        val list = mutableListOf<EqPreset>()
        val eq = EqualizerProcessor()

        val factoryColors = mapOf(
            "ATS-2835P Master" to 0xFFFF7700.toInt(), // Naranja AIMP
            "Bass Boost" to 0xFF00E676.toInt(),       // Flúor
            "Acoustic" to 0xFF00BCD4.toInt(),         // Cyan
            "Vocal Boost" to 0xFFFFC107.toInt(),      // Amarillo
            "Flat" to 0xFF2196F3.toInt(),             // Azul
            "Rock" to 0xFFFF1744.toInt(),             // Rojo
            "Electronic" to 0xFF651FFF.toInt()        // Morado
        )

        for (name in FACTORY_PRESET_NAMES) {
            eq.applyPreset(name)
            val color = factoryColors[name] ?: EqPreset.generateRandomColor()
            val preset = eq.toEqPreset(
                name = name,
                isCustom = false,
                mdrcSettings = MDRCSettings(),
                color = color
            )
            list.add(preset)
        }
        return list
    }

    fun getAllPresets(): List<EqPreset> {
        val list = mutableListOf<EqPreset>()

        // 1. Built-in factory presets (7 presets)
        list.addAll(getFactoryPresets())

        // 2. Custom user presets from SharedPreferences
        val customJson = prefs.getString(KEY_PRESETS, null)
        if (!customJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<EqPreset>>() {}.type
                val customList: List<EqPreset> = gson.fromJson(customJson, type)
                for (p in customList) {
                    val validColor = if (p.color != 0) p.color else EqPreset.generateRandomColor()
                    list.add(p.copy(color = validColor, isCustom = true))
                }
            } catch (_: Exception) {}
        }

        return list
    }

    fun saveCustomPreset(preset: EqPreset): Boolean {
        val currentCustom = getCustomPresets().toMutableList()
        val existingIndex = currentCustom.indexOfFirst { it.name.equals(preset.name, ignoreCase = true) }

        val finalPreset = if (existingIndex >= 0) {
            // If preset already exists, preserve its existing color unless a valid new one is passed
            val oldColor = currentCustom[existingIndex].color
            val chosenColor = if (preset.color != 0) preset.color else oldColor
            currentCustom.removeAt(existingIndex)
            preset.copy(isCustom = true, color = chosenColor)
        } else {
            val validColor = if (preset.color != 0) preset.color else EqPreset.generateRandomColor()
            preset.copy(isCustom = true, color = validColor)
        }

        currentCustom.add(finalPreset)

        val json = gson.toJson(currentCustom)
        prefs.edit().putString(KEY_PRESETS, json).apply()
        setActivePresetName(preset.name)
        return true
    }

    fun deletePreset(name: String): Boolean {
        if (FACTORY_PRESET_NAMES.any { it.equals(name, ignoreCase = true) }) {
            return false // cannot delete built-in presets
        }
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
            val list: List<EqPreset> = gson.fromJson(json, type) ?: emptyList()
            list.map {
                if (it.color == 0) it.copy(color = EqPreset.generateRandomColor(), isCustom = true)
                else it.copy(isCustom = true)
            }
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
                    var preset: EqPreset = gson.fromJson(reader, EqPreset::class.java)
                    // If imported preset has no color or invalid color (0), assign random from palette
                    if (preset.color == 0) {
                        preset = preset.withColor(EqPreset.generateRandomColor())
                    }
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
