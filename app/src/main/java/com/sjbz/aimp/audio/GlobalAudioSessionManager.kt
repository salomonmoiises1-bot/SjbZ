package com.sjbz.aimp

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs

class GlobalAudioSessionManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "SJBZ_Manager"
        @Volatile private var instance: GlobalAudioSessionManager? = null
        fun getInstance(c: Context): GlobalAudioSessionManager {
            return instance?: synchronized(this) {
                instance?: GlobalAudioSessionManager(c.applicationContext).also { instance = it }
            }
        }
        val BAND_FREQS = floatArrayOf(16f,20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,10000f,12500f,16000f,20000f)
    }

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    var softwareDsp: SjbzDspProcessor? = SjbzDspProcessor()
    var dspProcessor: SjbzDspProcessor? get() = softwareDsp; set(v){ softwareDsp = v }

    // ESTADO REAL
    private var bandGains = FloatArray(32){0f}
    private var mdrcGains = FloatArray(5){ -10f; 2f; 20f; 100f; 6f }
    private var globalAudioEnabled = true
    private var globalGain = 0f
    private var preampGain = 0f
    private var toneBass = 0f; private var toneMid = 0f; private var toneTreble = 0f
    private var bassBoostDb = 0f; private var bassFreqHz = 80f
    private var virtualizerStr = 0
    private var ats2835pMode = false
    private var mdrcEnabled = true
    private var limiterEnabled = true; private var limiterThreshold = -1f
    private var autoGainEnabled = false; private var autoGainTarget = -14f
    private var currentSessionId = 0
    private val bandMapCache = mutableMapOf<Int,Int>()
    private val bandQ = FloatArray(32){ 2.5f }

    // PROFILES - para que compile MainActivity
    var allProfiles: List<AppProfile> = emptyList()
    var currentProfile: AppProfile? = null
    var currentProfileName: String = "Default"

    fun initialize(){}
    fun onProfileChangedListener(listener: ()->Unit){}
    fun onActiveSessionsChangedListener(listener: (List<Int>)->Unit){}

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

    // ==== TODOS LOS METODOS QUE TE FALTAN EN EL LOG - AHORA FUNCIONALES ====
    fun isGlobalAudioEnabled(): Boolean = synchronized(lock){ globalAudioEnabled }
    fun setGlobalAudioEnabled(e: Boolean){ synchronized(lock){ globalAudioEnabled=e; try{ equalizer?.enabled=e }catch(_:Exception){}; softwareDsp?.setBypass(!e) } }

    fun setPreampGain(g: Float){ synchronized(lock){ preampGain=g.coerceIn(-12f,12f); softwareDsp?.setGlobalGain(globalGain+preampGain+ toneBass*0.1f) } }
    fun getPreampGain(): Float = synchronized(lock){ preampGain }
    fun setPreamp(p: Float)=setPreampGain(p)
    fun getPreamp(): Float = getPreampGain()

    fun setToneBass(g: Float){ synchronized(lock){ toneBass=g.coerceIn(-12f,12f); softwareDsp?.setToneBass(g); applyTones() } }
    fun setToneMid(g: Float){ synchronized(lock){ toneMid=g.coerceIn(-12f,12f); softwareDsp?.setToneMid(g); applyTones() } }
    fun setToneTreble(g: Float){ synchronized(lock){ toneTreble=g.coerceIn(-12f,12f); softwareDsp?.setToneTreble(g); applyTones() } }
    private fun applyTones(){ for(i in 0..2) setBandGain(i, bandGains[i]+toneBass*0.3f); for(i in 28..31) setBandGain(i, bandGains[i]+toneTreble*0.3f) }

    fun setBassBoost(db: Float){ synchronized(lock){ bassBoostDb=db.coerceIn(0f,20f); try{ bassBoost?.setStrength((bassBoostDb*50).toInt().coerceIn(0,1000).toShort()) }catch(_:Exception){}; softwareDsp?.setBass(bassBoostDb) } }
    fun setBassBoost(db: Int){ setBassBoost(db.toFloat()) }
    fun setBassBoostDb(v: Float)=setBassBoost(v)
    fun getBassBoostDb()=bassBoostDb
    fun setBassFreqHz(f: Float){ bassFreqHz=f }
    fun getBassFreqHz()=bassFreqHz
    val bassBoostDbProp get()=bassBoostDb; val bassFreqHzProp get()=bassFreqHz
    val bassBoostDbValue: Float get()=bassBoostDb
    val bassFreqHzValue: Float get()=bassFreqHz
    var bassBoostDbField: Float get()=bassBoostDb; set(v){ setBassBoost(v) }
    var bassFreqHzField: Float get()=bassFreqHz; set(v){ bassFreqHz=v }
    fun setToneBassDb(v: Float)=setToneBass(v); fun setToneMidDb(v: Float)=setToneMid(v); fun setToneTrebleDb(v: Float)=setToneTreble(v)

    fun setAts2835pEmulation(e: Boolean){ setAts2835pEnabled(e) }
    fun getAts2835pEmulation(): Boolean = isAts2835pEnabled()
    fun setAts2835pEnabled(e: Boolean){ synchronized(lock){ ats2835pMode=e; softwareDsp?.setAts2835pMode(e); applyAll() } }
    fun isAts2835pEnabled()=synchronized(lock){ ats2835pMode }

    fun setMdrcEnabled(e: Boolean){ synchronized(lock){ mdrcEnabled=e; softwareDsp?.setMdrcEnabled(e) } }
    fun isMdrcEnabled()=synchronized(lock){ mdrcEnabled }
    fun setMdrcGain(index: Int, v: Float){ synchronized(lock){ if(index in mdrcGains.indices){ mdrcGains[index]=v; softwareDsp?.setMdrcGains(mdrcGains.copyOf()) } } }
    fun getMdrcThreshold()=synchronized(lock){ mdrcGains[0] }
    fun getMdrcRatio()=synchronized(lock){ mdrcGains[1] }
    fun getMdrcAttack()=synchronized(lock){ mdrcGains[2] }
    fun getMdrcRelease()=synchronized(lock){ mdrcGains[3] }
    fun getMdrcKnee()=synchronized(lock){ mdrcGains[4] }
    fun getMdrcDynamics()=synchronized(lock){ mdrcGains.copyOf() }
    fun setMdrcDynamics(g: FloatArray)=setMdrcGains(g)
    fun setMdrcDynamics(t: Float, r: Float, a: Float, rel: Float, k: Float){ setMdrcGains(floatArrayOf(t,r,a,rel,k)) }
    fun setMdrcThreshold(v: Float)=setMdrcGain(0, v.coerceIn(-30f,0f))
    fun setMdrcRatio(v: Float)=setMdrcGain(1, v.coerceIn(1f,20f))
    fun setMdrcAttack(v: Float)=setMdrcGain(2, v.coerceIn(1f,200f))
    fun setMdrcRelease(v: Float)=setMdrcGain(3, v.coerceIn(10f,1000f))
    fun setMdrcKnee(v: Float)=setMdrcGain(4, v.coerceIn(0f,12f))

    fun setBandGain(index: Int, gain: Float){
        val g = if(gain.isNaN()) 0f else gain.coerceIn(-12f,12f)
        synchronized(lock){
            if(index in bandGains.indices){ bandGains[index]=g; softwareDsp?.setBandGain(index,g)
                for((sys,our) in bandMapCache) if(our==index){ try{ equalizer?.setBandLevel(sys.toShort(), (g*100).toInt().toShort()) }catch(_:Exception){} }
            }
        }
    }
    fun getBandGains()=synchronized(lock){ bandGains.copyOf() }
    var bandGainsPublic: FloatArray get()=getBandGains(); set(v){ for(i in v.indices) setBandGain(i,v[i]) }
    fun setBandQ(index: Int, q: Float){ if(index in bandQ.indices) bandQ[index]=q.coerceIn(0.5f,5f) }

    fun setVirtualizer(s: Int){ setVirtualizerStrength(s) }
    fun setVirtualizerStrength(s: Int){ synchronized(lock){ virtualizerStr=s.coerceIn(0,100); try{ virtualizer?.setStrength((s*10).toShort()) }catch(_:Exception){}; softwareDsp?.setVirtualizer(s) } }
    fun getVirtualizerStrength()=synchronized(lock){ virtualizerStr }
    val virtualizerStrength get()=virtualizerStr; val virtualizerStrengthValue get()=virtualizerStr

    fun setGlobalGain(g: Float){ synchronized(lock){ globalGain=g.coerceIn(-12f,12f); softwareDsp?.setGlobalGain(globalGain+preampGain) } }
    fun getGlobalGain()=synchronized(lock){ globalGain }
    val globalGainDb get()=globalGain; var globalGainDbField: Float get()=globalGain; set(v){ setGlobalGain(v) }

    fun setLimiter(e: Boolean){ limiterEnabled=e; softwareDsp?.setLimiterEnabled(e) }
    fun isLimiterEnabled()=limiterEnabled; fun setLimiterEnabled(e: Boolean)=setLimiter(e)
    fun setLimiterThresholdDb(v: Float){ limiterThreshold=v; softwareDsp?.setLimiterThreshold(v) }
    val limiterThresholdDb get()=limiterThreshold

    fun setAutoGain(e: Boolean){ autoGainEnabled=e; softwareDsp?.setAutoGainEnabled(e) }
    fun isAutoGainEnabled()=autoGainEnabled; fun setAutoGainEnabled(e: Boolean)=setAutoGain(e)
    fun setAutoGainTargetLufs(v: Float){ autoGainTarget=v }
    val autoGainTargetLufs get()=autoGainTarget

    fun getSystemVolume(): Int { val am=appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; return am.getStreamVolume(AudioManager.STREAM_MUSIC) }
    fun getMaxSystemVolume(): Int { val am=appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    fun setSystemVolume(v: Int){ val am=appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager; am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }

    fun saveCurrentAsProfile(name: String){ val p=AppProfile(name=name, bandGains=bandGains.copyOf()); allProfiles = allProfiles + p; currentProfile=p }
    fun applyProfileInMemory(profile: AppProfile){ synchronized(lock){ bandGains=profile.bandGains.copyOf(); applyAll() } }

    private fun applyAll(){ for(i in bandGains.indices) softwareDsp?.setBandGain(i, bandGains[i]); softwareDsp?.setMdrcGains(mdrcGains.copyOf()); softwareDsp?.setGlobalGain(globalGain+preampGain); softwareDsp?.setAts2835pMode(ats2835pMode) }
    fun release(){ mainHandler.post{ synchronized(lock){ releaseFx(); try{ softwareDsp?.release() }catch(_:Exception){}; softwareDsp=null } } }
}
