package com.sjbz.aimp.audio

import android.content.Context
import android.util.Log
import com.sjbz.aimp.model.EqPreset

typealias ATSEngine = ATS2835PEngine

/**
 * Core Audio DSP Engine for SjbZ.
 * Models the Actions Semiconductor ATS2835P audio processing architecture:
 * - Preamp & 32-Band ISO Equalizer with calibrated hardware curve (+1.5dB @ 60Hz, +1dB @ 12.5kHz, -0.5dB natural roll-off)
 * - 5-Band Hardware Multi-band Dynamic Range Compression (MDRC) with ATS2835P datasheet specs
 * - ATS2835P QFN68 Anti-clipping Limiter (-1.0dB, 1ms attack, 100ms release)
 * - ATS2835P Hardware SoftClipper
 * - Stereo Balance, Pitch & Speed scaling, Crossfade engine
 * - Bluetooth A2DP auto-adaptation (Gentle MDRC mode, limiter bypass)
 * - SjbZ Psicoacustic BassBoost 60/85/120Hz +15dB
 */
class ATS2835PEngine(
    private val context: Context? = null,
    var audioSessionId: Int = 0
) {

    constructor(audioSessionId: Int) : this(null, audioSessionId)

    companion object {
        private const val TAG = "ATS2835PEngine"
    }

    val equalizer = EqualizerProcessor()
    val mdrc = MDRCProcessor()
    val limiter = LimiterProcessor()
    val crossover = CrossoverProcessor()
    val dynamicsHelper = DynamicsProcessingHelper()
    
    // --- NUEVA CARACTERISTICA BASSBOOST ---
    val bassBoost = BassBoostProcessor()
    private var androidBassBoost: android.media.audiofx.BassBoost? = null

    // DSP Parameters
    var balance: Float = 0.0f // -1.0 (Left) to +1.0 (Right)
    var pitch: Float = 1.0f   // 0.5x to 2.0x
    var speed: Float = 1.0f   // 0.5x to 2.0x
    var crossfadeSeconds: Int = 3 // 0 to 10s

    var isBluetoothConnected: Boolean = false
        private set

    init {
        if (audioSessionId > 0) {
            attachAudioSession(audioSessionId)
        }
    }

    fun attachAudioSession(sessionId: Int) {
        if (sessionId <= 0) return
        this.audioSessionId = sessionId
        dynamicsHelper.attachToSession(sessionId, equalizer, mdrc, limiter)
        
        try {
            androidBassBoost?.release()
            androidBassBoost = android.media.audiofx.BassBoost(0, sessionId).apply {
                enabled = true
                setStrength(bassBoost.toStrength().toShort())
            }
        } catch (e: Exception) {
            Log.w(TAG, "BassBoost no soportado en este device: ${e.message}")
        }
    }

    fun applyPreset(preset: EqPreset) {
        equalizer.loadFromPreset(preset)
        mdrc.loadFromSettings(preset.mdrcSettings)
        // Cargar BassBoost del preset
        bassBoost.setBassBoost(preset.bassBoostFreq, preset.bassBoostGain)
        updateEqualizer()
        updateMDRC()
        updateBassBoost()
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

    // --- METODOS BASSBOOST ---
    fun setBassBoost(freq: Int, gain: Float) {
        bassBoost.setBassBoost(freq, gain)
        updateBassBoost()
    }

    fun updateBassBoost() {
        try {
            androidBassBoost?.let {
                it.setStrength(bassBoost.toStrength().toShort())
                it.enabled = bassBoost.isEnabled
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updateBassBoost: ${e.message}")
        }
    }

    fun release() {
        dynamicsHelper.release()
        try { androidBassBoost?.release() } catch (_: Exception) {}
        androidBassBoost = null
    }
}
