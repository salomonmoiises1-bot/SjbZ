package com.sb.dsp

import android.media.audiofx.Virtualizer
import android.util.Log
import android.os.Handler
import android.os.HandlerThread

/** Owns the Android system-audio effects attached to session 0. */
class AudioEffectManager {
    private var virtualizer: Virtualizer? = null
    private val dynamics = DynamicsProcessingManager()
    private val workerThread = HandlerThread("SB-AFX-Worker").apply { start() }
    private val worker = Handler(workerThread.looper)

    val postEqBandCount: Int get() = dynamics.postEqBandCount
    val physicalEqBandCount: Int get() = dynamics.physicalEqBandCount
    val dynamicsAvailable: Boolean get() = dynamics.isAvailable

    fun start(sessionId: Int, config: DspConfig): Boolean {
        release()
        val dpOk = dynamics.start(sessionId, config)
        try {
            virtualizer = Virtualizer(100, sessionId).also {
                it.setStrength((config.virtualizerStrength.coerceIn(0f, 1f) * 1000f).toInt().coerceIn(0, 1000).toShort())
                // Do not enable a zero-strength Virtualizer. Some vendor effects
                // mis-handle an enabled instance on global session 0 and can mute
                // the output. Strength is always written before enabling.
                it.enabled = config.virtualizerEnabled && config.masterEnabled && config.virtualizerStrength > 0.01f
            }
        } catch (t: Throwable) {
            Log.w("SB-AFX", "Virtualizer no disponible", t)
        }
        return dpOk || virtualizer != null
    }

    fun applyConfig(config: DspConfig) {
        worker.post {
            dynamics.applyConfig(config)
            try {
                val strength = (config.virtualizerStrength.coerceIn(0f, 1f) * 1000f).toInt().coerceIn(0, 1000).toShort()
                virtualizer?.setStrength(strength)
                virtualizer?.enabled = config.virtualizerEnabled && config.masterEnabled && config.virtualizerStrength > 0.01f
            } catch (_: Throwable) {}
        }
    }

    fun setAutoGainOffsetDb(offsetDb: Float) = dynamics.setAutoGainOffsetDb(offsetDb)

    fun release() {
        worker.removeCallbacksAndMessages(null)
        try { virtualizer?.release() } catch (_: Throwable) {}
        virtualizer = null
        dynamics.release()
    }
}
