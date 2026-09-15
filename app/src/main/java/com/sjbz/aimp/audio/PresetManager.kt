package com.sjbz.aimp.audio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sjbz.aimp.model.EqPreset
import com.sjbz.aimp.model.MDRCBandConfig
import com.sjbz.aimp.model.MDRCSettings
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Manages Equalizer + MDRC presets using SharedPreferences and JSON (Gson).
 * Features 7 factory presets calibrated for the Actions ATS2835P audio chip,
 * each with a distinct 32-band curve, MDRC profile, and color coding.
 * Supports saving, loading, deleting, and exporting/importing.sjbz preset files.
 */
class PresetManager(private val context: Context) {

    companion object {
        private const val TAG = "PresetManager"
        private const val PREFS_NAME = "sjbz_eq_presets_prefs"
        private const val KEY_PRESETS = "key_custom_presets"
        private const val KEY_ACTIVE_PRESET_NAME = "key_active_preset_name"
        private const val DEFAULT_PRESET = "ATS-2835P Master"

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
        val saved = prefs.getString(KEY_ACTIVE_PRESET_NAME, DEFAULT_PRESET)?: DEFAULT_PRESET
        // PATCH: si el activo guardado ya no existe (borrado o corrupto), vuelve al default
        // Antes devolvía el nombre borrado y EqActivity hacía find -> null -> first(), parpadeo.
        val exists = getAllPresets().any { it.name.equals(saved, ignoreCase = true) }
        return if (exists) saved else DEFAULT_PRESET
    }

    fun setActivePresetName(name: String) {
        prefs.edit().putString(KEY_ACTIVE_PRESET_NAME, name).apply()
    }

    // PATCH: perfiles MDRC por preset de fábrica. Antes todos usaban MDRCSettings()
    // vacío, contradiciendo el KDoc que promete "distinct MDRC profile".
    private fun mdrcForFactory(name: String): MDRCSettings {
        val base = MDRCProcessor()
        when (name) {
            "Bass Boost" -> {
                base.getBand(0)?.gainDb = 4.0f
                base.getBand(1)?.gainDb = 2.0f
            }
            "Acoustic" -> {
                base.getBand(2)?.gainDb = 2.0f
                base.getBand(3)?.gainDb = 1.5f
            }
            "Vocal Boost" -> {
                base.getBand(2)?.gainDb = 3.0f
                base.getBand(2)?.thresholdDb = -12.0f
            }
            "Rock" -> {
                base.getBand(0)?.gainDb = 2.5f
                base.getBand(3)?.gainDb = 2.0f
                base.getBand(4)?.gainDb = 1.5f
            }
            "Electronic" -> {
                base.getBand(0)?.gainDb = 3.5f
                base.getBand(4)?.gainDb = 2.5f
                base.getBand(1)?.ratio = 3.0f
            }
            "Flat" -> {
                // todo a 0, ya es el default
            }
            // "ATS-2835P Master" -> curva de referencia, deja defaults
        }
        return base.toMDRCSettings()
    }

