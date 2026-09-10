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

    var isGlobalModeEnabled: Boolean = false
    var onSessionsChangedListener: (() -> Unit)? = null

    fun enableGlobalMode(enabled: Boolean, ctx: Context) {
        isGlobalModeEnabled = enabled
    }

    fun getActiveSessionsSummary(): List<String> = emptyList()

    // FIRMA CORRECTA QUE USA EqActivity - 3 PARAMETROS
    fun syncAudioEffects(equalizer: Any?, mdrc: Any?, limiter: Any?) {
        // PARCHE MINIMO: compatible con tu EqualizerProcessor custom de 32 bandas
        // El sync real lo hace AudioChain.attachAudioSession() en PlaybackService
    }

    // OVERLOAD COMPATIBLE POR SI LO LLAMA PlaybackService CON 2
    fun syncAudioEffects(equalizer: Any?, engine: Any?) {
        syncAudioEffects(equalizer, engine, null)
    }
}
