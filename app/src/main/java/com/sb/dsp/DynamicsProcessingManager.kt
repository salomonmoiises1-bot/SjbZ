package com.sb.dsp

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import kotlin.math.*

/**
 * System-wide external-audio backend.
 *
 * SB intentionally follows the same family of design used by Equalizer314:
 * session 0, DynamicsProcessing, global input gain, EQ on the DP EQ stage,
 * native MBC and native limiter. Oboe/PCM remains a separate app-owned path.
 */
class DynamicsProcessingManager {
    companion object {
        private const val TAG = "SB-DP"
        private val PHYSICAL_CANDIDATES = intArrayOf(32, 20, 10, 5)
        private val EQ10 = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private val EQ20 = floatArrayOf(31.5f,45f,63f,90f,125f,180f,250f,355f,500f,710f,1000f,1400f,2000f,2800f,4000f,5600f,8000f,11200f,16000f,20000f)
        private val EQ32 = floatArrayOf(20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,10000f,12500f,16000f,18000f,20000f)
    }

    private var dp: DynamicsProcessing? = null
    private var physicalBandCount = 0
    private var physicalPostBandCount = 0
    private var currentMode = DspConfig.EqMode.BANDS_32
    private var currentSession = -1
    @Volatile private var autoGainOffsetDb = 0f
    @Volatile private var lastConfig: DspConfig = DspConfig()
    @Volatile private var controlGranted = false
    @Volatile private var lastReclaimAt = 0L
    private val reclaimHandler = android.os.Handler(android.os.Looper.getMainLooper())

    val isAvailable: Boolean get() = dp != null
    val postEqBandCount: Int get() = physicalPostBandCount
    val physicalEqBandCount: Int get() = physicalBandCount
    val sessionId: Int get() = currentSession

