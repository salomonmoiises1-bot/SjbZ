package com.sjbz.aimp.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sjbz.aimp.data.AudioSettingsDataStore
import com.sjbz.aimp.model.AppProfile
import kotlinx.coroutines.*
import kotlin.math.abs

class GlobalAudioSessionManager private constructor(ctx: Context) {
    companion object {
        private const val TAG = "SBZ-Manager"
        @Volatile private var inst: GlobalAudioSessionManager? = null
        @Volatile private var dsp: SjbzDspProcessor? = null

        @JvmStatic fun getInstance(c: Context): GlobalAudioSessionManager {
            return inst?: synchronized(this) {
                inst?: GlobalAudioSessionManager(c.applicationContext).also { inst = it }
            }
        }

        @JvmStatic fun getDspProcessorInstance(): SjbzDspProcessor {
            return dsp?: synchronized(this) {
                dsp?: SjbzDspProcessor(getOutputSampleRate(inst?.appContext)).also { dsp = it }
            }
        }

        // Para compatibilidad con tu receiver viejo y tu GitHub Action
        @JvmStatic fun openSession(sessionId: Int) { inst?.openSession(sessionId, "unknown") }
        @JvmStatic fun closeSession(sessionId: Int) { inst?.closeSession(sessionId) }
        @JvmStatic fun openSession(sessionId: Short) = openSession(sessionId.toInt())
        @JvmStatic fun closeSession(sessionId: Short) = closeSession(sessionId.toInt())

        private fun getOutputSampleRate(context: Context?): Float {
            return try {
                (context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                   ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toFloat()?: 48000f
            } catch (_: Exception) { 48000f }
        }
        private val MY_FREQS = intArrayOf(16,20,25,31,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000)
    }

    private val appContext = ctx.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dataStore = AudioSettingsDataStore(appContext)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    val dspProcessor: SjbzDspProcessor by lazy { getDspProcessorInstance() }

    @Volatile var isGlobalAudioEnabled = false
    var currentProfile: AppProfile = AppProfile.createDefaultProfiles().first()
    var allProfiles = AppProfile.createDefaultProfiles().toMutableList()
    @Volatile var globalGainDb = 0f; @Volatile var preampDb = 0f
    val bandGains = FloatArray(32); val bandQs = FloatArray(32) { 1.414f }
    @Volatile var toneBassDb = 0f; @Volatile var toneMidDb = 0f; @Volatile var toneTrebleDb = 0f
    @Volatile var isBassBoostEnabled = true; @Volatile var bassBoostDb = 4f; @Volatile var bassFreqHz = 85f
    @Volatile var isVirtualizerEnabled = false; @Volatile var virtualizerStrength = 0f
    @Volatile var isLimiterEnabled = true; @Volatile var limiterThresholdDb = -1f
    @Volatile var isAutoGainEnabled = true; @Volatile var autoGainTargetLufs = -14f
    @Volatile var mdrcThresholdDb = -18f; @Volatile var mdrcRatio = 2.5f; val mdrcGains = FloatArray(5)

    @Volatile private var systemEq: Equalizer? = null
    @Volatile private var systemBass: BassBoost? = null
    @Volatile private var systemVirt: Virtualizer? = null
    @Volatile private var systemLoud: LoudnessEnhancer? = null
    @Volatile private var attachedSessionId: Int = 0
    @Volatile private var bandMapCache: IntArray? = null

    var onProfileChangedListener: ((AppProfile) -> Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>) -> Unit)? = null

    init { scope.launch { load() } }

    // ESTE ES EL QUE HACE QUE FUNCIONE DE VERDAD
    fun initForSession(newSessionId: Int) {
        if (newSessionId <= 0) return
        if (newSessionId == attachedSessionId && systemEq!= null) {
            applyAll(); return
        }
        mainHandler.post {
            try {
                try { systemEq?.release() } catch (_: Exception) {}
                try { systemBass?.release() } catch (_: Exception) {}
                try { systemVirt?.release() } catch (_: Exception) {}
                try { systemLoud?.release() } catch (_: Exception) {}

                systemEq = try { Equalizer(0, newSessionId).apply { enabled = true } } catch (e: Exception) { Log.e(TAG, "EQ attach fail $newSessionId: ${e.message}"); null }
                systemBass = try { BassBoost(0, newSessionId).apply { enabled = isGlobalAudioEnabled && isBassBoostEnabled } } catch (_: Exception) { null }
                systemVirt = try { Virtualizer(0, newSessionId).apply { enabled = isGlobalAudioEnabled && isVirtualizerEnabled } } catch (_: Exception) { null }
                systemLoud = try { LoudnessEnhancer(newSessionId).apply { enabled = isGlobalAudioEnabled && (isLimiterEnabled || isAutoGainEnabled) } } catch (_: Exception) { null }

                attachedSessionId = newSessionId
                bandMapCache = null
                buildCache()
                applyAll()
                Log.i(TAG, "SUCCESS Attached to session $newSessionId")
            } catch (e: Exception) {
                Log.e(TAG, "initForSession crash ${e.message}")
            }
        }
    }

