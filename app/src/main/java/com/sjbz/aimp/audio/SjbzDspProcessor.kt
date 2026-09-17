package com.sjbz.aimp.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * SjbZ Studio Professional Audio DSP Processor.
 *
 * Implements an uncompromising 32-bit floating-point audio processing chain:
 *   Input -> Preamp Gain -> Low-Shelf Bass (RBJ) -> 32-Band ISO Peaking EQ (RBJ, Q=1.4) ->
 *   Analog Warmth Soft Clipper (tanh) -> Output
 *
 * Fully standalone (no external framework dependencies):
 * - Zero heap allocations during audio processing loops.
 * - Per-channel Transposed Direct Form II biquad filter state memory.
 * - Real-time mono 2048-sample FFT buffer streaming via fftListener.
 */
class SjbzDspProcessor {

    companion object {
        const val BAND_COUNT = 32
        const val TOTAL_FILTERS = 33 // Index 0: Low-Shelf Bass, Indices 1..32: Peaking EQ bands
        const val MAX_CHANNELS = 8
        const val FFT_BLOCK_SIZE = 2048
        const val DEFAULT_Q = 1.4f
        const val EMU_FILTERS = 4

        val ISO_FREQUENCIES = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f,
            125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
            800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f,
            5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 18000f, 20000f
        )

