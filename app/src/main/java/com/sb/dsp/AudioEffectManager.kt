package com.sb.dsp

import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.util.Log

class AudioEffectManager {
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private val dynamics = DynamicsProcessingManager()

    val postEqBandCount: Int get() = dynamics.postEqBandCount
    val dynamicsAvailable: Boolean get() = dynamics.isAvailable

    fun start(sessionId: Int, config: DspConfig): Boolean {
        release()
        val dpOk = dynamics.start(sessionId, config)
        try {
            bassBoost = BassBoost(100, sessionId).also {
                it.setStrength((config.bassBoostStrength.coerceIn(0f, 1f) * 1000f).toInt().toShort())
                it.enabled = config.bassBoostEnabled && config.masterEnabled
            }
        } catch (t: Throwable) {
            Log.w("SB-AFX", "BassBoost no disponible", t)
        }
        try {
            virtualizer = Virtualizer(100, sessionId).also {
                it.setStrength((config.virtualizerStrength.coerceIn(0f, 1f) * 1000f).toInt().toShort())
                it.enabled = config.virtualizerEnabled && config.masterEnabled
            }
        } catch (t: Throwable) {
            Log.w("SB-AFX", "Virtualizer no disponible", t)
        }
        return dpOk || bassBoost != null || virtualizer != null
    }

    fun applyConfig(config: DspConfig) {
        dynamics.applyConfig(config)
        try {
            bassBoost?.setStrength((config.bassBoostStrength.coerceIn(0f, 1f) * 1000f).toInt().toShort())
            bassBoost?.enabled = config.bassBoostEnabled && config.masterEnabled
        } catch (_: Throwable) {}
        try {
            virtualizer?.setStrength((config.virtualizerStrength.coerceIn(0f, 1f) * 1000f).toInt().toShort())
            virtualizer?.enabled = config.virtualizerEnabled && config.masterEnabled
        } catch (_: Throwable) {}
    }

    fun release() {
        try { bassBoost?.release() } catch (_: Throwable) {}
        try { virtualizer?.release() } catch (_: Throwable) {}
        bassBoost = null
        virtualizer = null
        dynamics.release()
    }
}
