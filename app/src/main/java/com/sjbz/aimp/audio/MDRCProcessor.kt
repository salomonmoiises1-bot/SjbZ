package com.sjbz.aimp.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MDRCProcessor {

    companion object {
        const val BAND_COUNT = 5
        const val MAX_CHANNELS = 8
        val BAND_NAMES = arrayOf("Sub (<120Hz)", "Low (120-500Hz)", "Mid (500-2kHz)", "High (2k-8kHz)", "Air (>8kHz)")
        val SPLIT_FREQS = floatArrayOf(120f, 500f, 2000f, 8000f)
        val CENTER_FREQS = floatArrayOf(60f, 250f, 1000f, 4000f, 12000f)
    }

    // FIX: Una sola propiedad, sin duplicar 'enabled'
    @get:JvmName("isMDRCEnabled")
    @set:JvmName("setMDRCEnabled")
    @Volatile
    var isEnabled: Boolean = true

    // Compatibilidad Java: si SjbzDspProcessor.java llama setEnabled()
    fun setEnabled(value: Boolean) {
        isEnabled = value
    }

    fun getEnabled(): Boolean = isEnabled
    fun isMdrcEnabledCompat(): Boolean = isEnabled

    var thresholdDb: Float = -14.0f
    var ratio: Float = 3.0f
    var attackMs: Float = 10.0f
    var releaseMs: Float = 80.0f

    private val bandGainsDb = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
    private val bandLinearGains = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f, 1.0f)

    var currentGainReductionDb: Float = 0.0f
        private set

    private val b0 = FloatArray(BAND_COUNT)
    private val b1 = FloatArray(BAND_COUNT)
    private val b2 = FloatArray(BAND_COUNT)
    private val a1 = FloatArray(BAND_COUNT)
    private val a2 = FloatArray(BAND_COUNT)

    private val s1 = FloatArray(MAX_CHANNELS * BAND_COUNT)
    private val s2 = FloatArray(MAX_CHANNELS * BAND_COUNT)
    private val envelope = FloatArray(MAX_CHANNELS * BAND_COUNT)

    private var sampleRate: Float = 48000.0f
    private var alphaAtt: Float = 0.0f
    private var alphaRel: Float = 0.0f

    @Volatile
    private var isDirty: Boolean = true

    init { updateCoefficients() }

    fun setSampleRate(sr: Float) {
        val validSr = sr.coerceAtLeast(8000.0f)
        if (abs(this.sampleRate - validSr) > 1.0f) {
            this.sampleRate = validSr
            isDirty = true
        }
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until BAND_COUNT) {
            val clamped = gainDb.coerceIn(-12.0f, 12.0f)
            bandGainsDb[bandIndex] = clamped
            bandLinearGains[bandIndex] = 10.0.pow(clamped / 20.0).toFloat()
        }
    }

    fun getBandGain(bandIndex: Int): Float {
        return if (bandIndex in 0 until BAND_COUNT) bandGainsDb[bandIndex] else 0.0f
    }

    fun setDynamics(thresh: Float, rat: Float, att: Float, rel: Float) {
        this.thresholdDb = thresh.coerceIn(-36.0f, 0.0f)
        this.ratio = rat.coerceIn(1.0f, 10.0f)
        this.attackMs = att.coerceIn(1.0f, 100.0f)
        this.releaseMs = rel.coerceIn(10.0f, 500.0f)
        isDirty = true
    }

    fun setGlobalDynamics(thresholdDb: Float, ratio: Float) {
        this.thresholdDb = thresholdDb.coerceIn(-36.0f, 0.0f)
        this.ratio = ratio.coerceIn(1.0f, 10.0f)
        isDirty = true
    }

    fun getGainReductionDb(): Float = currentGainReductionDb
    fun getMdrcThreshold(): Float = thresholdDb
    fun getMdrcRatio(): Float = ratio

    fun reset() {
        s1.fill(0.0f)
        s2.fill(0.0f)
        envelope.fill(0.0f)
        currentGainReductionDb = 0.0f
        isDirty = true
    }

    fun updateCoefficients() {
        val sr = sampleRate
        alphaAtt = kotlin.math.exp(-1.0f / (sr * (attackMs / 1000.0f)))
        alphaRel = kotlin.math.exp(-1.0f / (sr * (releaseMs / 1000.0f)))
        computeLowPass(0, 120.0f, 0.7071f, sr)
        computeBandPass(1, 245.0f, 0.8f, sr)
        computeBandPass(2, 1000.0f, 0.8f, sr)
        computeBandPass(3, 4000.0f, 0.8f, sr)
        computeHighPass(4, 8000.0f, 0.7071f, sr)
        isDirty = false
    }

    private fun computeLowPass(idx: Int, f0: Float, q: Float, sr: Float) {
        val w0 = (2.0 * Math.PI * f0 / sr).toFloat()
        val alpha = sin(w0) / (2.0f * q)
        val cosw0 = cos(w0)
        val a0 = 1.0f + alpha
        b0[idx] = ((1.0f - cosw0) / 2.0f) / a0
        b1[idx] = (1.0f - cosw0) / a0
        b2[idx] = ((1.0f - cosw0) / 2.0f) / a0
        a1[idx] = (-2.0f * cosw0) / a0
        a2[idx] = (1.0f - alpha) / a0
    }

    private fun computeBandPass(idx: Int, f0: Float, q: Float, sr: Float) {
        val w0 = (2.0 * Math.PI * f0 / sr).toFloat()
        val alpha = sin(w0) / (2.0f * q)
        val cosw0 = cos(w0)
        val a0 = 1.0f + alpha
        b0[idx] = alpha / a0
        b1[idx] = 0.0f
        b2[idx] = -alpha / a0
        a1[idx] = (-2.0f * cosw0) / a0
        a2[idx] = (1.0f - alpha) / a0
    }

    private fun computeHighPass(idx: Int, f0: Float, q: Float, sr: Float) {
        val w0 = (2.0 * Math.PI * f0 / sr).toFloat()
        val alpha = sin(w0) / (2.0f * q)
        val cosw0 = cos(w0)
        val a0 = 1.0f + alpha
        b0[idx] = ((1.0f + cosw0) / 2.0f) / a0
        b1[idx] = -(1.0f + cosw0) / a0
        b2[idx] = ((1.0f + cosw0) / 2.0f) / a0
        a1[idx] = (-2.0f * cosw0) / a0
        a2[idx] = (1.0f - alpha) / a0
    }

    fun processSample(x: Float, ch: Int): Float {
        if (!isEnabled) return x
        if (isDirty) updateCoefficients()

        val chOffset = ch * BAND_COUNT
        var summedOutput = 0.0f
        var maxGrDb = 0.0f
        val thresh = thresholdDb
        val rat = ratio
        val compSlope = 1.0f - (1.0f / rat)
        val att = alphaAtt
        val rel = alphaRel

        for (band in 0 until BAND_COUNT) {
            val stateIdx = chOffset + band
            val fb0 = b0[band]; val fb1 = b1[band]; val fb2 = b2[band]
            val fa1 = a1[band]; val fa2 = a2[band]
            val st1 = s1[stateIdx]; val st2 = s2[stateIdx]
            val yBand = fb0 * x + st1
            s1[stateIdx] = fb1 * x - fa1 * yBand + st2
            s2[stateIdx] = fb2 * x - fa2 * yBand
            val absY = abs(yBand)
            var env = envelope[stateIdx]
            if (absY > env) env = att * env + (1.0f - att) * absY
            else env = rel * env + (1.0f - rel) * absY
            envelope[stateIdx] = env
            var bandGainMult = bandLinearGains[band]
            if (env > 1e-5f) {
                val levelDb = 20.0f * log10(env)
                if (levelDb > thresh) {
                    val excessDb = levelDb - thresh
                    val grDb = excessDb * compSlope
                    if (grDb > maxGrDb) maxGrDb = grDb
                    val compGainLin = 10.0.pow(-grDb / 20.0).toFloat()
                    bandGainMult *= compGainLin
                }
            }
            summedOutput += yBand * bandGainMult
        }
        currentGainReductionDb = maxGrDb
        return summedOutput
    }
}