        val BAND_LABELS = arrayOf(
            "20", "25", "31", "40", "50", "63", "80", "100",
            "125", "160", "200", "250", "315", "400", "500", "630",
            "800", "1k", "1.25k", "1.6k", "2k", "2.5k", "3.15k", "4k",
            "5k", "6.3k", "8k", "10k", "12.5k", "16k", "18k", "20k"
        )
    }

    // Master switch
    var masterEnabled: Boolean = true

    var enabled: Boolean
        get() = masterEnabled
        set(value) { masterEnabled = value }

    // Preamp parameter (-12 dB to +12 dB)
    private var preampDb: Float = 0.0f
    private var linearPreamp: Float = 1.0f

    // Bass Boost parameters (Low-Shelf RBJ)
    private var bassEnabled: Boolean = true
    private var bassFreqHz: Float = 85.0f
    private var bassGainDb: Float = 0.0f

    // 32-Band EQ parameters (-12 dB to +12 dB)
    private val bandGainsDb = FloatArray(BAND_COUNT)

    // ATS2835P Emulation Mode parameters
    private var emulationEnabled: Boolean = false
    private var emulationAmount: Float = 0.8f
    private var bluetoothAutoBypass: Boolean = true
    @Volatile
    private var bluetoothConnected: Boolean = false

    // 5-Band Multi-Band Dynamic Range Compressor (MDRC)
    val mdrcProcessor = MDRCProcessor()

    private val emuB0 = FloatArray(EMU_FILTERS)
    private val emuB1 = FloatArray(EMU_FILTERS)
    private val emuB2 = FloatArray(EMU_FILTERS)
    private val emuA1 = FloatArray(EMU_FILTERS)
    private val emuA2 = FloatArray(EMU_FILTERS)

    private val emuS1 = FloatArray(MAX_CHANNELS * EMU_FILTERS)
    private val emuS2 = FloatArray(MAX_CHANNELS * EMU_FILTERS)

    private val limiterThresholdLin = 10.0.pow(-6.0 / 20.0).toFloat()
    private var limiterAlphaAtt: Float = 0.0f
    private var limiterAlphaRel: Float = 0.0f
    private val emuEnv = FloatArray(MAX_CHANNELS)

    private var ditherSeed: Int = 123456789

    @Volatile
    private var isDirty: Boolean = true
    private var currentSampleRate: Float = 48000.0f

    private val b0Array = FloatArray(TOTAL_FILTERS)
    private val b1Array = FloatArray(TOTAL_FILTERS)
    private val b2Array = FloatArray(TOTAL_FILTERS)
    private val a1Array = FloatArray(TOTAL_FILTERS)
    private val a2Array = FloatArray(TOTAL_FILTERS)

    private val s1 = FloatArray(MAX_CHANNELS * TOTAL_FILTERS)
    private val s2 = FloatArray(MAX_CHANNELS * TOTAL_FILTERS)

    private val fftRingBuffer = FloatArray(FFT_BLOCK_SIZE)
    private val fftDispatchBuffer = FloatArray(FFT_BLOCK_SIZE)
    private var fftRingIndex: Int = 0

    var fftListener: ((FloatArray) -> Unit)? = null

    init {
        recalculateCoefficients()
    }

    fun configure(sampleRate: Float) {
        currentSampleRate = sampleRate.coerceAtLeast(8000f)
        mdrcProcessor.setSampleRate(currentSampleRate)
        isDirty = true
    }

    fun reset() {
        s1.fill(0.0f)
        s2.fill(0.0f)
        emuS1.fill(0.0f)
        emuS2.fill(0.0f)
        emuEnv.fill(0.0f)
        mdrcProcessor.reset()
        fftRingBuffer.fill(0.0f)
        fftRingIndex = 0
        isDirty = true
    }

    fun processFloats(samples: FloatArray, offset: Int, length: Int, channelCount: Int) {
        if (isDirty) {
            recalculateCoefficients()
        }

        val chCount = channelCount.coerceIn(1, MAX_CHANNELS)
        val frames = length / chCount
        val localMasterEnabled = masterEnabled
        val localLinearPreamp = linearPreamp
        val localListener = fftListener
        val isEmuActive = localMasterEnabled && emulationEnabled && (!bluetoothAutoBypass || !bluetoothConnected)
        val localEmuAmount = emulationAmount.coerceIn(0.0f, 1.0f)
        val localLimiterThresh = limiterThresholdLin
        val localAlphaAtt = limiterAlphaAtt
        val localAlphaRel = limiterAlphaRel

        var frameMonoSum: Float

        for (frame in 0 until frames) {
            frameMonoSum = 0.0f

            for (ch in 0 until chCount) {
                val sampleIdx = offset + frame * chCount + ch
                var x = samples[sampleIdx] * localLinearPreamp

                if (localMasterEnabled) {
                    val chOffset = ch * TOTAL_FILTERS

                    for (f in 0 until TOTAL_FILTERS) {
                        val stateIdx = chOffset + f
                        val b0 = b0Array[f]
                        val b1 = b1Array[f]
                        val b2 = b2Array[f]
                        val a1 = a1Array[f]
                        val a2 = a2Array[f]

                        val st1 = s1[stateIdx]
                        val st2 = s2[stateIdx]

                        val y = b0 * x + st1
                        s1[stateIdx] = b1 * x - a1 * y + st2
                        s2[stateIdx] = b2 * x - a2 * y

                        x = y
                    }

                    if (isEmuActive) {
                        val dryX = x
                        var wetX = x
                        val emuOffset = ch * EMU_FILTERS

                        for (ef in 0 until EMU_FILTERS) {
                            val sIdx = emuOffset + ef
                            val eb0 = emuB0[ef]
                            val eb1 = emuB1[ef]
                            val eb2 = emuB2[ef]
                            val ea1 = emuA1[ef]
                            val ea2 = emuA2[ef]

                            val est1 = emuS1[sIdx]
                            val est2 = emuS2[sIdx]

                            val ey = eb0 * wetX + est1
                            emuS1[sIdx] = eb1 * wetX - ea1 * ey + est2
                            emuS2[sIdx] = eb2 * wetX - ea2 * ey

                            wetX = ey
                        }

                        ditherSeed = ditherSeed * 1664525 + 1013904223
                        val dither = ((ditherSeed and 0xFFFF) - 32768) * 0.0000003f
                        wetX += dither

                        val absX = abs(wetX)
                        var env = emuEnv[ch]
                        if (absX > env) {
                            env = localAlphaAtt * env + (1.0f - localAlphaAtt) * absX
                        } else {
                            env = localAlphaRel * env + (1.0f - localAlphaRel) * absX
                        }
                        emuEnv[ch] = env

                        var gain = 1.0f
                        if (env > localLimiterThresh) {
                            val overDb = 20.0f * kotlin.math.log10(env / localLimiterThresh)
                            val compGainDb = -overDb * (3.0f / 4.0f)
                            gain = 10.0.pow(compGainDb / 20.0).toFloat()
                        }
                        wetX *= gain

                        x = dryX * (1.0f - localEmuAmount) + wetX * localEmuAmount
                    }

                    // Analog Warmth Soft Clipper
                    if (x > 1.0f) {
                        x = 1.0f + tanh(x - 1.0f) * 0.3f
                    } else if (x < -1.0f) {
                        x = -1.0f + tanh(x + 1.0f) * 0.3f
                    }
                }

                samples[sampleIdx] = x
                frameMonoSum += x
            }

            if (localListener != null) {
                fftRingBuffer[fftRingIndex] = frameMonoSum / chCount
                fftRingIndex++
                if (fftRingIndex >= FFT_BLOCK_SIZE) {
                    fftRingIndex = 0
                    System.arraycopy(fftRingBuffer, 0, fftDispatchBuffer, 0, FFT_BLOCK_SIZE)
                    localListener.invoke(fftDispatchBuffer)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // DSP Parameter Setters
    // -------------------------------------------------------------------------

    fun setPreamp(db: Float) {
        val clamped = db.coerceIn(-12.0f, 12.0f)
        if (abs(preampDb - clamped) > 0.001f) {
            preampDb = clamped
            linearPreamp = 10.0.pow(clamped / 20.0).toFloat()
        }
    }

    fun getPreamp(): Float = preampDb

    fun setBassBoost(enabled: Boolean, freqHz: Float, gainDb: Float) {
        val clampedGain = gainDb.coerceIn(0.0f, 12.0f)
        val clampedFreq = freqHz.coerceIn(20.0f, 500.0f)
        if (bassEnabled != enabled || abs(bassFreqHz - clampedFreq) > 0.1f || abs(bassGainDb - clampedGain) > 0.01f) {
            bassEnabled = enabled
            bassFreqHz = clampedFreq
            bassGainDb = clampedGain
            isDirty = true
        }
    }

    fun isBassBoostEnabled(): Boolean = bassEnabled
    fun getBassBoostFreq(): Float = bassFreqHz
    fun getBassBoostGain(): Float = bassGainDb

    fun setBandGain(index: Int, gainDb: Float) {
        if (index in 0 until BAND_COUNT) {
            val clamped = gainDb.coerceIn(-12.0f, 12.0f)
            if (abs(bandGainsDb[index] - clamped) > 0.01f) {
                bandGainsDb[index] = clamped
                isDirty = true
            }
        }
    }

    fun setAllBands(gains: List<Float>) {
        var changed = false
        for (i in 0 until minOf(gains.size, BAND_COUNT)) {
            val clamped = gains[i].coerceIn(-12.0f, 12.0f)
            if (abs(bandGainsDb[i] - clamped) > 0.01f) {
                bandGainsDb[i] = clamped
                changed = true
            }
        }
        if (changed) {
            isDirty = true
        }
    }

    fun getBandGain(index: Int): Float {
        return if (index in 0 until BAND_COUNT) bandGainsDb[index] else 0.0f
    }

    fun setEmulationEnabled(enabled: Boolean) {
        if (emulationEnabled != enabled) {
            emulationEnabled = enabled
            isDirty = true
        }
    }

    fun isEmulationEnabled(): Boolean = emulationEnabled

    fun setEmulationAmount(amount: Float) {
        emulationAmount = amount.coerceIn(0.0f, 1.0f)
    }

    fun getEmulationAmount(): Float = emulationAmount

    fun setBluetoothAutoBypass(enabled: Boolean) {
        bluetoothAutoBypass = enabled
    }

    fun isBluetoothAutoBypass(): Boolean = bluetoothAutoBypass

    fun setBluetoothConnected(connected: Boolean) {
        bluetoothConnected = connected
    }

    fun isBluetoothConnected(): Boolean = bluetoothConnected

    fun setMdrcEnabled(enabled: Boolean) {
        mdrcProcessor.enabled = enabled
    }

    fun isMdrcEnabled(): Boolean = mdrcProcessor.isEnabled

    fun setMdrcDynamics(thresholdDb: Float, ratio: Float) {
        mdrcProcessor.setGlobalDynamics(thresholdDb, ratio)
    }

    fun setGlobalDynamics(thresholdDb: Float, ratio: Float) {
        setMdrcDynamics(thresholdDb, ratio)
    }

    fun setMdrcBandGain(bandIndex: Int, gainDb: Float) {
        mdrcProcessor.setBandGain(bandIndex, gainDb)
    }

    fun getMdrcBandGain(bandIndex: Int): Float {
        return mdrcProcessor.getBandGain(bandIndex)
    }

    fun getMdrcThreshold(): Float = mdrcProcessor.getMdrcThreshold()
    fun getMdrcRatio(): Float = mdrcProcessor.getMdrcRatio()

    fun getMdrcGainReduction(): Float {
        return mdrcProcessor.getGainReductionDb()
    }

    fun getGainReductionDb(): Float = getMdrcGainReduction()

    // -------------------------------------------------------------------------
    // Biquad Coefficient Computation
    // -------------------------------------------------------------------------

    private fun recalculateCoefficients() {
        val fs = currentSampleRate

        // 1. Filter 0: Low-Shelf Bass Boost
        if (bassEnabled && bassGainDb > 0.01f) {
            computeLowShelfRbj(0, bassFreqHz, bassGainDb, fs)
        } else {
            setFilterBypass(0)
        }

        // 2. Filters 1..32: 32 ISO Peaking EQ bands
        for (i in 0 until BAND_COUNT) {
            val filterIndex = i + 1
            val gain = bandGainsDb[i]
            if (abs(gain) > 0.01f) {
                computePeakingRbj(filterIndex, ISO_FREQUENCIES[i], gain, DEFAULT_Q, fs)
            } else {
                setFilterBypass(filterIndex)
            }
        }

        // 3. Emulation Filters
        computeEmuLowShelfRbj(0, 80.0f, 2.0f, fs)
        computePeakingRbjRaw(emuB0, emuB1, emuB2, emuA1, emuA2, 1, 3000.0f, -1.5f, 1.4f, fs)
        computeEmuHighShelfRbj(2, 18000.0f, -3.0f, fs)
        computeEmuLowPassRbj(3, 18500.0f, 0.7071f, fs)

        limiterAlphaAtt = kotlin.math.exp(-1.0f / (0.005f * fs))
        limiterAlphaRel = kotlin.math.exp(-1.0f / (0.080f * fs))

        isDirty = false
    }

    private fun computeEmuLowShelfRbj(filterIndex: Int, f0: Float, gainDb: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW * 0.7071067811865475
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha

        val a0 = (A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha
        val invA0 = 1.0 / a0

        emuB0[filterIndex] = (A * ((A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha) * invA0).toFloat()
        emuB1[filterIndex] = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW) * invA0).toFloat()
        emuB2[filterIndex] = (A * ((A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
        emuA1[filterIndex] = (-2.0 * ((A - 1.0) + (A + 1.0) * cosW) * invA0).toFloat()
        emuA2[filterIndex] = (((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
    }

    private fun computeEmuHighShelfRbj(filterIndex: Int, f0: Float, gainDb: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW * 0.7071067811865475
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha

        val a0 = (A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha
        val invA0 = 1.0 / a0

        emuB0[filterIndex] = (A * ((A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha) * invA0).toFloat()
        emuB1[filterIndex] = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosW) * invA0).toFloat()
        emuB2[filterIndex] = (A * ((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
        emuA1[filterIndex] = (2.0 * ((A - 1.0) - (A + 1.0) * cosW) * invA0).toFloat()
        emuA2[filterIndex] = (((A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
    }

    private fun computePeakingRbjRaw(
        b0A: FloatArray, b1A: FloatArray, b2A: FloatArray,
        a1A: FloatArray, a2A: FloatArray,
        idx: Int, f0: Float, gainDb: Float, q: Float, sampleRate: Float
    ) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)

        val a0 = 1.0 + alpha / A
        val invA0 = 1.0 / a0

        b0A[idx] = ((1.0 + alpha * A) * invA0).toFloat()
        b1A[idx] = ((-2.0 * cosW) * invA0).toFloat()
        b2A[idx] = ((1.0 - alpha * A) * invA0).toFloat()
        a1A[idx] = ((-2.0 * cosW) * invA0).toFloat()
        a2A[idx] = ((1.0 - alpha / A) * invA0).toFloat()
    }

    private fun computeEmuLowPassRbj(filterIndex: Int, f0: Float, q: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)

        val a0 = 1.0 + alpha
        val invA0 = 1.0 / a0
        val bVal = (1.0 - cosW) / 2.0

        emuB0[filterIndex] = (bVal * invA0).toFloat()
        emuB1[filterIndex] = ((1.0 - cosW) * invA0).toFloat()
        emuB2[filterIndex] = (bVal * invA0).toFloat()
        emuA1[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        emuA2[filterIndex] = ((1.0 - alpha) * invA0).toFloat()
    }

    private fun setFilterBypass(filterIndex: Int) {
        b0Array[filterIndex] = 1.0f
        b1Array[filterIndex] = 0.0f
        b2Array[filterIndex] = 0.0f
        a1Array[filterIndex] = 0.0f
        a2Array[filterIndex] = 0.0f
    }

    private fun computeLowShelfRbj(filterIndex: Int, f0: Float, gainDb: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)

        val alpha = sinW * 0.7071067811865475
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha

        val a0 = (A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha
        val invA0 = 1.0 / a0

        b0Array[filterIndex] = (A * ((A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha) * invA0).toFloat()
        b1Array[filterIndex] = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW) * invA0).toFloat()
        b2Array[filterIndex] = (A * ((A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
        a1Array[filterIndex] = (-2.0 * ((A - 1.0) + (A + 1.0) * cosW) * invA0).toFloat()
        a2Array[filterIndex] = (((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
    }

    private fun computePeakingRbj(filterIndex: Int, f0: Float, gainDb: Float, q: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)

        val a0 = 1.0 + alpha / A
        val invA0 = 1.0 / a0

        b0Array[filterIndex] = ((1.0 + alpha * A) * invA0).toFloat()
        b1Array[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        b2Array[filterIndex] = ((1.0 - alpha * A) * invA0).toFloat()
        a1Array[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        a2Array[filterIndex] = ((1.0 - alpha / A) * invA0).toFloat()
    }
}
