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

class GlobalAudioSessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GlobalAudioSession"
        @Volatile private var instance: GlobalAudioSessionManager? = null
        @Volatile private var globalDspProcessor: SjbzDspProcessor? = null

        @JvmStatic fun getInstance(context: Context): GlobalAudioSessionManager {
            return instance?: synchronized(this) {
                instance?: GlobalAudioSessionManager(context.applicationContext).also { instance = it }
            }
        }
        // RENOMBRADO para no chocar con la propiedad dspProcessor
        @JvmStatic fun getDspProcessorInstance(): SjbzDspProcessor {
            return globalDspProcessor?: synchronized(this) {
                globalDspProcessor?: SjbzDspProcessor(48000.0f).also { globalDspProcessor = it }
            }
        }
        // Alias compatibilidad
        @JvmStatic fun getDspProcessor(): SjbzDspProcessor = getDspProcessorInstance()
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dataStore = AudioSettingsDataStore(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // FIX: JvmName distinto para no chocar con getDspProcessor()
    @get:JvmName("getDspProcessorProperty")
    val dspProcessor: SjbzDspProcessor = getDspProcessorInstance()

    // FIX: todos los 'isX' con JvmName distinto para poder tener setX() sin clash
    @get:JvmName("isGlobalAudioEnabledProp")
    @set:JvmName("setGlobalAudioEnabledProp")
    @Volatile var isGlobalAudioEnabled: Boolean = false

    @get:JvmName("isMdrcEnabledProp")
    @set:JvmName("setMdrcEnabledProp")
    @Volatile var isMdrcEnabled: Boolean = true

    @get:JvmName("isLimiterEnabledProp")
    @set:JvmName("setLimiterEnabledProp")
    @Volatile var isLimiterEnabled: Boolean = true

    @get:JvmName("isAutoGainEnabledProp")
    @set:JvmName("setAutoGainEnabledProp")
    @Volatile var isAutoGainEnabled: Boolean = true

    private val activeSessions = mutableMapOf<Int, String>()
    var currentProfile: AppProfile = AppProfile.createDefaultProfiles().first()
    var allProfiles: MutableList<AppProfile> = AppProfile.createDefaultProfiles().toMutableList()

    @Volatile var globalGainDb: Float = 0.0f
    @Volatile var preampDb: Float = 0.0f
    val bandGains: FloatArray = FloatArray(32)
    val bandQs: FloatArray = FloatArray(32) { 1.414f }
    @Volatile var toneBassDb: Float = 0.0f
    @Volatile var toneMidDb: Float = 0.0f
    @Volatile var toneTrebleDb: Float = 0.0f
    @Volatile var isBassBoostEnabled: Boolean = true
    @Volatile var bassBoostDb: Float = 4.0f
    @Volatile var bassFreqHz: Float = 85.0f
    @Volatile var isVirtualizerEnabled: Boolean = false
    @Volatile var virtualizerStrength: Float = 0.0f
    @Volatile var limiterThresholdDb: Float = -1.0f
    @Volatile var autoGainTargetLufs: Float = -14.0f
    @Volatile var currentAutoGainOffsetDb: Float = 0.0f
    @Volatile var mdrcThresholdDb: Float = -18.0f
    @Volatile var mdrcRatio: Float = 2.5f
    val mdrcGains: FloatArray = FloatArray(5)
    @Volatile var ats2835pEmuEnabled: Boolean = true
    @Volatile var ats2835pEmuAmount: Float = 0.8f
    @Volatile var ats2835pBtBypass: Boolean = false

    var onSystemVolumeChangedListener: ((Int, Int) -> Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>) -> Unit)? = null
    var onProfileChangedListener: ((AppProfile) -> Unit)? = null
    var onAutoGainAdjustmentListener: ((Float) -> Unit)? = null

    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            val currentVol = getSystemVolume()
            val maxVol = getMaxSystemVolume()
            onSystemVolumeChangedListener?.invoke(currentVol, maxVol)
        }
    }

    init {
        try { context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver) } catch (e: Exception) {}
        scope.launch { loadPersistedSettings() }
        syncAllParamsToDsp()
    }

    private suspend fun loadPersistedSettings() {
        try {
            val loadedProfiles = dataStore.loadProfiles()
            if (loadedProfiles.isNotEmpty()) { allProfiles.clear(); allProfiles.addAll(loadedProfiles) }
            val (savedGains, savedQs) = dataStore.loadBands()
            for (i in bandGains.indices) { if (i < savedGains.size) bandGains[i] = savedGains[i] }
            for (i in bandQs.indices) { if (i < savedQs.size) bandQs[i] = savedQs[i] }
            val currentId = dataStore.loadCurrentProfileId()
            val found = allProfiles.find { it.id == currentId }?: allProfiles.firstOrNull()
            if (found!= null) applyProfileInMemory(found, saveSelection = false)
            syncAllParamsToDsp()
        } catch (e: Exception) { Log.e(TAG, "Error loading: ${e.message}") }
    }

    fun getSystemVolume(): Int = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 0 }
    fun getMaxSystemVolume(): Int = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
    fun setSystemVolume(volume: Int) {
        try {
            val clamped = volume.coerceIn(0, getMaxSystemVolume())
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
            onSystemVolumeChangedListener?.invoke(clamped, getMaxSystemVolume())
        } catch (_: Exception) {}
    }
    fun adjustSystemVolume(increase: Boolean) {
        try {
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            onSystemVolumeChangedListener?.invoke(getSystemVolume(), getMaxSystemVolume())
        } catch (_: Exception) {}
    }

    // Ahora NO choca con la propiedad porque la propiedad tiene JvmName distinto
    fun setGlobalAudioEnabled(enabled: Boolean) {
        if (isGlobalAudioEnabled == enabled) return
        isGlobalAudioEnabled = enabled
        if (enabled) openSession(0, "Sistema Global") else releaseAllSessions()
        checkAndApplyGlobalBypass()
        dispatchSessionsChanged()
        scope.launch { dataStore.saveGlobalEnabled(enabled) }
    }

    fun checkAndApplyGlobalBypass(): Boolean {
        val globalActive = isGlobalAudioEnabled || GlobalAudioService.isGlobalAudioEnabled
        if (globalActive) dspProcessor.setGlobalBypass(false) else dspProcessor.setGlobalBypass(!dspProcessor.isMasterEnabled)
        return globalActive
    }
    fun openSession(sessionId: Int, packageName: String? = null) {
        if (!isGlobalAudioEnabled) return
        if (activeSessions.containsKey(sessionId)) return
        activeSessions[sessionId] = packageName?: "App desconocida"
        if (packageName!= null) switchProfileForPackage(packageName)
        checkAndApplyGlobalBypass()
        dispatchSessionsChanged()
    }
    fun closeSession(sessionId: Int) { activeSessions.remove(sessionId); dispatchSessionsChanged() }
    private fun releaseAllSessions() { activeSessions.clear() }
    private fun dispatchSessionsChanged() {
        val count = activeSessions.size
        val packages = activeSessions.values.toList()
        mainHandler.post { onActiveSessionsChangedListener?.invoke(count, packages) }
    }
    fun setMasterGain(gainDb: Float) { this.globalGainDb = gainDb; dspProcessor.setMasterGain(gainDb) }
    fun setGlobalGain(gainDb: Float) = setMasterGain(gainDb)
    fun setPreampGain(gainDb: Float) { this.preampDb = gainDb.coerceIn(-12f, 12f); dspProcessor.setPreamp(this.preampDb) }
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 32) {
            val clamped = gainDb.coerceIn(-12f, 12f)
            bandGains[bandIndex] = clamped
            dspProcessor.setBandLevel(bandIndex, clamped)
        }
    }
    fun setBandQ(bandIndex: Int, qValue: Float) {
        if (bandIndex in 0 until 32) {
            bandQs[bandIndex] = qValue.coerceIn(0.5f, 5f)
            dspProcessor.setBandLevel(bandIndex, bandGains[bandIndex])
        }
    }
    fun setToneBass(gainDb: Float) { this.toneBassDb = gainDb.coerceIn(-12f, 12f); dspProcessor.setToneBass(this.toneBassDb) }
    fun setToneMid(gainDb: Float) { this.toneMidDb = gainDb.coerceIn(-12f, 12f); dspProcessor.setToneMid(this.toneMidDb) }
    fun setToneTreble(gainDb: Float) { this.toneTrebleDb = gainDb.coerceIn(-12f, 12f); dspProcessor.setToneTreble(this.toneTrebleDb) }
    fun setBassBoost(enabled: Boolean, gainDb: Float, freqHz: Float = 85.0f) {
        this.isBassBoostEnabled = enabled; this.bassBoostDb = gainDb.coerceIn(0f, 12f); this.bassFreqHz = freqHz
        dspProcessor.setBassBoost(enabled, freqHz, this.bassBoostDb)
    }
    fun setVirtualizer(enabled: Boolean, strength: Float) {
        this.isVirtualizerEnabled = enabled; this.virtualizerStrength = strength.coerceIn(0f, 1f)
        dspProcessor.setVirtualizer(enabled, this.virtualizerStrength)
    }
    fun setVirtualizer(progress: Int) {
        val enabled = progress > 0; val strength = (progress / 100.0f).coerceIn(0f, 1f)
        setVirtualizer(enabled, strength)
    }
    fun setAutoGain(enabled: Boolean, targetLufs: Float) { this.isAutoGainEnabled = enabled; this.autoGainTargetLufs = targetLufs }
    fun saveCurrentAsProfile(name: String): AppProfile {
        val newProfile = AppProfile(
            id = "custom_${System.currentTimeMillis()}",
            packageName = "", appName = name, presetName = name,
            globalGainDb = globalGainDb,
            bandGains = bandGains.toList(), bandQs = bandQs.toList(),
            bassBoostDb = bassBoostDb, bassFreqHz = bassFreqHz,
            virtualizerStrength = (virtualizerStrength * 100).toInt(),
            limiterEnabled = isLimiterEnabled, limiterThresholdDb = limiterThresholdDb,
            autoGainEnabled = isAutoGainEnabled, autoGainTargetLufs = autoGainTargetLufs,
            mdrcEnabled = isMdrcEnabled, mdrcGains = mdrcGains.toList(),
            ats2835pEmuEnabled = ats2835pEmuEnabled
        )
        allProfiles.add(newProfile); currentProfile = newProfile
        scope.launch { dataStore.saveProfiles(allProfiles); dataStore.saveCurrentProfileId(newProfile.id) }
        mainHandler.post { onProfileChangedListener?.invoke(newProfile) }
        return newProfile
    }
    fun setMdrcEnabled(enabled: Boolean) { this.isMdrcEnabled = enabled; dspProcessor.setMdrcEnabled(enabled) }
    fun setMdrcDynamics(thresholdDb: Float, ratio: Float) {
        this.mdrcThresholdDb = thresholdDb; this.mdrcRatio = ratio.coerceAtLeast(1.0f)
        dspProcessor.setMdrcDynamics(thresholdDb, ratio)
    }
    fun setMdrcGain(bandIndex: Int, gainDb: Float) { if (bandIndex in 0 until 5) { this.mdrcGains[bandIndex] = gainDb; dspProcessor.setMdrcBandGain(bandIndex, gainDb) } }
    fun setLimiter(enabled: Boolean, thresholdDb: Float = -1.0f) { this.isLimiterEnabled = enabled; this.limiterThresholdDb = thresholdDb; dspProcessor.setLimiter(enabled, thresholdDb) }
    fun setAts2835pEmulation(enabled: Boolean, amount: Float = 0.8f, bluetoothBypass: Boolean = false) {
        this.ats2835pEmuEnabled = enabled; this.ats2835pEmuAmount = amount.coerceIn(0f, 1f); this.ats2835pBtBypass = bluetoothBypass
        dspProcessor.setEmulationEnabled(enabled); dspProcessor.setEmulationAmount(this.ats2835pEmuAmount); dspProcessor.setBluetoothAutoBypass(bluetoothBypass)
    }
    fun reapplyAllParams() { syncAllParamsToDsp(); checkAndApplyGlobalBypass() }
    private fun syncAllParamsToDsp() {
        dspProcessor.setMasterGain(globalGainDb); dspProcessor.setPreamp(preampDb)
        for (i in 0 until 32) dspProcessor.setBandLevel(i, bandGains[i])
        dspProcessor.setToneBass(toneBassDb); dspProcessor.setToneMid(toneMidDb); dspProcessor.setToneTreble(toneTrebleDb)
        dspProcessor.setBassBoost(isBassBoostEnabled, bassFreqHz, bassBoostDb)
        dspProcessor.setVirtualizer(isVirtualizerEnabled, virtualizerStrength)
        dspProcessor.setMdrcEnabled(isMdrcEnabled); dspProcessor.setMdrcDynamics(mdrcThresholdDb, mdrcRatio)
        for (b in 0 until 5) dspProcessor.setMdrcBandGain(b, mdrcGains[b])
        dspProcessor.setLimiter(isLimiterEnabled, limiterThresholdDb)
        dspProcessor.setEmulationEnabled(ats2835pEmuEnabled); dspProcessor.setEmulationAmount(ats2835pEmuAmount); dspProcessor.setBluetoothAutoBypass(ats2835pBtBypass)
    }
    fun applyProfileInMemory(profile: AppProfile, saveSelection: Boolean = true) {
        currentProfile = profile; globalGainDb = profile.globalGainDb
        for (i in bandGains.indices) { if (i < profile.bandGains.size) bandGains[i] = profile.bandGains[i] }
        for (i in bandQs.indices) { if (i < profile.bandQs.size) bandQs[i] = profile.bandQs[i] }
        bassBoostDb = profile.bassBoostDb; bassFreqHz = profile.bassFreqHz
        isBassBoostEnabled = profile.bassBoostDb > 0.05f
        virtualizerStrength = (profile.virtualizerStrength / 100f).coerceIn(0f, 1f)
        isVirtualizerEnabled = profile.virtualizerStrength > 0
        isMdrcEnabled = profile.mdrcEnabled; isLimiterEnabled = profile.limiterEnabled; limiterThresholdDb = profile.limiterThresholdDb
        isAutoGainEnabled = profile.autoGainEnabled; autoGainTargetLufs = profile.autoGainTargetLufs
        for (i in mdrcGains.indices) { if (i < profile.mdrcGains.size) mdrcGains[i] = profile.mdrcGains[i] }
        ats2835pEmuEnabled = profile.ats2835pEmuEnabled
        reapplyAllParams()
        if (saveSelection) scope.launch { dataStore.saveCurrentProfileId(profile.id) }
        mainHandler.post { onProfileChangedListener?.invoke(profile) }
    }
    fun switchProfileForPackage(packageName: String) {
        val matchedProfile = allProfiles.find { it.packageName.equals(packageName, ignoreCase = true) }
        if (matchedProfile!= null && matchedProfile.id!= currentProfile.id) {
            applyProfileInMemory(matchedProfile, saveSelection = true)
        }
    }
    fun releaseResources() {
        try { context.contentResolver.unregisterContentObserver(volumeObserver) } catch (e: Exception) {}
        releaseAllSessions(); dspProcessor.resetFilterStates()
    }
}
