package com.sjbz.aimp.audio

import android.content.Context
import android.util.Log

/**
 * Core Audio DSP Engine for SjbZ.
 * Models the Actions Semiconductor ATS2835P audio processing architecture:
 * - Preamp & 32-Band ISO Equalizer
 * - 5-Band Hardware Multi-band Dynamic Range Compression (MDRC)
 * - ATS2835P QFN68 Anti-clipping Limiter (-0.3dB, 1ms attack, 100ms release)
 * - Stereo Balance, Pitch & Speed scaling, Crossfade engine
 * - Bluetooth A2DP auto-adaptation (Gentle MDRC mode, limiter bypass)
 * - Harman Kardon style transparent final stage (no harmonic color)
 */
class ATS2835PEngine(private val context: Context) {

    companion object {
        private const val TAG = "ATS2835PEngine"
    }

    val equalizer = EqualizerProcessor()
    val mdrc = MDRCProcessor()
    val limiter = LimiterProcessor()
    val dynamicsHelper = DynamicsProcessingHelper()

    // DSP Parameters
    var balance: Float = 0.0f // -1.0 (Left) to +1.0 (Right)
    var pitch: Float = 1.0f  // 0.5x to 2.0x
    var speed: Float = 1.0f // 0.5x to 2.0x
    var crossfadeSeconds: Int = 3 // 0 to 10s

    var isBluetoothConnected: Boolean = false
        private set

    fun attachAudioSession(sessionId: Int) {
        if (sessionId <= 0) return
        dynamicsHelper.attachToSession(sessionId, equalizer, mdrc, limiter)
    }

    fun onBluetoothStatusChanged(connected: Boolean) {
        isBluetoothConnected = connected
        limiter.isBypassedForBluetooth = connected
        mdrc.isGentleBluetoothMode = connected

        Log.i(TAG, "Bluetooth A2DP state: $connected. Gentle MDRC: $connected. Internal limiter bypassed: $connected")
        dynamicsHelper.applyMDRC(mdrc)
        dynamicsHelper.applyLimiter(limiter)
    }

    fun updateEqualizer() {
        dynamicsHelper.applyEqualizer(equalizer)
    }

    fun updateMDRC() {
        dynamicsHelper.applyMDRC(mdrc)
    }

    fun updateLimiter() {
        dynamicsHelper.applyLimiter(limiter)
    }

    fun release() {
        dynamicsHelper.release()
    }
}
