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

    // PATCH anti-clipseo: evita reentradas updateBassBoost <-> onParametersChanged
    private var isUpdatingBb = false

    init {
        // PATCH: usa linkBassBoost para encadenar callbacks en vez de pisar
        // onParametersChanged. Antes se asignaba directo y si EqActivity u otro
        // componente seteaba su propio listener, se perdía este.
        equalizer.linkBassBoost(bassBoost) {
            if (!isUpdatingBb) updateBassBoost()
        }
        if (audioSessionId > 0) {
            attachAudioSession(audioSessionId)
        }
    }

    fun attachAudioSession(sessionId: Int) {
        Log.d(TAG, "attachAudioSession: $sessionId")
        if (sessionId <= 0) return
        this.audioSessionId = sessionId
        // 1. Helper primero (crea DP o fallback legacy)
        dynamicsHelper.attachToSession(sessionId, equalizer, mdrc, limiter, bassBoost)
        // 2. BassBoost nativo en modo pasivo: guardar sesión pero forzado a disabled
        // para que isNativeActive() devuelva false y el helper haga todo en software.
        // Evita doble boost +24dB (12dB DSP + 12dB HAL).
        bassBoost.attachToSession(sessionId)
        bassBoost.setNativeEnabled(false)

        // PATCH: un solo ciclo de apply. updateBassBoost() ya hace
        // applyLimiter + applyEqualizer internamente, no hace falta
        // llamar updateEqualizer() + updateLimiter() por separado antes.
        // Antes se hacían 4 applys seguidos (eq, mdrc, limiter, bb->limiter+eq).
        updateMDRC()
        updateBassBoost()
    }

    fun applyPreset(preset: EqPreset) {
        equalizer.loadFromPreset(preset)
        mdrc.loadFromSettings(preset.mdrcSettings)
        if (preset.name.equals("Bass Boost", ignoreCase = true)) {
            bassBoost.isEnabled = true
            // PATCH: usa setStrengthDbDsp para escala consistente con el motor DSP.
            // Antes se seteaba 800 directo (9.6dB DSP) sin pasar por el guard
            // de isUpdatingBb, disparando callback en medio del applyPreset.
            isUpdatingBb = true
            try {
                bassBoost.setStrengthDbDsp(9.0f)
            } finally {
                isUpdatingBb = false
            }
        }
        // PATCH: mismo orden que attach, un solo ciclo
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
        // PATCH SjbZ32: EQ ahora en software via Sjbz32BandProcessor (ExoPlayer AudioProcessor).
        // Ya no usamos DynamicsProcessing PreEq para EQ, solo para MDRC/Limiter.
        try {
            com.sjbz.aimp.service.PlaybackService.instance?.sjbzEqProcessor?.refresh()
        } catch (t: Throwable) {
            Log.w(TAG, "sjbzEqProcessor.refresh() falló: ${t.message}")
        }
    }

    fun updateMDRC() {
        dynamicsHelper.applyMDRC(mdrc)
    }

    fun updateLimiter() {
        dynamicsHelper.applyLimiter(limiter)
    }

    fun updateBassBoost() {
        if (isUpdatingBb) return
        isUpdatingBb = true
        try {
            // PATCH anti-clipseo: compensación automática de headroom
            // PATCH escala: usa getStrengthDbDsp() (12dB) en vez de getStrengthDb() (15dB UI).
            // Antes sobre-compensaba 25%: con 9dB UI bajaba -3.6dB en vez de -2.88dB DSP.
            val bbDb = try { bassBoost.getStrengthDbDsp() } catch (_: Throwable) { 0f }
            limiter.postGainDb = if (bassBoost.isEnabled) {
                (-bbDb * 0.4f).coerceIn(-6f, 0f)
            } else {
                0f
            }
            dynamicsHelper.applyLimiter(limiter)

            // PATCH SjbZ32: refrescar EQ software en vez de PreEq HAL
            try {
                com.sjbz.aimp.service.PlaybackService.instance?.sjbzEqProcessor?.refresh()
            } catch (_: Throwable) {}
            // FIX MUTE: solo path software cuando DP está activo.
            // No llamar a updateNativeEffect() con DP activo porque duplica +24dB.
            // setNativeEnabled(false) asegura que isNativeActive()=false
            if (dynamicsHelper.isHardwareDspActive) {
                // Asegurar que el nativo quede apagado
                bassBoost.setNativeEnabled(false)
            } else {
                // Solo en fallback legacy sin DP, usar nativo como segunda capa
                bassBoost.updateNativeEffect()
            }
        } finally {
            isUpdatingBb = false
        }
    }

    fun release() {
        dynamicsHelper.release()
        bassBoost.release()
        // PATCH: reset de flags de sesión para que un re-attach no herede estado
        audioSessionId = 0
        isUpdatingBb = false
    }
}
