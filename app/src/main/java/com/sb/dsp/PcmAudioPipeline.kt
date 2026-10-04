package com.sb.dsp

import kotlin.math.pow

class PcmAudioPipeline(
    val sampleRate: Int = 48000,
    val channels: Int = 2
) {
    companion object {
        private const val INV_32768 = 1.0f / 32768.0f
        private const val SCALE_32767 = 32767.0f
        private const val DEFAULT_BUFFER_SIZE = 4096
    }

    @Volatile
    var currentConfig: DspConfig = DspConfig(sampleRate = sampleRate, channels = channels)
        private set

    val graphicEq = ConstantQGraphicEq(sampleRate)
    val bassBoost = BassBoostManager(sampleRate)
    val mdrc = MdrcProcessor(sampleRate)
    val tone = ToneManager(sampleRate)
    val virtualizer = VirtualizerManager(sampleRate)
    val headroom = HeadroomManager(sampleRate)
    val autoGain = AutoGainManager(sampleRate)

    private var scratchCapacity = DEFAULT_BUFFER_SIZE
    private var floatScratch = FloatArray(scratchCapacity)

    @Volatile
    private var pregainLinear: Float = 1.0f

    init {
        updateConfig(currentConfig)
    }

    fun updateConfig(newConfig: DspConfig) {
        this.currentConfig = newConfig
        pregainLinear = if (newConfig.pregainEnabled) {
            10.0f.pow(newConfig.pregainDb / 20.0f)
        } else {
            1.0f
        }

        graphicEq.updateConfig(newConfig)
        bassBoost.updateConfig(newConfig)
        mdrc.updateConfig(newConfig)
        tone.updateConfig(newConfig)
        virtualizer.updateConfig(newConfig)
        headroom.updateConfig(newConfig)
        autoGain.updateConfig(newConfig)
    }

    fun process(pcmBuffer: ShortArray, offset: Int, sampleCount: Int) {
        val config = currentConfig
        if (!config.masterEnabled) return

        val frameCount = sampleCount / channels
        ensureFloatScratchCapacity(sampleCount)

        // Step 0: Unpack 16-bit Short PCM to 32-bit Normalized Float [-1.0, 1.0] (Zero GC)
        val scratch = floatScratch
        val inv = INV_32768
        var sIdx = offset
        for (i in 0 until sampleCount) {
            scratch[i] = pcmBuffer[sIdx] * inv
            sIdx++
        }

        // Step 1: Active Graphic Equalizer (Strict Mutual Exclusion: 10, 20 or 32 Bands)
        if (config.eqEnabled) {
            graphicEq.process(scratch, frameCount, channels)
        }

        // Step 2: Pregain
        if (config.pregainEnabled) {
            val gain = pregainLinear
            for (i in 0 until sampleCount) {
                scratch[i] *= gain
            }
        }

        // Step 3: Bass Boost
        if (config.bassBoostEnabled) {
            bassBoost.process(scratch, frameCount, channels)
        }

        // Step 4: MDRC (Multiband Dynamic Range Compression)
        if (config.mdrcEnabled) {
            mdrc.process(scratch, frameCount, channels)
        }

        // Step 5: Tone Controls (Bass & Treble Shelving)
        if (config.toneEnabled) {
            tone.process(scratch, frameCount, channels)
        }

        if (config.virtualizerEnabled && channels == 2) {
            virtualizer.process(scratch, frameCount, channels)
        }

        if (config.autoGainEnabled) {
            autoGain.process(scratch, frameCount, channels)
        }

        // Step 6: Headroom Manager & Safety Anti-Clipping Soft Limiter
        if (config.headroomEnabled) {
            headroom.process(scratch, frameCount, channels)
        }

        // Step 7: Repack Normalized Float [-1.0, 1.0] to 16-bit Short PCM for DAC Output
        val scale = SCALE_32767
        var dIdx = offset
        for (i in 0 until sampleCount) {
            val fVal = scratch[i] * scale
            val clamped = when {
                fVal >= 32767.0f -> 32767
                fVal <= -32768.0f -> -32768
                else -> fVal.toInt()
            }
            pcmBuffer[dIdx] = clamped.toShort()
            dIdx++
        }
    }

    private fun ensureFloatScratchCapacity(needed: Int) {
        if (needed > scratchCapacity) {
            scratchCapacity = needed
            floatScratch = FloatArray(needed)
        }
    }

    fun reset() {
        graphicEq.reset()
        bassBoost.reset()
        mdrc.reset()
        tone.reset()
        virtualizer.reset()
        headroom.reset()
        autoGain.reset()
    }
}