package com.sjbz.aimp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log
import com.sjbz.aimp.audio.GlobalAudioSessionManager

/**
 * BroadcastReceiver listening for system-wide audio session events:
 * - AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
 * - AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
 *
 * Automatically attaches the SB-Z Studio 32-Band EQ, Bass Boost, and MDRC Multiband Compressor
 * to external media players (Spotify, YouTube, Chrome, Tidal, Apple Music, etc.).
 */
class AudioEffectSessionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AudioEffectReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return
        // EXTRA_AUDIO_SESSION puede venir como int
        val sessionId = try {
            intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        } catch (_: Exception) {
            AudioEffect.ERROR_BAD_VALUE
        }
        val packageName = try {
            intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        } catch (_: Exception) {
            null
        } ?: "Desconocido"

        if (sessionId == AudioEffect.ERROR_BAD_VALUE) return
        // Session 0 la abre manualmente GlobalAudioSessionManager, no duplicar
        if (sessionId == 0) return

        val manager = try {
            GlobalAudioSessionManager.getInstance(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Manager no disponible: ${e.message}")
            return
        }

        // Si el DSP global está en pausa, no atar sesiones externas
        if (!manager.isGlobalAudioEnabled) return

        when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.i(TAG, "External audio session opened: $sessionId by $packageName")
                try {
                    manager.openSession(sessionId, packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "openSession falló: ${e.message}")
                }
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.i(TAG, "External audio session closed: $sessionId by $packageName")
                try {
                    manager.closeSession(sessionId)
                } catch (e: Exception) {
                    Log.w(TAG, "closeSession falló: ${e.message}")
                }
            }
        }
    }
}
