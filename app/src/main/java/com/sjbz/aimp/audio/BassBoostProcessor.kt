package com.sjbz.aimp.audio

import android.media.audiofx.BassBoost
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2

/**
 * Bass Boost Processor para SjbZ / ATS2835P.
 *
 * Motor principal: DSP sintetizado vía DynamicsProcessing PreEq (funciona 100% en Android 12+).
 * Motor secundario: legacy BassBoost donde el HAL lo permite.
 * Curva psicoacústica plana en sub-graves para que las 32 bandas (20-125Hz) respondan.
 *
 * Notas de escala:
 * - Escala DSP (getBoostGainForFrequency): (strength/1000)*12.0f, clamp 14.0f.
 * Es la ganancia real inyectada al PreEq de DynamicsProcessing.
 * - Escala UI (getStrengthDb/setStrengthDb): (strength/1000)*15.0f.
 * Es la escala mostrada en la EqActivity (+9.0 dB al 60%). Se mantiene en 15dB
 * para no romper el texto existente de la UI. La diferencia de 3dB entre ambas
 * escalas es intencional y documentada: la UI muestra percepción, el DSP aplica
 * ganancia física con headroom de 2dB extra para el shelf plano.
 */
class BassBoostProcessor(@Volatile var audioSessionId: Int = 0) {

    companion object {
        private const val TAG = "BassBoostProcessor"
        const val MAX_STRENGTH: Short = 1000
        const val DEFAULT_STRENGTH: Short = 600 // 60% -> 7.2dB DSP / 9.0dB UI
        const val DEFAULT_FREQ_HZ = 60
        const val MIN_FREQ_HZ = 20
        const val MAX_FREQ_HZ = 150

        // Escalas separadas y documentadas
        const val DSP_MAX_GAIN_DB = 12.0f
        const val DSP_CLAMP_DB = 14.0f
        const val UI_MAX_DB = 15.0f
    }

    var onParametersChanged: (() -> Unit)? = null

    private val nativeLock = Any()

    var isEnabled: Boolean = true
        set(value) {
            val changed = field!= value
            field = value
            if (!changed) return
            updateNativeEffect()
            onParametersChanged?.invoke()
        }

    var strength: Short = DEFAULT_STRENGTH
        set(value) {
            val coerced = value.coerceIn(0.toShort(), MAX_STRENGTH)
            val changed = field!= coerced
            field = coerced
            if (!changed) return
            updateNativeEffect()
            onParametersChanged?.invoke()
        }

    var centerFrequencyHz: Int = DEFAULT_FREQ_HZ
        set(value) {
            val coerced = value.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
            val changed = field!= coerced
            field = coerced
            if (!changed) return
            // El centro de frecuencia no afecta al BassBoost nativo (usa su freq fija del HAL),
            // solo afecta a la curva DSP. Por eso no se llama a updateNativeEffect() aquí,
            // solo se notifica para que el engine reaplique el PreEq.
            onParametersChanged?.invoke()
        }

    private var nativeBassBoost: BassBoost? = null

    init {
        if (audioSessionId > 0) {
            attachToSession(audioSessionId)
        }
    }

