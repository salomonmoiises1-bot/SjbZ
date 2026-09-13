package com.sjbz.aimp.audio
import android.content.Context
import android.media.MediaPlayer

object GlobalAudioSessionManager {
    @Volatile private var instance: GlobalAudioSessionManager? = null
    @Volatile private var _globalEnabled: Boolean = false

    @get:JvmName("getGlobalEnabled")
    @set:JvmName("setGlobalEnabled")
    var isGlobalModeEnabled: Boolean
        get() = _globalEnabled
        set(value) { _globalEnabled = value }

    var onSessionsChangedListener: (() -> Unit)? = null

    fun getInstance(context: Context): GlobalAudioSessionManager { instance = this; return this }
    fun getInstance(): GlobalAudioSessionManager = this

    fun attach() {}
    fun attach(session: Any?) {}
    fun attach(s1: Any?, s2: Any?) {}
    fun attach(s1: Any?, s2: Any?, s3: Any?) {}
    fun attach(id: Int) {}
    fun attach(context: Context) {}
    fun attach(player: MediaPlayer?) {}

    fun enableGlobalMode(enabled: Boolean, context: Context) { _globalEnabled = enabled }
    fun enableGlobalMode(enabled: Boolean) { _globalEnabled = enabled }

    fun getActiveSessionsSummary(): String = if (_globalEnabled) "Global: Activo" else ""
    fun syncAudioEffects(eq: Any?, mdrc: Any?, limiter: Any?) {}
    fun release() {}
    fun release(session: Any?) {}
    fun release(id: Int) {}
    fun release(player: MediaPlayer?) {}
}
