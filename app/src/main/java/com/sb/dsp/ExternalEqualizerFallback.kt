package com.sb.dsp

import android.media.audiofx.Equalizer
import kotlin.math.ln

/**
 * Compatibility backend for devices where DynamicsProcessing is unavailable.
 * It maps SB's logical EQ (10/20/32 bands) onto the physical bands exposed by
 * Android's legacy Equalizer effect. It never attempts to capture PCM itself.
 */
class ExternalEqualizerFallback {
    private var equalizer: Equalizer? = null

    val isAvailable: Boolean get() = equalizer != null
    val bandCount: Int get() = equalizer?.numberOfBands?.toInt() ?: 0

    fun start(sessionId: Int, config: DspConfig): Boolean {
        release()
        return runCatching {
            equalizer = Equalizer(Int.MAX_VALUE, sessionId).also { eq ->
                eq.enabled = false
            }
            applyConfig(config)
            true
        }.getOrElse {
            release()
            false
        }
    }

    fun applyConfig(config: DspConfig) {
        val eq = equalizer ?: return
        val enabled = config.masterEnabled && (config.eqEnabled || config.toneEnabled)
        try {
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minDb = range[0] / 100f
            val maxDb = range[1] / 100f
            val freqs = logicalFrequencies(config.eqMode)
            val gains = config.activeGains()
            for (i in 0 until bands) {
                val hz = eq.getCenterFreq(i.toShort()).toFloat() / 1000f
                var db = if (config.eqEnabled) interpolateLog(freqs, gains, hz) else 0f
                if (config.toneEnabled) {
                    db += toneApprox(hz, config)
                }
                val level = (db.coerceIn(minDb, maxDb) * 100f).toInt().toShort()
                eq.setBandLevel(i.toShort(), level)
            }
            eq.enabled = enabled
        } catch (_: Throwable) {
            // A vendor effect can reject individual band writes; keep the effect alive.
        }
    }

    fun release() {
        try { equalizer?.release() } catch (_: Throwable) {}
        equalizer = null
    }

    private fun logicalFrequencies(mode: DspConfig.EqMode): FloatArray = when (mode) {
        DspConfig.EqMode.BANDS_10 -> floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        DspConfig.EqMode.BANDS_20 -> floatArrayOf(31.5f,45f,63f,90f,125f,180f,250f,355f,500f,710f,1000f,1400f,2000f,2800f,4000f,5600f,8000f,11200f,16000f,20000f)
        DspConfig.EqMode.BANDS_32 -> floatArrayOf(20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,10000f,12500f,16000f,18000f,20000f)
    }

    private fun interpolateLog(freqs: FloatArray, gains: FloatArray, target: Float): Float {
        if (freqs.isEmpty() || gains.isEmpty()) return 0f
        val last = minOf(freqs.lastIndex, gains.lastIndex)
        if (target <= freqs.first()) return gains.first()
        if (target >= freqs[last]) return gains[last]
        for (i in 0 until last) if (target <= freqs[i + 1]) {
            val t = ((ln(target.toDouble()) - ln(freqs[i].toDouble())) /
                (ln(freqs[i + 1].toDouble()) - ln(freqs[i].toDouble()))).toFloat()
            return gains[i] + (gains[i + 1] - gains[i]) * t
        }
        return gains[last]
    }

    private fun toneApprox(hz: Float, config: DspConfig): Float {
        fun lowShelf(f: Float, edge: Float, db: Float): Float = if (f <= edge) db else 0f
        fun highShelf(f: Float, edge: Float, db: Float): Float = if (f >= edge) db else 0f
        return lowShelf(hz, 100f, config.bassToneDb) +
            config.midToneDb * (1f - kotlin.math.abs(kotlin.math.log10(hz.coerceAtLeast(20f) / 1000f)).coerceIn(0f, 1f)) +
            highShelf(hz, 10000f, config.trebleToneDb)
    }
}
