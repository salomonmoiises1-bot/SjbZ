package com.sjbz.aimp

import android.content.Context

class GlobalAudioSessionManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var INSTANCE: GlobalAudioSessionManager? = null
        fun getInstance(context: Context): GlobalAudioSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GlobalAudioSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    fun syncAudioEffects(equalizer: Any?, engine: Any?) {
    }
}
