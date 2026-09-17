package com.sjbz.aimp.audio

import android.app.usage.UsageStatsManager
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.sjbz.aimp.data.AudioSettingsDataStore
import com.sjbz.aimp.model.AppProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * GlobalAudioSessionManager: Manages system-wide audio effects on audioSessionId = 0
 * and any external media sessions (Spotify, YouTube, YouTube Music, Chrome, etc.).
 *
 * Implements:
 * - 32-Band ISO Equalizer with Q-ratio bandwidth control
 * - Master Input Gain / Preamp (-12 dB to +12 dB)
 * - Anti-Clipping Limiter (API 28+ DynamicsProcessing.Limiter)
 * - AutoGain loudness leveler between apps (-23 LUFS to -9 LUFS)
 * - Bass Boost & Virtualizer 3D Surround sound
 * - Per-App Profiles with automatic profile switching based on foreground package
 * - DataStore persistence
 */
class GlobalAudioSessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GlobalAudioSession"
        const val CONFIG_VARIANT_FAVOR_FREQUENCY_RESOLUTION = 0

        @Volatile
        private var instance: GlobalAudioSessionManager? = null

        fun getInstance(context: Context): GlobalAudioSessionManager {
            return instance ?: synchronized(this) {
                instance ?: GlobalAudioSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dataStore = AudioSettingsDataStore(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Master switch for global audio processing
    var isGlobalAudioEnabled: Boolean = false
        private set

    // Active audio sessions (sessionId -> SessionHolder)
    private val activeSessions = mutableMapOf<Int, SessionHolder>()
    private val activePackages = mutableMapOf<Int, String>()

    // Current active profile
    var currentProfile: AppProfile = AppProfile.createDefaultProfiles().first()
        private set
    var allProfiles: MutableList<AppProfile> = AppProfile.createDefaultProfiles().toMutableList()
        private set

    // Audio Parameters in Memory
    var globalGainDb: Float = 0.0f
    val bandGains: FloatArray = FloatArray(32) { 0.0f }
    val bandQs: FloatArray = FloatArray(32) { 1.414f }
    var bassBoostDb: Float = 0.0f
    var bassFreqHz: Float = 85.0f
    var virtualizerStrength: Int = 0

    // Dynamics: Limiter & AutoGain
    var isLimiterEnabled: Boolean = true
    var limiterThresholdDb: Float = -0.5f
    var limiterReleaseMs: Float = 60.0f

    var isAutoGainEnabled: Boolean = true
    var autoGainTargetLufs: Float = -14.0f
    var currentAutoGainOffsetDb: Float = 0.0f

    // MDRC & ATS2835P
    var isMdrcEnabled: Boolean = true
    val mdrcGains: FloatArray = FloatArray(5) { 0.0f }
    var ats2835pEmuEnabled: Boolean = false

    // Callbacks
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

    // App polling runnable for per-app profile auto switching
    private val appDetectorRunnable = object : Runnable {
        override fun run() {
            if (isGlobalAudioEnabled) {
                checkForegroundAppAndApplyProfile()
            }
            mainHandler.postDelayed(this, 2000L)
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

        mainHandler.postDelayed(appDetectorRunnable, 3000L)
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
    // Global Audio Master Switch & Session Management
    // -------------------------------------------------------------------------

    fun setGlobalAudioEnabled(enabled: Boolean) {
        if (isGlobalAudioEnabled == enabled) return
        isGlobalAudioEnabled = enabled

        if (enabled) {
            // Open session 0: affects global audio mix on Android
            openSession(0, "Sistema Global")
        } else {
            releaseAllSessions()
        }
        dispatchSessionsChanged()

        scope.launch {
            dataStore.saveGlobalEnabled(enabled)
        }
    }

    fun openSession(sessionId: Int, packageName: String? = null) {
        if (!isGlobalAudioEnabled) return
        if (activeSessions.containsKey(sessionId)) return

        try {
            val holder = SessionHolder(sessionId)
            initEffectsForSession(holder)
            activeSessions[sessionId] = holder
            packageName?.let { activePackages[sessionId] = it }
            Log.i(TAG, "Attached to audio session $sessionId (pkg: $packageName)")

            // Check if active package has a tailored profile
            if (packageName != null) {
                switchProfileForPackage(packageName)
            }

            dispatchSessionsChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open session $sessionId: ${e.message}")
        }
    }

    fun closeSession(sessionId: Int) {
        val holder = activeSessions.remove(sessionId)
        activePackages.remove(sessionId)
        holder?.release()
        Log.d(TAG, "Closed audio session $sessionId")
        dispatchSessionsChanged()
    }

    private fun releaseAllSessions() {
        for ((_, holder) in activeSessions) {
            holder.release()
        }
        activeSessions.clear()
        activePackages.clear()
    }

    private fun dispatchSessionsChanged() {
        val count = activeSessions.size
        val packages = activePackages.values.distinct()
        mainHandler.post {
            onActiveSessionsChangedListener?.invoke(count, packages)
        }
    }

    // -------------------------------------------------------------------------
    // Per-App Profile Detection & Switching
    // -------------------------------------------------------------------------

    fun switchProfileForPackage(pkg: String) {
        val matchedProfile = allProfiles.find { it.packageName == pkg }
        if (matchedProfile != null && matchedProfile.id != currentProfile.id) {
            Log.i(TAG, "Auto-switching EQ profile to: ${matchedProfile.appName} (${matchedProfile.presetName})")
            applyProfile(matchedProfile)
        }
    }

    fun applyProfile(profile: AppProfile) {
        applyProfileInMemory(profile, saveSelection = true)
        reapplyAllParams()
        mainHandler.post {
            onProfileChangedListener?.invoke(profile)
        }
    }

    private fun applyProfileInMemory(profile: AppProfile, saveSelection: Boolean) {
        currentProfile = profile
        globalGainDb = profile.globalGainDb

        for (i in 0 until minOf(32, profile.bandGains.size)) {
            bandGains[i] = profile.bandGains[i]
        }
        for (i in 0 until minOf(32, profile.bandQs.size)) {
            bandQs[i] = profile.bandQs[i]
        }
        bassBoostDb = profile.bassBoostDb
        bassFreqHz = profile.bassFreqHz
        virtualizerStrength = profile.virtualizerStrength
        isLimiterEnabled = profile.limiterEnabled
        limiterThresholdDb = profile.limiterThresholdDb
        isAutoGainEnabled = profile.autoGainEnabled
        autoGainTargetLufs = profile.autoGainTargetLufs
        isMdrcEnabled = profile.mdrcEnabled
        ats2835pEmuEnabled = profile.ats2835pEmuEnabled

        for (i in 0 until minOf(5, profile.mdrcGains.size)) {
            mdrcGains[i] = profile.mdrcGains[i]
        }

        if (saveSelection) {
            scope.launch {
                dataStore.saveCurrentProfileId(profile.id)
                dataStore.saveGlobalGain(globalGainDb)
                dataStore.saveBands(bandGains, bandQs)
                dataStore.saveBassAndVirtualizer(bassBoostDb, bassFreqHz, virtualizerStrength)
                dataStore.saveLimiter(isLimiterEnabled, limiterThresholdDb)
                dataStore.saveAutoGain(isAutoGainEnabled, autoGainTargetLufs)
            }
        }
    }

    fun saveCurrentAsProfile(profileName: String, targetPackage: String = AppProfile.PACKAGE_GLOBAL): AppProfile {
        val newId = "prof_${System.currentTimeMillis()}"
        val newProfile = AppProfile(
            id = newId,
            packageName = targetPackage,
            appName = profileName,
            presetName = profileName,
            globalGainDb = globalGainDb,
            bandGains = bandGains.toList(),
            bandQs = bandQs.toList(),
            bassBoostDb = bassBoostDb,
            bassFreqHz = bassFreqHz,
            virtualizerStrength = virtualizerStrength,
            limiterEnabled = isLimiterEnabled,
            limiterThresholdDb = limiterThresholdDb,
            autoGainEnabled = isAutoGainEnabled,
            autoGainTargetLufs = autoGainTargetLufs,
            mdrcEnabled = isMdrcEnabled,
            mdrcGains = mdrcGains.toList(),
            ats2835pEmuEnabled = ats2835pEmuEnabled
        )
        allProfiles.removeAll { it.id == newId || (it.packageName == targetPackage && it.packageName != AppProfile.PACKAGE_GLOBAL) }
        allProfiles.add(newProfile)
        currentProfile = newProfile

        scope.launch {
            dataStore.saveProfiles(allProfiles)
            dataStore.saveCurrentProfileId(newId)
        }
        mainHandler.post {
            onProfileChangedListener?.invoke(newProfile)
        }
        return newProfile
    }

    private fun checkForegroundAppAndApplyProfile() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                val time = System.currentTimeMillis()
                val stats = usageStatsManager?.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    time - 10000,
                    time
                )
                val topApp = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
                if (!topApp.isNullOrEmpty() && topApp != context.packageName) {
                    switchProfileForPackage(topApp)
                }
            }
        } catch (_: Exception) {
            // Permission or usage query silently handled
        }
    }

    // -------------------------------------------------------------------------
    // Audio DSP Parameter Control & Updates
    // -------------------------------------------------------------------------

    fun setBandGain(index: Int, gainDb: Float) {
        if (index in 0 until 32) {
            bandGains[index] = gainDb
            reapplyBand(index)
            scope.launch { dataStore.saveBands(bandGains, bandQs) }
        }
    }

    fun setBandQ(index: Int, q: Float) {
        if (index in 0 until 32) {
            bandQs[index] = q
            reapplyBand(index)
            scope.launch { dataStore.saveBands(bandGains, bandQs) }
        }
    }

    fun setGlobalGain(gainDb: Float) {
        this.globalGainDb = gainDb
        reapplyAllParams()
        scope.launch { dataStore.saveGlobalGain(gainDb) }
    }

    fun setLimiter(enabled: Boolean) {
        setLimiter(enabled, limiterThresholdDb, limiterReleaseMs)
    }

    fun setLimiter(enabled: Boolean, thresholdDb: Float) {
        setLimiter(enabled, thresholdDb, limiterReleaseMs)
    }

    fun setLimiter(enabled: Boolean, thresholdDb: Float, releaseMs: Float) {
        this.isLimiterEnabled = enabled
        this.limiterThresholdDb = thresholdDb
        this.limiterReleaseMs = releaseMs
        reapplyAllParams()
        scope.launch { dataStore.saveLimiter(enabled, thresholdDb) }
    }

    fun setPreEqBand(bandIndex: Int, gainDb: Float) {
        setBandGain(bandIndex, gainDb)
    }

    fun setPreEqBand(channelIndex: Int, bandIndex: Int, gainDb: Float) {
        setBandGain(bandIndex, gainDb)
    }

    fun setBobcBand(bandIndex: Int, gainDb: Float) {
        setBobcBand(0, bandIndex, gainDb)
    }

    fun setBobcBand(channelIndex: Int, bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 5) {
            mdrcGains[bandIndex] = gainDb.coerceIn(-12f, 12f)
            reapplyAllParams()
        }
    }

    fun setAutoGain(enabled: Boolean, targetLufs: Float = -14.0f) {
        this.isAutoGainEnabled = enabled
        this.autoGainTargetLufs = targetLufs
        calculateAutoGainOffset()
        reapplyAllParams()
        scope.launch { dataStore.saveAutoGain(enabled, targetLufs) }
    }

    fun setBassBoost(gainDb: Float, freqHz: Float) {
        this.bassBoostDb = gainDb
        this.bassFreqHz = freqHz
        reapplyAllParams()
        scope.launch { dataStore.saveBassAndVirtualizer(bassBoostDb, bassFreqHz, virtualizerStrength) }
    }

    fun setVirtualizer(strength: Int) {
        this.virtualizerStrength = strength
        reapplyAllParams()
        scope.launch { dataStore.saveBassAndVirtualizer(bassBoostDb, bassFreqHz, virtualizerStrength) }
    }

    fun updateEqParams(
        gains: FloatArray,
        preampDb: Float,
        bassDb: Float,
        bassFreqHz: Float,
        mdrcEnabled: Boolean = true,
        mdrcGains: FloatArray = FloatArray(5),
        mdrcThresholdDb: Float = -14f,
        mdrcRatio: Float = 3f
    ) {
        this.globalGainDb = preampDb
        for (i in 0 until minOf(32, gains.size)) {
            this.bandGains[i] = gains[i]
        }
        this.bassBoostDb = bassDb
        this.bassFreqHz = bassFreqHz
        this.isMdrcEnabled = mdrcEnabled
        for (i in 0 until minOf(5, mdrcGains.size)) {
            this.mdrcGains[i] = mdrcGains[i]
        }
        reapplyAllParams()
    }

    private fun calculateAutoGainOffset() {
        if (!isAutoGainEnabled) {
            currentAutoGainOffsetDb = 0.0f
            return
        }
        // Leveling formula towards target LUFS (standard broadcast/streaming normalization)
        // Adjusts input headroom so quieter apps get a gentle boost and loud apps don't overload
        val avgBandGain = bandGains.average().toFloat()
        val estimatedLufs = -18.0f + avgBandGain + globalGainDb
        val delta = (autoGainTargetLufs - estimatedLufs).coerceIn(-6.0f, 6.0f)
        currentAutoGainOffsetDb = delta * 0.5f // gentle 50% leveling step
        mainHandler.post {
            onAutoGainAdjustmentListener?.invoke(currentAutoGainOffsetDb)
        }
    }

    fun reapplyAllParams() {
        calculateAutoGainOffset()
        if (!isGlobalAudioEnabled) return
        for ((_, holder) in activeSessions) {
            applyParamsToHolder(holder)
        }
    }

    private fun reapplyBand(bandIndex: Int) {
        if (!isGlobalAudioEnabled) return
        for ((_, holder) in activeSessions) {
            applySingleBandToHolder(holder, bandIndex)
        }
    }

    // -------------------------------------------------------------------------
    // Hardware Effect Initialization and Configuration
    // -------------------------------------------------------------------------

    private fun initEffectsForSession(holder: SessionHolder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // DynamicsProcessing with 32 EQ Bands + 5-Band Multiband Compressor + Limiter
                val channelCount = 2
                val mbcBandCount = 5
                val eqBandCount = 32

                val builder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    channelCount,
                    true, // preEqInUse
                    eqBandCount,
                    true, // mbcInUse
                    mbcBandCount,
                    false, // postEqInUse
                    0,
                    true  // limiterInUse (Anti-clipping protection!)
                )
                val config = builder.build()
                val dp = DynamicsProcessing(0, holder.sessionId, config)
                dp.enabled = true
                holder.dynamicsProcessing = dp
                Log.d(TAG, "Initialized DynamicsProcessing with Limiter for session ${holder.sessionId}")
            } catch (e: Exception) {
                Log.w(TAG, "DynamicsProcessing unavailable on session ${holder.sessionId}: ${e.message}")
            }
        }

        // Standard Equalizer fallback
        try {
            val eq = Equalizer(0, holder.sessionId)
            eq.enabled = true
            holder.equalizer = eq
        } catch (e: Exception) {
            Log.w(TAG, "Standard Equalizer fallback unavailable for session ${holder.sessionId}: ${e.message}")
        }

        // Standard BassBoost
        try {
            val bb = BassBoost(0, holder.sessionId)
            bb.enabled = true
            holder.bassBoost = bb
        } catch (e: Exception) {
            Log.w(TAG, "BassBoost unavailable for session ${holder.sessionId}: ${e.message}")
        }

        // Virtualizer 3D Surround
        try {
            val virt = Virtualizer(0, holder.sessionId)
            virt.enabled = true
            holder.virtualizer = virt
        } catch (e: Exception) {
            Log.w(TAG, "Virtualizer unavailable for session ${holder.sessionId}: ${e.message}")
        }

        applyParamsToHolder(holder)
    }

    private fun applyParamsToHolder(holder: SessionHolder) {
        val totalPreamp = globalGainDb + currentAutoGainOffsetDb

        // 1. DynamicsProcessing (Android 9.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && holder.dynamicsProcessing != null) {
            val dp = holder.dynamicsProcessing!!
            try {
                for (ch in 0 until 2) {
                    // Pre-EQ 32 Bands with Q-ratio calculation
                    for (i in 0 until 32) {
                        val freq = SjbzDspProcessor.ISO_FREQUENCIES[i]
                        val qFactor = bandQs[i]
                        // Q-factor shaping: narrower Q tightens the peak gain
                        val shapedGain = bandGains[i] * (1.414f / qFactor.coerceAtLeast(0.5f))
                        val finalGain = (shapedGain + totalPreamp).coerceIn(-24f, 24f)

                        val eqBand = DynamicsProcessing.EqBand(true, freq, finalGain)
                        dp.setPreEqBand(ch, i, eqBand)
                    }

                    // 5-Band Multiband Dynamic Range Compressor
                    val mbcCutoffs = MDRCProcessor.SPLIT_FREQS
                    for (b in 0 until 5) {
                        val cutoff = if (b < mbcCutoffs.size) mbcCutoffs[b] else 20000f
                        val mbcGain = if (isMdrcEnabled && b < mdrcGains.size) mdrcGains[b] else 0f
                        val mbcBand = DynamicsProcessing.MbcBand(
                            isMdrcEnabled,
                            cutoff,
                            10.0f,
                            80.0f,
                            3.0f,
                            -14.0f,
                            4.0f,
                            -90.0f,
                            1.0f,
                            0.0f,
                            mbcGain
                        )
                        dp.setMbcBand(ch, b, mbcBand)
                    }

                    // Anti-Clipping Limiter (prevents digital distortion on high boosts)
                    val limiter = DynamicsProcessing.Limiter(
                        isLimiterEnabled,
                        isLimiterEnabled,
                        0,
                        1.0f, // 1ms fast peak attack
                        limiterReleaseMs,
                        10.0f, // 10:1 brickwall ratio
                        limiterThresholdDb,
                        0.0f  // postGain
                    )
                    dp.setLimiter(ch, limiter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying DynamicsProcessing params: ${e.message}")
            }
        }

        // 2. Standard Equalizer fallback
        holder.equalizer?.let { eq ->
            try {
                val numBands = eq.numberOfBands.toInt()
                for (b in 0 until numBands) {
                    val bandFreq = eq.getCenterFreq(b.toShort()) / 1000f
                    var closestIdx = 0
                    var minDist = Float.MAX_VALUE
                    for (i in SjbzDspProcessor.ISO_FREQUENCIES.indices) {
                        val dist = kotlin.math.abs(SjbzDspProcessor.ISO_FREQUENCIES[i] - bandFreq)
                        if (dist < minDist) {
                            minDist = dist
                            closestIdx = i
                        }
                    }
                    val gainMilliBels = ((bandGains[closestIdx] + totalPreamp) * 100).toInt().coerceIn(-1200, 1200)
                    eq.setBandLevel(b.toShort(), gainMilliBels.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error applying Equalizer level: ${e.message}")
            }
        }

        // 3. BassBoost
        holder.bassBoost?.let { bb ->
            try {
                if (bassBoostDb > 0.1f) {
                    val strength = (bassBoostDb / 12.0f * 1000).toInt().coerceIn(0, 1000)
                    bb.setStrength(strength.toShort())
                    bb.enabled = true
                } else {
                    bb.enabled = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error applying BassBoost: ${e.message}")
            }
        }

        // 4. Virtualizer 3D Surround
        holder.virtualizer?.let { virt ->
            try {
                if (virtualizerStrength > 0) {
                    virt.setStrength(virtualizerStrength.coerceIn(0, 1000).toShort())
                    virt.enabled = true
                } else {
                    virt.enabled = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error applying Virtualizer: ${e.message}")
            }
        }
    }

    private fun applySingleBandToHolder(holder: SessionHolder, bandIndex: Int) {
        val totalPreamp = globalGainDb + currentAutoGainOffsetDb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && holder.dynamicsProcessing != null) {
            val dp = holder.dynamicsProcessing!!
            try {
                val freq = SjbzDspProcessor.ISO_FREQUENCIES[bandIndex]
                val qFactor = bandQs[bandIndex]
                val shapedGain = bandGains[bandIndex] * (1.414f / qFactor.coerceAtLeast(0.5f))
                val finalGain = (shapedGain + totalPreamp).coerceIn(-24f, 24f)
                val eqBand = DynamicsProcessing.EqBand(true, freq, finalGain)
                for (ch in 0 until 2) {
                    dp.setPreEqBand(ch, bandIndex, eqBand)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error updating single band $bandIndex: ${e.message}")
            }
        }
    }

    /**
     * SessionHolder: Holds references to hardware effects for a session.
     */
    private class SessionHolder(val sessionId: Int) {
        var dynamicsProcessing: DynamicsProcessing? = null
        var equalizer: Equalizer? = null
        var bassBoost: BassBoost? = null
        var virtualizer: Virtualizer? = null

        fun release() {
            try {
                dynamicsProcessing?.enabled = false
                dynamicsProcessing?.release()
            } catch (_: Exception) {}
            dynamicsProcessing = null

            try {
                equalizer?.enabled = false
                equalizer?.release()
            } catch (_: Exception) {}
            equalizer = null

            try {
                bassBoost?.enabled = false
                bassBoost?.release()
            } catch (_: Exception) {}
            bassBoost = null

            try {
                virtualizer?.enabled = false
                virtualizer?.release()
            } catch (_: Exception) {}
            virtualizer = null
        }
    }
}

// -----------------------------------------------------------------------------
// DynamicsProcessing Extension Helpers (API 28+)
// -----------------------------------------------------------------------------

private fun DynamicsProcessing.setPreEqBand(channelIndex: Int, bandIndex: Int, band: DynamicsProcessing.EqBand) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        setPreEqBandByChannelIndex(channelIndex, bandIndex, band)
    }
}

private fun DynamicsProcessing.setMbcBand(channelIndex: Int, bandIndex: Int, band: DynamicsProcessing.MbcBand) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        setMbcBandByChannelIndex(channelIndex, bandIndex, band)
    }
}

private fun DynamicsProcessing.setBobcBand(channelIndex: Int, bandIndex: Int, band: DynamicsProcessing.MbcBand) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        setMbcBandByChannelIndex(channelIndex, bandIndex, band)
    }
}

private fun DynamicsProcessing.setLimiter(channelIndex: Int, limiter: DynamicsProcessing.Limiter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        setLimiterByChannelIndex(channelIndex, limiter)
    }
}

