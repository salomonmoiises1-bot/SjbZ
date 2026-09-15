package com.sjbz.aimp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
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
 * Requirements:
 * - Strict C.ENCODING_PCM_FLOAT format negotiation.
 * - Zero heap allocations during queueInput (real-time audio thread safety).
 * - Per-channel Transposed Direct Form II biquad filter state memory.
 * - Coefficient recalculation exclusively when marked dirty.
 * - Real-time mono 2048-sample FFT buffer streaming via fftListener.
 */
class SjbzDspProcessor : BaseAudioProcessor() {

    companion object {
        const val BAND_COUNT = 32
        const val TOTAL_FILTERS = 33 // Index 0: Low-Shelf Bass, Indices 1..32: Peaking EQ bands
        const val MAX_CHANNELS = 8
        const val FFT_BLOCK_SIZE = 2048
        const val DEFAULT_Q = 1.4f
        const val EMU_FILTERS = 4 // 0: Low-Shelf 80Hz, 1: Dip 3kHz, 2: High-Shelf 18kHz, 3: LowPass 18.5kHz

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
    private var emulationAmount: Float = 0.8f // 0.0 to 1.0 (dry/wet)
    private var bluetoothAutoBypass: Boolean = true
    @Volatile
    private var bluetoothConnected: Boolean = false

    // 5-Band Multi-Band Dynamic Range Compressor (MDRC)
    val mdrcProcessor = MDRCProcessor()

    // Emulation Filters (4 Biquads):
    // 0: Signature Low-shelf +2dB @ 80Hz
    // 1: Signature Dip -1.5dB @ 3000Hz (Q=1.4)
    // 2: Signature High-shelf -3dB @ 18000Hz
    // 3: Codec Loss 2nd-order Butterworth Low-pass @ 18500Hz
    private val emuB0 = FloatArray(EMU_FILTERS)
    private val emuB1 = FloatArray(EMU_FILTERS)
    private val emuB2 = FloatArray(EMU_FILTERS)
    private val emuA1 = FloatArray(EMU_FILTERS)
    private val emuA2 = FloatArray(EMU_FILTERS)

    // Per-channel biquad states for Emulation filters:
    // emuS1[channel * EMU_FILTERS + filterIndex], emuS2[...]
    private val emuS1 = FloatArray(MAX_CHANNELS * EMU_FILTERS)
    private val emuS2 = FloatArray(MAX_CHANNELS * EMU_FILTERS)

    // Dynamic Limiter: 1-band, threshold -6dB, ratio 4:1, attack 5ms, release 80ms
    private val limiterThresholdLin = 10.0.pow(-6.0 / 20.0).toFloat() // ~0.5011872f
    private var limiterAlphaAtt: Float = 0.0f
    private var limiterAlphaRel: Float = 0.0f
    private val emuEnv = FloatArray(MAX_CHANNELS)

    // PRNG seed for zero-allocation hardware dither simulation
    private var ditherSeed: Int = 123456789

    // Dirty state flag for coefficient updates
    @Volatile
    private var isDirty: Boolean = true
    private var currentSampleRate: Float = 48000.0f

    // Biquad normalized coefficients: b0, b1, b2, a1, a2 (for 33 filters)
    private val b0Array = FloatArray(TOTAL_FILTERS)
    private val b1Array = FloatArray(TOTAL_FILTERS)
    private val b2Array = FloatArray(TOTAL_FILTERS)
    private val a1Array = FloatArray(TOTAL_FILTERS)
    private val a2Array = FloatArray(TOTAL_FILTERS)

    // Per-channel filter states for Transposed Direct Form II:
    // s1[channel * TOTAL_FILTERS + filterIndex]
    // s2[channel * TOTAL_FILTERS + filterIndex]
    private val s1 = FloatArray(MAX_CHANNELS * TOTAL_FILTERS)
    private val s2 = FloatArray(MAX_CHANNELS * TOTAL_FILTERS)

    // Preallocated ring buffer and dispatch array for 2048-sample FFT streaming
    private val fftRingBuffer = FloatArray(FFT_BLOCK_SIZE)
    private val fftDispatchBuffer = FloatArray(FFT_BLOCK_SIZE)
    private var fftRingIndex: Int = 0

    /**
     * Callback for real-time spectrum analysis. Dispatches a 2048-sample mono block.
     */
    var fftListener: ((FloatArray) -> Unit)? = null

    init {
        recalculateCoefficients()
    }

    // -------------------------------------------------------------------------
    // AudioProcessor Lifecycle & Configuration
    // -------------------------------------------------------------------------

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        currentSampleRate = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        mdrcProcessor.setSampleRate(currentSampleRate)
        isDirty = true
        return inputAudioFormat
    }

