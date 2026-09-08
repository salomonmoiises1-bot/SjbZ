package com.sjbz.aimp.audio
import android.media.audiofx.Equalizer
import android.util.Log

class DynamicsProcessingHelper {
    private var androidEq: Equalizer? = null
    fun attachToSession(sessionId: Int, eqProcessor: EqualizerProcessor, mdrc: MDRCProcessor, limiter: LimiterProcessor) {
        try {
            release()
            if (sessionId <= 0) return
            androidEq = Equalizer(0, sessionId).apply { enabled = true }
            applyEqualizer(eqProcessor)
        } catch (e: Exception) { Log.e("DynamicsHelper", "Attach failed", e) }
    }
    fun applyEqualizer(eqProcessor: EqualizerProcessor) {
        try {
            val eq = androidEq?: return
            val bands = eqProcessor.getAllBands()
            for (i in 0 until eq.numberOfBands) {
                val src = (i * bands.size / eq.numberOfBands).coerceIn(0, bands.size-1)
                val level = (bands[src]*100).toInt().coerceIn(eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt())
                eq.setBandLevel(i.toShort(), level.toShort())
            }
        } catch (_: Exception) {}
    }
    fun applyMDRC(mdrc: MDRCProcessor) {}
    fun applyLimiter(limiter: LimiterProcessor) {}
    fun release() { try { androidEq?.release() } catch(_: Exception){}; androidEq=null }
}