    /**
     * Ganancia en dB para una frecuencia dada.
     * Shelf plano hacia abajo (20Hz mantiene 85%+), roll-off suave hacia arriba
     * para no colorear voces. Así las 8 bandas bajas (25-125) sí suenan.
     *
     * Usa escala DSP: (strength/1000)*12.0f.
     */
    fun getBoostGainForFrequency(freqHz: Float): Float {
        if (!isEnabled || strength <= 0) return 0.0f
        val maxGainDb = (strength.toFloat() / MAX_STRENGTH.toFloat()) * DSP_MAX_GAIN_DB
        val f0 = centerFrequencyHz.toFloat()
        if (freqHz <= 0f || f0 <= 0f) return 0.0f

        val octDiff = abs(log2((freqHz / f0).toDouble())).toFloat()

        val factor = if (freqHz <= f0) {
            // PARCHE 32 bandas: de 0.70 -> 0.85 mínimo, de 0.12 -> 0.08 pendiente
            // Para f0=60Hz:
            // 20Hz -> octDiff=1.585 -> factor=1.0-0.1268=0.873
            // 30Hz -> octDiff=1.0 -> factor=0.92
            // 40Hz -> octDiff=0.585 -> factor=0.953
            // 50Hz -> octDiff=0.263 -> factor=0.979
            // Mantiene shelf casi plano en sub-graves.
            (1.0f - (octDiff * 0.08f)).coerceIn(0.85f, 1.0f)
        } else {
            // Roll-off gaussiano: exp(-octDiff^2 / 1.2)
            // Denominador 1.2 = 2*sigma^2 con sigma^2=0.6 -> sigma=0.775 octavas
            // A +1 octava (120Hz): factor=exp(-1/1.2)=0.435
            // A +2 octavas (240Hz): factor=exp(-4/1.2)=0.0357
            // Roll-off más abierto que el original para no matar 100-200Hz.
            exp(-(octDiff * octDiff) / 1.2f).coerceIn(0.0f, 1.0f)
        }
        return (maxGainDb * factor).coerceIn(0.0f, DSP_CLAMP_DB)
    }

    /**
     * Curva completa de 32 bandas lista para inyectar al DynamicsProcessing.
     * El orden y tamaño deben coincidir exactamente con el isoFreqs del DynamicsProcessingHelper.
     * Si isoFreqs.size!= 32, se devuelve el tamaño correspondiente sin asumir.
     */
    fun getFull32BandCurve(isoFreqs: FloatArray): FloatArray {
        return FloatArray(isoFreqs.size) { i -> getBoostGainForFrequency(isoFreqs[i]) }
    }

