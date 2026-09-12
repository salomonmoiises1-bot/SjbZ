package com.sjbz.aimp.audio
import android.media.audiofx.BassBoost

class BassBoostProcessor {
    var isEnabled = true
    var strength = 400 // 0 a 1000 = Off a +15dB. 400 = +6.0dB
    var centerFreq = 85
    private var bb: BassBoost? = null

    fun attach(sessionId: Int){
        try {
            if(sessionId == 0) return
            bb?.release()
            bb = BassBoost(0, sessionId).apply {
                enabled = isEnabled
                setStrength(strength.toShort())
            }
        } catch(e: Exception){}
    }
    fun setDb(db: Int){
        strength = (db * 1000 / 15).coerceIn(0,1000)
        try { bb?.setStrength(strength.toShort()) } catch(_: Exception){}
    }
    fun release(){ try{ bb?.release() }catch(_: Exception){}; bb=null }
    fun toDisplay(): String = "+${strength * 15 / 1000}.0 dB (${strength*100/1000}%) ${if(isEnabled) "ACTIVO" else "OFF"}"
}
