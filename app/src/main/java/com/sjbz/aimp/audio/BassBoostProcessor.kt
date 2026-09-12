package com.sjbz.aimp.audio

import android.media.audiofx.BassBoost
import android.util.Log

class BassBoostProcessor {
    enum class FreqMode(val hz: Int, val label: String){ SUB_60(60,"60 Hz\n(Sub)"), PUNCH_85(85,"85 Hz\n(Punch)"), MID_120(120,"120 Hz\n(Mid)") }
    
    var strength: Int = 400
    var freqMode: FreqMode = FreqMode.PUNCH_85
    private var bassBoost: BassBoost? = null
    private var sessionId: Int = 0

    fun attach(id: Int){
        if(id==0) return
        try{
            if(sessionId==id && bassBoost!=null){ apply(); return }
            release()
            sessionId=id
            bassBoost = BassBoost(0,id).apply{ enabled=true; setStrength(strength.toShort()) }
        }catch(e:Exception){ Log.e("BassBoost","attach fail ${e.message}") }
    }
    fun setPercent(p:Int){ strength=(p.coerceIn(0,100)*10).coerceIn(0,1000); apply() }
    fun setPercentDirect(p1000:Int){ strength=p1000.coerceIn(0,1000); apply() }
    fun setPercent(p: Int, dummy: Unit){ setPercent(p) } // compat
    fun setFrequency(mode: FreqMode){ freqMode=mode; apply() } // en BassBoost nativo la freq es fija, pero la guardamos para UI y para boost extra en EQ
    private fun apply(){ try{ bassBoost?.setStrength(strength.toShort()); bassBoost?.enabled=strength>0 }catch(_:Exception){} }
    fun toDisplay(): String { val db=strength*15/1000; val per=strength*100/1000; return if(strength==0) "OFF" else "+$db.0 dB (${per}%) - ${freqMode.hz}Hz" }
    fun toShortDisplay(): String { val db=strength*15/1000; val per=strength*100/1000; return if(strength==0) "OFF" else "+$db.0 dB (${per}%)" }
    fun release(){ try{bassBoost?.release()}catch(_:Exception){}; bassBoost=null; sessionId=0 }
    // Para compat con tu EqActivity viejo
    fun setStrengthPercent(p:Int)=setPercent(p)
    fun setPercent(p1000: Int, is1000: Boolean){ if(is1000) setPercentDirect(p1000) else setPercent(p1000) }
    fun setPercent(percent: Int, fromSlider: Boolean, dummy:Boolean){ setPercent(percent) }
    fun setPercent(percent: Int, unit: String){ setPercent(percent) }
    fun setStrength(s:Int){ setPercentDirect(s) }
    fun setPercent(percent1000: Float){ setPercentDirect(percent1000.toInt()) }
    fun setPercent(p:Int, mode:FreqMode){ freqMode=mode; setPercent(p) }
    fun setPercent(v:Int, ignore:Boolean, ignore2:Boolean, ignore3:Boolean){ setPercent(v) }
    // Metodo real que usa tu EqActivity
    fun setPercent(percent: Int, frequency: FreqMode?){ if(frequency!=null) freqMode=frequency; setPercent(percent) }
    fun setPercent(percent: Int, isLegacy: Int){ setPercent(percent) }
    // El que llama tu SeekBar: setPercent(int)
    fun setPercentCompat(p:Int){ setPercent(p) }
    fun setPercent(p:Int, dummy1:Int, dummy2:Int){ setPercent(p) }
}