    fun attachToSession(sessionId: Int) {
        synchronized(nativeLock) {
            // Guard contra callbacks duplicados de ExoPlayer.
            // onAudioSessionIdChanged puede dispararse múltiples veces con el mismo ID
            // en seek, en transición de MediaItem, y en playTrackAtIndex().
            // Sin este guard, cada duplicado hace release()+new BassBoost() y deja
            // una ventana de 50-150ms sin efecto, además de invalidar referencias
            // que updateNativeEffect() pueda estar usando en otro hilo.
            if (sessionId == audioSessionId && nativeBassBoost!= null) {
                Log.d(TAG, "attachToSession: sesión $sessionId ya attachada, re-aplicando estado sin recrear")
                applyNativeStateLocked()
                return
            }

            // Liberación de instancia anterior bajo lock para evitar carrera con
            // updateNativeEffect() que corre en UI thread.
            try {
                nativeBassBoost?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "attachToSession: error liberando instancia anterior: ${t.message}")
            } finally {
                nativeBassBoost = null
            }

            audioSessionId = sessionId

            if (sessionId <= 0) {
                Log.i(TAG, "Session $sessionId: bypass nativo, solo DSP (Android 12+ safe)")
                return
            }

            try {
                val bb = BassBoost(0, sessionId)

                // ORDEN CORRECTO según AOSP AudioEffect:
                // 1. setParameter(PARAM_STRENGTH) via setStrength()
                // 2. EFFECT_CMD_ENABLE via enabled = true
                // Si se habilita primero con strength=0 (default del HAL), algunos HAL
                // no aplican el setStrength posterior hasta un toggle de enabled.
                val supported: Boolean = try {
                    bb.strengthSupported
                } catch (t: Throwable) {
                    Log.w(TAG, "attachToSession: no se pudo consultar strengthSupported: ${t.message}")
                    false
                }

                if (supported) {
                    try {
                        bb.setStrength(if (isEnabled) strength else 0.toShort())
                    } catch (t: Throwable) {
                        Log.w(TAG, "attachToSession: setStrength($strength) falló: ${t.message}")
                    }
                } else {
                    Log.w(TAG, "attachToSession: HAL reporta strengthSupported=false en sesión $sessionId. El nativo será no-op, el DSP PreEq es el motor real.")
                }

                try {
                    bb.enabled = isEnabled
                } catch (t: Throwable) {
                    Log.w(TAG, "attachToSession: setEnabled($isEnabled) falló: ${t.message}")
                }

                nativeBassBoost = bb

                // Diagnóstico post-creación. roundedStrength confirma si el HAL aceptó el valor.
                try {
                    val rs = bb.roundedStrength
                    Log.i(TAG, "BassBoost nativo creado: session=$sessionId enabled=${bb.enabled} strength=$strength strengthSupported=$supported roundedStrength=$rs isEnabled=$isEnabled")
                } catch (t: Throwable) {
                    Log.i(TAG, "BassBoost nativo creado: session=$sessionId enabled=${bb.enabled} strength=$strength strengthSupported=$supported isEnabled=$isEnabled (roundedStrength no disponible: ${t.message})")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "BassBoost nativo no disponible en sesión $sessionId: ${t.message}, usando solo DSP", t)
                nativeBassBoost = null
            }
        }
    }

    /**
     * Aplica isEnabled y strength al objeto nativo actual.
     * Requiere nativeLock adquirido por el llamante.
     * Orden: setStrength primero, enabled después.
     */
    private fun applyNativeStateLocked() {
        val bb = nativeBassBoost?: return
        try {
            val supported = try { bb.strengthSupported } catch (_: Throwable) { false }
            if (supported) {
                bb.setStrength(if (isEnabled) strength else 0.toShort())
            }
            bb.enabled = isEnabled
        } catch (t: Throwable) {
            Log.w(TAG, "applyNativeStateLocked: ${t.message}")
        }
    }

    fun updateNativeEffect() {
        synchronized(nativeLock) {
            val bb = nativeBassBoost?: return
            try {
                // Orden: strength antes de enabled, por requerimiento del HAL.
                val supported = try { bb.strengthSupported } catch (_: Throwable) { false }
                if (supported) {
                    bb.setStrength(if (isEnabled) strength else 0.toShort())
                }
                bb.enabled = isEnabled
            } catch (t: Throwable) {
                Log.w(TAG, "updateNativeEffect: session=$audioSessionId enabled=$isEnabled strength=$strength error=${t.message}")
            }
        }
    }

    fun getStrengthPercent(): Int = (strength.toInt() / 10).coerceIn(0, 100)

    fun setStrengthPercent(percent: Int) {
        strength = (percent.coerceIn(0, 100) * 10).toShort()
    }

    /**
     * Escala UI: (strength/1000)*15.0f.
     * 600 -> 9.0dB. Coincide con el texto "+9.0 dB (60%)" de la EqActivity.
     * No unificar con la escala DSP (12dB) sin migrar también la UI.
     */
    fun getStrengthDb(): Float = (strength.toFloat() / MAX_STRENGTH.toFloat()) * UI_MAX_DB

    fun setStrengthDb(db: Float) {
        val clamped = db.coerceIn(0.0f, UI_MAX_DB)
        strength = ((clamped / UI_MAX_DB) * MAX_STRENGTH.toFloat()).toInt().toShort().coerceIn(0.toShort(), MAX_STRENGTH)
    }

    /**
     * Verifica actividad real del nativo: no-nulo Y enabled=true.
     * La versión anterior solo chequeaba no-nulidad y reportaba activo
     * incluso con el efecto deshabilitado.
     */
    fun isNativeActive(): Boolean {
        synchronized(nativeLock) {
            val bb = nativeBassBoost?: return false
            return try {
                bb.enabled
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun hasValidSession(): Boolean = audioSessionId > 0

    fun release() {
        synchronized(nativeLock) {
            try {
                nativeBassBoost?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release(): ${t.message}")
            } finally {
                nativeBassBoost = null
            }
        }
    }
}
