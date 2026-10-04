package com.sb.dsp

import kotlin.math.*

class BassBoostManager(private val sampleRate: Int = 48000) {
    @Volatile
    var isEnabled: Boolean = false
    private var strength: Float = 0.0f
    private var centerFreq: Float = 80.0f
    private val bassFilter = BiquadFilter()

    fun updateConfig(config: DspConfig) {
        this.isEnabled = config.bassBoostEnabled
        if (!isEnabled) return
        this.strength = config.bassBoostStrength.coerceIn(0.0f, 1.0f)
        this.centerFreq = config.bassBoostCenterFreq.coerceIn(30.0f, 160.0f)
        val gainDb = strength * 12.0f
        bassFilter.configure(BiquadFilter.Type.LOW_SHELF, sampleRate.toFloat(), centerFreq, 0.9f, gainDb)
    }

    fun process(buffer: FloatArray, frameCount: Int, channels: Int) {
        if (!isEnabled || strength <= 0.001f) return
        if (channels == 2) bassFilter.processStereoInterleaved(buffer, frameCount)
        else {
            for (i in 0 until frameCount) buffer[i] = bassFilter.processSampleMono(buffer[i])
        }
    }

    fun reset() = bassFilter.reset()
}