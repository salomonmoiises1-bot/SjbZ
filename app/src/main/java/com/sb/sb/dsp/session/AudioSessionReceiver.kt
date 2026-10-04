package com.sb.dsp.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log
import com.sb.dsp.DspEngine
import com.sb.dsp.service.SbDspForegroundService

class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
                val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown"
                Log.i("SB-SESSION", "audio open session=$session package=$pkg -> global session 0")
                SbDspForegroundService.startService(context, DspEngine.GLOBAL_SESSION_ID)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                // Never stop on a single app closing its session: session 0 is shared system audio.
                Log.i("SB-SESSION", "audio close -> keeping global DSP alive")
            }
            Intent.ACTION_BOOT_COMPLETED -> SbDspForegroundService.startService(context, DspEngine.GLOBAL_SESSION_ID)
        }
    }
}
