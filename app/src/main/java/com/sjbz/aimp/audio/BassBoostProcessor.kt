package com.sjbz.aimp.audio
import android.media.audiofx.BassBoost
class BassBoostProcessor {
    enum class FreqMode(val hz:Int){ SUB_60(60), PUNCH_85(85), MID_120(120) }
    var strength:Int=400; var freqMode=FreqMode.PUNCH_85
    private var bb:BassBoost?=null; private var sid:Int=0
    fun attach(id:Int){ if(id==0) return; try{ if(sid==id && bb!=null){ apply(); return }; release(); sid=id; bb=BassBoost(0,id).apply{ enabled=true; setStrength(strength.toShort()) } }catch(_:Exception){} }
    fun setPercent(p:Int){ strength=(p.coerceIn(0,100)*10).coerceIn(0,1000); apply() }
    fun setStrength(s:Int){ strength=s.coerceIn(0,1000); apply() }
    fun setStrengthPercent(p:Int){ setPercent(p) }
    fun setFrequency(m:FreqMode){ freqMode=m }
    private fun apply(){ try{ bb?.setStrength(strength.toShort()); bb?.enabled=strength>0 }catch(_:Exception){} }
    fun toDisplay():String{ val db=strength*15/1000; val per=strength*100/1000; return if(strength==0) "OFF" else "+$db.0 dB ($per%)" }
    fun toShortDisplay():String{ val db=strength*15/1000; return if(strength==0) "OFF" else "+$db.0 dB" }
    fun release(){ try{bb?.release()}catch(_:Exception){}; bb=null; sid=0 }
}
