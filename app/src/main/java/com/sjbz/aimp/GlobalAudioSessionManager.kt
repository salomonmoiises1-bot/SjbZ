package com.sjbz.aimp

import android.content.Context
import android.media.audiofx.Equalizer

class GlobalAudioSessionManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var INSTANCE: GlobalAudioSessionManager? = null
        fun getInstance(context: Context): GlobalAudioSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GlobalAudioSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    fun syncAudioEffects(equalizer: Equalizer?, engine: Any?) {
        // El sync real lo hace AudioChain.attachAudioSession() en tu código original
    }
}
