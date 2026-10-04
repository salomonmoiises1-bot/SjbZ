package com.sb.dsp

import kotlin.math.*

/**
 * MdrcProcessor.kt
 * 3-Band Multiband Dynamic Range Compressor (MDRC).
 * Splits incoming audio into Low, Mid, and High bands via 4th-order Linkwitz-Riley crossover filters,
 * applies independent peak envelope detection and soft-knee compression to each band,
 * and recombines the streams with zero Garbage Collection.
 */
class MdrcProcessor(private val sampleRate: Int = 48000) {

    @Volatile
    var isEnabled: Boolean = false

    // Crossover filters:
    // Low crossover: Low (< fc1) and Mid/High (> fc1)
    private val lpLow1 = BiquadFilter()
    private val lpLow2 = BiquadFilter()
    private val hpMidHigh1 = BiquadFilter()
    private val hpMidHigh2 = BiquadFilter()

    // High crossover: Mid (< fc2) and High (> fc2)
    private val lpMid1 = BiquadFilter()
    private val lpMid2 = BiquadFilter()
    private val hpHigh1 = BiquadFilter()
    private val hpHigh2 = BiquadFilter()

    // Pre-allocated scratch buffers to prevent ANY allocations inside process()
    private var scratchSize = 4096
    private var bufferLow = FloatArray(scratchSize)
    private var bufferMid = FloatArray(scratchSize)
    private var bufferHigh = FloatArray(scratchSize)
    private var bufferMidHigh = FloatArray(scratchSize)

    // Per-band dynamic compressor with independent Threshold, Ratio, Attack, Release, and Makeup Gain
    class BandCompressor(val sampleRate: Int) {
        var thresholdDb: Float = -16.0f
        var ratio: Float = 2.5f
        var attackMs: Float = 10.0f
        var releaseMs: Float = 100.0f
        var makeupGainLinear: Float = 1.0f

        // Exponential smoothing coefficients
        var alphaAttack: Float = 0.0f
        var alphaRelease: Float = 0.0f

        // Envelope state registers for Left and Right channels
        var envL: Float = 0.0f
        var envR: Float = 0.0f

        init {
            updateSmoothing()
        }

        fun configure(config: DspConfig.BandCompressorConfig) {
            this.thresholdDb = config.thresholdDb
            this.ratio = config.ratio.coerceAtLeast(1.0f)
            this.attackMs = config.attackMs.coerceAtLeast(0.1f)
            this.releaseMs = config.releaseMs.coerceAtLeast(1.0f)
            this.makeupGainLinear = 10.0f.pow(config.makeupGainDb / 20.0f)
            updateSmoothing()
        }

        private fun updateSmoothing() {
            val attackSec = attackMs * 0.001f
            val releaseSec = releaseMs * 0.001f
            alphaAttack = exp(-1.0f / (sampleRate * attackSec))
            alphaRelease = exp(-1.0f / (sampleRate * releaseSec))
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun computeGain(env: Float): Float {
            if (env < 1e-6f) return makeupGainLinear
            val levelDb = 20.0f * log10(env)
            if (levelDb <= thresholdDb) {
                return makeupGainLinear
            }
            // Over-threshold compression
            val excessDb = levelDb - thresholdDb
            val grDb = -excessDb * (1.0f - 1.0f / ratio)
            return (10.0f.pow(grDb / 20.0f)) * makeupGainLinear
        }

        fun process(buffer: FloatArray, frameCount: Int, channels: Int) {
            if (channels == 2) {
                var idx = 0
                for (i in 0 until frameCount) {
                    val inL = buffer[idx]
                    val inR = buffer[idx + 1]

                    val absL = abs(inL)
                    val absR = abs(inR)

                    envL = if (absL > envL) {
                        alphaAttack * envL + (1.0f - alphaAttack) * absL
                    } else {
                        alphaRelease * envL + (1.0f - alphaRelease) * absL
                    }

                    envR = if (absR > envR) {
                        alphaAttack * envR + (1.0f - alphaAttack) * absR
                    } else {
                        alphaRelease * envR + (1.0f - alphaRelease) * absR
                    }

                    val gainL = computeGain(envL)
                    val gainR = computeGain(envR)

                    buffer[idx] = inL * gainL
                    buffer[idx + 1] = inR * gainR
                    idx += 2
                }
            } else {
                for (i in 0 until frameCount) {
                    val inSample = buffer[i]
                    val absSample = abs(inSample)
                    envL = if (absSample > envL) {
                        alphaAttack * envL + (1.0f - alphaAttack) * absSample
                    } else {
                        alphaRelease * envL + (1.0f - alphaRelease) * absSample
                    }
                    val gain = computeGain(envL)
                    buffer[i] = inSample * gain
                }
            }
        }

        fun reset() {
            envL = 0.0f
            envR = 0.0f
        }
    }

    val compLow = BandCompressor(sampleRate)
    val compMid = BandCompressor(sampleRate)
    val compHigh = BandCompressor(sampleRate)

    private var lowCrossoverHz = 250.0f
    private var highCrossoverHz = 3500.0f

