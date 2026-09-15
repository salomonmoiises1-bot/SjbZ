package com.sjbz.aimp.audio

import android.content.Context
import android.util.Log
import com.sjbz.aimp.model.EqPreset

typealias ATSEngine = ATS2835PEngine

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
    val bassBoost = BassBoostProcessor()
    val dynamicsHelper = DynamicsProcessingHelper()

    var balance: Float = 0.0f
    var pitch: Float = 1.0f
    var speed: Float = 1.0f
    var crossfadeSeconds: Int = 3

    var isBluetoothConnected: Boolean = false
        private set

    init {
        equalizer.bassBoostProcessor = bassBoost
        bassBoost.onParametersChanged = {
            updateBassBoost()
        }
        if (audioSessionId > 0) {
            attachAudioSession(audioSessionId)
        }
    }

    fun attachAudioSession(sessionId: Int) {
        Log.d(TAG, "attachAudioSession: $sessionId")
        if (sessionId <= 0) return
        this.audioSessionId = sessionId
        // 1. Helper primero
        dynamicsHelper.attachToSession(sessionId, equalizer, mdrc, limiter, bassBoost)
        // 2. BassBoost nativo en modo pasivo: no crear efecto HAL, solo guardar sesión
        // para que isNativeActive() devuelva false y el helper haga todo en software.
        bassBoost.attachToSession(sessionId)
        bassBoost.setNativeEnabled(false)

        updateEqualizer()
        updateMDRC()
        updateLimiter()
        updateBassBoost()
    }

    fun applyPreset(preset: EqPreset) {
        equalizer.loadFromPreset(preset)
        mdrc.loadFromSettings(preset.mdrcSettings)
        if (preset.name == "Bass Boost") {
            bassBoost.isEnabled = true
            bassBoost.strength = 800.toShort()
        }
        updateEqualizer()
        updateMDRC()
        updateBassBoost()
    }

    fun onBluetoothStatusChanged(connected: Boolean) {
        isBluetoothConnected = connected
        limiter.isBypassedForBluetooth = connected
        mdrc.isGentleBluetoothMode = connected
        Log.i(TAG, "Bluetooth A2DP state: $connected")
        dynamicsHelper.applyMDRC(mdrc)
        dynamicsHelper.applyLimiter(limiter)
    }

    fun updateEqualizer() {
        dynamicsHelper.applyEqualizer(equalizer, bassBoost)
    }

    fun updateMDRC() {
        dynamicsHelper.applyMDRC(mdrc)
    }

    fun updateLimiter() {
        dynamicsHelper.applyLimiter(limiter)
    }

    fun updateBassBoost() {
        // FIX MUTE: solo path software. No llamar a updateNativeEffect()
        // cuando DynamicsProcessing está activo, porque duplica +24dB.
        if (dynamicsHelper.isHardwareDspActive) {
            dynamicsHelper.applyEqualizer(equalizer, bassBoost)
            // Asegurar que el nativo quede apagado
            bassBoost.setNativeEnabled(false)
        } else {
            // Solo en fallback legacy sin DP, usar nativo como segunda capa
            dynamicsHelper.applyEqualizer(equalizer, bassBoost)
            bassBoost.updateNativeEffect()
        }
    }

    fun release() {
        dynamicsHelper.release()
        bassBoost.release()
    }
}
