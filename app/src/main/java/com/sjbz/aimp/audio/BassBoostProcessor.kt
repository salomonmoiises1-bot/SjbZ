package com.sjbz.aimp.audio
import android.media.audiofx.BassBoost

class BassBoostProcessor {
    var isEnabled = true
    var strength = 400 // 0-1000 = Off a +15dB
    var freqMode = 85 // 60, 85, 120 para tu UI
    private var bb: BassBoost? = null

    fun attach(sessionId: Int){
        try {
            bb?.release()
            bb = BassBoost(0, sessionId).apply {
                enabled = isEnabled
                setStrength(strength.toShort())
            }
        } catch(e: Exception){}
    }
    fun setStrengthFromDb(db: Int){
        strength = (db * 1000 / 15).coerceIn(0,1000)
        try { bb?.setStrength(strength.toShort()) } catch(_: Exception){}
    }
    fun release(){ bb?.release(); bb = null }
    fun toDbString() = "+${strength * 15 / 1000}.0 dB (${strength*100/1000}%)"
}
