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

class GlobalAudioSessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GlobalAudioSession"
        const val CONFIG_VARIANT_FAVOR_FREQUENCY_RESOLUTION = 0
        @Volatile private var instance: GlobalAudioSessionManager? = null
        fun getInstance(context: Context): GlobalAudioSessionManager {
            return instance?: synchronized(this) {
                instance?: GlobalAudioSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dataStore = AudioSettingsDataStore(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    var isGlobalAudioEnabled: Boolean = false
        private set

    private val activeSessions = mutableMapOf<Int, SessionHolder>()
    private val activePackages = mutableMapOf<Int, String>()

    var currentProfile: AppProfile = AppProfile.createDefaultProfiles().first()
        private set
    var allProfiles: MutableList<AppProfile> = AppProfile.createDefaultProfiles().toMutableList()
        private set

    var globalGainDb: Float = 0.0f
    val bandGains: FloatArray = FloatArray(32) { 0.0f }
    val bandQs: FloatArray = FloatArray(32) { 1.414f }
    var bassBoostDb: Float = 0.0f
    var bassFreqHz: Float = 85.0f
    var virtualizerStrength: Int = 0

    // Pre-amp Bass / Mid / Treble
    var bassPreampDb: Float = 0f
    var midPreampDb: Float = 0f
    var treblePreampDb: Float = 0f

    fun setBassPreamp(db: Float) { bassPreampDb = db.coerceIn(-12f,12f); reapplyAllParams() }
    fun setMidPreamp(db: Float) { midPreampDb = db.coerceIn(-12f,12f); reapplyAllParams() }
    fun setTreblePreamp(db: Float) { treblePreampDb = db.coerceIn(-12f,12f); reapplyAllParams() }

    var isLimiterEnabled: Boolean = true
    var limiterThresholdDb: Float = -0.5f
    var limiterReleaseMs: Float = 60.0f
    var isAutoGainEnabled: Boolean = true
    var autoGainTargetLufs: Float = -14.0f
    var currentAutoGainOffsetDb: Float = 0.0f
    var isMdrcEnabled: Boolean = true
    val mdrcGains: FloatArray = FloatArray(5) { 0.0f }
    var ats2835pEmuEnabled: Boolean = false

    var onSystemVolumeChangedListener: ((Int, Int) -> Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>) -> Unit)? = null
    var onProfileChangedListener: ((AppProfile) -> Unit)? = null
    var onAutoGainAdjustmentListener: ((Float) -> Unit)? = null

    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            onSystemVolumeChangedListener?.invoke(getSystemVolume(), getMaxSystemVolume())
        }
    }

    private val appDetectorRunnable = object : Runnable {
        override fun run() {
            if (isGlobalAudioEnabled) checkForegroundAppAndApplyProfile()
            mainHandler.postDelayed(this, 2000L)
        }
    }

    init {
        try { context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver) }
        catch (e: Exception) { Log.w(TAG, "Could not register volume observer: ${e.message}") }
        scope.launch { loadPersistedSettings() }
        mainHandler.postDelayed(appDetectorRunnable, 3000L)
    }

    private suspend fun loadPersistedSettings() {
        try {
            val loadedProfiles = dataStore.loadProfiles()
            if (loadedProfiles.isNotEmpty()) { allProfiles.clear(); allProfiles.addAll(loadedProfiles) }
            val (savedGains, savedQs) = dataStore.loadBands()
            System.arraycopy(savedGains, 0, bandGains, 0, minOf(savedGains.size, bandGains.size))
            System.arraycopy(savedQs, 0, bandQs, 0, minOf(savedQs.size, bandQs.size))
            val currentId = dataStore.loadCurrentProfileId()
            val found = allProfiles.find { it.id == currentId }?: allProfiles.firstOrNull()
            if (found!= null) applyProfileInMemory(found, saveSelection = false)
        } catch (e: Exception) { Log.e(TAG, "Error loading: ${e.message}") }
    }

    fun getSystemVolume(): Int = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 0 }
    fun getMaxSystemVolume(): Int = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
    fun setSystemVolume(volume: Int) {
        try {
            val clamped = volume.coerceIn(0, getMaxSystemVolume())
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
            onSystemVolumeChangedListener?.invoke(clamped, getMaxSystemVolume())
        } catch (e: Exception) { Log.e(TAG, "Error setting volume: ${e.message}") }
    }
    fun adjustSystemVolume(increase: Boolean) {
        try {
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            onSystemVolumeChangedListener?.invoke(getSystemVolume(), getMaxSystemVolume())
        } catch (e: Exception) {}
    }

    fun setGlobalAudioEnabled(enabled: Boolean) {
        if (isGlobalAudioEnabled == enabled) return
        isGlobalAudioEnabled = enabled
        if (enabled) openSession(0, "Sistema Global") else releaseAllSessions()
        dispatchSessionsChanged()
        scope.launch { dataStore.saveGlobalEnabled(enabled) }
    }

    fun openSession(sessionId: Int, packageName: String? = null) {
        if (!isGlobalAudioEnabled) return
        if (activeSessions.containsKey(sessionId)) return
        try {
            val holder = SessionHolder(sessionId)
            initEffectsForSession(holder)
            activeSessions[sessionId] = holder
            packageName?.let { activePackages[sessionId] = it }
            if (packageName!= null) switchProfileForPackage(packageName)
            dispatchSessionsChanged()
        } catch (e: Exception) { Log.e(TAG, "Failed to open session $sessionId: ${e.message}") }
    }

    fun closeSession(sessionId: Int) {
        activeSessions.remove(sessionId)?.release()
        activePackages.remove(sessionId)
        dispatchSessionsChanged()
    }

    private fun releaseAllSessions() {
        for ((_, h) in activeSessions) h.release()
        activeSessions.clear(); activePackages.clear()
    }

    private fun dispatchSessionsChanged() {
        val count = activeSessions.size
        val packages = activePackages.values.distinct()
        mainHandler.post { onActiveSessionsChangedListener?.invoke(count, packages) }
    }

    fun switchProfileForPackage(pkg: String) {
        val matched = allProfiles.find { it.packageName == pkg }
        if (matched!= null && matched.id!= currentProfile.id) applyProfile(matched)
    }

    fun applyProfile(profile: AppProfile) {
        applyProfileInMemory(profile, saveSelection = true)
        reapplyAllParams()
        mainHandler.post { onProfileChangedListener?.invoke(profile) }
    }

    private fun applyProfileInMemory(profile: AppProfile, saveSelection: Boolean) {
        currentProfile = profile
        globalGainDb = profile.globalGainDb
        for (i in 0 until minOf(32, profile.bandGains.size)) bandGains[i] = profile.bandGains[i]
        for (i in 0 until minOf(32, profile.bandQs.size)) bandQs[i] = profile.bandQs[i]
        bassBoostDb = profile.bassBoostDb; bassFreqHz = profile.bassFreqHz
        virtualizerStrength = profile.virtualizerStrength
        isLimiterEnabled = profile.limiterEnabled; limiterThresholdDb = profile.limiterThresholdDb
        isAutoGainEnabled = profile.autoGainEnabled; autoGainTargetLufs = profile.autoGainTargetLufs
        isMdrcEnabled = profile.mdrcEnabled; ats2835pEmuEnabled = profile.ats2835pEmuEnabled
        for (i in 0 until minOf(5, profile.mdrcGains.size)) mdrcGains[i] = profile.mdrcGains[i]
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
        val newProfile = AppProfile(newId, targetPackage, profileName, profileName, globalGainDb,
            bandGains.toList(), bandQs.toList(), bassBoostDb, bassFreqHz, virtualizerStrength,
            isLimiterEnabled, limiterThresholdDb, isAutoGainEnabled, autoGainTargetLufs,
            isMdrcEnabled, mdrcGains.toList(), ats2835pEmuEnabled)
        allProfiles.removeAll { it.id == newId || (it.packageName == targetPackage && it.packageName!= AppProfile.PACKAGE_GLOBAL) }
        allProfiles.add(newProfile); currentProfile = newProfile
        scope.launch { dataStore.saveProfiles(allProfiles); dataStore.saveCurrentProfileId(newId) }
        mainHandler.post { onProfileChangedListener?.invoke(newProfile) }
        return newProfile
    }

    private fun checkForegroundAppAndApplyProfile() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                val time = System.currentTimeMillis()
                val stats = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time)
                val topApp = stats?.maxByOrNull { it.lastTimeUsed }?.packageName
                if (!topApp.isNullOrEmpty() && topApp!= context.packageName) switchProfileForPackage(topApp)
            }
        } catch (_: Exception) {}
    }

    fun setBandGain(index: Int, gainDb: Float) {
        if (index in 0 until 32) {
            bandGains[index] = gainDb.coerceIn(-15f, 15f); reapplyBand(index)
            scope.launch { dataStore.saveBands(bandGains, bandQs) }
        }
    }
    fun setBandQ(index: Int, q: Float) {
        if (index in 0 until 32) {
            bandQs[index] = q.coerceIn(0.5f, 4f); reapplyBand(index)
            scope.launch { dataStore.saveBands(bandGains, bandQs) }
        }
    }
    fun setGlobalGain(gainDb: Float) { globalGainDb = gainDb.coerceIn(-12f,12f); reapplyAllParams(); scope.launch { dataStore.saveGlobalGain(gainDb) } }
    fun setLimiter(enabled: Boolean) = setLimiter(enabled, limiterThresholdDb, limiterReleaseMs)
    fun setLimiter(enabled: Boolean, thresholdDb: Float) = setLimiter(enabled, thresholdDb, limiterReleaseMs)
    fun setLimiter(enabled: Boolean, thresholdDb: Float, releaseMs: Float) {
        isLimiterEnabled = enabled; limiterThresholdDb = thresholdDb.coerceIn(-12f,0f); limiterReleaseMs = releaseMs.coerceIn(10f,200f)
        reapplyAllParams(); scope.launch { dataStore.saveLimiter(enabled, thresholdDb) }
    }
    fun setPreEqBand(bandIndex: Int, gainDb: Float) = setBandGain(bandIndex, gainDb)
    fun setPreEqBand(channelIndex: Int, bandIndex: Int, gainDb: Float) = setBandGain(bandIndex, gainDb)
    fun setBobcBand(bandIndex: Int, gainDb: Float) = setBobcBand(0, bandIndex, gainDb)
    fun setBobcBand(channelIndex: Int, bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until 5) { mdrcGains[bandIndex] = gainDb.coerceIn(-12f,12f); reapplyAllParams() }
    }
    fun setAutoGain(enabled: Boolean, targetLufs: Float = -14.0f) {
        isAutoGainEnabled = enabled; autoGainTargetLufs = targetLufs.coerceIn(-23f,-9f)
        calculateAutoGainOffset(); reapplyAllParams(); scope.launch { dataStore.saveAutoGain(enabled, targetLufs) }
    }
    fun setBassBoost(gainDb: Float, freqHz: Float) {
        bassBoostDb = gainDb.coerceIn(0f,12f); bassFreqHz = freqHz.coerceIn(20f,500f)
        reapplyAllParams(); scope.launch { dataStore.saveBassAndVirtualizer(bassBoostDb, bassFreqHz, virtualizerStrength) }
    }
    fun setVirtualizer(strength: Int) {
        virtualizerStrength = strength.coerceIn(0,1000); reapplyAllParams()
        scope.launch { dataStore.saveBassAndVirtualizer(bassBoostDb, bassFreqHz, virtualizerStrength) }
    }

    fun updateEqParams(gains: FloatArray, preampDb: Float, bassDb: Float, bassFreqHz: Float,
        mdrcEnabled: Boolean = true, mdrcGains: FloatArray = FloatArray(5)) {
        globalGainDb = preampDb.coerceIn(-12f,12f)
        for (i in 0 until minOf(32, gains.size)) bandGains[i] = gains[i].coerceIn(-15f,15f)
        bassBoostDb = bassDb.coerceIn(0f,12f); this.bassFreqHz = bassFreqHz.coerceIn(20f,500f)
        isMdrcEnabled = mdrcEnabled
        for (i in 0 until minOf(5, mdrcGains.size)) this.mdrcGains[i] = mdrcGains[i].coerceIn(-12f,12f)
        reapplyAllParams()
    }

    private fun calculateAutoGainOffset() {
        if (!isAutoGainEnabled) { currentAutoGainOffsetDb = 0f; return }
        val avg = bandGains.average().toFloat()
        val est = -18f + avg + globalGainDb
        currentAutoGainOffsetDb = ((autoGainTargetLufs - est).coerceIn(-6f,6f) * 0.5f).coerceIn(-6f,6f)
        mainHandler.post { onAutoGainAdjustmentListener?.invoke(currentAutoGainOffsetDb) }
    }

    fun reapplyAllParams() {
        calculateAutoGainOffset()
        if (!isGlobalAudioEnabled) return
        for ((_, h) in activeSessions) applyParamsToHolder(h)
    }
    private fun reapplyBand(i: Int) { if (isGlobalAudioEnabled) for ((_, h) in activeSessions) applySingleBandToHolder(h, i) }

    private fun getPreampExtra(freq: Float): Float = when {
        freq < 250f -> bassPreampDb
        freq < 4000f -> midPreampDb
        else -> treblePreampDb
    }

    private fun initEffectsForSession(holder: SessionHolder) {
        var dpOk = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val builder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, true, 32, true, 5, false, 0, true)
                val dp = DynamicsProcessing(0, holder.sessionId, builder.build())
                holder.dynamicsProcessing = dp; dpOk = true
            } catch (e: Exception) { holder.dynamicsProcessing = null }
        }
        if (!dpOk) { try { holder.equalizer = Equalizer(0, holder.sessionId) } catch (_: Exception) {} }
        try { holder.bassBoost = BassBoost(0, holder.sessionId) } catch (_: Exception) {}
        // FIX MUTE: no crear Virtualizer en session 0
        if (holder.sessionId!= 0) {
            try { holder.virtualizer = Virtualizer(0, holder.sessionId) } catch (_: Exception) {}
        } else { holder.virtualizer = null }

        applyParamsToHolder(holder)
        try { holder.dynamicsProcessing?.enabled = true } catch (_: Exception) {}
        try { holder.equalizer?.enabled = holder.dynamicsProcessing == null } catch (_: Exception) {}
        try { holder.bassBoost?.enabled = bassBoostDb > 0.1f } catch (_: Exception) {}
        try { holder.virtualizer?.enabled = virtualizerStrength > 0 } catch (_: Exception) {}
    }

    private fun applyParamsToHolder(holder: SessionHolder) {
        val totalPreamp = (globalGainDb + currentAutoGainOffsetDb).coerceIn(-12f,12f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && holder.dynamicsProcessing!= null) {
            val dp = holder.dynamicsProcessing!!
            try {
                for (ch in 0 until 2) {
                    try { dp.setInputGainSafe(ch, totalPreamp) } catch (_: Exception) {}
                    for (i in 0 until 32) {
                        val freq = SjbzDspProcessor.ISO_FREQUENCIES[i]
                        val q = bandQs[i].coerceIn(0.5f,4f)
                        val extra = getPreampExtra(freq)
                        val shaped = ((bandGains[i] + extra) * (1.414f / q)).coerceIn(-15f,15f)
                        dp.setPreEqBand(ch, i, DynamicsProcessing.EqBand(true, freq, shaped))
                    }
                    val cutoffs = MDRCProcessor.SPLIT_FREQS
                    for (b in 0 until 5) {
                        val cutoff = if (b < cutoffs.size) cutoffs[b] else 20000f
                        val g = if (isMdrcEnabled && b < mdrcGains.size) mdrcGains[b].coerceIn(-12f,12f) else 0f
                        dp.setMbcBand(ch, b, DynamicsProcessing.MbcBand(isMdrcEnabled, cutoff, 10f, 80f, 3f, -14f, 4f, -90f, 1f, 0f, g))
                    }
                    val th = if (isLimiterEnabled) limiterThresholdDb.coerceIn(-12f,0f) else 0f
                    dp.setLimiter(ch, DynamicsProcessing.Limiter(isLimiterEnabled, isLimiterEnabled, 0, 1f, limiterReleaseMs.coerceIn(10f,200f), 10f, th, 0f))
                }
            } catch (e: Exception) { Log.e(TAG, "Error DP: ${e.message}") }
        }
        if (holder.dynamicsProcessing == null) {
            holder.equalizer?.let { eq ->
                try {
                    val n = eq.numberOfBands.toInt()
                    for (b in 0 until n) {
                        val bf = eq.getCenterFreq(b.toShort()) / 1000f
                        var idx = 0; var md = Float.MAX_VALUE
                        for (i in SjbzDspProcessor.ISO_FREQUENCIES.indices) {
                            val d = kotlin.math.abs(SjbzDspProcessor.ISO_FREQUENCIES[i] - bf)
                            if (d < md) { md = d; idx = i }
                        }
                        val extra = getPreampExtra(SjbzDspProcessor.ISO_FREQUENCIES[idx])
                        val mb = ((bandGains[idx] + extra + totalPreamp) * 100).toInt().coerceIn(-1200,1200)
                        eq.setBandLevel(b.toShort(), mb.toShort())
                    }
                } catch (e: Exception) {}
            }
        }
        holder.bassBoost?.let { bb ->
            try { if (bassBoostDb > 0.1f) { bb.setStrength((bassBoostDb/12f*1000).toInt().coerceIn(0,1000).toShort()); bb.enabled = true } else bb.enabled = false } catch (_: Exception) {}
        }
        holder.virtualizer?.let { v ->
            try { if (virtualizerStrength > 0) { v.setStrength(virtualizerStrength.coerceIn(0,1000).toShort()); v.enabled = true } else v.enabled = false } catch (_: Exception) {}
        }
    }

    private fun applySingleBandToHolder(holder: SessionHolder, bandIndex: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && holder.dynamicsProcessing!= null) {
            try {
                val freq = SjbzDspProcessor.ISO_FREQUENCIES[bandIndex]
                val q = bandQs[bandIndex].coerceIn(0.5f,4f)
                val extra = getPreampExtra(freq)
                val shaped = ((bandGains[bandIndex] + extra) * (1.414f / q)).coerceIn(-15f,15f)
                for (ch in 0 until 2) holder.dynamicsProcessing!!.setPreEqBand(ch, bandIndex, DynamicsProcessing.EqBand(true, freq, shaped))
            } catch (_: Exception) {}
        } else applyParamsToHolder(holder)
    }

    private class SessionHolder(val sessionId: Int) {
        var dynamicsProcessing: DynamicsProcessing? = null
        var equalizer: Equalizer? = null
        var bassBoost: BassBoost? = null
        var virtualizer: Virtualizer? = null
        fun release() {
            try { dynamicsProcessing?.enabled = false; dynamicsProcessing?.release() } catch (_: Exception) {}
            try { equalizer?.enabled = false; equalizer?.release() } catch (_: Exception) {}
            try { bassBoost?.enabled = false; bassBoost?.release() } catch (_: Exception) {}
            try { virtualizer?.enabled = false; virtualizer?.release() } catch (_: Exception) {}
            dynamicsProcessing = null; equalizer = null; bassBoost = null; virtualizer = null
        }
    }
}

private fun DynamicsProcessing.setInputGainSafe(c: Int, g: Float) {
    try { javaClass.getMethod("setInputGainByChannelIndex", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).invoke(this, c, g) } catch (_: Exception) {}
}
private fun DynamicsProcessing.setPreEqBand(c: Int, b: Int, band: DynamicsProcessing.EqBand) {
    try { javaClass.getMethod("setPreEqBandByChannelIndex", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, DynamicsProcessing.EqBand::class.java).invoke(this, c, b, band) } catch (_: Exception) {}
}
private fun DynamicsProcessing.setMbcBand(c: Int, b: Int, band: DynamicsProcessing.MbcBand) {
    try { javaClass.getMethod("setMbcBandByChannelIndex", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, DynamicsProcessing.MbcBand::class.java).invoke(this, c, b, band) } catch (_: Exception) {}
}
private fun DynamicsProcessing.setLimiter(c: Int, l: DynamicsProcessing.Limiter) {
    try { javaClass.getMethod("setLimiterByChannelIndex", Int::class.javaPrimitiveType, DynamicsProcessing.Limiter::class.java).invoke(this, c, l) } catch (_: Exception) {}
}