    override fun onReset() {
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

    // -------------------------------------------------------------------------
    // Zero-Allocation Real-Time Audio Buffer Processing
    // -------------------------------------------------------------------------

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return

        if (isDirty) {
            recalculateCoefficients()
        }

        // BaseAudioProcessor allocates/reuses the internal output ByteBuffer
        val outputBuffer = replaceOutputBuffer(remainingBytes)
        outputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())

        val channelCount = inputAudioFormat.channelCount.coerceIn(1, MAX_CHANNELS)
        val totalFloats = remainingBytes / 4
        val frames = totalFloats / channelCount

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

            for (ch in 0 until channelCount) {
                var x = inputBuffer.float * localLinearPreamp

                if (localMasterEnabled) {
                    val chOffset = ch * TOTAL_FILTERS

                    // Cascade through 33 Biquad Filters (Direct Form II Transposed)
                    // Index 0: Low-Shelf Bass
                    // Indices 1..32: 32 ISO Peaking EQ bands
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

                    // ATS2835P Hardware Emulation Block (Input -> Bass -> 32x Peaking -> ATS2835P Emu -> Soft Clipper -> Output)
                    if (isEmuActive) {
                        val dryX = x
                        var wetX = x
                        val emuOffset = ch * EMU_FILTERS

                        // 1. Signature EQ (Filters 0, 1, 2) + Codec Loss Low-Pass (Filter 3)
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

                        // 2. Codec Loss Dither (simulating subtle hardware DAC noise floor)
                        ditherSeed = ditherSeed * 1664525 + 1013904223
                        val dither = ((ditherSeed and 0xFFFF) - 32768) * 0.0000003f
                        wetX += dither

                        // 3. Dynamic Limiter: 1-band compressor, threshold -6dB, ratio 4:1, attack 5ms, release 80ms
                        val absX = abs(wetX)
                        var env = emuEnv[ch]
                        if (absX > env) {
                            env = localAlphaAtt * env + (1.0f - localAlphaAtt) * absX
                        } else {
                            env = localAlphaRel * env + (1.0f - localAlphaRel) * absX
                        }
                        emuEnv[ch] = env

                        if (env > localLimiterThresh) {
                            // Ratio 4:1 -> gain = (threshold / env)^(1 - 1/ratio) = (threshold / env)^0.75
                            val ratioFactor = localLimiterThresh / env
                            val gain = ratioFactor.pow(0.75f)
                            wetX *= gain
                        }

                        // 4. Wet/Dry mix with amount
                        x = (1.0f - localEmuAmount) * dryX + localEmuAmount * wetX
                    }

                    // 5-Band Multi-Band Dynamic Range Compressor (MDRC)
                    x = mdrcProcessor.processSample(x, ch)

                    // Analog Soft Clipper: tanh(x) prevents harsh 0dBFS digital intersample clipping
                    x = tanh(x.toDouble()).toFloat()
                }

                outputBuffer.putFloat(x)
                frameMonoSum += x
            }

