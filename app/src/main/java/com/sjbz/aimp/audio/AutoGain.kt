package com.sjbz.aimp.audio
import kotlin.math.pow
class AutoGain {
    var enabled: Boolean = true
    var strength: Float = 0.8f
    fun compensationDb(preampDb: Float, bandGains: FloatArray, bassGainDb: Float): Float {
        if (!enabled) return 0f
        var avgBand = 0f
        for (g in bandGains) avgBand += g
        if (bandGains.isNotEmpty()) avgBand /= bandGains.size
        val total = preampDb + bassGainDb * 0.5f + avgBand
        return (-total * strength).coerceIn(-12f, 0f)
    }
    fun apply(buf: FloatArray, totalFloats: Int, compDb: Float) {
        if (compDb == 0f) return
        val g = 10f.pow(compDb / 20f)
        for (i in 0 until totalFloats) buf[i] *= g
    }
}