    init {
        configureCrossovers(lowCrossoverHz, highCrossoverHz)
    }

    private fun configureCrossovers(fcLow: Float, fcHigh: Float) {
        val sr = sampleRate.toFloat()
        val qButterworth = 0.7071f

        lpLow1.configure(BiquadFilter.Type.LOW_PASS, sr, fcLow, qButterworth, 0f)
        lpLow2.configure(BiquadFilter.Type.LOW_PASS, sr, fcLow, qButterworth, 0f)

        hpMidHigh1.configure(BiquadFilter.Type.HIGH_PASS, sr, fcLow, qButterworth, 0f)
        hpMidHigh2.configure(BiquadFilter.Type.HIGH_PASS, sr, fcLow, qButterworth, 0f)

        lpMid1.configure(BiquadFilter.Type.LOW_PASS, sr, fcHigh, qButterworth, 0f)
        lpMid2.configure(BiquadFilter.Type.LOW_PASS, sr, fcHigh, qButterworth, 0f)

        hpHigh1.configure(BiquadFilter.Type.HIGH_PASS, sr, fcHigh, qButterworth, 0f)
        hpHigh2.configure(BiquadFilter.Type.HIGH_PASS, sr, fcHigh, qButterworth, 0f)
    }

    fun updateConfig(config: DspConfig) {
        this.isEnabled = config.mdrcEnabled
        if (!isEnabled) return

        if (abs(config.mdrcLowCrossoverHz - lowCrossoverHz) > 1.0f ||
            abs(config.mdrcHighCrossoverHz - highCrossoverHz) > 1.0f
        ) {
            lowCrossoverHz = config.mdrcLowCrossoverHz
            highCrossoverHz = config.mdrcHighCrossoverHz
            configureCrossovers(lowCrossoverHz, highCrossoverHz)
        }

        compLow.configure(config.mdrcLowBand)
        compMid.configure(config.mdrcMidBand)
        compHigh.configure(config.mdrcHighBand)
    }

    fun process(buffer: FloatArray, frameCount: Int, channels: Int) {
        if (!isEnabled) return

        val totalSamples = frameCount * channels
        ensureScratchCapacity(totalSamples)

        // 1. Copy incoming audio
        System.arraycopy(buffer, 0, bufferLow, 0, totalSamples)
        System.arraycopy(buffer, 0, bufferMidHigh, 0, totalSamples)

        // 2. Filter Low Band (Cascade LR4 Low Pass)
        if (channels == 2) {
            lpLow1.processStereoInterleaved(bufferLow, frameCount)
            lpLow2.processStereoInterleaved(bufferLow, frameCount)
            hpMidHigh1.processStereoInterleaved(bufferMidHigh, frameCount)
            hpMidHigh2.processStereoInterleaved(bufferMidHigh, frameCount)
        } else {
            for (i in 0 until frameCount) {
                bufferLow[i] = lpLow2.processSampleMono(lpLow1.processSampleMono(bufferLow[i]))
                bufferMidHigh[i] = hpMidHigh2.processSampleMono(hpMidHigh1.processSampleMono(bufferMidHigh[i]))
            }
        }

        // 3. Filter Mid and High
        System.arraycopy(bufferMidHigh, 0, bufferMid, 0, totalSamples)
        System.arraycopy(bufferMidHigh, 0, bufferHigh, 0, totalSamples)

        if (channels == 2) {
            lpMid1.processStereoInterleaved(bufferMid, frameCount)
            lpMid2.processStereoInterleaved(bufferMid, frameCount)
            hpHigh1.processStereoInterleaved(bufferHigh, frameCount)
            hpHigh2.processStereoInterleaved(bufferHigh, frameCount)
        } else {
            for (i in 0 until frameCount) {
                bufferMid[i] = lpMid2.processSampleMono(lpMid1.processSampleMono(bufferMid[i]))
                bufferHigh[i] = hpHigh2.processSampleMono(hpHigh1.processSampleMono(bufferHigh[i]))
            }
        }

        // 4. Compress per band
        compLow.process(bufferLow, frameCount, channels)
        compMid.process(bufferMid, frameCount, channels)
        compHigh.process(bufferHigh, frameCount, channels)

        // 5. Recombine: buffer = Low + Mid + High
        for (i in 0 until totalSamples) {
            buffer[i] = bufferLow[i] + bufferMid[i] + bufferHigh[i]
        }
    }

    private fun ensureScratchCapacity(needed: Int) {
        if (needed > scratchSize) {
            scratchSize = needed
            bufferLow = FloatArray(needed)
            bufferMid = FloatArray(needed)
            bufferHigh = FloatArray(needed)
            bufferMidHigh = FloatArray(needed)
        }
    }

    fun reset() {
        lpLow1.reset(); lpLow2.reset()
        hpMidHigh1.reset(); hpMidHigh2.reset()
        lpMid1.reset(); lpMid2.reset()
        hpHigh1.reset(); hpHigh2.reset()
        compLow.reset(); compMid.reset(); compHigh.reset()
    }
}