            // Downmix to mono for FFT Spectrum Analysis
            fftRingBuffer[fftRingIndex++] = frameMonoSum / channelCount
            if (fftRingIndex >= FFT_BLOCK_SIZE) {
                fftRingIndex = 0
                if (localListener != null) {
                    System.arraycopy(fftRingBuffer, 0, fftDispatchBuffer, 0, FFT_BLOCK_SIZE)
                    localListener.invoke(fftDispatchBuffer)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // DSP Parameters Configuration (Thread-Safe Dirty Notification)
    // -------------------------------------------------------------------------

    fun setPreamp(gainDb: Float) {
        val clamped = gainDb.coerceIn(-12.0f, 12.0f)
        if (abs(this.preampDb - clamped) > 0.001f) {
            this.preampDb = clamped
            this.linearPreamp = 10.0.pow(clamped / 20.0).toFloat()
        }
    }

    fun getPreamp(): Float = preampDb

    fun setBassBoost(enabled: Boolean, freqHz: Float, gainDb: Float) {
        val clampedGain = gainDb.coerceIn(0.0f, 12.0f)
        val validFreq = when {
            freqHz <= 70f -> 60.0f
            freqHz <= 100f -> 85.0f
            else -> 120.0f
        }
        if (bassEnabled != enabled || abs(bassFreqHz - validFreq) > 0.1f || abs(bassGainDb - clampedGain) > 0.01f) {
            bassEnabled = enabled
            bassFreqHz = validFreq
            bassGainDb = clampedGain
            isDirty = true
        }
    }

    fun isBassBoostEnabled(): Boolean = bassEnabled
    fun getBassBoostFreq(): Float = bassFreqHz
    fun getBassBoostGain(): Float = bassGainDb

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until BAND_COUNT) {
            val clamped = gainDb.coerceIn(-12.0f, 12.0f)
            if (abs(bandGainsDb[bandIndex] - clamped) > 0.01f) {
                bandGainsDb[bandIndex] = clamped
                isDirty = true
            }
        }
    }

    fun getBandGain(bandIndex: Int): Float {
        return if (bandIndex in 0 until BAND_COUNT) bandGainsDb[bandIndex] else 0.0f
    }

    // -------------------------------------------------------------------------
    // ATS2835P Emulation Mode Configuration & Bluetooth Auto-Bypass
    // -------------------------------------------------------------------------

    fun setEmulationEnabled(enabled: Boolean) {
        if (emulationEnabled != enabled) {
            emulationEnabled = enabled
            isDirty = true
        }
    }

    fun isEmulationEnabled(): Boolean = emulationEnabled

