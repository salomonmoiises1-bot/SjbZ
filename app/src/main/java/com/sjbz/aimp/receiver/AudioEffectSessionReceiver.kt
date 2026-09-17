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
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Desconocido"

        if (sessionId == AudioEffect.ERROR_BAD_VALUE || sessionId == 0) {
            return
        }

        val manager = GlobalAudioSessionManager.getInstance(context)

        when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.i(TAG, "External audio session opened: $sessionId by $packageName")
                manager.openSession(sessionId, packageName)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.i(TAG, "External audio session closed: $sessionId by $packageName")
                manager.closeSession(sessionId)
            }
        }
    }
}
