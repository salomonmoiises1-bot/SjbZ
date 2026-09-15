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

class SjbzDspProcessor : BaseAudioProcessor() {

    companion object {
        const val BAND_COUNT = 32
        const val TOTAL_FILTERS = 33
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

    var masterEnabled: Boolean = true
    private var preampDb: Float = 0.0f
    private var linearPreamp: Float = 1.0f
    private var bassEnabled: Boolean = true
    private var bassFreqHz: Float = 85.0f
    private var bassGainDb: Float = 0.0f
    private val bandGainsDb = FloatArray(BAND_COUNT)
    private var emulationEnabled: Boolean = false
    private var emulationAmount: Float = 0.8f
    private var bluetoothAutoBypass: Boolean = true
    @Volatile private var bluetoothConnected: Boolean = false
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
    @Volatile private var isDirty: Boolean = true
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

    init { recalculateCoefficients() }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding!= C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        currentSampleRate = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        isDirty = true
        return inputAudioFormat
    }

    override fun onReset() {
        s1.fill(0.0f); s2.fill(0.0f); emuS1.fill(0.0f); emuS2.fill(0.0f); emuEnv.fill(0.0f)
        fftRingBuffer.fill(0.0f); fftRingIndex = 0; isDirty = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return
        if (isDirty) recalculateCoefficients()
        val outputBuffer = replaceOutputBuffer(remainingBytes)
        outputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        val channelCount = inputAudioFormat.channelCount.coerceIn(1, MAX_CHANNELS)
        val totalFloats = remainingBytes / 4
        val frames = totalFloats / channelCount
        val localMasterEnabled = masterEnabled
        val localLinearPreamp = linearPreamp
        val localListener = fftListener
        val isEmuActive = localMasterEnabled && emulationEnabled && (!bluetoothAutoBypass ||!bluetoothConnected)
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
                    for (f in 0 until TOTAL_FILTERS) {
                        val stateIdx = chOffset + f
                        val y = b0Array[f] * x + s1[stateIdx]
                        s1[stateIdx] = b1Array[f] * x - a1Array[f] * y + s2[stateIdx]
                        s2[stateIdx] = b2Array[f] * x - a2Array[f] * y
                        x = y
                    }
                    if (isEmuActive) {
                        val dryX = x
                        var wetX = x
                        val emuOffset = ch * EMU_FILTERS
                        for (ef in 0 until EMU_FILTERS) {
                            val sIdx = emuOffset + ef
                            val ey = emuB0[ef] * wetX + emuS1[sIdx]
                            emuS1[sIdx] = emuB1[ef] * wetX - emuA1[ef] * ey + emuS2[sIdx]
                            emuS2[sIdx] = emuB2[ef] * wetX - emuA2[ef] * ey
                            wetX = ey
                        }
                        ditherSeed = ditherSeed * 1664525 + 1013904223
                        wetX += ((ditherSeed and 0xFFFF) - 32768) * 0.0000003f
                        val absX = abs(wetX)
                        var env = emuEnv[ch]
                        env = if (absX > env) localAlphaAtt * env + (1.0f - localAlphaAtt) * absX else localAlphaRel * env + (1.0f - localAlphaRel) * absX
                        emuEnv[ch] = env
                        if (env > localLimiterThresh) wetX *= (localLimiterThresh / env).pow(0.75f)
                        x = (1.0f - localEmuAmount) * dryX + localEmuAmount * wetX
                    }
                    x = tanh(x.toDouble()).toFloat()
                }
                outputBuffer.putFloat(x)
                frameMonoSum += x
            }
            fftRingBuffer[fftRingIndex++] = frameMonoSum / channelCount
            if (fftRingIndex >= FFT_BLOCK_SIZE) {
                fftRingIndex = 0
                localListener?.let {
                    System.arraycopy(fftRingBuffer, 0, fftDispatchBuffer, 0, FFT_BLOCK_SIZE)
                    it.invoke(fftDispatchBuffer)
                }
            }
        }
    }

    fun setPreamp(gainDb: Float) {
        val clamped = gainDb.coerceIn(-12.0f, 12.0f)
        if (abs(preampDb - clamped) > 0.001f) {
            preampDb = clamped
            linearPreamp = 10.0.pow(clamped / 20.0).toFloat()
        }
    }
    fun getPreamp(): Float = preampDb
    fun setBassBoost(enabled: Boolean, freqHz: Float, gainDb: Float) {
        val clampedGain = gainDb.coerceIn(0.0f, 12.0f)
        val validFreq = when { freqHz <= 70f -> 60.0f; freqHz <= 100f -> 85.0f; else -> 120.0f }
        if (bassEnabled!= enabled || abs(bassFreqHz - validFreq) > 0.1f || abs(bassGainDb - clampedGain) > 0.01f) {
            bassEnabled = enabled; bassFreqHz = validFreq; bassGainDb = clampedGain; isDirty = true
        }
    }
    fun isBassBoostEnabled(): Boolean = bassEnabled
    fun getBassBoostFreq(): Float = bassFreqHz
    fun getBassBoostGain(): Float = bassGainDb
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until BAND_COUNT) {
            val clamped = gainDb.coerceIn(-12.0f, 12.0f)
            if (abs(bandGainsDb[bandIndex] - clamped) > 0.01f) { bandGainsDb[bandIndex] = clamped; isDirty = true }
        }
    }
    fun getBandGain(bandIndex: Int): Float = if (bandIndex in 0 until BAND_COUNT) bandGainsDb[bandIndex] else 0.0f
    fun getBandGainsCopy(): FloatArray = bandGainsDb.clone()
    fun setEmulationEnabled(enabled: Boolean) { if (emulationEnabled!= enabled) { emulationEnabled = enabled; isDirty = true } }
    fun isEmulationEnabled(): Boolean = emulationEnabled
    fun setEmulationAmount(amount: Float) { emulationAmount = amount.coerceIn(0.0f, 1.0f) }
    fun getEmulationAmount(): Float = emulationAmount
    fun setBluetoothAutoBypass(enabled: Boolean) { bluetoothAutoBypass = enabled }
    fun isBluetoothAutoBypass(): Boolean = bluetoothAutoBypass
    fun setBluetoothConnected(connected: Boolean) { bluetoothConnected = connected }
    fun isBluetoothConnected(): Boolean = bluetoothConnected
    fun isEmulationActive(): Boolean = masterEnabled && emulationEnabled && (!bluetoothAutoBypass ||!bluetoothConnected)

    private fun recalculateCoefficients() {
        val Fs = currentSampleRate
        if (!bassEnabled || bassGainDb <= 0.01f) setFilterBypass(0)
        else computeLowShelfRbj(0, bassFreqHz, bassGainDb, Fs)
        for (i in 0 until BAND_COUNT) {
            val filterIndex = i + 1
            val gain = bandGainsDb[i]
            if (abs(gain) < 0.01f) setFilterBypass(filterIndex)
            else computePeakingRbj(filterIndex, ISO_FREQUENCIES[i], gain, DEFAULT_Q, Fs)
        }
        recalculateEmuCoefficients()
        isDirty = false
    }

    private fun recalculateEmuCoefficients() {
        val Fs = currentSampleRate
        computeEmuLowShelfRbj(0, 80.0f, 2.0f, Fs)
        computeEmuPeakingRbj(1, 3000.0f, -1.5f, 1.4f, Fs)
        val highShelfFreq = (Fs * 0.45f).coerceAtMost(18000.0f)
        computeEmuHighShelfRbj(2, highShelfFreq, -3.0f, Fs)
        val lowPassFreq = (Fs * 0.45f).coerceAtMost(18500.0f)
        computeEmuLowPassRbj(3, lowPassFreq, 0.70710678f, Fs)
        limiterAlphaAtt = kotlin.math.exp(-1.0 / (Fs * 0.005)).toFloat()
        limiterAlphaRel = kotlin.math.exp(-1.0 / (Fs * 0.080)).toFloat()
    }

    private fun computeEmuLowShelfRbj(filterIndex: Int, f0: Float, gainDb: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0); val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0); val sinW = sin(w0); val alpha = sinW * 0.7071067811865475
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha; val a0 = (A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha; val invA0 = 1.0 / a0
        emuB0[filterIndex] = (A * ((A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha) * invA0).toFloat()
        emuB1[filterIndex] = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW) * invA0).toFloat()
        emuB2[filterIndex] = (A * ((A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
        emuA1[filterIndex] = (-2.0 * ((A - 1.0) + (A + 1.0) * cosW) * invA0).toFloat()
        emuA2[filterIndex] = (((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
    }

    private fun computeEmuPeakingRbj(filterIndex: Int, f0: Float, gainDb: Float, q: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0); val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0); val sinW = sin(w0); val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha / A; val invA0 = 1.0 / a0
        emuB0[filterIndex] = ((1.0 + alpha * A) * invA0).toFloat()
        emuB1[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        emuB2[filterIndex] = ((1.0 - alpha * A) * invA0).toFloat()
        emuA1[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        emuA2[filterIndex] = ((1.0 - alpha / A) * invA0).toFloat()
    }

    private fun computeEmuHighShelfRbj(filterIndex: Int, f0: Float, gainDb: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0); val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0); val sinW = sin(w0); val alpha = sinW * 0.7071067811865475
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha; val a0 = (A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha; val invA0 = 1.0 / a0
        emuB0[filterIndex] = (A * ((A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha) * invA0).toFloat()
        emuB1[filterIndex] = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cosW) * invA0).toFloat()
        emuB2[filterIndex] = (A * ((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
        emuA1[filterIndex] = (2.0 * ((A - 1.0) - (A + 1.0) * cosW) * invA0).toFloat()
        emuA2[filterIndex] = (((A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
    }

    private fun computeEmuLowPassRbj(filterIndex: Int, f0: Float, q: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val w0 = 2.0 * Math.PI * freq / sampleRate; val cosW = cos(w0); val sinW = sin(w0); val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha; val invA0 = 1.0 / a0; val bVal = (1.0 - cosW) / 2.0
        emuB0[filterIndex] = (bVal * invA0).toFloat()
        emuB1[filterIndex] = ((1.0 - cosW) * invA0).toFloat()
        emuB2[filterIndex] = (bVal * invA0).toFloat()
        emuA1[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        emuA2[filterIndex] = ((1.0 - alpha) * invA0).toFloat()
    }

    private fun setFilterBypass(filterIndex: Int) {
        b0Array[filterIndex] = 1.0f; b1Array[filterIndex] = 0.0f; b2Array[filterIndex] = 0.0f; a1Array[filterIndex] = 0.0f; a2Array[filterIndex] = 0.0f
    }

    private fun computeLowShelfRbj(filterIndex: Int, f0: Float, gainDb: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0); val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0); val sinW = sin(w0); val alpha = sinW * 0.7071067811865475
        val twoSqrtAAlpha = 2.0 * sqrt(A) * alpha; val a0 = (A + 1.0) + (A - 1.0) * cosW + twoSqrtAAlpha; val invA0 = 1.0 / a0
        b0Array[filterIndex] = (A * ((A + 1.0) - (A - 1.0) * cosW + twoSqrtAAlpha) * invA0).toFloat()
        b1Array[filterIndex] = (2.0 * A * ((A - 1.0) - (A + 1.0) * cosW) * invA0).toFloat()
        b2Array[filterIndex] = (A * ((A + 1.0) - (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
        a1Array[filterIndex] = (-2.0 * ((A - 1.0) + (A + 1.0) * cosW) * invA0).toFloat()
        a2Array[filterIndex] = (((A + 1.0) + (A - 1.0) * cosW - twoSqrtAAlpha) * invA0).toFloat()
    }

    private fun computePeakingRbj(filterIndex: Int, f0: Float, gainDb: Float, q: Float, sampleRate: Float) {
        val freq = f0.coerceIn(10.0f, sampleRate * 0.49f)
        val A = 10.0.pow(gainDb / 40.0); val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0); val sinW = sin(w0); val alpha = sinW / (2.0 * q)
        val a0 = 1.0 + alpha / A; val invA0 = 1.0 / a0
        b0Array[filterIndex] = ((1.0 + alpha * A) * invA0).toFloat()
        b1Array[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        b2Array[filterIndex] = ((1.0 - alpha * A) * invA0).toFloat()
        a1Array[filterIndex] = ((-2.0 * cosW) * invA0).toFloat()
        a2Array[filterIndex] = ((1.0 - alpha / A) * invA0).toFloat()
    }
}