    fun getFactoryPresets(): List<EqPreset> {
        val list = mutableListOf<EqPreset>()
        val eq = EqualizerProcessor()

        val factoryColors = mapOf(
            "ATS-2835P Master" to 0xFFFF7700.toInt(),
            "Bass Boost" to 0xFF00E676.toInt(),
            "Acoustic" to 0xFF00BCD4.toInt(),
            "Vocal Boost" to 0xFFFFC107.toInt(),
            "Flat" to 0xFF2196F3.toInt(),
            "Rock" to 0xFFFF1744.toInt(),
            "Electronic" to 0xFF651FFF.toInt()
        )

        for (name in FACTORY_PRESET_NAMES) {
            try {
                eq.applyPreset(name)
            } catch (e: Exception) {
                Log.w(TAG, "applyPreset($name) falló: ${e.message}")
            }
            val color = factoryColors[name]?: EqPreset.generateRandomColor()
            val preset = eq.toEqPreset(
                name = name,
                isCustom = false,
                mdrcSettings = mdrcForFactory(name),
                color = color
            )
            list.add(preset)
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
                    // PATCH: evita duplicados contra fábrica (insensible a mayúsculas).
                    // Antes un custom "bass boost" aparecía dos veces en el spinner.
                    if (FACTORY_PRESET_NAMES.any { it.equals(p.name, ignoreCase = true) }) {
                        Log.w(TAG, "custom duplicado de fábrica ignorado: ${p.name}")
                        continue
                    }
                    // PATCH: valida tamaño de bandas, un.sjbz corrupto podía traer 10 bandas
                    // y romper setup32BandSliders.
                    val fixed = sanitizePreset(p)
                    val validColor = if (fixed.color!= 0) fixed.color else EqPreset.generateRandomColor()
                    list.add(fixed.copy(color = validColor, isCustom = true))
                }
            } catch (e: Exception) {
                Log.e(TAG, "parse custom presets falló: ${e.message}")
            }
        }

        return list
    }

    // PATCH: normaliza un preset importado o guardado a 32 bandas y valores en rango.
    private fun sanitizePreset(p: EqPreset): EqPreset {
        val gains = p.bandGains
        val fixedGains = when {
            gains.size == EqualizerProcessor.BAND_COUNT -> gains.map { it.coerceIn(-12f, 12f) }
            gains.size > EqualizerProcessor.BAND_COUNT -> gains.take(EqualizerProcessor.BAND_COUNT).map { it.coerceIn(-12f, 12f) }
            else -> gains.map { it.coerceIn(-12f, 12f) } + List(EqualizerProcessor.BAND_COUNT - gains.size) { 0f }
        }
        val fixedPreamp = p.preampDb.coerceIn(-12f, 12f)
        return p.copy(bandGains = fixedGains, preampDb = fixedPreamp)
    }

    fun saveCustomPreset(preset: EqPreset): Boolean {
        // PATCH: no permite pisar un nombre de fábrica con un custom.
        // Antes se guardaba "Rock" custom y el spinner mostraba dos "Rock".
        if (FACTORY_PRESET_NAMES.any { it.equals(preset.name, ignoreCase = true) }) {
            Log.w(TAG, "saveCustomPreset rechazado: '${preset.name}' es nombre de fábrica")
            return false
        }
        val sanitized = sanitizePreset(preset)
        val currentCustom = getCustomPresets().toMutableList()
        val existingIndex = currentCustom.indexOfFirst { it.name.equals(sanitized.name, ignoreCase = true) }

        val finalPreset = if (existingIndex >= 0) {
            val oldColor = currentCustom[existingIndex].color
            val chosenColor = if (sanitized.color!= 0) sanitized.color else oldColor
            currentCustom.removeAt(existingIndex)
            sanitized.copy(isCustom = true, color = chosenColor)
        } else {
            val validColor = if (sanitized.color!= 0) sanitized.color else EqPreset.generateRandomColor()
            sanitized.copy(isCustom = true, color = validColor)
        }

        currentCustom.add(finalPreset)

        return try {
            val json = gson.toJson(currentCustom)
            prefs.edit().putString(KEY_PRESETS, json).apply()
            setActivePresetName(sanitized.name)
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveCustomPreset falló: ${e.message}")
            false
        }
    }

    fun deletePreset(name: String): Boolean {
        if (FACTORY_PRESET_NAMES.any { it.equals(name, ignoreCase = true) }) {
            return false
        }
        val currentCustom = getCustomPresets().toMutableList()
        val removed = currentCustom.removeAll { it.name.equals(name, ignoreCase = true) }
        if (removed) {
            try {
                val json = gson.toJson(currentCustom)
                prefs.edit().putString(KEY_PRESETS, json).apply()
            } catch (e: Exception) {
                Log.e(TAG, "deletePreset persist falló: ${e.message}")
            }
            // PATCH: solo resetea el activo si el borrado era el activo
            if (getActivePresetName().equals(name, ignoreCase = true)) {
                setActivePresetName(DEFAULT_PRESET)
            }
        }
        return removed
    }

    fun getCustomPresets(): List<EqPreset> {
        val json = prefs.getString(KEY_PRESETS, null)?: return emptyList()
        return try {
            val type = object : TypeToken<List<EqPreset>>() {}.type
            val list: List<EqPreset> = gson.fromJson(json, type)?: emptyList()
            list.map {
                val s = sanitizePreset(it)
                if (s.color == 0) s.copy(color = EqPreset.generateRandomColor(), isCustom = true)
                else s.copy(isCustom = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCustomPresets falló: ${e.message}")
            emptyList()
        }
    }

    fun exportPresetToSjbz(preset: EqPreset, outputUri: Uri): Boolean {
        return try {
            // PATCH: exporta versión sanitizada para no escribir.sjbz corruptos
            val clean = sanitizePreset(preset)
            context.contentResolver.openOutputStream(outputUri)?.use { outStream ->
                OutputStreamWriter(outStream).use { writer ->
                    gson.toJson(clean, writer)
                }
            }?: run {
                Log.e(TAG, "exportPresetToSjbz: openOutputStream null para $outputUri")
                return false
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importPresetFromSjbz(inputUri: Uri): EqPreset? {
        return try {
            val preset: EqPreset? = context.contentResolver.openInputStream(inputUri)?.use { inStream ->
                InputStreamReader(inStream).use { reader ->
                    gson.fromJson(reader, EqPreset::class.java)
                }
            }
            if (preset == null) {
                Log.e(TAG, "importPresetFromSjbz: preset null en $inputUri")
                return null
            }
            if (preset.name.isBlank()) {
                Log.e(TAG, "importPresetFromSjbz: nombre vacío")
                return null
            }
            var fixed: EqPreset = sanitizePreset(preset)
            if (fixed.color == 0) {
                fixed = fixed.withColor(EqPreset.generateRandomColor())
            }
            fixed = fixed.copy(isCustom = true)
            // PATCH: si el importado choca con fábrica, se renombra en vez de duplicar
            var finalName = fixed.name
            var suffix = 1
            while (FACTORY_PRESET_NAMES.any { it.equals(finalName, ignoreCase = true) }) {
                suffix++
                finalName = "${fixed.name} ($suffix)"
            }
            fixed = fixed.copy(name = finalName)
            val ok = saveCustomPreset(fixed)
            if (!ok) {
                Log.e(TAG, "importPresetFromSjbz: saveCustomPreset rechazó '${fixed.name}'")
                return null
            }
            // devuelve el guardado con el nombre final
            fixed
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
