package com.sjbz.aimp.audio

import android.media.audiofx.BassBoost
import android.os.Build
import android.util.Log

class BassBoostProcessor {
    private var bassBoost: BassBoost? = null
    var strength: Int = 400 // 0-1000
        private set
    private var sessionId: Int = 0
    private val TAG = "SjbZ-BassBoost"

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            if (sessionId == audioSessionId && bassBoost != null) {
                apply()
                return
            }
            release()
            sessionId = audioSessionId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try { BassBoost(0, audioSessionId).apply { release() } } catch (_: Exception) {}
            }
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = true
                setStrength(strength.toShort())
            }
            Log.d(TAG, "Attached to session $audioSessionId strength $strength")
        } catch (e: Exception) {
            Log.e(TAG, "Attach failed: ${e.message}")
        }
    }

    fun setPercent(percent: Int) {
        setStrengthPercent(percent)
    }

    fun setStrengthPercent(percent: Int) {
        val p = percent.coerceIn(0, 100)
        strength = (p * 10).coerceIn(0, 1000)
        apply()
    }

    fun setStrength(strength1000: Int) {
        strength = strength1000.coerceIn(0, 1000)
        apply()
    }

    private fun apply() {
        try {
            bassBoost?.let {
                it.setStrength(strength.toShort())
                it.enabled = strength > 0
            }
        } catch (e: Exception) { Log.e(TAG, "apply failed ${e.message}") }
    }

    fun toDisplay(): String {
        val db = strength * 15 / 1000
        val percent = strength * 100 / 1000
        return if (strength == 0) "BassBoost OFF" else "+${db}.0 dB (${percent}%) ACTIVO"
    }

    fun release() {
        try { bassBoost?.release() } catch (_: Exception) {}
        bassBoost = null
        sessionId = 0
    }
}
