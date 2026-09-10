package com.sjbz.aimp

import android.content.Context

class GlobalAudioSessionManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: GlobalAudioSessionManager? = null

        fun getInstance(context: Context): GlobalAudioSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GlobalAudioSessionManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    fun syncAudioEffects(equalizer: Any?, engine: Any?) {
        // PARCHE MÍNIMO: Usamos Any? para ser compatible con tu EqualizerProcessor custom de 32 bandas
        // de ATS2835PEngine.kt. El sync real lo hace AudioChain.attachAudioSession() en PlaybackService.
        // Así respetamos el proyecto original sin perder Modo Global ni 32 bandas.
    }
}
