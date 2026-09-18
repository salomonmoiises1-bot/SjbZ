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

        @JvmStatic fun getInstance(c: Context) = inst?: synchronized(this) {
            inst?: GlobalAudioSessionManager(c.applicationContext).also { inst = it }
        }

        @JvmStatic fun getDspProcessorInstance(): SjbzDspProcessor = dsp?: synchronized(this) {
            dsp?: SjbzDspProcessor(getOutputSampleRate(inst?.appContext)).also { dsp = it }
        }

        // --- ESTO FALTABA Y ROMPIA AudioEffectSessionReceiver ---
        @JvmStatic fun openSession(sessionId: Int) {
            try {
                inst?.initForSession(sessionId)
                Log.i(TAG, "openSession $sessionId")
            } catch (e: Exception) { Log.e(TAG, "openSession failed", e) }
        }
        @JvmStatic fun closeSession(sessionId: Int) {
            Log.i(TAG, "closeSession $sessionId")
        }
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

    val dspProcessor: SjbzDspProcessor = getDspProcessorInstance()
    @Volatile var isGlobalAudioEnabled = false
    var currentProfile: AppProfile = AppProfile.createDefaultProfiles().first()
    var allProfiles = AppProfile.createDefaultProfiles().toMutableList()
    @Volatile var globalGainDb = 0f; @Volatile var preampDb = 0f
    val bandGains = FloatArray(32); val bandQs = FloatArray(32){1.414f}
    @Volatile var toneBassDb = 0f; @Volatile var toneMidDb = 0f; @Volatile var toneTrebleDb = 0f
    @Volatile var isBassBoostEnabled = true; @Volatile var bassBoostDb = 4f; @Volatile var bassFreqHz = 85f
    @Volatile var isVirtualizerEnabled = false; @Volatile var virtualizerStrength = 0f
    @Volatile var isLimiterEnabled = true; @Volatile var limiterThresholdDb = -1f
    @Volatile var isAutoGainEnabled = true; @Volatile var autoGainTargetLufs = -14f
    @Volatile var mdrcThresholdDb = -18f; @Volatile var mdrcRatio = 2.5f; val mdrcGains = FloatArray(5)

    private var systemEq: Equalizer? = null; private var systemBass: BassBoost? = null
    private var systemVirt: Virtualizer? = null; private var systemLoud: LoudnessEnhancer? = null
    private var bandMapCache: IntArray? = null
    var onProfileChangedListener: ((AppProfile)->Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>)->Unit)? = null

    init { scope.launch { load() } }

    // NUEVO: inicia el EQ del sistema en una session de Spotify/YouTube
    fun initForSession(newSessionId: Int) {
        if (newSessionId == 0) return
        try {
            if (systemEq == null || systemEq?.audioSessionId!= newSessionId) {
                try { systemEq?.release() } catch (_: Exception) {}
                systemEq = Equalizer(0, newSessionId).apply { enabled = true }
                bandMapCache = null
                buildCache()
            }
            if (systemBass == null || systemBass?.audioSessionId!= newSessionId) {
                try { systemBass?.release() } catch (_: Exception) {}
                systemBass = BassBoost(0, newSessionId).apply { enabled = isBassBoostEnabled }
            }
            if (systemVirt == null || systemVirt?.audioSessionId!= newSessionId) {
                try { systemVirt?.release() } catch (_: Exception) {}
                systemVirt = Virtualizer(0, newSessionId).apply { enabled = isVirtualizerEnabled }
            }
            if (systemLoud == null) {
                try { systemLoud?.release() } catch (_: Exception) {}
                systemLoud = LoudnessEnhancer(newSessionId).apply { enabled = isLimiterEnabled }
            }
            applyAll()
        } catch (e: Exception) { Log.e(TAG, "initForSession $newSessionId failed: ${e.message}") }
    }

    private suspend fun load(){
        try{
            val profiles = dataStore.loadProfiles(); if(profiles.isNotEmpty()){ allProfiles.clear(); allProfiles.addAll(profiles) }
            val (gains, _) = dataStore.loadBands(); for(i in bandGains.indices) if(i<gains.size) bandGains[i]=gains[i]
            applyProfileInMemory(allProfiles.first(), false)
        }catch(e:Exception){ Log.e(TAG, e.message.toString()) }
    }

    fun getSystemVolume()=try{ audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}catch(_:Exception){0}
    fun getMaxSystemVolume()=try{ audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}catch(_:Exception){15}
    fun setSystemVolume(v: Int){ try{ audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, getMaxSystemVolume()), 0)}catch(_:Exception){} }

    fun setGlobalAudioEnabled(enabled: Boolean){
        if(isGlobalAudioEnabled==enabled) return
        isGlobalAudioEnabled=enabled
        scope.launch { dataStore.saveGlobalEnabled(enabled) }
        checkBypass(); applyAll()
    }
    fun checkBypass(): Boolean {
        val active = isGlobalAudioEnabled
        systemEq?.enabled = active
        systemBass?.enabled = active && isBassBoostEnabled
        systemVirt?.enabled = active && isVirtualizerEnabled
        systemLoud?.enabled = active && (isLimiterEnabled || isAutoGainEnabled)
        return active
    }

    fun setGlobalGain(db: Float){ globalGainDb=db; dspProcessor.setMasterGain(db); applyEq() }
    fun setPreampGain(db: Float){ preampDb=db.coerceIn(-12f,12f); dspProcessor.setPreamp(preampDb); applyEq() }
    fun setBandGain(i: Int, db: Float){ if(i in 0..31){ bandGains[i]=db.coerceIn(-12f,12f); dspProcessor.setBandLevel(i, bandGains[i]); applyEq(); scope.launch{ dataStore.saveBands(bandGains, bandQs) } } }
    fun setBandQ(i: Int, q: Float){ if(i in 0..31) bandQs[i]=q }
    fun setToneBass(db: Float){ toneBassDb=db; dspProcessor.setToneBass(db); applyEq() }
    fun setToneMid(db: Float){ toneMidDb=db; dspProcessor.setToneMid(db); applyEq() }
    fun setToneTreble(db: Float){ toneTrebleDb=db; dspProcessor.setToneTreble(db); applyEq() }
    fun setBassBoost(enabled: Boolean, gainDb: Float, freq: Float=85f){
        isBassBoostEnabled=enabled; bassBoostDb=gainDb.coerceIn(0f,12f); bassFreqHz=freq
        dspProcessor.setBassBoost(enabled, freq, bassBoostDb)
        applyEq(); applyBass()
    }
    fun setVirtualizer(progress: Int){ setVirtualizer(progress>0, progress/100f) }
    fun setVirtualizer(en: Boolean, strength: Float){ isVirtualizerEnabled=en; virtualizerStrength=strength; applyVirt() }
    fun setLimiter(en: Boolean, thresh: Float=-1f){ isLimiterEnabled=en; limiterThresholdDb=thresh; applyLoud() }
    fun setAutoGain(en: Boolean, target: Float){ isAutoGainEnabled=en; autoGainTargetLufs=target; applyLoud() }
    fun setMdrcEnabled(e: Boolean){ dspProcessor.setMdrcEnabled(e) }
    fun setMdrcDynamics(t: Float, r: Float){ dspProcessor.setMdrcDynamics(t,r) }
    fun setMdrcGain(i: Int, g: Float){ dspProcessor.setMdrcBandGain(i,g) }
    fun setAts2835pEmulation(e: Boolean, a: Float, b: Boolean){ dspProcessor.setEmulationEnabled(e); dspProcessor.setEmulationAmount(a); dspProcessor.setBluetoothAutoBypass(b) }
    fun saveCurrentAsProfile(name: String): AppProfile {
        val p = AppProfile(id="custom_${System.currentTimeMillis()}", packageName="", appName=name, presetName=name, globalGainDb=globalGainDb, bandGains=bandGains.toList(), bandQs=bandQs.toList(), bassBoostDb=bassBoostDb, bassFreqHz=bassFreqHz, virtualizerStrength=(virtualizerStrength*100).toInt(), limiterEnabled=isLimiterEnabled, limiterThresholdDb=limiterThresholdDb, autoGainEnabled=isAutoGainEnabled, autoGainTargetLufs=autoGainTargetLufs, mdrcEnabled=true, mdrcGains=mdrcGains.toList(), ats2835pEmuEnabled=true)
        allProfiles.add(p); currentProfile=p; scope.launch{ dataStore.saveProfiles(allProfiles) }; return p
    }
    fun applyProfileInMemory(p: AppProfile, save: Boolean=true){ currentProfile=p; globalGainDb=p.globalGainDb; for(i in bandGains.indices) if(i<p.bandGains.size) bandGains[i]=p.bandGains[i]; bassBoostDb=p.bassBoostDb; bassFreqHz=p.bassFreqHz; virtualizerStrength=p.virtualizerStrength/100f; applyAll() }

    fun attachSystemEqualizer(eq: Equalizer){ systemEq=eq; bandMapCache=null; buildCache() }
    fun attachSystemBassBoost(b: BassBoost){ systemBass=b }
    fun attachSystemVirtualizer(v: Virtualizer){ systemVirt=v }
    fun attachSystemLoudness(l: LoudnessEnhancer){ systemLoud=l }
    fun reapplyAllParams(){ applyAll() }

    private fun buildCache(){
        val eq=systemEq?:return; val num=eq.numberOfBands; val cache=IntArray(num)
        for(i in 0 until num){ val cf=eq.getCenterFreq(i.toShort())/1000; var closest=15; var md=Int.MAX_VALUE; for(j in MY_FREQS.indices){ val d=abs(MY_FREQS[j]-cf); if(d<md){ md=d; closest=j } }; cache[i]=closest }
        bandMapCache=cache
    }
    fun applyAll(){ applyEq(); applyBass(); applyVirt(); applyLoud(); checkBypass() }
    fun applyEq(){
        if(systemEq==null){ try{ systemEq=Equalizer(0,0).apply{ enabled=true }; buildCache() }catch(_:Exception){ return } }
        val eq=systemEq?:return; val cache=bandMapCache?:return
        try{
            for(i in 0 until eq.numberOfBands){
                val idx=cache[i];
                var total=bandGains[idx]+globalGainDb+preampDb
                val cf=eq.getCenterFreq(i.toShort())/1000;
                total+= when{ cf<250->toneBassDb; cf<=4000->toneMidDb; else->toneTrebleDb }
                // FIX: Type mismatch Short vs Int - ahora usamos Short correcto
                eq.setBandLevel(i.toShort(), total.coerceIn(-15f,15f).let{ (it*100).toInt().toShort() })
            };
            eq.enabled=isGlobalAudioEnabled
        }catch(e:Exception){ Log.e(TAG,"eq ${e.message}") }
    }
    private fun applyBass(){ try{ systemBass?.setStrength((bassBoostDb*1000/12f).toInt().coerceIn(0,1000).toShort()); systemBass?.enabled=isGlobalAudioEnabled && isBassBoostEnabled }catch(_:Exception){} }
    private fun applyVirt(){
        try{
            if(systemVirt==null) return
            if(!systemVirt!!.strengthSupported) { systemVirt!!.enabled=false; return }
            systemVirt!!.setStrength((virtualizerStrength*1000).toInt().coerceIn(0,1000).toShort())
            systemVirt!!.enabled=isGlobalAudioEnabled && isVirtualizerEnabled
        }catch(_:Exception){ systemVirt?.enabled=false }
    }
    private fun applyLoud(){
        try{
            val gainMb = if(isLimiterEnabled) ((limiterThresholdDb*100).toInt()+100) else (if(isAutoGainEnabled) 200 else 0)
            systemLoud?.setTargetGain(gainMb.coerceIn(-1500, 600))
            systemLoud?.enabled=isGlobalAudioEnabled && (isLimiterEnabled || isAutoGainEnabled)
        }catch(_:Exception){}
    }
    fun releaseResources(){
        scope.cancel()
        try{ systemEq?.release() }catch(_:Exception){}; try{ systemBass?.release() }catch(_:Exception){}; try{ systemVirt?.release() }catch(_:Exception){}; try{ systemLoud?.release() }catch(_:Exception){}
    }
}
