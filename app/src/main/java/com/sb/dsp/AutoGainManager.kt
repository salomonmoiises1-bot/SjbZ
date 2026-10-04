package com.sb.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

class AutoGainManager(private val sampleRate: Int = 48000) {
    @Volatile var isEnabled = false
        private set
    @Volatile var gainDb = 0f
        private set

    private var targetDb = -14f
    private var smoothedRms = 1e-4f

    fun updateConfig(config: DspConfig) {
        isEnabled = config.autoGainEnabled
        targetDb = config.autoGainTargetRmsDb
        if (!isEnabled) gainDb = 0f
    }

    fun process(buffer: FloatArray, frameCount: Int, channels: Int) {
        if (!isEnabled || frameCount <= 0) return
        val n = (frameCount * channels).coerceAtMost(buffer.size)
        var sum = 0.0
        for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i].toDouble()
        val rms = sqrt(max(1e-12, sum / n.toDouble())).toFloat()
        smoothedRms = 0.98f * smoothedRms + 0.02f * rms
        val rmsDb = 20f * (ln(smoothedRms.toDouble()) / ln(10.0)).toFloat()
        gainDb = (targetDb - rmsDb).coerceIn(-12f, 12f)
        val linear = 10f.powDb(gainDb)
        for (i in 0 until n) buffer[i] *= linear
    }

    fun reset() {
        gainDb = 0f
        smoothedRms = 1e-4f
    }

    private fun Float.powDb(db: Float): Float =
        kotlin.math.exp((db * kotlin.math.ln(10f) / 20f).toDouble()).toFloat()
}
