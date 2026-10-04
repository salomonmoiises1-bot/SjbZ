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
            virtualizer = Virtualizer(Int.MAX_VALUE, sessionId).also {
                it.setStrength((config.virtualizerStrength.coerceIn(0f, 1f) * 1000f).toInt().toShort())
                it.enabled = config.virtualizerEnabled && config.masterEnabled
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
                virtualizer?.setStrength((config.virtualizerStrength.coerceIn(0f, 1f) * 1000f).toInt().toShort())
                virtualizer?.enabled = config.virtualizerEnabled && config.masterEnabled
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
