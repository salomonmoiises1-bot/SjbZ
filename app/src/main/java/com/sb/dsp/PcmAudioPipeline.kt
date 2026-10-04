package com.sb.dsp

import kotlin.math.pow

class PcmAudioPipeline(
    val sampleRate: Int = 48000,
    val channels: Int = 2
) {
    companion object {
        private const val INV_32768 = 1f / 32768f
        private const val SCALE_32767 = 32767f
        private const val DEFAULT_BUFFER_SIZE = 4096
    }

    @Volatile
    var currentConfig: DspConfig = DspConfig(sampleRate = sampleRate, channels = channels)
        private set

    val graphicEq = ConstantQGraphicEq(sampleRate)
    val bassBoost = BassBoostManager(sampleRate)
    val tone = ToneManager(sampleRate)
    val mdrc = MdrcProcessor(sampleRate)
    val virtualizer = VirtualizerManager(sampleRate)
    val headroom = HeadroomManager(sampleRate)
    val autoGain = AutoGainManager(sampleRate)

    private var scratchCapacity = DEFAULT_BUFFER_SIZE
    private var floatScratch = FloatArray(scratchCapacity)
    private var pregainLinear = 1f

    init { updateConfig(currentConfig) }

    fun updateConfig(newConfig: DspConfig) {
        val config = newConfig.validate()
        currentConfig = config
        pregainLinear = if (config.pregainEnabled) 10f.pow(config.pregainDb / 20f) else 1f
        graphicEq.updateConfig(config)
        bassBoost.updateConfig(config)
        tone.updateConfig(config)
        mdrc.updateConfig(config)
        virtualizer.updateConfig(config)
        headroom.updateConfig(config)
        autoGain.updateConfig(config)
    }

    fun process(pcmBuffer: ShortArray, offset: Int, sampleCount: Int) {
        val config = currentConfig
        if (!config.masterEnabled || sampleCount <= 0) return
        require(offset >= 0 && offset + sampleCount <= pcmBuffer.size)
        require(sampleCount % channels == 0)

        ensureFloatScratchCapacity(sampleCount)
        val scratch = floatScratch

        for (i in 0 until sampleCount) {
            scratch[i] = pcmBuffer[offset + i] * INV_32768
        }

        // Método PCM: PreGain -> Bass -> Tone -> EQ -> MDRC -> AutoGain -> Limiter -> Spatial -> Master/Balance.
        if (config.pregainEnabled) {
            val g = pregainLinear
            for (i in 0 until sampleCount) scratch[i] *= g
        }
        if (config.bassBoostEnabled) bassBoost.process(scratch, sampleCount / channels, channels)
        if (config.toneEnabled) tone.process(scratch, sampleCount / channels, channels)
        if (config.eqEnabled) graphicEq.process(scratch, sampleCount / channels, channels)
        if (config.mdrcEnabled) mdrc.process(scratch, sampleCount / channels, channels)
        if (config.autoGainEnabled) autoGain.process(scratch, sampleCount / channels, channels)
        if (config.headroomEnabled) headroom.process(scratch, sampleCount / channels, channels)
        if (config.virtualizerEnabled && channels == 2) virtualizer.process(scratch, sampleCount / channels, channels)

        applyMasterAndBalance(scratch, sampleCount, config)

        for (i in 0 until sampleCount) {
            pcmBuffer[offset + i] = (scratch[i] * SCALE_32767)
                .coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    private fun applyMasterAndBalance(buffer: FloatArray, samples: Int, config: DspConfig) {
        val master = 10f.pow(config.masterGainDb / 20f)
        val b = config.balance.coerceIn(-1f, 1f)
        val left = if (b > 0f) 1f - b else 1f
        val right = if (b < 0f) 1f + b else 1f
        var i = 0
        while (i + 1 < samples && channels == 2) {
            buffer[i] *= master * left
            buffer[i + 1] *= master * right
            i += 2
        }
        if (channels == 1) for (j in 0 until samples) buffer[j] *= master
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
        tone.reset()
        mdrc.reset()
        virtualizer.reset()
        headroom.reset()
        autoGain.reset()
    }
}
