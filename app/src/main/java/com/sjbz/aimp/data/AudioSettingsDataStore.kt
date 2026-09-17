package com.sjbz.aimp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sjbz.aimp.model.AppProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sbz_audio_settings")

/**
 * AudioSettingsDataStore: Manages asynchronous, persistent audio parameters using Jetpack DataStore.
 * Ensures all EQ bands, Q ratios, Limiter thresholds, AutoGain parameters, and Per-App profiles
 * survive device reboots without loss.
 */
class AudioSettingsDataStore(private val context: Context) {

    private val gson = Gson()

    companion object {
        val KEY_GLOBAL_ENABLED = booleanPreferencesKey("global_dsp_enabled")
        val KEY_AUTO_START_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val KEY_GLOBAL_GAIN = floatPreferencesKey("global_gain_db")
        val KEY_LIMITER_ENABLED = booleanPreferencesKey("limiter_enabled")
        val KEY_LIMITER_THRESHOLD = floatPreferencesKey("limiter_threshold_db")
        val KEY_AUTOGAIN_ENABLED = booleanPreferencesKey("autogain_enabled")
        val KEY_AUTOGAIN_TARGET = floatPreferencesKey("autogain_target_lufs")
        val KEY_BASS_BOOST_DB = floatPreferencesKey("bass_boost_db")
        val KEY_BASS_FREQ_HZ = floatPreferencesKey("bass_freq_hz")
        val KEY_VIRTUALIZER_STRENGTH = intPreferencesKey("virtualizer_strength")
        val KEY_MDRC_ENABLED = booleanPreferencesKey("mdrc_enabled")
        val KEY_ATS2835P_EMU = booleanPreferencesKey("ats2835p_emu_enabled")
        val KEY_BAND_GAINS_JSON = stringPreferencesKey("band_gains_json")
        val KEY_BAND_QS_JSON = stringPreferencesKey("band_qs_json")
        val KEY_MDRC_GAINS_JSON = stringPreferencesKey("mdrc_gains_json")
        val KEY_CURRENT_PROFILE_ID = stringPreferencesKey("current_profile_id")
        val KEY_PROFILES_JSON = stringPreferencesKey("app_profiles_json")
        val KEY_CUSTOM_PRESETS_JSON = stringPreferencesKey("custom_presets_json")
    }

    val globalEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_GLOBAL_ENABLED] ?: true
    }

    val autoStartBootFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_START_BOOT] ?: true
    }

    val globalGainFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_GLOBAL_GAIN] ?: 0.0f
    }

    val limiterEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LIMITER_ENABLED] ?: true
    }

    val limiterThresholdFlow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_LIMITER_THRESHOLD] ?: -0.5f
    }

    val autoGainEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTOGAIN_ENABLED] ?: true
    }

    suspend fun saveGlobalEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_GLOBAL_ENABLED] = enabled }
    }

    suspend fun saveAutoStartBoot(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_START_BOOT] = enabled }
    }

    suspend fun saveGlobalGain(gainDb: Float) {
        context.dataStore.edit { it[KEY_GLOBAL_GAIN] = gainDb }
    }

    suspend fun saveLimiter(enabled: Boolean, thresholdDb: Float) {
        context.dataStore.edit {
            it[KEY_LIMITER_ENABLED] = enabled
            it[KEY_LIMITER_THRESHOLD] = thresholdDb
        }
    }

    suspend fun saveAutoGain(enabled: Boolean, targetLufs: Float = -14f) {
        context.dataStore.edit {
            it[KEY_AUTOGAIN_ENABLED] = enabled
            it[KEY_AUTOGAIN_TARGET] = targetLufs
        }
    }

    suspend fun saveBassAndVirtualizer(bassDb: Float, bassFreq: Float, virtualizer: Int) {
        context.dataStore.edit {
            it[KEY_BASS_BOOST_DB] = bassDb
            it[KEY_BASS_FREQ_HZ] = bassFreq
            it[KEY_VIRTUALIZER_STRENGTH] = virtualizer
        }
    }

    suspend fun saveBands(gains: FloatArray, qs: FloatArray) {
        val gainsList = gains.toList()
        val qsList = qs.toList()
        context.dataStore.edit {
            it[KEY_BAND_GAINS_JSON] = gson.toJson(gainsList)
            it[KEY_BAND_QS_JSON] = gson.toJson(qsList)
        }
    }

    suspend fun loadBands(): Pair<FloatArray, FloatArray> {
        val prefs = context.dataStore.data.first()
        val gainsJson = prefs[KEY_BAND_GAINS_JSON]
        val qsJson = prefs[KEY_BAND_QS_JSON]

        val gains = if (!gainsJson.isNullOrEmpty()) {
            val list: List<Float> = gson.fromJson(gainsJson, object : TypeToken<List<Float>>() {}.type)
            list.toFloatArray()
        } else {
            FloatArray(32) { 0f }
        }

        val qs = if (!qsJson.isNullOrEmpty()) {
            val list: List<Float> = gson.fromJson(qsJson, object : TypeToken<List<Float>>() {}.type)
            list.toFloatArray()
        } else {
            FloatArray(32) { 1.414f }
        }

        return Pair(gains, qs)
    }

    suspend fun saveProfiles(profiles: List<AppProfile>) {
        val json = gson.toJson(profiles)
        context.dataStore.edit { it[KEY_PROFILES_JSON] = json }
    }

    suspend fun loadProfiles(): List<AppProfile> {
        val prefs = context.dataStore.data.first()
        val json = prefs[KEY_PROFILES_JSON]
        return if (!json.isNullOrEmpty()) {
            try {
                gson.fromJson(json, object : TypeToken<List<AppProfile>>() {}.type)
            } catch (e: Exception) {
                AppProfile.createDefaultProfiles()
            }
        } else {
            AppProfile.createDefaultProfiles()
        }
    }

    suspend fun saveCurrentProfileId(profileId: String) {
        context.dataStore.edit { it[KEY_CURRENT_PROFILE_ID] = profileId }
    }

    suspend fun loadCurrentProfileId(): String {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_CURRENT_PROFILE_ID] ?: "prof_global"
    }

    suspend fun loadAutoStartBoot(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_AUTO_START_BOOT] ?: true
    }

    suspend fun loadGlobalEnabled(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_GLOBAL_ENABLED] ?: true
    }
}
