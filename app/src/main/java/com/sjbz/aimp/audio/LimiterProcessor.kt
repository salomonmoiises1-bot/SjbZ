package com.sjbz.aimp.audio

/**
 * Brickwall Limiter Processor modeled after the ATS2835P QFN68 DSP hardware protection stage.
 * Threshold: -0.3 dBFS
 * Attack: 1.0 ms
 * Release: 100.0 ms
 * Protects speakers, amplifiers and headphones from digital inter-sample clipping and overload.
 * + ATS2835P SoftClipper Modeled
 */
class LimiterProcessor {

    companion object {
        const val DEFAULT_THRESHOLD_DB = -0.3f
        const val DEFAULT_ATTACK_MS = 1.0f
        const val DEFAULT_RELEASE_MS = 100.0f
        const val DEFAULT_RATIO = 20.0f
    }

    var isEnabled: Boolean = true
    var thresholdDb: Float = DEFAULT_THRESHOLD_DB
    var attackMs: Float = DEFAULT_ATTACK_MS
    var releaseMs: Float = DEFAULT_RELEASE_MS
    var ratio: Float = DEFAULT_RATIO

    // ATS2835P SoftClipper Modeled - Agregado para UI 100%
    var softClipEnabled: Boolean = true
    var softClipDrive: Float = 0.75f

    // When Bluetooth A2DP is connected, hardware limiter on device can be bypassed
    var isBypassedForBluetooth: Boolean = false

    fun isEffectivelyActive(): Boolean {
        return isEnabled && !isBypassedForBluetooth
    }

    fun isSoftClipActive(): Boolean {
        return softClipEnabled && isEnabled && !isBypassedForBluetooth
    }
}
