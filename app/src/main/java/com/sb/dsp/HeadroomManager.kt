package com.sb.dsp

import kotlin.math.*

class HeadroomManager(private val sampleRate: Int = 48000) {
    @Volatile
    var isEnabled: Boolean = true
    private var headroomDb: Float = -1.0f
    private var headroomLinear: Float = 0.89125f
    private val limitThreshold = 0.95f
    private val limitRange = 1.0f - limitThreshold

    @Volatile
    var peakLevelObserved: Float = 0.0f
        private set

    fun updateConfig(config: DspConfig) {
        this.isEnabled = config.headroomEnabled
        if (!isEnabled) return
        this.headroomDb = config.headroomDb.coerceIn(-12.0f, 0.0f)
        this.headroomLinear = 10.0f.pow(headroomDb / 20.0f)
    }

    fun process(buffer: FloatArray, frameCount: Int, channels: Int) {
        if (!isEnabled) return
        val total = frameCount * channels
        val margin = headroomLinear
        var maxPeak = 0.0f

        for (i in 0 until total) {
            var s = buffer[i] * margin
            val absS = abs(s)
            if (absS > maxPeak) maxPeak = absS

            if (absS > limitThreshold) {
                val sign = if (s >= 0.0f) 1.0f else -1.0f
                val excess = (absS - limitThreshold) / limitRange
                val compressed = excess / (1.0f + excess)
                s = sign * (limitThreshold + compressed * limitRange * 0.98f)
            }
            buffer[i] = s.coerceIn(-0.9999f, 0.9999f)
        }
        peakLevelObserved = maxPeak
    }

    fun reset() {
        peakLevelObserved = 0.0f
    }
}