    fun openSession(sessionId: Int, packageName: String) {
        Log.i(TAG, "openSession $sessionId pkg=$packageName")
        initForSession(sessionId)
        try { onActiveSessionsChangedListener?.invoke(sessionId, listOf(packageName)) } catch (_: Exception) {}
    }

    fun openSession(sessionId: Int) = openSession(sessionId, "unknown")
    fun closeSession(sessionId: Int) { Log.i(TAG, "closeSession $sessionId") }

    private suspend fun load() {
        try {
            val profiles = dataStore.loadProfiles()
            if (profiles.isNotEmpty()) { allProfiles.clear(); allProfiles.addAll(profiles) }
            val (gains, _) = dataStore.loadBands()
            for (i in bandGains.indices) if (i < gains.size) bandGains[i] = gains[i]
            withContext(Dispatchers.Main) { if (allProfiles.isNotEmpty()) applyProfileInMemory(allProfiles.first(), false) }
        } catch (e: Exception) { Log.e(TAG, "load fail ${e.message}") }
    }

    fun getSystemVolume() = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 7 }
    fun getMaxSystemVolume() = try { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
    fun setSystemVolume(v: Int) { try { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, getMaxSystemVolume()), 0) } catch (_: Exception) {} }

    fun setGlobalAudioEnabled(enabled: Boolean) {
        if (isGlobalAudioEnabled == enabled) return
        isGlobalAudioEnabled = enabled
        scope.launch { try { dataStore.saveGlobalEnabled(enabled) } catch (_: Exception) {} }
        checkBypass(); applyAll()
    }

    fun checkBypass(): Boolean {
        val active = isGlobalAudioEnabled
        try { systemEq?.enabled = active } catch (_: Exception) {}
        try { systemBass?.enabled = active && isBassBoostEnabled } catch (_: Exception) {}
        try { systemVirt?.enabled = active && isVirtualizerEnabled } catch (_: Exception) {}
        try { systemLoud?.enabled = active && (isLimiterEnabled || isAutoGainEnabled) } catch (_: Exception) {}
        return active
    }

    fun setGlobalGain(db: Float) { globalGainDb = db; try { dspProcessor.setMasterGain(db) } catch (_: Exception) {}; applyEq() }
    fun setPreampGain(db: Float) { preampDb = db.coerceIn(-12f, 12f); try { dspProcessor.setPreamp(preampDb) } catch (_: Exception) {}; applyEq() }
    fun setBandGain(i: Int, db: Float) {
        if (i in 0..31) {
            bandGains[i] = db.coerceIn(-12f, 12f)
            try { dspProcessor.setBandLevel(i, bandGains[i]) } catch (_: Exception) {}
            applyEq()
            scope.launch { try { dataStore.saveBands(bandGains, bandQs) } catch (_: Exception) {} }
        }
    }
    fun setBandQ(i: Int, q: Float) { if (i in 0..31) bandQs[i] = q.coerceIn(0.1f, 10f) }
    fun setToneBass(db: Float) { toneBassDb = db.coerceIn(-12f, 12f); try { dspProcessor.setToneBass(db) } catch (_: Exception) {}; applyEq() }
    fun setToneMid(db: Float) { toneMidDb = db.coerceIn(-12f, 12f); try { dspProcessor.setToneMid(db) } catch (_: Exception) {}; applyEq() }
    fun setToneTreble(db: Float) { toneTrebleDb = db.coerceIn(-12f, 12f); try { dspProcessor.setToneTreble(db) } catch (_: Exception) {}; applyEq() }
    fun setBassBoost(enabled: Boolean, gainDb: Float, freq: Float = 85f) {
        isBassBoostEnabled = enabled; bassBoostDb = gainDb.coerceIn(0f, 12f); bassFreqHz = freq.coerceIn(20f, 250f)
        try { dspProcessor.setBassBoost(enabled, freq, bassBoostDb) } catch (_: Exception) {}
        applyBass()
    }
    fun setVirtualizer(progress: Int) = setVirtualizer(progress > 0, progress / 100f)
    fun setVirtualizer(en: Boolean, strength: Float) { isVirtualizerEnabled = en; virtualizerStrength = strength.coerceIn(0f, 1f); applyVirt() }
    fun setLimiter(en: Boolean, thresh: Float = -1f) { isLimiterEnabled = en; limiterThresholdDb = thresh; applyLoud() }
    fun setAutoGain(en: Boolean, target: Float) { isAutoGainEnabled = en; autoGainTargetLufs = target; applyLoud() }
    fun setMdrcEnabled(e: Boolean) { try { dspProcessor.setMdrcEnabled(e) } catch (_: Exception) {} }
    fun setMdrcDynamics(t: Float, r: Float) { try { dspProcessor.setMdrcDynamics(t, r) } catch (_: Exception) {} }
    fun setMdrcGain(i: Int, g: Float) { try { dspProcessor.setMdrcBandGain(i, g) } catch (_: Exception) {} }
    fun setAts2835pEmulation(e: Boolean, a: Float, b: Boolean) { try { dspProcessor.setEmulationEnabled(e); dspProcessor.setEmulationAmount(a); dspProcessor.setBluetoothAutoBypass(b) } catch (_: Exception) {} }

    fun saveCurrentAsProfile(name: String): AppProfile {
        val p = AppProfile(id = "custom_${System.currentTimeMillis()}", packageName = "", appName = name, presetName = name, globalGainDb = globalGainDb, bandGains = bandGains.toList(), bandQs = bandQs.toList(), bassBoostDb = bassBoostDb, bassFreqHz = bassFreqHz, virtualizerStrength = (virtualizerStrength * 100).toInt(), limiterEnabled = isLimiterEnabled, limiterThresholdDb = limiterThresholdDb, autoGainEnabled = isAutoGainEnabled, autoGainTargetLufs = autoGainTargetLufs, mdrcEnabled = true, mdrcGains = mdrcGains.toList(), ats2835pEmuEnabled = true)
        allProfiles.add(p); currentProfile = p; scope.launch { try { dataStore.saveProfiles(allProfiles) } catch (_: Exception) {} }; return p
    }
    fun applyProfileInMemory(p: AppProfile, save: Boolean = true) {
        currentProfile = p; globalGainDb = p.globalGainDb
        for (i in bandGains.indices) if (i < p.bandGains.size) bandGains[i] = p.bandGains[i]
        bassBoostDb = p.bassBoostDb; bassFreqHz = p.bassFreqHz; virtualizerStrength = p.virtualizerStrength / 100f
        applyAll()
    }

    fun attachSystemEqualizer(eq: Equalizer) { systemEq = eq; bandMapCache = null; buildCache() }
    fun attachSystemBassBoost(b: BassBoost) { systemBass = b }
    fun attachSystemVirtualizer(v: Virtualizer) { systemVirt = v }
    fun attachSystemLoudness(l: LoudnessEnhancer) { systemLoud = l }
    fun reapplyAllParams() { applyAll() }

    private fun buildCache() {
        val eq = systemEq?: return
        try {
            val num = eq.numberOfBands.toInt()
            if (num <= 0) return
            val cache = IntArray(num)
            for (i in 0 until num) {
                val cf = try { eq.getCenterFreq(i.toShort()) / 1000 } catch (_: Exception) { MY_FREQS[i % MY_FREQS.size] }
                var closest = 15; var md = Int.MAX_VALUE
                for (j in MY_FREQS.indices) { val d = abs(MY_FREQS[j] - cf); if (d < md) { md = d; closest = j } }
                cache[i] = closest
            }
            bandMapCache = cache
        } catch (_: Exception) { bandMapCache = null }
    }

    fun applyAll() { applyEq(); applyBass(); applyVirt(); applyLoud(); checkBypass() }

    fun applyEq() {
        val eq = systemEq?: return
        val cache = bandMapCache?: run { buildCache(); bandMapCache }?: return
        try {
            for (i in 0 until eq.numberOfBands) {
                if (i >= cache.size) continue
                val idx = cache[i].coerceIn(0, 31)
                var total = bandGains[idx] + globalGainDb + preampDb
                val cf = try { eq.getCenterFreq(i.toShort()) / 1000 } catch (_: Exception) { 1000 }
                total += when { cf < 250 -> toneBassDb; cf <= 4000 -> toneMidDb; else -> toneTrebleDb }
                eq.setBandLevel(i.toShort(), total.coerceIn(-15f, 15f).let { (it * 100).toInt().toShort() })
            }
            eq.enabled = isGlobalAudioEnabled
        } catch (e: Exception) { Log.e(TAG, "applyEq fail ${e.message}") }
    }

    private fun applyBass() {
        try {
            val strength = (bassBoostDb * 1000 / 12f).toInt().coerceIn(0, 1000).toShort()
            systemBass?.setStrength(strength)
            systemBass?.enabled = isGlobalAudioEnabled && isBassBoostEnabled
        } catch (_: Exception) {}
    }

    private fun applyVirt() {
        try {
            val v = systemVirt?: return
            if (!v.strengthSupported) { v.enabled = false; return }
            v.setStrength((virtualizerStrength * 1000).toInt().coerceIn(0, 1000).toShort())
            v.enabled = isGlobalAudioEnabled && isVirtualizerEnabled
        } catch (_: Exception) { try { systemVirt?.enabled = false } catch (_: Exception) {} }
    }

    private fun applyLoud() {
        try {
            val gainMb = if (isLimiterEnabled) ((limiterThresholdDb * 100).toInt() + 100) else (if (isAutoGainEnabled) 200 else 0)
            systemLoud?.setTargetGain(gainMb.coerceIn(-1500, 600))
            systemLoud?.enabled = isGlobalAudioEnabled && (isLimiterEnabled || isAutoGainEnabled)
        } catch (_: Exception) {}
    }

    fun releaseResources() {
        scope.cancel()
        try { systemEq?.release() } catch (_: Exception) {}
        try { systemBass?.release() } catch (_: Exception) {}
        try { systemVirt?.release() } catch (_: Exception) {}
        try { systemLoud?.release() } catch (_: Exception) {}
        systemEq = null; systemBass = null; systemVirt = null; systemLoud = null
        bandMapCache = null; attachedSessionId = 0
    }
}
