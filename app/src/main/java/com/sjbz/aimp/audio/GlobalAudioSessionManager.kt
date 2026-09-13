package com.sjbz.aimp.audio
import android.content.Context
import android.media.MediaPlayer

object GlobalAudioSessionManager {
    @JvmStatic var isGlobalModeEnabled: Boolean = false
    var onSessionsChangedListener: (() -> Unit)? = null

    @JvmStatic fun getInstance(): GlobalAudioSessionManager = this
    @JvmStatic fun getInstance(c: Any?): GlobalAudioSessionManager = this
    @JvmStatic fun getInstance(c1: Any?, c2: Any?): GlobalAudioSessionManager = this

    @JvmStatic fun syncAudioEffects() {}
    @JvmStatic fun syncAudioEffects(a: Any?) {}
    @JvmStatic fun syncAudioEffects(a: Any?, b: Any?) {}
    @JvmStatic fun syncAudioEffects(a: Any?, b: Any?, c: Any?) {}

    @JvmStatic fun enableGlobalMode(enabled: Boolean) { isGlobalModeEnabled = enabled }
    @JvmStatic fun enableGlobalMode(a: Any?, b: Boolean) { isGlobalModeEnabled = b }
    @JvmStatic fun enableGlobalMode(a: Boolean, b: Any?) { isGlobalModeEnabled = a }
    @JvmStatic fun isGlobalModeEnabled(): Boolean = isGlobalModeEnabled
    @JvmStatic fun getActiveSessionsSummary(): String = ""
    @JvmStatic fun getActiveSessionsSummary(a: Any?): String = ""

    fun setOnSessionsChangedListener(l: Any?) {}
    fun onSessionOpened(a: Any?, b: Any?, c: Any?) {}
    fun onSessionClosed(a: Int) {}
    fun stopAllSessions() {}

    @JvmStatic fun attach() {}
    @JvmStatic fun attach(a: Any?) {}
    @JvmStatic fun attach(a: Any?, b: Any?) {}
    @JvmStatic fun attach(a: Any?, b: Any?, c: Any?) {}
    @JvmStatic fun attach(a: Int) {}
    @JvmStatic fun attach(c: Context) {}
    @JvmStatic fun attach(p: MediaPlayer?) {}
    @JvmStatic fun release() {}
    @JvmStatic fun release(a: Any?) {}
    @JvmStatic fun release(a: Int) {}
}
