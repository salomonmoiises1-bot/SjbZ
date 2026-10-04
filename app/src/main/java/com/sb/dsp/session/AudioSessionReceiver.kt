package com.sb.dsp.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log
import com.sb.dsp.service.SbDspForegroundService

class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
                val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "Unknown"
                Log.i("AudioSessionReceiver", "Nueva sesión detectada ID=$sessionId pkg=$pkg")
                SbDspForegroundService.startService(context)
            }
        }
    }
}
