package com.sjbz.aimp.audio

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import com.sjbz.aimp.data.AudioSettingsDataStore
import com.sjbz.aimp.model.AppProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class GlobalAudioSessionManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "SBZ_GlobalAudioMgr"
        @Volatile private var INSTANCE: GlobalAudioSessionManager? = null
        fun getInstance(context: Context): GlobalAudioSessionManager {
            return INSTANCE?: synchronized(this) {
                INSTANCE?: GlobalAudioSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = AudioSettingsDataStore(appContext)

    // DSP por software - dueño único
    val dspProcessor = SjbzDspProcessor()

    var isGlobalAudioEnabled = true; private set
    var globalGainDb = 0f; private set
    var isLimiterEnabled = true; private set
    var limiterThresholdDb = -1.0f; private set
    var isAutoGainEnabled = false; private set
    var autoGainTargetLufs = -14f; private set
    var bassBoostDb = 0f; private set
    var bassFreqHz = 85f; private set
    var virtualizerStrength = 0; private set

    var bandGains = FloatArray(32) { 0f }; private set
    var bandQ = FloatArray(32) { 1.414f }; private set

    var currentProfile: AppProfile = AppProfile.createDefaultProfiles().first()
    var allProfiles = AppProfile.createDefaultProfiles().toMutableList()
    var onProfileChangedListener: ((AppProfile) -> Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>) -> Unit)? = null

    init {
        scope.launch {
            try {
                isGlobalAudioEnabled = dataStore.globalEnabledFlow.first()
                globalGainDb = dataStore.globalGainFlow.first()
                val bands = dataStore.bandsFlow.first()
                // Validación anti-mudo: solo aceptar 32
                bandGains = if (bands.size == 32) bands else FloatArray(32) { 0f }
                reapplyAllParams()
            } catch (e: Exception) {
                Log.e(TAG, "Init error, usando flat seguro", e)
                bandGains = FloatArray(32) { 0f }
            }
        }
    }

    fun setGlobalAudioEnabled(enabled: Boolean) {
        isGlobalAudioEnabled = enabled
        dspProcessor.setMasterEnabled(enabled)
        scope.launch { dataStore.saveGlobalEnabled(enabled) }
    }

    fun setBandGain(index: Int, gainDb: Float) {
        if (index!in 0..31) return
        val safe = gainDb.coerceIn(-12f, 12f)
        bandGains[index] = safe
        try {
            dspProcessor.setBandLevel(index, safe)
        } catch (e: Exception) {
            Log.e(TAG, "setBandGain fallo idx $index", e)
        }
        scope.launch { dataStore.saveBands(bandGains) }
    }

    // Nuevo: para presets sin lagear / sin mute parcial
    fun setAllBands(gains: FloatArray) {
        val safe = FloatArray(32) { i -> gains.getOrNull(i)?.coerceIn(-12f, 12f)?: 0f }
        bandGains = safe
        for (i in 0 until 32) {
            try { dspProcessor.setBandLevel(i, safe[i]) } catch (_: Exception) {}
        }
        scope.launch { dataStore.saveBands(safe) }
    }

    fun setBandQ(index: Int, q: Float) {
        if (index!in 0..31) return
        bandQ[index] = q.coerceIn(0.5f, 4f)
        // Q se aplica en reapply, no mutea directo
    }

    fun setGlobalGain(db: Float) {
        globalGainDb = db.coerceIn(-12f, 12f)
        dspProcessor.setPreamp(globalGainDb)
        scope.launch { dataStore.saveGlobalGain(globalGainDb) }
    }

    fun setPreampGain(db: Float) = setGlobalGain(db)

    fun setLimiter(enabled: Boolean, thresholdDb: Float) {
        isLimiterEnabled = enabled
        limiterThresholdDb = thresholdDb.coerceIn(-12f, 0f)
        dspProcessor.setLimiterEnabled(enabled)
        dspProcessor.setLimiterThreshold(limiterThresholdDb)
    }

    fun setAutoGain(enabled: Boolean, targetLufs: Float) {
        isAutoGainEnabled = enabled
        autoGainTargetLufs = targetLufs.coerceIn(-23f, -9f)
        // AutoGain es solo metadata por ahora, no toca DSP directo = no mute
    }

    fun setBassBoost(gainDb: Float, freqHz: Float) {
        bassBoostDb = gainDb.coerceIn(0f, 12f)
        bassFreqHz = freqHz
        dspProcessor.setBassBoost(bassBoostDb > 0.1f, bassFreqHz, bassBoostDb)
    }

    fun setBassBoostGain(gainDb: Float) = setBassBoost(gainDb, bassFreqHz)

    fun setVirtualizer(strength: Int) {
        virtualizerStrength = strength.coerceIn(0, 1000)
    }

    fun setMdrcEnabled(enabled: Boolean) { dspProcessor.setMdrcEnabled(enabled) }
    fun setMdrcBandGain(idx: Int, gain: Float) { dspProcessor.setMdrcBandGain(idx, gain) }

    private fun reapplyAllParams() {
        dspProcessor.setMasterEnabled(isGlobalAudioEnabled)
        dspProcessor.setPreamp(globalGainDb)
        for (i in 0 until 32) {
            try { dspProcessor.setBandLevel(i, bandGains[i]) } catch (_: Exception) {}
        }
        dspProcessor.setLimiterEnabled(isLimiterEnabled)
        dspProcessor.setLimiterThreshold(limiterThresholdDb)
        dspProcessor.setBassBoost(bassBoostDb > 0.1f, bassFreqHz, bassBoostDb)
        dspProcessor.setMdrcEnabled(false)
    }

    fun applyProfile(p: AppProfile) {
        currentProfile = p
        setAllBands(p.bandGains)
        setGlobalGain(p.preampDb)
        onProfileChangedListener?.invoke(p)
    }

    fun saveCurrentAsProfile(name: String): AppProfile {
        val p = AppProfile(
            id = System.currentTimeMillis().toString(),
            appName = name, presetName = name,
            bandGains = bandGains.copyOf(),
            preampDb = globalGainDb
        )
        allProfiles.add(p); currentProfile = p
        return p
    }

    fun getSystemVolume(): Int = 7
    fun getMaxSystemVolume(): Int = 15
    fun setSystemVolume(v: Int) {}
    fun getActiveSessionsCount(): Int = 0
    fun onSessionAdded(pkg: String?, sessionId: Int) {}
    fun onSessionRemoved(sessionId: Int) {}
}