    @Synchronized
    fun start(audioSession: Int, config: DspConfig): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || audioSession < 0) return false
        release()
        val requested = config.eqMode.bandCount
        val candidates = PHYSICAL_CANDIDATES.filter { it <= requested }.ifEmpty { listOf(5) }
        for (candidate in candidates) {
            for (usePostEq in booleanArrayOf(true, false)) {
                try {
                    val builder = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        config.channels.coerceIn(1, 2),
                        true, candidate,
                        true, 4,
                        usePostEq, if (usePostEq) candidate else 0,
                        true
                    )
                    builder.setPreferredFrameDuration(80f)
                    val effect = DynamicsProcessing(Int.MAX_VALUE, audioSession, builder.build())
                    val actual = effect.getPreEqByChannelIndex(0).bandCount
                    val postActual = if (usePostEq) effect.getPostEqByChannelIndex(0).bandCount else 0
                    if (actual <= 0) { effect.release(); continue }
                    dp = effect
                    physicalBandCount = actual
                    physicalPostBandCount = postActual
                    currentMode = config.eqMode
                    currentSession = audioSession
                    controlGranted = effect.hasControl()
                    installControlListeners(effect)
                    applyConfig(config)
                    effect.enabled = config.masterEnabled
                    Log.i(TAG, "DP activo session=$audioSession logical=$requested physicalPreEQ=$actual physicalPostEQ=$postActual mbc=${effect.getMbcByChannelIndex(0).bandCount}")
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "DP candidate=$candidate post=$usePostEq rejected", t)
                }
            }
        }
        release()
        return false
    }

    @Synchronized
    fun applyConfig(config: DspConfig) {
        val effect = dp ?: return
        val safe = config.validate()
        lastConfig = safe
        currentConfigForGain = safe
        try {
            for (ch in 0 until effect.channelCount) {
                effect.setInputGainbyChannel(ch, inputGainForChannel(safe, ch))
            }
            applyEq(effect, safe)
            applyMdrc(effect, safe)
            applyLimiter(effect, safe)
            effect.enabled = safe.masterEnabled
        } catch (t: Throwable) { Log.e(TAG, "applyConfig failed", t) }
    }

    fun setAutoGainOffsetDb(offsetDb: Float) {
        autoGainOffsetDb = offsetDb.coerceIn(-12f, 12f)
        val effect = dp ?: return
        try {
            val c = currentConfigForGain
            for (ch in 0 until effect.channelCount) effect.setInputGainbyChannel(ch, inputGainForChannel(c, ch))
        } catch (_: Throwable) {}
    }

    @Volatile private var currentConfigForGain: DspConfig = DspConfig()

    private fun inputGainForChannel(config: DspConfig, ch: Int): Float {
        currentConfigForGain = config
        val base = (if (config.pregainEnabled) config.pregainDb else 0f) +
            config.masterGainDb + (if (config.autoGainEnabled) autoGainOffsetDb else 0f)
        val b = config.balance.coerceIn(-1f, 1f)
        val attenuation = when {
            ch == 0 && b > 0f -> linearToDb(1f - b)
            ch == 1 && b < 0f -> linearToDb(1f + b)
            else -> 0f
        }
        return (base + attenuation).coerceIn(-60f, 12f)
    }

    private fun applyEq(effect: DynamicsProcessing, config: DspConfig) {
        val freqs = when (config.eqMode) { DspConfig.EqMode.BANDS_10 -> EQ10; DspConfig.EqMode.BANDS_20 -> EQ20; DspConfig.EqMode.BANDS_32 -> EQ32 }
        val gains = config.activeGains()
        val sr = config.sampleRate.toFloat()
        for (ch in 0 until effect.channelCount) {
            val pre = effect.getPreEqByChannelIndex(ch)
            for (i in 0 until physicalBandCount) {
                val f = physicalFrequency(i, physicalBandCount)
                val target = targetGainAt(f, freqs, gains, config, sr)
                val g = if (physicalPostBandCount > 0) target * 0.5f else target
                pre.setBand(i, DynamicsProcessing.EqBand(config.eqEnabled || config.toneEnabled, f, g.coerceIn(-24f, 24f)))
            }
            pre.setEnabled(config.eqEnabled || config.toneEnabled)
            if (physicalPostBandCount > 0) {
                val post = effect.getPostEqByChannelIndex(ch)
                for (i in 0 until physicalPostBandCount) {
                    val f = postPhysicalFrequency(i, physicalPostBandCount, physicalBandCount)
                    val target = targetGainAt(f, freqs, gains, config, sr)
                    post.setBand(i, DynamicsProcessing.EqBand(config.eqEnabled || config.toneEnabled, f, (target * 0.5f).coerceIn(-24f, 24f)))
                }
                post.setEnabled(config.eqEnabled || config.toneEnabled)
            }
        }
    }

    private fun targetGainAt(f: Float, freqs: FloatArray, gains: FloatArray, config: DspConfig, sr: Float): Float {
        var gain = if (config.eqEnabled) interpolateLog(freqs, gains, f) else 0f
        if (config.toneEnabled) {
            gain += biquadMagnitudeDb(BiquadKind.LOW_SHELF, f, 100f, .707f, config.bassToneDb, sr)
            gain += biquadMagnitudeDb(BiquadKind.PEAKING, f, 1000f, 1f, config.midToneDb, sr)
            gain += biquadMagnitudeDb(BiquadKind.HIGH_SHELF, f, 10000f, .707f, config.trebleToneDb, sr)
        }
        return gain
    }

    private fun applyMdrc(effect: DynamicsProcessing, config: DspConfig) {
        for (ch in 0 until effect.channelCount) {
            val mbc = effect.getMbcByChannelIndex(ch)
            mbc.setEnabled(config.mdrcEnabled)
            val cuts = floatArrayOf(config.mdrcLowCrossoverHz, config.mdrcMidCrossoverHz, config.mdrcHighCrossoverHz, 20000f)
            val bands = arrayOf(config.mdrcLowBand, config.mdrcMidBand, config.mdrcHighBand, config.mdrcUltraBand)
            val count = mbc.bandCount
            for (i in 0 until count) {
                val b = bands[min(i, bands.lastIndex)]
                val nyquist = (config.sampleRate * 0.49f).coerceAtLeast(20f)
                val cutoff = cuts[min(i, cuts.lastIndex)].coerceIn(20f, nyquist)
                val band = DynamicsProcessing.MbcBand(
                    config.mdrcEnabled,
                    cutoff,
                    b.attackMs.coerceIn(0.01f, 500f),
                    b.releaseMs.coerceIn(1f, 5000f),
                    b.ratio.coerceIn(1f, 50f),
                    b.thresholdDb.coerceIn(-60f, 0f),
                    8f,
                    -90f,
                    1f,
                    0f,
                    b.makeupGainDb.coerceIn(-30f, 30f)
                )
                effect.setMbcBandByChannelIndex(ch, i, band)
            }
        }
    }

    private fun applyLimiter(effect: DynamicsProcessing, config: DspConfig) {
        val enabled = config.headroomEnabled && config.masterEnabled
        val threshold = if (config.headroomEnabled) min(config.limiterThresholdDb, config.headroomDb) else config.limiterThresholdDb
        val limiter = DynamicsProcessing.Limiter(
            enabled, enabled, 0,
            config.limiterAttackMs.coerceIn(0.01f, 100f),
            config.limiterReleaseMs.coerceIn(1f, 500f),
            config.limiterRatio.coerceIn(1f, 50f),
            threshold.coerceIn(-30f, 0f),
            config.limiterPostGainDb.coerceIn(-12f, 12f)
        )
        for (ch in 0 until effect.channelCount) effect.setLimiterByChannelIndex(ch, limiter)
    }

    private fun installControlListeners(effect: DynamicsProcessing) {
        effect.setControlStatusListener { _, granted ->
            controlGranted = granted
            Log.i(TAG, "DP session=$currentSession controlGranted=$granted")
            if (granted && dp === effect) {
                applyConfig(lastConfig)
                try { effect.enabled = lastConfig.masterEnabled } catch (_: Throwable) {}
            } else if (!granted) {
                scheduleReclaim()
            }
        }
        effect.setEnableStatusListener { _, enabled ->
            if (!enabled && dp === effect && lastConfig.masterEnabled) scheduleReclaim()
        }
    }

    private fun scheduleReclaim() {
        val now = System.currentTimeMillis()
        if (now - lastReclaimAt < 2000L) return
        lastReclaimAt = now
        reclaimHandler.postDelayed({
            val cfg = lastConfig
            if (currentSession == DspEngine.GLOBAL_SESSION_ID && dp != null) {
                Log.w(TAG, "DP control lost; reclaiming session 0")
                start(currentSession, cfg)
            }
        }, 100L)
    }

    private enum class BiquadKind { PEAKING, LOW_SHELF, HIGH_SHELF }
    private data class Coeffs(val b0:Double,val b1:Double,val b2:Double,val a0:Double,val a1:Double,val a2:Double)

    private fun biquadMagnitudeDb(kind: BiquadKind, f: Float, fc: Float, q: Float, gainDb: Float, sr: Float): Float {
        if (abs(gainDb) < 0.001f) return 0f
        val w = 2.0 * Math.PI * f.coerceIn(10f, sr * .49f) / sr
        val wc = 2.0 * Math.PI * fc.coerceIn(10f, sr * .49f) / sr
        val cosW = cos(w); val sinW = sin(w); val cosC = cos(wc); val sinC = sin(wc)
        val A = 10.0.pow(gainDb / 40.0)
        val alpha = sinC / (2.0 * q.coerceAtLeast(.1f))
        val c = when (kind) {
            BiquadKind.PEAKING -> Coeffs(1+A*alpha, -2*cosC, 1-A*alpha, 1+alpha/A, -2*cosC, 1-alpha/A)
            BiquadKind.LOW_SHELF -> {
                val sA=sqrt(A); val t=2*sA*alpha
                Coeffs(A*((A+1)-(A-1)*cosC+t), 2*A*((A-1)-(A+1)*cosC), A*((A+1)-(A-1)*cosC-t), (A+1)+(A-1)*cosC+t, -2*((A-1)+(A+1)*cosC), (A+1)-(A-1)*cosC-t)
            }
            BiquadKind.HIGH_SHELF -> {
                val sA=sqrt(A); val t=2*sA*alpha
                Coeffs(A*((A+1)+(A-1)*cosC+t), -2*A*((A-1)+(A+1)*cosC), A*((A+1)-(A-1)*cosC-t), (A+1)-(A-1)*cosC+t, 2*((A-1)-(A+1)*cosC), (A+1)-(A-1)*cosC-t)
            }
        }
        fun mag(num0: Double,num1:Double,num2:Double, den0:Double,den1:Double,den2:Double):Double {
            val nr=num0*num0 + num1*num1 + num2*num2 + 2*(num0*num1+num1*num2)*cosW + 2*num0*num2*cos(2*w)
            val dr=den0*den0 + den1*den1 + den2*den2 + 2*(den0*den1+den1*den2)*cosW + 2*den0*den2*cos(2*w)
            return sqrt((nr/dr).coerceAtLeast(1e-12))
        }
        return (20.0*log10(mag(c.b0,c.b1,c.b2,c.a0,c.a1,c.a2))).toFloat()
    }

    private fun linearToDb(linear: Float) = (20.0 * log10(linear.coerceIn(0.001f, 1f).toDouble())).toFloat()
    private fun postPhysicalFrequency(index: Int, count: Int, preCount: Int): Float =
        physicalFrequency(index, count)

    /**
     * Map the logical EQ range onto however many physical DP bands the device exposes.
     * The previous implementation accidentally ended at 1 kHz, leaving the upper
     * half of the spectrum represented by extrapolated values instead of real bands.
     */
    private fun physicalFrequency(index: Int, count: Int): Float {
        if (count <= 1) return 1000f
        val minHz = 20.0
        val maxHz = 20000.0
        return exp(ln(minHz) + (ln(maxHz) - ln(minHz)) * index / (count - 1)).toFloat()
    }
    private fun interpolateLog(freqs: FloatArray, gains: FloatArray, target: Float): Float {
        if (freqs.isEmpty() || gains.isEmpty()) return 0f
        val last = min(freqs.lastIndex, gains.lastIndex)
        if (target <= freqs.first()) return gains.first()
        if (target >= freqs[last]) return gains[last]
        for (i in 0 until last) if (target <= freqs[i + 1]) {
            val t = ((ln(target.toDouble()) - ln(freqs[i].toDouble())) / (ln(freqs[i+1].toDouble()) - ln(freqs[i].toDouble()))).toFloat()
            return gains[i] + (gains[i+1]-gains[i])*t
        }
        return gains[last]
    }

    fun release() {
        try { dp?.release() } catch (_: Throwable) {}
        dp = null; physicalBandCount = 0; physicalPostBandCount = 0; currentSession = -1; controlGranted = false
    }
}
