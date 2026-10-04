package com.sb.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class VirtualizerManager(private val sampleRate: Int = 48000) {
    @Volatile var isEnabled = false
        private set
    private var strength = 0f

    fun updateConfig(config: DspConfig) {
        isEnabled = config.virtualizerEnabled && config.channels == 2
        strength = config.virtualizerStrength.coerceIn(0f, 1f)
    }

    fun process(buffer: FloatArray, frameCount: Int, channels: Int) {
        if (!isEnabled || channels != 2 || strength <= 0.001f) return
        val width = 1f + strength * 0.65f
        var i = 0
        repeat(frameCount) {
            val l = buffer[i]
            val r = buffer[i + 1]
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * width
            buffer[i] = mid + side
            buffer[i + 1] = mid - side
            i += 2
        }
    }

    fun reset() = Unit
}
