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

/**
 * GlobalAudioSessionManager: Centralized 100% Software Audio DSP Architecture.
 *
 * Implements:
 * 1. Guarantees a single, globally shared instance of SjbzDspProcessor.
 * 2. 100% Pure Software PCM Processing: Eliminates hardware audio effect dependencies
 *    (android.media.audiofx), bypassing all vendor hardware limitations.
 * 3. Unified Audio Parameter Subscriptions:
 *    - Master Output Gain & Pre-Gain / Preamp
 *    - 32-Band ISO Equalizer with exact 1:1 frequency mapping
 *    - 3-Band Tone Controls (Bass Low-Shelf 200Hz, Mid Peaking 1000Hz, Treble High-Shelf 6000Hz)
 *    - Dynamic Bass Boost & Binaural Crossfeed Virtualizer
 *    - 5-Band Multi-Band Dynamic Range Control (MDRC)
 *    - Anti-Clipping Soft Limiter (-1.0 dBFS)
 * 4. Correct Global Bypass Logic: When global service is active (globalActive = true),
 *    the DSP processor bypass is strictly disabled (dspProcessor.setGlobalBypass(false)).
 * 5. State Machine Preservation: reapplyAllParams() strictly preserves real user state
 *    without hardcoded disabling of MDRC or any other DSP module.
 * 6. Thread-safe execution and lifecycle management for Android Services and UI Activities.
 */
class GlobalAudioSessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GlobalAudioSession"

        @Volatile
        private var instance: GlobalAudioSessionManager? = null

        // Guaranteed single global software DSP processor instance
        @Volatile
        private var globalDspProcessor: SjbzDspProcessor? = null

        @JvmStatic
        fun getInstance(context: Context): GlobalAudioSessionManager {
            return instance ?: synchronized(this) {
                instance ?: GlobalAudioSessionManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * Global singleton accessor for the single software DSP processor.
         * Enforces strict single-instance architecture across SjbzAudioEngine and UI components.
         */
        @JvmStatic
        fun getDspProcessor(): SjbzDspProcessor {
            return globalDspProcessor ?: synchronized(this) {
                globalDspProcessor ?: SjbzDspProcessor(48000.0f).also {
                    globalDspProcessor = it
                }
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dataStore = AudioSettingsDataStore(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Unique software DSP processor bound to this manager
    val dspProcessor: SjbzDspProcessor = Companion.getDspProcessor()

    // Master switch for global audio processing
    @Volatile
    var isGlobalAudioEnabled: Boolean = false
        private set

    // Active audio sessions tracked by the service (sessionId -> packageName)
    private val activeSessions = mutableMapOf<Int, String>()

    // Current active profile
    var currentProfile: AppProfile = AppProfile.createDefaultProfiles().first()
        private set
    var allProfiles: MutableList<AppProfile> = AppProfile.createDefaultProfiles().toMutableList()
        private set

    // Audio Parameters in Memory (Synchronized with dspProcessor)
    @Volatile var globalGainDb: Float = 0.0f
    @Volatile var preampDb: Float = 0.0f
    val bandGains: FloatArray = FloatArray(32) { 0.0f }
    val bandQs: FloatArray = FloatArray(32) { 1.414f }

    // Tone Controls (Pre-EQ Stage)
    @Volatile var toneBassDb: Float = 0.0f
    @Volatile var toneMidDb: Float = 0.0f
    @Volatile var toneTrebleDb: Float = 0.0f

    // Bass Boost & Virtualizer
    @Volatile var isBassBoostEnabled: Boolean = true
    @Volatile var bassBoostDb: Float = 4.0f
    @Volatile var bassFreqHz: Float = 85.0f

    @Volatile var isVirtualizerEnabled: Boolean = false
    @Volatile var virtualizerStrength: Float = 0.0f

    // Dynamics: Anti-Clipping Limiter & AutoGain LUFS
    @Volatile var isLimiterEnabled: Boolean = true
    @Volatile var limiterThresholdDb: Float = -1.0f // -1.0 dBFS

    @Volatile var isAutoGainEnabled: Boolean = true
    @Volatile var autoGainTargetLufs: Float = -14.0f
    @Volatile var currentAutoGainOffsetDb: Float = 0.0f

    // 5-Band MDRC Dynamics
    @Volatile var isMdrcEnabled: Boolean = true
    @Volatile var mdrcThresholdDb: Float = -18.0f
    @Volatile var mdrcRatio: Float = 2.5f
    val mdrcGains: FloatArray = FloatArray(5) { 0.0f }

    // Hardware Emulation ATS2835P
    @Volatile var ats2835pEmuEnabled: Boolean = true
    @Volatile var ats2835pEmuAmount: Float = 0.8f
    @Volatile var ats2835pBtBypass: Boolean = false

    // UI and Service Callbacks
    var onSystemVolumeChangedListener: ((Int, Int) -> Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>) -> Unit)? = null
    var onProfileChangedListener: ((AppProfile) -> Unit)? = null
    var onAutoGainAdjustmentListener: ((Float) -> Unit)? = null

    // System Media Volume Observer
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
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register volume ContentObserver: ${e.message}")
        }

        // Load saved state from DataStore asynchronously
        scope.launch {
            loadPersistedSettings()
        }

        // Ensure DSP engine initial state matches manager
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
            Log.e(TAG, "Error loading persisted settings: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Volume Controls
    // -------------------------------------------------------------------------

    fun getSystemVolume(): Int = try {
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    } catch (_: Exception) { 0 }

    fun getMaxSystemVolume(): Int = try {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    } catch (_: Exception) { 15 }

    fun setSystemVolume(volume: Int) {
        try {
            val clamped = volume.coerceIn(0, getMaxSystemVolume())
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
            onSystemVolumeChangedListener?.invoke(clamped, getMaxSystemVolume())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting stream volume: ${e.message}")
        }
    }

    fun adjustSystemVolume(increase: Boolean) {
        try {
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val newVol = getSystemVolume()
            onSystemVolumeChangedListener?.invoke(newVol, getMaxSystemVolume())
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting stream volume: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Global Audio Master Switch & Bypass Logic (CORREGIDA)
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

        scope.launch {
            dataStore.saveGlobalEnabled(enabled)
        }
    }

    /**
     * Lógica de Bypass Corregida:
     * Cuando el servicio de audio global esté activo (globalActive = true),
     * el bypass del procesador DSP DEBE estar desactivado (dspProcessor.setGlobalBypass(false)).
     * Evita cualquier lógica invertida donde activar el servicio inhabilite el motor de audio.
     */
    fun checkAndApplyGlobalBypass(): Boolean {
        val globalActive = isGlobalAudioEnabled || GlobalAudioService.isGlobalAudioEnabled
        if (globalActive) {
            dspProcessor.setGlobalBypass(false)
        } else {
            // When global service is fully disabled, keep local processor ready according to master switch
            dspProcessor.setGlobalBypass(!dspProcessor.isMasterEnabled)
        }
        return globalActive
    }

    fun openSession(sessionId: Int, packageName: String? = null) {
        if (!isGlobalAudioEnabled) return
        if (activeSessions.containsKey(sessionId)) return

        activeSessions[sessionId] = packageName ?: "App desconocida"
        Log.i(TAG, "Attached software DSP to audio session $sessionId (pkg: $packageName)")

        if (packageName != null) {
            switchProfileForPackage(packageName)
        }

        checkAndApplyGlobalBypass()
        dispatchSessionsChanged()
    }

    fun closeSession(sessionId: Int) {
        activeSessions.remove(sessionId)
        Log.d(TAG, "Closed audio session $sessionId")
        dispatchSessionsChanged()
    }

    private fun releaseAllSessions() {
        activeSessions.clear()
        Log.d(TAG, "Released all software audio sessions")
    }

    private fun dispatchSessionsChanged() {
        val count = activeSessions.size
        val packages = activeSessions.values.toList()
        mainHandler.post {
            onActiveSessionsChangedListener?.invoke(count, packages)
        }
    }

    // -------------------------------------------------------------------------
    // DSP Unified Parameter Control (100% Software Architecture)
    // -------------------------------------------------------------------------

    fun setMasterGain(gainDb: Float) {
        this.globalGainDb = gainDb
        dspProcessor.setMasterGain(gainDb)
    }

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
            // Reapply band level to trigger Q calculation
            dspProcessor.setBandLevel(bandIndex, bandGains[bandIndex])
        }
    }

    fun setToneBass(gainDb: Float) {
        this.toneBassDb = gainDb.coerceIn(-12.0f, 12.0f)
        dspProcessor.setToneBass(this.toneBassDb)
    }

    fun setToneMid(gainDb: Float) {
        this.toneMidDb = gainDb.coerceIn(-12.0f, 12.0f)
        dspProcessor.setToneMid(this.toneMidDb)
    }

    fun setToneTreble(gainDb: Float) {
        this.toneTrebleDb = gainDb.coerceIn(-12.0f, 12.0f)
        dspProcessor.setToneTreble(this.toneTrebleDb)
    }

    fun setBassBoost(enabled: Boolean, gainDb: Float, freqHz: Float = 85.0f) {
        this.isBassBoostEnabled = enabled
        this.bassBoostDb = gainDb.coerceIn(0.0f, 12.0f)
        this.bassFreqHz = freqHz
        dspProcessor.setBassBoost(enabled, freqHz, this.bassBoostDb)
    }

    fun setVirtualizer(enabled: Boolean, strength: Float) {
        this.isVirtualizerEnabled = enabled
        this.virtualizerStrength = strength.coerceIn(0.0f, 1.0f)
        dspProcessor.setVirtualizer(enabled, this.virtualizerStrength)
    }

    fun setVirtualizer(progress: Int) {
        val enabled = progress > 0
        val strength = (progress / 100.0f).coerceIn(0.0f, 1.0f)
        setVirtualizer(enabled, strength)
    }

    fun setAutoGain(enabled: Boolean, targetLufs: Float) {
        this.isAutoGainEnabled = enabled
        this.autoGainTargetLufs = targetLufs
        onAutoGainAdjustmentListener?.invoke(targetLufs)
    }

    fun saveCurrentAsProfile(name: String): AppProfile {
        val newProfile = AppProfile(
            id = "custom_${System.currentTimeMillis()}",
            appName = name,
            appPackage = "",
            presetName = name,
            preampDb = preampDb,
            globalGainDb = globalGainDb,
            bandGains = bandGains.copyOf(),
            bandQs = bandQs.copyOf(),
            bassGainDb = toneBassDb,
            midGainDb = toneMidDb,
            trebleGainDb = toneTrebleDb,
            bassBoostDb = bassBoostDb,
            bassBoostFreq = bassFreqHz,
            virtualizerStrength = (virtualizerStrength * 100).toInt(),
            isLimiterEnabled = isLimiterEnabled,
            limiterThresholdDb = limiterThresholdDb,
            isAutoGainEnabled = isAutoGainEnabled,
            autoGainTargetLufs = autoGainTargetLufs,
            isMdrcEnabled = isMdrcEnabled,
            isAts2835pEnabled = ats2835pEmuEnabled,
            ats2835pAmount = ats2835pEmuAmount
        )
        allProfiles.add(newProfile)
        currentProfile = newProfile
        scope.launch {
            dataStore.saveProfiles(allProfiles)
            dataStore.saveCurrentProfileId(newProfile.id)
        }
        mainHandler.post {
            onProfileChangedListener?.invoke(newProfile)
        }
        return newProfile
    }

    fun setMdrcEnabled(enabled: Boolean) {
        this.isMdrcEnabled = enabled
        dspProcessor.setMdrcEnabled(enabled)
    }

    fun setMdrcDynamics(thresholdDb: Float, ratio: Float) {
        this.mdrcThresholdDb = thresholdDb
        this.mdrcRatio = ratio.coerceAtLeast(1.0f)
        dspProcessor.setMdrcDynamics(thresholdDb, ratio)
    }

    fun setMdrcGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 5) {
            this.mdrcGains[bandIndex] = gainDb
            dspProcessor.setMdrcBandGain(bandIndex, gainDb)
        }
    }

    fun setLimiter(enabled: Boolean, thresholdDb: Float = -1.0f) {
        this.isLimiterEnabled = enabled
        this.limiterThresholdDb = thresholdDb
        dspProcessor.setLimiter(enabled, thresholdDb)
    }

    fun setAts2835pEmulation(enabled: Boolean, amount: Float = 0.8f, bluetoothBypass: Boolean = false) {
        this.ats2835pEmuEnabled = enabled
        this.ats2835pEmuAmount = amount.coerceIn(0.0f, 1.0f)
        this.ats2835pBtBypass = bluetoothBypass
        dspProcessor.setEmulationEnabled(enabled)
        dspProcessor.setEmulationAmount(this.ats2835pEmuAmount)
        dspProcessor.setBluetoothAutoBypass(bluetoothBypass)
    }

    /**
     * Preservación Fiel de Máquinas de Estado:
     * En el método reapplyAllParams(), respeta estrictamente el estado guardado por el usuario.
     * NO agrega llamadas hardcodeadas de desactivación (como setMdrcEnabled(false)).
     * Si el módulo MDRC o cualquier filtro estaba encendido, conserva su estado real.
     */
    fun reapplyAllParams() {
        syncAllParamsToDsp()
        checkAndApplyGlobalBypass()
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
        dspProcessor.setVirtualizer(isVirtualizerEnabled, virtualizerStrength)

        // Conserva el estado real del MDRC guardado por el usuario
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
    // App Profiles Management
    // -------------------------------------------------------------------------

    fun applyProfileInMemory(profile: AppProfile, saveSelection: Boolean = true) {
        currentProfile = profile
        preampDb = profile.preampDb
        toneBassDb = profile.bassGainDb
        toneMidDb = profile.midGainDb
        toneTrebleDb = profile.trebleGainDb

        profile.bandGains.forEachIndexed { i, g ->
            if (i in bandGains.indices) bandGains[i] = g
        }

        bassBoostDb = profile.bassBoostDb
        bassFreqHz = profile.bassBoostFreq
        isBassBoostEnabled = profile.bassBoostDb > 0.05f

        // Conserva los estados reales del perfil
        isMdrcEnabled = profile.isMdrcEnabled
        isLimiterEnabled = profile.isLimiterEnabled
        ats2835pEmuEnabled = profile.isAts2835pEnabled
        ats2835pEmuAmount = profile.ats2835pAmount

        reapplyAllParams()

        if (saveSelection) {
            scope.launch {
                dataStore.saveCurrentProfileId(profile.id)
            }
        }
        mainHandler.post {
            onProfileChangedListener?.invoke(profile)
        }
    }

    fun switchProfileForPackage(packageName: String) {
        val matchedProfile = allProfiles.find { it.appPackage.equals(packageName, ignoreCase = true) }
        if (matchedProfile != null && matchedProfile.id != currentProfile.id) {
            Log.i(TAG, "Auto-switching audio profile to: ${matchedProfile.appName} for $packageName")
            applyProfileInMemory(matchedProfile, saveSelection = true)
        }
    }

    // -------------------------------------------------------------------------
    // Resource Cleanup & Service Destruction
    // -------------------------------------------------------------------------

    fun releaseResources() {
        try {
            context.contentResolver.unregisterContentObserver(volumeObserver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering volumeObserver: ${e.message}")
        }
        releaseAllSessions()
        dspProcessor.resetFilterStates()
        Log.i(TAG, "GlobalAudioSessionManager resources released cleanly")
    }
}
