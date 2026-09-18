package com.sjbz.aimp

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sjbz.aimp.audio.SjbzDspProcessor
import kotlin.math.abs

data class AppProfile(val name: String = "Default", val bandGains: FloatArray = FloatArray(32){0f})

class GlobalAudioSessionManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "SJBZ_Manager"
        @Volatile private var instance: GlobalAudioSessionManager? = null
        fun getInstance(c: Context): GlobalAudioSessionManager {
            return instance?: synchronized(this) {
                instance?: GlobalAudioSessionManager(c.applicationContext).also { instance = it }
            }
        }
        val BAND_FREQS = SjbzDspProcessor.BAND_FREQS
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    var softwareDsp: SjbzDspProcessor = SjbzDspProcessor()
    var dspProcessor: SjbzDspProcessor get() = softwareDsp; set(v){ softwareDsp = v }

    // PUBLICOS para MainActivity
    var bandGains = FloatArray(32){0f}
    var globalAudioEnabled = true
    var globalGain = 0f
    var preampGain = 0f
    var bassBoostDb = 0f
    var bassFreqHz = 85f
    var virtualizerStr = 0
    var ats2835pMode = true
    var limiterThresholdDb = -1f
    var autoGainTargetLufs = -14f
    var limiterEnabled = true
    var autoGainEnabled = false
    private var currentSessionId = 0
    private val bandMapCache = mutableMapOf<Int,Int>()

    var allProfiles: List<AppProfile> = emptyList()
    var currentProfile: AppProfile? = null
    var currentProfileName: String = "Default"

    // Props sin () para if(manager.isGlobalAudioEnabled)
    val isGlobalAudioEnabled: Boolean get() = synchronized(lock){ globalAudioEnabled }
    val isLimiterEnabled: Boolean get() = synchronized(lock){ limiterEnabled }
    val isAutoGainEnabled: Boolean get() = synchronized(lock){ autoGainEnabled }
    val isAts2835pEnabled: Boolean get() = synchronized(lock){ ats2835pMode }
    val isMdrcEnabled: Boolean get() = softwareDsp.isMdrcEnabled()
    val virtualizerStrength: Int get() = synchronized(lock){ virtualizerStr }
    val globalGainDb: Float get() = synchronized(lock){ globalGain }

    fun initialize(){}
    fun onProfileChangedListener(listener: ()->Unit){}
    fun onProfileChangedListener(listener: (AppProfile)->Unit){}
    fun onActiveSessionsChangedListener(listener: (List<Int>)->Unit){}
    fun onActiveSessionsChangedListener(listener: (List<Int>, String?)->Unit){}

    fun openSession(sessionId: Int, packageName: String? = null){
        if(sessionId==0||sessionId==-1) return
        mainHandler.post{ initForSession(sessionId) }
    }
    fun openSession(sessionId: Short){ openSession(sessionId.toInt(), null) }

    private fun initForSession(sessionId: Int){
        synchronized(lock){
            try{
                releaseFx()
                currentSessionId = sessionId
                equalizer = Equalizer(0, sessionId).apply{ enabled = globalAudioEnabled }
                try{ bassBoost = BassBoost(0, sessionId).apply{ enabled = bassBoostDb!=0f } }catch(_:Exception){}
                try{ virtualizer = Virtualizer(0, sessionId).apply{ enabled = virtualizerStr>0 } }catch(_:Exception){}
                bandMapCache.clear()
                equalizer?.let{ eq ->
                    for(i in 0 until eq.numberOfBands.toInt()){
                        val center = eq.getCenterFreq(i.toShort())/1000f
                        var best=15; var diff=Float.MAX_VALUE
                        for(j in BAND_FREQS.indices){ val d=abs(BAND_FREQS[j]-center); if(d<diff){diff=d; best=j} }
                        bandMapCache[i]=best
                    }
                }
                applyAll()
            }catch(e:Exception){ Log.e(TAG,"init fail",e) }
        }
    }
    fun closeSession(id: Int){ mainHandler.post{ synchronized(lock){ if(currentSessionId==id) releaseFx() } } }
    private fun releaseFx(){
        try{ equalizer?.enabled=false; equalizer?.release() }catch(_:Exception){}
        try{ bassBoost?.enabled=false; bassBoost?.release() }catch(_:Exception){}
        try{ virtualizer?.enabled=false; virtualizer?.release() }catch(_:Exception){}
        equalizer=null; bassBoost=null; virtualizer=null
    }

    fun isGlobalAudioEnabled(): Boolean = isGlobalAudioEnabled
    fun setGlobalAudioEnabled(e: Boolean){
        synchronized(lock){
            globalAudioEnabled=e
            softwareDsp.setMasterEnabled(e)
            softwareDsp.setGlobalBypass(!e)
            try{ equalizer?.enabled=e }catch(_:Exception){}
        }
    }

    fun setPreampGain(g: Float){ synchronized(lock){ preampGain=g.coerceIn(-12f,12f); softwareDsp.setPreamp(preampGain) } }
    fun getPreampGain(): Float = synchronized(lock){ preampGain }
    fun setPreamp(p: Float)=setPreampGain(p)
    fun getPreamp(): Float = getPreampGain()

    fun setToneBass(g: Float){ synchronized(lock){ softwareDsp.setToneBass(g.coerceIn(-12f,12f)) } }
    fun setToneMid(g: Float){ synchronized(lock){ softwareDsp.setToneMid(g.coerceIn(-12f,12f)) } }
    fun setToneTreble(g: Float){ synchronized(lock){ softwareDsp.setToneTreble(g.coerceIn(-12f,12f)) } }
    fun setToneBassDb(v: Float)=setToneBass(v)
    fun setToneMidDb(v: Float)=setToneMid(v)
    fun setToneTrebleDb(v: Float)=setToneTreble(v)

    fun setBassBoost(db: Float){ synchronized(lock){ bassBoostDb=db.coerceIn(0f,12f); softwareDsp.setBassBoost(true, bassFreqHz, bassBoostDb) } }
    fun setBassBoost(db: Int){ setBassBoost(db.toFloat()) }
    fun setBassBoost(enabled: Boolean, db: Float){ setBassBoost(if(enabled) db else 0f) }
    fun setBassBoost(enabled: Boolean, db: Int){ setBassBoost(enabled, db.toFloat()) }
    fun setBassBoost(freq: Float, db: Float){ synchronized(lock){ bassFreqHz=freq; bassBoostDb=db.coerceIn(0f,12f); softwareDsp.setBassBoost(true, freq, bassBoostDb) } }
    fun setBassBoostDb(v: Float)=setBassBoost(v)
    fun getBassBoostDb()=synchronized(lock){ bassBoostDb }
    fun setBassFreqHz(f: Float){ synchronized(lock){ bassFreqHz=f; softwareDsp.setBassBoost(true, f, bassBoostDb) } }
    fun getBassFreqHz()=synchronized(lock){ bassFreqHz }

    fun setAts2835pEmulation(e: Boolean){ setAts2835pEnabled(e) }
    fun setAts2835pEmulation(e: Boolean, amount: Float){ setAts2835pEnabled(e); softwareDsp.setEmulationAmount(amount.coerceIn(0f,1f)) }
    fun setAts2835pEmulation(e: Boolean, amount: Int){ setAts2835pEmulation(e, amount/100f) }
    fun getAts2835pEmulation(): Boolean = isAts2835pEnabled()
    fun setAts2835pEnabled(e: Boolean){ synchronized(lock){ ats2835pMode=e; softwareDsp.setAts2835pMode(e) } }
    fun isAts2835pEnabled(): Boolean = synchronized(lock){ ats2835pMode }

    fun setMdrcEnabled(e: Boolean){ synchronized(lock){ softwareDsp.setMdrcEnabled(e) } }
    fun isMdrcEnabled()=softwareDsp.isMdrcEnabled()
    fun setMdrcGain(index: Int, v: Float){ softwareDsp.setMdrcGain(index, v) }
    fun getMdrcThreshold()=softwareDsp.getMdrcThreshold()
    fun getMdrcRatio()=softwareDsp.getMdrcRatio()
    fun getMdrcDynamics()=softwareDsp.getMdrcGains()
    fun setMdrcDynamics(g: FloatArray){ softwareDsp.setMdrcGains(g) }
    fun setMdrcDynamics(t: Float, r: Float, a: Float, rel: Float, k: Float){ softwareDsp.setMdrcDynamics(t, r) }
    fun setMdrcThreshold(v: Float){ softwareDsp.setMdrcDynamics(v.coerceIn(-30f,0f), getMdrcRatio()) }
    fun setMdrcRatio(v: Float){ softwareDsp.setMdrcDynamics(getMdrcThreshold(), v.coerceIn(1f,20f)) }

    fun setBandGain(index: Int, gain: Float){
        val g = if(gain.isNaN()) 0f else gain.coerceIn(-12f,12f)
        synchronized(lock){
            if(index in bandGains.indices){
                bandGains[index]=g
                softwareDsp.setBandGain(index,g)
                for((sys,our) in bandMapCache) if(our==index){ try{ equalizer?.setBandLevel(sys.toShort(), (g*100).toInt().toShort()) }catch(_:Exception){} }
            }
        }
    }
    fun getBandGains()=synchronized(lock){ bandGains.copyOf() }
    fun setBandQ(index: Int, q: Float){}

    fun setVirtualizer(s: Int){ setVirtualizerStrength(s) }
    fun setVirtualizer(enabled: Boolean, s: Int){ setVirtualizerStrength(if(enabled) s else 0) }
    fun setVirtualizerStrength(s: Int){
        synchronized(lock){
            virtualizerStr=s.coerceIn(0,100)
            softwareDsp.setVirtualizer(virtualizerStr>0, virtualizerStr/100f)
            try{ virtualizer?.setStrength((s*10).toShort()) }catch(_:Exception){}
        }
    }
    fun getVirtualizerStrength()=synchronized(lock){ virtualizerStr }

    fun setGlobalGain(g: Float){ synchronized(lock){ globalGain=g.coerceIn(-12f,12f); softwareDsp.setGlobalGain(globalGain) } }
    fun getGlobalGain()=synchronized(lock){ globalGain }

    fun setLimiter(e: Boolean){ synchronized(lock){ limiterEnabled=e; softwareDsp.setLimiterEnabled(e) } }
    fun setLimiter(e: Boolean, threshold: Float){ synchronized(lock){ limiterEnabled=e; limiterThresholdDb=threshold; softwareDsp.setLimiter(true, threshold) } }
    fun setLimiter(e: Boolean, threshold: Int){ setLimiter(e, threshold.toFloat()) }
    fun isLimiterEnabled(): Boolean = synchronized(lock){ limiterEnabled }
    fun setLimiterEnabled(e: Boolean)=setLimiter(e)
    fun setLimiterThresholdDb(v: Float){ synchronized(lock){ limiterThresholdDb=v; softwareDsp.setLimiterThresholdDb(v) } }

    fun setAutoGain(e: Boolean){ synchronized(lock){ autoGainEnabled=e; softwareDsp.setAutoGainEnabled(e) } }
    fun setAutoGain(e: Boolean, target: Float){ synchronized(lock){ autoGainEnabled=e; autoGainTargetLufs=target; softwareDsp.setAutoGainEnabled(e) } }
    fun setAutoGain(e: Boolean, target: Int){ setAutoGain(e, target.toFloat()) }
    fun setAutoGainEnabled(e: Boolean)=setAutoGain(e)
    fun isAutoGainEnabled(): Boolean = synchronized(lock){ autoGainEnabled }

    fun getSystemVolume(): Int { val am=appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; return am.getStreamVolume(AudioManager.STREAM_MUSIC) }
    fun getMaxSystemVolume(): Int { val am=appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    fun setSystemVolume(v: Int){ val am=appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }

    fun saveCurrentAsProfile(name: String){ val p=AppProfile(name=name, bandGains=bandGains.copyOf()); allProfiles = allProfiles + p; currentProfile=p; currentProfileName=name }
    fun applyProfileInMemory(profile: AppProfile){ synchronized(lock){ bandGains=profile.bandGains.copyOf(); applyAll() } }
    fun applyProfileInMemory(profile: AppProfile, appName: String?){ applyProfileInMemory(profile) }
    fun applyProfileInMemory(profile: AppProfile, id: Int){ applyProfileInMemory(profile) }
    fun applyProfileInMemory(profile: AppProfile, id: Int, appName: String?){ applyProfileInMemory(profile) }

    private fun applyAll(){ for(i in bandGains.indices) softwareDsp.setBandGain(i, bandGains[i]) }
    fun release(){ mainHandler.post{ synchronized(lock){ releaseFx(); softwareDsp.resetFilterStates() } } }
}