    fun setEmulationAmount(amount: Float) {
        val clamped = amount.coerceIn(0.0f, 1.0f)
        if (abs(emulationAmount - clamped) > 0.001f) {
            emulationAmount = clamped
        }
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

    fun isEmulationActive(): Boolean {
        return masterEnabled && emulationEnabled && (!bluetoothAutoBypass || !bluetoothConnected)
    }

    // -------------------------------------------------------------------------
    // 5-Band Multi-Band Dynamic Range Compressor (MDRC) Configuration
    // -------------------------------------------------------------------------

    fun setMdrcEnabled(enabled: Boolean) {
        mdrcProcessor.isEnabled = enabled
    }

    fun isMdrcEnabled(): Boolean = mdrcProcessor.isEnabled

    fun setMdrcBandGain(bandIndex: Int, gainDb: Float) {
        mdrcProcessor.setBandGain(bandIndex, gainDb)
    }

    fun getMdrcBandGain(bandIndex: Int): Float = mdrcProcessor.getBandGain(bandIndex)

    fun setMdrcDynamics(thresholdDb: Float, ratio: Float, attackMs: Float = 10f, releaseMs: Float = 80f) {
        mdrcProcessor.setDynamics(thresholdDb, ratio, attackMs, releaseMs)
    }

    fun getMdrcThreshold(): Float = mdrcProcessor.thresholdDb
    fun getMdrcRatio(): Float = mdrcProcessor.ratio
    fun getMdrcAttack(): Float = mdrcProcessor.attackMs
    fun getMdrcRelease(): Float = mdrcProcessor.releaseMs
    fun getMdrcGainReduction(): Float = mdrcProcessor.currentGainReductionDb

    // -------------------------------------------------------------------------
    // Robert Bristow-Johnson (RBJ) Audio EQ Cookbook Coefficient Calculation
    // -------------------------------------------------------------------------

    private fun recalculateCoefficients() {
        val Fs = currentSampleRate

        // 1. Compute Filter 0: Low-Shelf Bass Boost
        if (!bassEnabled || bassGainDb <= 0.01f) {
            setFilterBypass(0)
        } else {
            computeLowShelfRbj(
                filterIndex = 0,
                f0 = bassFreqHz,
                gainDb = bassGainDb,
                sampleRate = Fs
            )
        }

        // 2. Compute Filters 1..32: Peaking EQ ISO Bands
        for (i in 0 until BAND_COUNT) {
            val filterIndex = i + 1
            val gain = bandGainsDb[i]
            val freq = ISO_FREQUENCIES[i]

            if (abs(gain) < 0.01f) {
                setFilterBypass(filterIndex)
            } else {
                computePeakingRbj(
                    filterIndex = filterIndex,
                    f0 = freq,
                    gainDb = gain,
                    q = DEFAULT_Q,
                    sampleRate = Fs
                )
            }
        }

        // 3. Compute ATS2835P Hardware Emulation Coefficients (4 Biquads + Limiter)
        recalculateEmuCoefficients()

        isDirty = false
    }

    private fun recalculateEmuCoefficients() {
        val Fs = currentSampleRate

        // Filter 0: Signature Low-Shelf +2dB @ 80Hz
        computeEmuLowShelfRbj(
            filterIndex = 0,
            f0 = 80.0f,
            gainDb = 2.0f,
            sampleRate = Fs
        )

        // Filter 1: Signature Dip -1.5dB @ 3000Hz (Peaking, Q=1.4)
        computeEmuPeakingRbj(
            filterIndex = 1,
            f0 = 3000.0f,
            gainDb = -1.5f,
            q = 1.4f,
            sampleRate = Fs
        )

        // Filter 2: Signature High-Shelf -3dB @ 18000Hz (clamped below Nyquist for 44.1k/48k/96k)
        val highShelfFreq = (Fs * 0.45f).coerceAtMost(18000.0f)
        computeEmuHighShelfRbj(
            filterIndex = 2,
            f0 = highShelfFreq,
            gainDb = -3.0f,
            sampleRate = Fs
        )

        // Filter 3: Codec Loss 2nd-order Butterworth Low-pass @ 18500Hz
        val lowPassFreq = (Fs * 0.45f).coerceAtMost(18500.0f)
        computeEmuLowPassRbj(
            filterIndex = 3,
            f0 = lowPassFreq,
            q = 0.70710678f,
            sampleRate = Fs
        )

        // Limiter Envelope Follower attack (5ms) & release (80ms) time constants
        limiterAlphaAtt = kotlin.math.exp(-1.0 / (Fs * 0.005)).toFloat()
        limiterAlphaRel = kotlin.math.exp(-1.0 / (Fs * 0.080)).toFloat()
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

    private fun computeEmuPeakingRbj(filterIndex: Int, f0: Float, gainDb: Float, q: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)

        val a0 = 1.0 + alpha / A
        val invA0 = 1.0 / a0

        emuB0[filterIndex] = ((1.0 + alpha * A) * invA0).toFloat()
        emuB1[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        emuB2[filterIndex] = ((1.0 - alpha * A) * invA0).toFloat()
        emuA1[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        emuA2[filterIndex] = ((1.0 - alpha / A) * invA0).toFloat()
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

    /**
     * RBJ Audio EQ Cookbook - Low-Shelf Filter
     */
    private fun computeLowShelfRbj(filterIndex: Int, f0: Float, gainDb: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)

        // For shelf slope S = 1.0, alpha = (sin(w0) / 2) * sqrt(2)
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

    /**
     * RBJ Audio EQ Cookbook - Peaking EQ Filter
     */
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
