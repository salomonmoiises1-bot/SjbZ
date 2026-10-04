package com.sb.dsp

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlin.math.*

/**
 * Real external-audio backend for SB.
 *
 * The only system-wide DSP path is Android DynamicsProcessing on session 0.
 * The UI keeps 10/20/32 logical EQ bands; this class maps that logical curve
 * onto however many physical DP EQ bands the device actually grants.
 */
class DynamicsProcessingManager {
    companion object {
        private const val TAG = "SB-DP"
        private val PHYSICAL_CANDIDATES = intArrayOf(32, 20, 10, 5)
        private val EQ10 = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private val EQ20 = floatArrayOf(31.5f,45f,63f,90f,125f,180f,250f,355f,500f,710f,1000f,1400f,2000f,2800f,4000f,5600f,8000f,11200f,16000f,20000f)
        private val EQ32 = floatArrayOf(20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,10000f,12500f,16000f,18000f,20000f)
        private const val GRID_POINTS = 192
        private const val MIN_FREQ = 20f
        private const val MAX_FREQ = 20000f
    }

    private var dp: DynamicsProcessing? = null
    private var physicalBandCount = 0
    private var physicalPostBandCount = 0
    private var currentSession = -1
    @Volatile private var autoGainOffsetDb = 0f
    @Volatile private var lastConfig: DspConfig = DspConfig()
    @Volatile private var currentConfigForGain: DspConfig = DspConfig()
    @Volatile private var controlGranted = false
    @Volatile private var lastReclaimAt = 0L
    private val reclaimHandler = Handler(android.os.Looper.getMainLooper())
    private val workerThread = HandlerThread("SB-DP-Worker").apply { start() }
    private val worker = Handler(workerThread.looper)
    @Volatile private var pendingApply: Runnable? = null

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
                    currentSession = audioSession
                    lastConfig = config.validate()
                    currentConfigForGain = lastConfig
                    controlGranted = effect.hasControl()
                    installControlListeners(effect)
                    applyConfigNow(lastConfig, effect)
                    effect.enabled = lastConfig.masterEnabled
                    Log.i(TAG, "DP activo session=$audioSession logical=$requested physicalPreEQ=$actual physicalPostEQ=$postActual mbc=${effect.getMbcByChannelIndex(0).bandCount} control=$controlGranted")
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "DP candidate=$candidate post=$usePostEq rejected", t)
                }
            }
        }
        release()
        return false
    }

    /** Non-blocking live update. Binder writes happen off the service/UI thread. */
    @Synchronized
    fun applyConfig(config: DspConfig) {
        val safe = config.validate()
        lastConfig = safe
        currentConfigForGain = safe
        val effect = dp ?: return
        val old = pendingApply
        if (old != null) worker.removeCallbacks(old)
        val job = Runnable {
            pendingApply = null
            applyConfigNow(safe, effect)
        }
        pendingApply = job
        worker.post(job)
    }

    fun setAutoGainOffsetDb(offsetDb: Float) {
        autoGainOffsetDb = offsetDb.coerceIn(-12f, 12f)
        val effect = dp ?: return
        val cfg = currentConfigForGain
        worker.post {
            try {
                if (!effect.hasControl()) return@post
                for (ch in 0 until effect.channelCount) {
                    effect.setInputGainbyChannel(ch, inputGainForChannel(cfg, ch))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AutoGain write failed", t)
            }
        }
    }

    private fun applyConfigNow(config: DspConfig, effect: DynamicsProcessing) {
        try {
            if (dp !== effect || !effect.hasControl()) {
                controlGranted = false
                scheduleReclaim()
                return
            }
            controlGranted = true
            for (ch in 0 until effect.channelCount) {
                effect.setInputGainbyChannel(ch, inputGainForChannel(config, ch))
            }
            applyEq(effect, config)
            applyMdrc(effect, config)
            applyLimiter(effect, config)
            effect.enabled = config.masterEnabled
        } catch (t: Throwable) {
            Log.e(TAG, "applyConfig failed", t)
        }
    }

    private fun inputGainForChannel(config: DspConfig, ch: Int): Float {
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
        val freqs = when (config.eqMode) {
            DspConfig.EqMode.BANDS_10 -> EQ10
            DspConfig.EqMode.BANDS_20 -> EQ20
            DspConfig.EqMode.BANDS_32 -> EQ32
        }
        val gains = config.activeGains()
        val sr = config.sampleRate.toFloat()
        val useEq = config.eqEnabled || config.toneEnabled
        val preMap = mapLogicalCurve(freqs, gains, config, physicalBandCount, sr)
        val postMap = if (physicalPostBandCount > 0) {
            mapLogicalCurve(freqs, gains, config, physicalPostBandCount, sr, preMap.cutoffs)
        } else null

        for (ch in 0 until effect.channelCount) {
            val pre = DynamicsProcessing.Eq(true, useEq, preMap.cutoffs.size)
            for (i in preMap.cutoffs.indices) {
                pre.setBand(i, DynamicsProcessing.EqBand(useEq, preMap.cutoffs[i], preMap.gains[i]))
            }
            effect.setPreEqByChannelIndex(ch, pre)

            if (postMap != null) {
                val post = DynamicsProcessing.Eq(true, useEq, postMap.cutoffs.size)
                for (i in postMap.cutoffs.indices) {
                    post.setBand(i, DynamicsProcessing.EqBand(useEq, postMap.cutoffs[i], postMap.gains[i]))
                }
                effect.setPostEqByChannelIndex(ch, post)
            }
        }
    }

    private data class EqMap(val cutoffs: FloatArray, val gains: FloatArray)

    /**
     * Compresses the logical graphic-EQ response into the actual number of DP
     * staircase bands. Cutoffs are chosen by dynamic programming to minimise
     * log-frequency response error, rather than blindly spreading 5 bands over
     * 20 Hz..20 kHz. This is the important portability layer for HALs exposing
     * only 5/10 physical bands.
     */
    private fun mapLogicalCurve(
        freqs: FloatArray,
        gains: FloatArray,
        config: DspConfig,
        bandCount: Int,
        sampleRate: Float,
        preCutoffs: FloatArray? = null,
    ): EqMap {
        val n = bandCount.coerceAtLeast(1)
        val maxFreq = min(MAX_FREQ, sampleRate * 0.49f)
        val gridFreq = FloatArray(GRID_POINTS) { i ->
            exp(ln(MIN_FREQ.toDouble()) + (ln(maxFreq.toDouble()) - ln(MIN_FREQ.toDouble())) * i / (GRID_POINTS - 1)).toFloat()
        }
        val target = FloatArray(GRID_POINTS) { i ->
            targetGainAt(gridFreq[i], freqs, gains, config, sampleRate)
        }

        if (n == 1) return EqMap(floatArrayOf(maxFreq), floatArrayOf(target.average().toFloat().coerceIn(-24f, 24f)))

        // Pre/post interleave: offset the second staircase. The first map is
        // optimised normally; the post map can reuse those boundaries as seeds.
        if (preCutoffs != null && preCutoffs.size == n) {
            val cut = FloatArray(n)
            for (i in 0 until n - 1) cut[i] = ((preCutoffs[i] + preCutoffs[i + 1]) * 0.5f).coerceAtLeast(MIN_FREQ)
            cut[n - 1] = maxFreq
            return EqMap(cut, segmentMeans(gridFreq, target, cut).map { it * 0.5f }.toFloatArray())
        }

        val minPoints = 3
        val cost = Array(n + 1) { DoubleArray(GRID_POINTS) { Double.POSITIVE_INFINITY } }
        val prev = Array(n + 1) { IntArray(GRID_POINTS) { -1 } }
        cost[0][0] = 0.0

        val prefix = DoubleArray(GRID_POINTS + 1)
        val prefixSq = DoubleArray(GRID_POINTS + 1)
        for (i in target.indices) {
            prefix[i + 1] = prefix[i] + target[i]
            prefixSq[i + 1] = prefixSq[i] + target[i].toDouble() * target[i]
        }
        fun segCost(a: Int, b: Int): Double {
            if (b - a + 1 < minPoints) return Double.POSITIVE_INFINITY
            val count = (b - a + 1).toDouble()
            val sum = prefix[b + 1] - prefix[a]
            val sq = prefixSq[b + 1] - prefixSq[a]
            return max(0.0, sq - sum * sum / count)
        }

        for (bands in 1..n) {
            val minEnd = (bands * minPoints - 1).coerceAtMost(GRID_POINTS - 1)
            for (end in minEnd until GRID_POINTS) {
                val minStart = ((bands - 1) * minPoints - 1).coerceAtLeast(0)
                for (start in minStart until end) {
                    val base = cost[bands - 1][start]
                    if (!base.isFinite()) continue
                    val c = segCost(start, end)
                    val total = base + c
                    if (total < cost[bands][end]) {
                        cost[bands][end] = total
                        prev[bands][end] = start
                    }
                }
            }
        }

        val cuts = FloatArray(n)
        val segments = ArrayList<Int>(n)
        var end = GRID_POINTS - 1
        for (band in n downTo 1) {
            val start = prev[band][end]
            if (start < 0) break
            segments.add(start)
            end = start
        }
        segments.reverse()

        if (segments.size != n) {
            // Defensive fallback: stable octave-ish cutoffs, never invalid.
            for (i in 0 until n) cuts[i] = exp(ln(MIN_FREQ.toDouble()) + (ln(maxFreq.toDouble()) - ln(MIN_FREQ.toDouble())) * (i + 1) / n).toFloat()
        } else {
            for (i in 0 until n - 1) {
                val boundaryIndex = segments[i + 1]
                cuts[i] = gridFreq[boundaryIndex].coerceAtLeast(MIN_FREQ)
            }
            cuts[n - 1] = maxFreq
        }
        val means = segmentMeans(gridFreq, target, cuts)
        val scale = if (physicalPostBandCount > 0) 0.5f else 1f
        return EqMap(cuts, means.map { it * scale }.toFloatArray())
    }

    private fun segmentMeans(freqs: FloatArray, target: FloatArray, cuts: FloatArray): List<Float> {
        val result = ArrayList<Float>(cuts.size)
        var start = 0
        for (cut in cuts) {
            var end = start
            while (end < freqs.lastIndex && freqs[end] <= cut) end++
            if (end < start) end = start
            var sum = 0.0
            var count = 0
            for (i in start..end) { sum += target[i]; count++ }
            result.add((sum / count.coerceAtLeast(1)).toFloat().coerceIn(-24f, 24f))
            start = (end + 1).coerceAtMost(freqs.lastIndex)
        }
        return result
    }

    private fun targetGainAt(f: Float, freqs: FloatArray, gains: FloatArray, config: DspConfig, sr: Float): Float {
        var gain = if (config.eqEnabled) interpolateLog(freqs, gains, f) else 0f
        if (config.toneEnabled) {
            gain += biquadMagnitudeDb(BiquadKind.LOW_SHELF, f, 100f, .707f, config.bassToneDb, sr)
            gain += biquadMagnitudeDb(BiquadKind.PEAKING, f, 1000f, 1f, config.midToneDb, sr)
            gain += biquadMagnitudeDb(BiquadKind.HIGH_SHELF, f, 10000f, .707f, config.trebleToneDb, sr)
        }
        return gain.coerceIn(-24f, 24f)
    }

    private fun applyMdrc(effect: DynamicsProcessing, config: DspConfig) {
        for (ch in 0 until effect.channelCount) {
            val mbc = effect.getMbcByChannelIndex(ch)
            mbc.setEnabled(config.mdrcEnabled)
            val maxFreq = min(MAX_FREQ, config.sampleRate * 0.49f)
            val cuts = floatArrayOf(config.mdrcLowCrossoverHz, config.mdrcMidCrossoverHz, config.mdrcHighCrossoverHz, maxFreq)
            val bands = arrayOf(config.mdrcLowBand, config.mdrcMidBand, config.mdrcHighBand, config.mdrcUltraBand)
            for (i in 0 until mbc.bandCount) {
                val b = bands[min(i, bands.lastIndex)]
                val cutoff = cuts[min(i, cuts.lastIndex)].coerceIn(20f, maxFreq)
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
        val enabled = config.limiterEnabled && config.masterEnabled
        val threshold = if (config.limiterEnabled) min(config.limiterThresholdDb, config.headroomDb) else config.limiterThresholdDb
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
            if (granted && dp === effect) {
                applyConfig(lastConfig)
            } else if (!granted) {
                scheduleReclaim()
            }
        }
        effect.setEnableStatusListener { _, enabled ->
            if (!enabled && dp === effect && lastConfig.masterEnabled) scheduleReclaim()
        }
    }

    private fun scheduleReclaim() {
        val now = SystemClock.uptimeMillis()
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
        val cosW = cos(w); val cos2W = cos(2*w); val cosC = cos(wc); val sinC = sin(wc)
        val A = 10.0.pow(gainDb / 40.0)
        val alpha = sinC / (2.0 * q.coerceAtLeast(.1f))
        val c = when (kind) {
            BiquadKind.PEAKING -> Coeffs(1+A*alpha, -2*cosC, 1-A*alpha, 1+alpha/A, -2*cosC, 1-alpha/A)
            BiquadKind.LOW_SHELF -> { val sA=sqrt(A); val t=2*sA*alpha; Coeffs(A*((A+1)-(A-1)*cosC+t), 2*A*((A-1)-(A+1)*cosC), A*((A+1)-(A-1)*cosC-t), (A+1)+(A-1)*cosC+t, -2*((A-1)+(A+1)*cosC), (A+1)-(A-1)*cosC-t) }
            BiquadKind.HIGH_SHELF -> { val sA=sqrt(A); val t=2*sA*alpha; Coeffs(A*((A+1)+(A-1)*cosC+t), -2*A*((A-1)+(A+1)*cosC), A*((A+1)-(A-1)*cosC-t), (A+1)-(A-1)*cosC+t, 2*((A-1)-(A+1)*cosC), (A+1)-(A-1)*cosC-t) }
        }
        val nr=c.b0*c.b0+c.b1*c.b1+c.b2*c.b2+2*(c.b0*c.b1+c.b1*c.b2)*cosW+2*c.b0*c.b2*cos2W
        val dr=c.a0*c.a0+c.a1*c.a1+c.a2*c.a2+2*(c.a0*c.a1+c.a1*c.a2)*cosW+2*c.a0*c.a2*cos2W
        return (20.0*log10(sqrt((nr/dr).coerceAtLeast(1e-12)))).toFloat()
    }

    private fun linearToDb(linear: Float) = (20.0 * log10(linear.coerceIn(0.001f, 1f).toDouble())).toFloat()

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

    @Synchronized
    fun release() {
        val job = pendingApply
        if (job != null) worker.removeCallbacks(job)
        pendingApply = null
        try { dp?.release() } catch (_: Throwable) {}
        dp = null
        physicalBandCount = 0
        physicalPostBandCount = 0
        currentSession = -1
        controlGranted = false
    }
}
