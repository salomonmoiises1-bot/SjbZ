package com.sjbz.aimp.audio

/**
 * Brickwall Limiter Processor modeled after the ATS2835P QFN68 DSP hardware protection stage.
 * Threshold: -0.3 dBFS
 * Attack: 1.0 ms
 * Release: 100.0 ms
 * Protects speakers, amplifiers and headphones from digital inter-sample clipping and overload.
 */
class LimiterProcessor {

    companion object {
        const val DEFAULT_THRESHOLD_DB = -1.0f
        const val DEFAULT_ATTACK_MS = 1.0f
        const val DEFAULT_RELEASE_MS = 100.0f
        const val DEFAULT_RATIO = 20.0f
    }

    var isEnabled: Boolean = true
    var thresholdDb: Float = DEFAULT_THRESHOLD_DB
    var attackMs: Float = DEFAULT_ATTACK_MS
    var releaseMs: Float = DEFAULT_RELEASE_MS
    var ratio: Float = DEFAULT_RATIO

    // When Bluetooth A2DP is connected, hardware limiter on device can be bypassed
    var isBypassedForBluetooth: Boolean = false

    fun isEffectivelyActive(): Boolean {
        return isEnabled && !isBypassedForBluetooth
    }
}
