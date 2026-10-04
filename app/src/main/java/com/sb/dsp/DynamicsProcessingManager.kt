package com.sb.dsp

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Real backend for external audio sessions.
 *
 * The logical EQ remains 10/20/32 bands. The native DP Post-EQ is probed at
 * runtime and the highest compatible physical band count <= the logical count
 * is selected. Logical gains are then interpolated on the physical cutoffs.
 */
class DynamicsProcessingManager {
    companion object {
        private const val TAG = "SB-DP"
        private val PHYSICAL_CANDIDATES = intArrayOf(32, 20, 10, 5)
        private val EQ10 = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private val EQ20 = floatArrayOf(31.5f,45f,63f,90f,125f,180f,250f,355f,500f,710f,1000f,1400f,2000f,2800f,4000f,5600f,8000f,11200f,16000f,20000f)
        private val EQ32 = floatArrayOf(
            20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,630f,
            800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,10000f,12500f,16000f,18000f,20000f
        )
    }

    private var dp: DynamicsProcessing? = null
    private var sessionId = -1
    private var physicalBandCount = 0
    private var logicalMode = DspConfig.EqMode.BANDS_32

    val isAvailable: Boolean get() = dp != null
    val postEqBandCount: Int get() = physicalBandCount

    fun start(audioSession: Int, config: DspConfig): Boolean {
        release()
        if (audioSession < 0) return false

        val requested = config.eqMode.bandCount
        val candidates = PHYSICAL_CANDIDATES.filter { it <= requested }.toIntArray()
        for (candidate in candidates) {
            try {
                val builder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    config.channels.coerceIn(1, 2),
                    false, 0,
                    true, 4,
                    true, candidate,
                    true
                )
                val effect = DynamicsProcessing(100, audioSession, builder.build())
                val actual = effect.getPostEqByChannelIndex(0).bandCount
                if (actual <= 0 || actual > requested) {
                    effect.release()
                    continue
                }
                dp = effect
                sessionId = audioSession
                physicalBandCount = actual
                logicalMode = config.eqMode
                applyConfig(config)
                effect.enabled = config.masterEnabled
                Log.i(TAG, "DP activo session=$audioSession logical=${requested} physical=$actual")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "DP candidate=$candidate no aceptado", t)
            }
        }
        release()
        return false
    }

    fun applyConfig(config: DspConfig) {
        val effect = dp ?: return
        try {
            val safe = config.validate()
            for (ch in 0 until effect.channelCount) {
                // Global gain path: PreGain + MasterGain + Balance, no fake Pre-EQ.
                val auto = if (safe.autoGainEnabled) 0f else 0f
                val base = (if (safe.pregainEnabled) safe.pregainDb else 0f) +
                    safe.masterGainDb + auto
                val balanceDb = safe.balance.coerceIn(-1f, 1f) * 60f
                effect.setInputGainbyChannel(ch, (base + if (ch == 0) min(0f, balanceDb) else max(0f, balanceDb)).coerceIn(-60f, 12f))
            }

            applyEq(effect, safe)
            applyMdrc(effect, safe)
            applyLimiter(effect, safe)
            effect.setEnabled(safe.masterEnabled)
        } catch (t: Throwable) {
            Log.e(TAG, "applyConfig fallo", t)
        }
    }

    private fun applyEq(effect: DynamicsProcessing, config: DspConfig) {
        val logicalFrequencies = when (config.eqMode) {
            DspConfig.EqMode.BANDS_10 -> EQ10
            DspConfig.EqMode.BANDS_20 -> EQ20
            DspConfig.EqMode.BANDS_32 -> EQ32
        }
        val logicalGains = config.activeGains()
        val physical = physicalBandCount
        for (ch in 0 until effect.channelCount) {
            for (i in 0 until physical) {
                val f = physicalFrequency(i, physical)
                val gain = interpolateLog(logicalFrequencies, logicalGains, f)
                val band = DynamicsProcessing.EqBand(true, f, gain.coerceIn(-24f, 24f))
                effect.setPostEqBandByChannelIndex(ch, i, band)
            }
            effect.getPostEqByChannelIndex(ch).setEnabled(config.eqEnabled)
        }
    }

    private fun applyMdrc(effect: DynamicsProcessing, config: DspConfig) {
        val cuts = floatArrayOf(
            config.mdrcLowCrossoverHz.coerceIn(20f, 20000f),
            config.mdrcMidCrossoverHz.coerceIn(40f, 20000f),
            config.mdrcHighCrossoverHz.coerceIn(100f, 20000f),
            20000f
        ).sorted()
        val bands = arrayOf(config.mdrcLowBand, config.mdrcMidBand, config.mdrcHighBand)
        for (ch in 0 until effect.channelCount) {
            val mbc = effect.getMbcByChannelIndex(ch)
            mbc.setEnabled(config.mdrcEnabled)
            val count = mbc.bandCount
            for (i in 0 until count) {
                val src = bands[min(i, bands.lastIndex)]
                val cutoff = cuts[min(i, cuts.lastIndex)]
                val old = effect.getMbcBandByChannelIndex(ch, i)
                old.setEnabled(config.mdrcEnabled)
                old.setCutoffFrequency(cutoff)
                old.setThreshold(src.thresholdDb.coerceIn(-60f, 0f))
                old.setRatio(src.ratio.coerceIn(1f, 20f))
                old.setAttackTime(src.attackMs.coerceIn(0.1f, 200f))
                old.setReleaseTime(src.releaseMs.coerceIn(1f, 1000f))
                old.setPreGain(0f)
                old.setPostGain(src.makeupGainDb.coerceIn(-12f, 12f))
                effect.setMbcBandByChannelIndex(ch, i, old)
            }
        }
    }

    private fun applyLimiter(effect: DynamicsProcessing, config: DspConfig) {
        for (ch in 0 until effect.channelCount) {
            val limiter = effect.getLimiterByChannelIndex(ch)
            limiter.setEnabled(config.headroomEnabled || config.masterEnabled)
        }
    }

    private fun physicalFrequency(index: Int, count: Int): Float {
        if (count <= 1) return 20000f
        val minF = 20f
        val maxF = 20000f
        val ratio = maxF / minF
        return (minF * kotlin.math.exp(ln(ratio) * index / (count - 1))).toFloat()
    }

    private fun interpolateLog(freqs: FloatArray, gains: FloatArray, target: Float): Float {
        if (freqs.isEmpty() || gains.isEmpty()) return 0f
        if (target <= freqs.first()) return gains.firstOrNull() ?: 0f
        val last = min(freqs.lastIndex, gains.lastIndex)
        if (target >= freqs[last]) return gains[last]
        for (i in 0 until last) {
            val f1 = freqs[i]
            val f2 = freqs[i + 1]
            if (target <= f2) {
                val a = ln(target.toDouble())
                val b = ln(f1.toDouble())
                val c = ln(f2.toDouble())
                val t = ((a - b) / (c - b)).toFloat()
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return gains[last]
    }

    fun release() {
        try { dp?.release() } catch (_: Throwable) {}
        dp = null
        sessionId = -1
        physicalBandCount = 0
    }
}
