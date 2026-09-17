package com.sjbz.aimp.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.sjbz.aimp.data.AudioSettingsDataStore
import com.sjbz.aimp.model.AppProfile
import com.sjbz.aimp.service.GlobalAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * GlobalAudioSessionManager: Arquitectura centralizada de DSP de audio 100% por Software.
 * Compatible con la interfaz y controles requeridos por MainActivity.
 */
class GlobalAudioSessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GlobalAudioSession"

        @Volatile
        private var instance: GlobalAudioSessionManager? = null

        @Volatile
        private var globalDspProcessor: SjbzDspProcessor? = null

        @JvmStatic
        fun getInstance(context: Context): GlobalAudioSessionManager {
            return instance ?: synchronized(this) {
                instance ?: GlobalAudioSessionManager(context.applicationContext).also { instance = it }
            }
        }

        @JvmStatic
        fun getDspProcessor(): SjbzDspProcessor {
            return globalDspProcessor ?: synchronized(this) {
                globalDspProcessor ?: SjbzDspProcessor(48000.0f).also { globalDspProcessor = it }
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dataStore = AudioSettingsDataStore(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    val dspProcessor: SjbzDspProcessor = Companion.getDspProcessor()

    @Volatile
    var isGlobalAudioEnabled: Boolean = false
        private set

    private val activeSessions = mutableMapOf<Int, String>()

    var currentProfile: AppProfile = AppProfile.createDefaultProfiles().first()
        private set
    var allProfiles: MutableList<AppProfile> = AppProfile.createDefaultProfiles().toMutableList()
        private set

    // Parámetros de Audio
    @Volatile var globalGainDb: Float = 0.0f
    @Volatile var preampDb: Float = 0.0f
    val bandGains: FloatArray = FloatArray(32) { 0.0f }
    val bandQs: FloatArray = FloatArray(32) { 1.414f }

    // Controles de Tono
    @Volatile var toneBassDb: Float = 0.0f
    @Volatile var toneMidDb: Float = 0.0f
    @Volatile var toneTrebleDb: Float = 0.0f

    // Bass Boost & Virtualizer
    @Volatile var isBassBoostEnabled: Boolean = true
    @Volatile var bassBoostDb: Float = 4.0f
    @Volatile var bassFreqHz: Float = 85.0f
    @Volatile var isVirtualizerEnabled: Boolean = false
    @Volatile var virtualizerStrength: Int = 0

    // Dinámica: Limiter & AutoGain
    @Volatile var isLimiterEnabled: Boolean = true
    @Volatile var limiterThresholdDb: Float = -1.0f
    @Volatile var isAutoGainEnabled: Boolean = true
    @Volatile var autoGainTargetLufs: Float = -14.0f
    @Volatile var currentAutoGainOffsetDb: Float = 0.0f

    // MDRC
    @Volatile var isMdrcEnabled: Boolean = true
    @Volatile var mdrcThresholdDb: Float = -18.0f
    @Volatile var mdrcRatio: Float = 2.5f
    val mdrcGains: FloatArray = FloatArray(5) { 0.0f }

    // Emulación ATS2835P
    @Volatile var ats2835pEmuEnabled: Boolean = true
    @Volatile var ats2835pEmuAmount: Float = 0.8f
    @Volatile var ats2835pBtBypass: Boolean = false

    // Callbacks de UI y Servicio
    var onSystemVolumeChangedListener: ((Int, Int) -> Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>) -> Unit)? = null
    var onProfileChangedListener: ((AppProfile) -> Unit)? = null
    var onAutoGainAdjustmentListener: ((Float) -> Unit)? = null

    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val currentVol = getSystemVolume()
            val maxVol = getMaxSystemVolume()
            onSystemVolumeChangedListener?.invoke(currentVol, maxVol)
        }
    }

    init {
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver
            )
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar ContentObserver de volumen: ${e.message}")
        }

        scope.launch { loadPersistedSettings() }
        syncAllParamsToDsp()
    }

    private suspend fun loadPersistedSettings() {
        try {
            val loadedProfiles = dataStore.loadProfiles()
            if (loadedProfiles.isNotEmpty()) {
                allProfiles.clear()
                allProfiles.addAll(loadedProfiles)
            }
            val (savedGains, savedQs) = dataStore.loadBands()
            System.arraycopy(savedGains, 0, bandGains, 0, minOf(savedGains.size, bandGains.size))
            System.arraycopy(savedQs, 0, bandQs, 0, minOf(savedQs.size, bandQs.size))
            
            val currentId = dataStore.loadCurrentProfileId()
            val found = allProfiles.find { it.id == currentId } ?: allProfiles.firstOrNull()
            if (found != null) {
                applyProfileInMemory(found, saveSelection = false)
            }
            syncAllParamsToDsp()
        } catch (e: Exception) {
            Log.e(TAG, "Error al cargar configuración guardada: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Controles de Volumen
    // -------------------------------------------------------------------------
    fun getSystemVolume(): Int = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 0 }
    fun getMaxSystemVolume(): Int = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
    
    fun setSystemVolume(volume: Int) {
        try {
            val clamped = volume.coerceIn(0, getMaxSystemVolume())
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
            onSystemVolumeChangedListener?.invoke(clamped, getMaxSystemVolume())
        } catch (e: Exception) {
            Log.e(TAG, "Error ajustando volumen del sistema: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Interruptor Master Global
    // -------------------------------------------------------------------------
    fun setGlobalAudioEnabled(enabled: Boolean) {
        if (isGlobalAudioEnabled == enabled) return
        isGlobalAudioEnabled = enabled
        if (enabled) {
            openSession(0, "Sistema Global")
        } else {
            releaseAllSessions()
        }
        checkAndApplyGlobalBypass()
        dispatchSessionsChanged()
        scope.launch { dataStore.saveGlobalEnabled(enabled) }
    }

    fun checkAndApplyGlobalBypass(): Boolean {
        val globalActive = isGlobalAudioEnabled || GlobalAudioService.isGlobalAudioEnabled
        if (globalActive) {
            dspProcessor.setGlobalBypass(false)
        } else {
            dspProcessor.setGlobalBypass(!dspProcessor.isMasterEnabled)
        }
        return globalActive
    }

    fun openSession(sessionId: Int, packageName: String? = null) {
        if (!isGlobalAudioEnabled) return
        if (activeSessions.containsKey(sessionId)) return
        activeSessions[sessionId] = packageName ?: "App desconocida"
        if (packageName != null) {
            switchProfileForPackage(packageName)
        }
        checkAndApplyGlobalBypass()
        dispatchSessionsChanged()
    }

    fun closeSession(sessionId: Int) {
        activeSessions.remove(sessionId)
        dispatchSessionsChanged()
    }

    private fun releaseAllSessions() {
        activeSessions.clear()
    }

    private fun dispatchSessionsChanged() {
        val count = activeSessions.size
        val packages = activeSessions.values.toList()
        mainHandler.post { onActiveSessionsChangedListener?.invoke(count, packages) }
    }

    // -------------------------------------------------------------------------
    // Control de Parámetros DSP (Métodos compatibles con MainActivity)
    // -------------------------------------------------------------------------
    fun setMasterGain(gainDb: Float) {
        this.globalGainDb = gainDb
        dspProcessor.setMasterGain(gainDb)
    }

    // Alias directo para compatibilidad con MainActivity.kt
    fun setGlobalGain(gainDb: Float) {
        setMasterGain(gainDb)
    }

    fun setPreampGain(gainDb: Float) {
        this.preampDb = gainDb.coerceIn(-12.0f, 12.0f)
        dspProcessor.setPreamp(this.preampDb)
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 32) {
            val clamped = gainDb.coerceIn(-12.0f, 12.0f)
            bandGains[bandIndex] = clamped
            dspProcessor.setBandLevel(bandIndex, clamped)
        }
    }

    fun setBandQ(bandIndex: Int, qValue: Float) {
        if (bandIndex in 0 until 32) {
            bandQs[bandIndex] = qValue.coerceIn(0.5f, 5.0f)
            dspProcessor.setBandLevel(bandIndex, bandGains[bandIndex])
        }
    }

    // Sobrecargas de BassBoost para llamadas de 1, 2 o 3 argumentos
    fun setBassBoost(gainDb: Float, freqHz: Float) {
        setBassBoost(gainDb > 0.05f, gainDb, freqHz)
    }

    fun setBassBoost(gainDb: Float) {
        setBassBoost(gainDb > 0.05f, gainDb, this.bassFreqHz)
    }

    fun setBassBoost(enabled: Boolean, gainDb: Float, freqHz: Float = 85.0f) {
        this.isBassBoostEnabled = enabled
        this.bassBoostDb = gainDb.coerceIn(0.0f, 12.0f)
        this.bassFreqHz = freqHz
        dspProcessor.setBassBoost(enabled, freqHz, this.bassBoostDb)
    }

    // Sobrecargas de Virtualizer (Entrada Int 0..100 desde SeekBar)
    fun setVirtualizer(strengthProgress: Int) {
        val floatStrength = (strengthProgress / 100.0f).coerceIn(0.0f, 1.0f)
        this.virtualizerStrength = strengthProgress
        this.isVirtualizerEnabled = strengthProgress > 0
        dspProcessor.setVirtualizer(this.isVirtualizerEnabled, floatStrength)
    }

    fun setVirtualizer(enabled: Boolean, strength: Float) {
        this.isVirtualizerEnabled = enabled
        this.virtualizerStrength = (strength * 100).toInt()
        dspProcessor.setVirtualizer(enabled, strength.coerceIn(0.0f, 1.0f))
    }

    fun setAutoGain(enabled: Boolean, targetLufs: Float) {
        this.isAutoGainEnabled = enabled
        this.autoGainTargetLufs = targetLufs.coerceIn(-23.0f, -9.0f)
    }

    fun setLimiter(enabled: Boolean, thresholdDb: Float = -1.0f) {
        this.isLimiterEnabled = enabled
        this.limiterThresholdDb = thresholdDb
        dspProcessor.setLimiter(enabled, thresholdDb)
    }

    private fun syncAllParamsToDsp() {
        dspProcessor.setMasterGain(globalGainDb)
        dspProcessor.setPreamp(preampDb)
        for (i in 0 until 32) {
            dspProcessor.setBandLevel(i, bandGains[i])
        }
        dspProcessor.setToneBass(toneBassDb)
        dspProcessor.setToneMid(toneMidDb)
        dspProcessor.setToneTreble(toneTrebleDb)
        dspProcessor.setBassBoost(isBassBoostEnabled, bassFreqHz, bassBoostDb)
        dspProcessor.setVirtualizer(isVirtualizerEnabled, virtualizerStrength / 100.0f)
        dspProcessor.setMdrcEnabled(isMdrcEnabled)
        dspProcessor.setMdrcDynamics(mdrcThresholdDb, mdrcRatio)
        for (b in 0 until 5) {
            dspProcessor.setMdrcBandGain(b, mdrcGains[b])
        }
        dspProcessor.setLimiter(isLimiterEnabled, limiterThresholdDb)
        dspProcessor.setEmulationEnabled(ats2835pEmuEnabled)
        dspProcessor.setEmulationAmount(ats2835pEmuAmount)
        dspProcessor.setBluetoothAutoBypass(ats2835pBtBypass)
    }

    // -------------------------------------------------------------------------
    // Gestión de Perfiles
    // -------------------------------------------------------------------------
    fun applyProfile(profile: AppProfile) {
        applyProfileInMemory(profile, saveSelection = true)
    }

    fun applyProfileInMemory(profile: AppProfile, saveSelection: Boolean = true) {
        currentProfile = profile
        preampDb = profile.preampDb
        toneBassDb = profile.bassGainDb
        toneMidDb = profile.midGainDb
        toneTrebleDb = profile.trebleGainDb
        profile.bandGains.forEachIndexed { i, g -> if (i in bandGains.indices) bandGains[i] = g }
        bassBoostDb = profile.bassBoostDb
        bassFreqHz = profile.bassBoostFreq
        isBassBoostEnabled = profile.bassBoostDb > 0.05f
        isMdrcEnabled = profile.isMdrcEnabled
        isLimiterEnabled = profile.isLimiterEnabled
        ats2835pEmuEnabled = profile.isAts2835pEnabled
        ats2835pEmuAmount = profile.ats2835pAmount
        
        syncAllParamsToDsp()
        
        if (saveSelection) {
            scope.launch { dataStore.saveCurrentProfileId(profile.id) }
        }
        mainHandler.post { onProfileChangedListener?.invoke(profile) }
    }

    fun saveCurrentAsProfile(name: String): AppProfile {
        val newProfile = AppProfile(
            id = UUID.randomUUID().toString(),
            appName = name,
            presetName = "Personalizado",
            appPackage = "",
            preampDb = this.preampDb,
            bassGainDb = this.toneBassDb,
            midGainDb = this.toneMidDb,
            trebleGainDb = this.toneTrebleDb,
            bandGains = this.bandGains.copyOf(),
            bassBoostDb = this.bassBoostDb,
            bassBoostFreq = this.bassFreqHz,
            isMdrcEnabled = this.isMdrcEnabled,
            isLimiterEnabled = this.isLimiterEnabled,
            isAts2835pEnabled = this.ats2835pEmuEnabled,
            ats2835pAmount = this.ats2835pEmuAmount
        )
        allProfiles.add(newProfile)
        currentProfile = newProfile
        scope.launch { dataStore.saveProfiles(allProfiles) }
        return newProfile
    }

    fun switchProfileForPackage(packageName: String) {
        val matchedProfile = allProfiles.find { it.appPackage.equals(packageName, ignoreCase = true) }
        if (matchedProfile != null && matchedProfile.id != currentProfile.id) {
            applyProfileInMemory(matchedProfile, saveSelection = true)
        }
    }

    fun releaseResources() {
        try {
            context.contentResolver.unregisterContentObserver(volumeObserver)
        } catch (e: Exception) {
            Log.w(TAG, "Error desregistrando volumeObserver: ${e.message}")
        }
        releaseAllSessions()
        dspProcessor.resetFilterStates()
    }
}
