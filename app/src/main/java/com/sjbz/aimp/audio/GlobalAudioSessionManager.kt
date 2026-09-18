package com.sjbz.aimp.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
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
        @JvmStatic fun getDspProcessorInstance(): SjbzDspProcessor {
            return globalDspProcessor?: synchronized(this) {
                globalDspProcessor?: SjbzDspProcessor(48000.0f).also { globalDspProcessor = it }
            }
        }
        @JvmStatic fun getDspProcessor(): SjbzDspProcessor = getDspProcessorInstance()
        private val MY_32_BAND_FREQS = intArrayOf(16,20,25,31,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dataStore = AudioSettingsDataStore(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @get:JvmName("getDspProcessorProperty") val dspProcessor: SjbzDspProcessor = getDspProcessorInstance()
    @Volatile var isGlobalAudioEnabled: Boolean = false
    @Volatile var isMdrcEnabled: Boolean = true
    @Volatile var isLimiterEnabled: Boolean = true
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

    // EFECTOS DE SISTEMA - AHORA TODO ES REAL
    private var systemEq: Equalizer? = null
    private var systemBass: BassBoost? = null
    private var systemVirtualizer: Virtualizer? = null
    private var systemLoudness: LoudnessEnhancer? = null

    var onSystemVolumeChangedListener: ((Int, Int) -> Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>) -> Unit)? = null
    var onProfileChangedListener: ((AppProfile) -> Unit)? = null

    init { scope.launch { loadPersistedSettings() }; syncAllParamsToDsp() }

    private suspend fun loadPersistedSettings() {
        try {
            val loadedProfiles = dataStore.loadProfiles()
            if (loadedProfiles.isNotEmpty()) { allProfiles.clear(); allProfiles.addAll(loadedProfiles) }
            val (savedGains, savedQs) = dataStore.loadBands()
            for (i in bandGains.indices) if (i < savedGains.size) bandGains[i] = savedGains[i]
            for (i in bandQs.indices) if (i < savedQs.size) bandQs[i] = savedQs[i]
            val currentId = dataStore.loadCurrentProfileId()
            val found = allProfiles.find { it.id == currentId }?: allProfiles.firstOrNull()
            if (found!= null) applyProfileInMemory(found, false)
            syncAllParamsToDsp()
        } catch (e: Exception) { Log.e(TAG, "load: ${e.message}") }
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

    fun setGlobalAudioEnabled(enabled: Boolean) {
        if (isGlobalAudioEnabled == enabled) return
        isGlobalAudioEnabled = enabled
        checkAndApplyGlobalBypass()
        scope.launch { dataStore.saveGlobalEnabled(enabled) }
        applyAllSettingsToSystemEq()
    }

    fun checkAndApplyGlobalBypass(): Boolean {
        val globalActive = isGlobalAudioEnabled || GlobalAudioService.isServiceRunning
        dspProcessor.setGlobalBypass(!globalActive &&!dspProcessor.isMasterEnabled)
        systemEq?.enabled = globalActive
        systemBass?.enabled = globalActive && isBassBoostEnabled
        systemVirtualizer?.enabled = globalActive && isVirtualizerEnabled
        systemLoudness?.enabled = globalActive && (isLimiterEnabled || isAutoGainEnabled)
        return globalActive
    }

    fun openSession(id: Int, pkg: String? = null) {}
    fun closeSession(id: Int) {}

    fun setMasterGain(gainDb: Float) { globalGainDb = gainDb; dspProcessor.setMasterGain(gainDb); applyAllSettingsToSystemEq() }
    fun setGlobalGain(gainDb: Float) = setMasterGain(gainDb)
    fun setPreampGain(gainDb: Float) { preampDb = gainDb.coerceIn(-12f,12f); dspProcessor.setPreamp(preampDb); applyAllSettingsToSystemEq() }
    fun setBandGain(index: Int, gainDb: Float) {
        if (index in 0..31) {
            bandGains[index] = gainDb.coerceIn(-12f,12f)
            dspProcessor.setBandLevel(index, bandGains[index])
            applyAllSettingsToSystemEq()
            scope.launch { dataStore.saveBands(bandGains, bandQs) }
        }
    }
    fun setBandQ(index: Int, q: Float) { if (index in 0..31) { bandQs[index]=q; dspProcessor.setBandLevel(index, bandGains[index]) } }
    fun setToneBass(g: Float) { toneBassDb=g.coerceIn(-12f,12f); dspProcessor.setToneBass(toneBassDb); applyAllSettingsToSystemEq() }
    fun setToneMid(g: Float) { toneMidDb=g.coerceIn(-12f,12f); dspProcessor.setToneMid(toneMidDb); applyAllSettingsToSystemEq() }
    fun setToneTreble(g: Float) { toneTrebleDb=g.coerceIn(-12f,12f); dspProcessor.setToneTreble(toneTrebleDb); applyAllSettingsToSystemEq() }
    fun setBassBoost(enabled: Boolean, gainDb: Float, freqHz: Float = 85f) {
        isBassBoostEnabled=enabled; bassBoostDb=gainDb.coerceIn(0f,12f); bassFreqHz=freqHz
        dspProcessor.setBassBoost(enabled, freqHz, bassBoostDb)
        applyBassBoostToSystem()
    }
    fun setVirtualizer(enabled: Boolean, strength: Float) { isVirtualizerEnabled=enabled; virtualizerStrength=strength.coerceIn(0f,1f); dspProcessor.setVirtualizer(enabled, virtualizerStrength); applyVirtualizerToSystem() }
    fun setVirtualizer(progress: Int) { setVirtualizer(progress>0, progress/100f) }
    fun setLimiter(enabled: Boolean, thresholdDb: Float = -1f) { isLimiterEnabled=enabled; limiterThresholdDb=thresholdDb; dspProcessor.setLimiter(enabled, thresholdDb); applyLoudnessToSystem() }
    fun setAutoGain(enabled: Boolean, targetLufs: Float) { isAutoGainEnabled=enabled; autoGainTargetLufs=targetLufs; applyLoudnessToSystem() }
    fun setMdrcEnabled(e: Boolean){ isMdrcEnabled=e; dspProcessor.setMdrcEnabled(e) }
    fun setMdrcDynamics(t: Float, r: Float){ mdrcThresholdDb=t; mdrcRatio=r; dspProcessor.setMdrcDynamics(t,r) }
    fun setMdrcGain(i: Int, g: Float){ if(i in 0..4){ mdrcGains[i]=g; dspProcessor.setMdrcBandGain(i,g) } }
    fun setAts2835pEmulation(e: Boolean, a: Float=0.8f, b: Boolean=false){ ats2835pEmuEnabled=e; ats2835pEmuAmount=a; ats2835pBtBypass=b; dspProcessor.setEmulationEnabled(e); dspProcessor.setEmulationAmount(a); dspProcessor.setBluetoothAutoBypass(b) }

    fun saveCurrentAsProfile(name: String): AppProfile {
        val p = AppProfile(id="custom_${System.currentTimeMillis()}", packageName="", appName=name, presetName=name, globalGainDb=globalGainDb, bandGains=bandGains.toList(), bandQs=bandQs.toList(), bassBoostDb=bassBoostDb, bassFreqHz=bassFreqHz, virtualizerStrength=(virtualizerStrength*100).toInt(), limiterEnabled=isLimiterEnabled, limiterThresholdDb=limiterThresholdDb, autoGainEnabled=isAutoGainEnabled, autoGainTargetLufs=autoGainTargetLufs, mdrcEnabled=isMdrcEnabled, mdrcGains=mdrcGains.toList(), ats2835pEmuEnabled=ats2835pEmuEnabled)
        allProfiles.add(p); currentProfile=p
        scope.launch { dataStore.saveProfiles(allProfiles); dataStore.saveCurrentProfileId(p.id) }
        mainHandler.post { onProfileChangedListener?.invoke(p) }
        return p
    }
    fun applyProfileInMemory(profile: AppProfile, saveSelection: Boolean = true) {
        currentProfile=profile; globalGainDb=profile.globalGainDb
        for (i in bandGains.indices) if (i < profile.bandGains.size) bandGains[i]=profile.bandGains[i]
        bassBoostDb=profile.bassBoostDb; bassFreqHz=profile.bassFreqHz; isBassBoostEnabled=bassBoostDb>0.05f
        virtualizerStrength=(profile.virtualizerStrength/100f).coerceIn(0f,1f); isVirtualizerEnabled=profile.virtualizerStrength>0
        isMdrcEnabled=profile.mdrcEnabled; isLimiterEnabled=profile.limiterEnabled; limiterThresholdDb=profile.limiterThresholdDb
        isAutoGainEnabled=profile.autoGainEnabled; autoGainTargetLufs=profile.autoGainTargetLufs
        reapplyAllParams()
        if (saveSelection) scope.launch { dataStore.saveCurrentProfileId(profile.id) }
        mainHandler.post { onProfileChangedListener?.invoke(profile) }
    }
    fun switchProfileForPackage(pkg: String){ allProfiles.find{ it.packageName.equals(pkg,true)}?.let{ if(it.id!=currentProfile.id) applyProfileInMemory(it,true) } }
    fun reapplyAllParams(){ syncAllParamsToDsp(); checkAndApplyGlobalBypass(); applyAllSettingsToSystemEq(); applyBassBoostToSystem(); applyVirtualizerToSystem(); applyLoudnessToSystem() }
    private fun syncAllParamsToDsp(){
        dspProcessor.setMasterGain(globalGainDb); dspProcessor.setPreamp(preampDb)
        for (i in 0..31) dspProcessor.setBandLevel(i, bandGains[i])
        dspProcessor.setToneBass(toneBassDb); dspProcessor.setToneMid(toneMidDb); dspProcessor.setToneTreble(toneTrebleDb)
        dspProcessor.setBassBoost(isBassBoostEnabled, bassFreqHz, bassBoostDb); dspProcessor.setVirtualizer(isVirtualizerEnabled, virtualizerStrength)
        dspProcessor.setLimiter(isLimiterEnabled, limiterThresholdDb)
    }

    // ATTACHERS LLAMADOS DESDE EL SERVICE
    fun attachSystemEqualizer(eq: Equalizer){ try{ systemEq?.release() }catch(_:Exception){}; systemEq=eq }
    fun attachSystemBassBoost(bb: BassBoost){ try{ systemBass?.release() }catch(_:Exception){}; systemBass=bb }
    fun attachSystemVirtualizer(v: Virtualizer){ try{ systemVirtualizer?.release() }catch(_:Exception){}; systemVirtualizer=v }
    fun attachSystemLoudness(l: LoudnessEnhancer){ try{ systemLoudness?.release() }catch(_:Exception){}; systemLoudness=l }

    fun applyAllSettingsToSystemEq(){
        if (systemEq==null) { try{ systemEq=Equalizer(0,0).apply{ enabled=true } }catch(_:Exception){ return } }
        val eq=systemEq?:return
        try{
            for (i in 0 until eq.numberOfBands){
                val center=eq.getCenterFreq(i.toShort())/1000
                var closest=15; var minDiff=Int.MAX_VALUE
                for (j in MY_32_BAND_FREQS.indices){ val d=kotlin.math.abs(MY_32_BAND_FREQS[j]-center); if(d<minDiff){ minDiff=d; closest=j } }
                var total=bandGains[closest]+globalGainDb+preampDb
                if (center<250) total+=toneBassDb else if (center<=4000) total+=toneMidDb else total+=toneTrebleDb
                eq.setBandLevel(i.toShort(), total.coerceIn(-15f,15f).let{ (it*100).toInt().toShort() })
            }
            eq.enabled=isGlobalAudioEnabled
        }catch(e: Exception){ Log.e(TAG,"eq: ${e.message}") }
    }
    private fun applyBassBoostToSystem(){
        val bb=systemBass?:return
        try{
            val strength=(bassBoostDb*1000/12f).toInt().coerceIn(0,1000)
            bb.setStrength(strength.toShort())
            bb.enabled=isGlobalAudioEnabled && isBassBoostEnabled
        }catch(e: Exception){ Log.e(TAG,"bass: ${e.message}") }
    }
    private fun applyVirtualizerToSystem(){
        val v=systemVirtualizer?:return
        try{
            val strength=(virtualizerStrength*1000).toInt().coerceIn(0,1000)
            v.setStrength(strength.toShort())
            v.enabled=isGlobalAudioEnabled && isVirtualizerEnabled
        }catch(e: Exception){ Log.e(TAG,"virt: ${e.message}") }
    }
    private fun applyLoudnessToSystem(){
        val loud=systemLoudness?:return
        try{
            val targetGainMb = if (isAutoGainEnabled) ((autoGainTargetLufs+23f)*100).toInt() else 0
            val limiterGain = if (isLimiterEnabled) ((limiterThresholdDb+1f)*100).toInt() else targetGainMb
            loud.setTargetGain((targetGainMb+limiterGain).coerceIn(-1500,1500))
            loud.enabled=isGlobalAudioEnabled && (isLimiterEnabled || isAutoGainEnabled)
        }catch(e: Exception){ Log.e(TAG,"loud: ${e.message}") }
    }
    fun releaseResources(){
        try{ systemEq?.enabled=false; systemEq?.release() }catch(_:Exception){}
        try{ systemBass?.enabled=false; systemBass?.release() }catch(_:Exception){}
        try{ systemVirtualizer?.enabled=false; systemVirtualizer?.release() }catch(_:Exception){}
        try{ systemLoudness?.enabled=false; systemLoudness?.release() }catch(_:Exception){}
        systemEq=null; systemBass=null; systemVirtualizer=null; systemLoudness=null
        dspProcessor.resetFilterStates()
    }
}
