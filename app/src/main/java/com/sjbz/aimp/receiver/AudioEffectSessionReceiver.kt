package com.sjbz.aimp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log
import com.sjbz.aimp.audio.GlobalAudioSessionManager

/**
 * AudioEffectSessionReceiver
 * Receives Android audio session broadcasts sent by external music and media applications:
 * - android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION
 * - android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION
 *
 * Automatically links active external playback (Spotify, Tidal, Deezer, VLC, Apple Music, etc.)
 * directly to the ATS2835P 32-band hardware DSP engine without requiring root access.
 */
class AudioEffectSessionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AudioEffectReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)

        if (sessionId < 0) return

        val manager = GlobalAudioSessionManager.getInstance(context)
        if (!manager.isGlobalModeEnabled) return

        when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.i(TAG, "External audio effect session opened: $sessionId (pkg: $packageName)")
                manager.onSessionOpened(sessionId, packageName, context)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.i(TAG, "External audio effect session closed: $sessionId (pkg: $packageName)")
                manager.onSessionClosed(sessionId)
            }
        }
    }
}
