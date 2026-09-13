package com.sjbz.aimp.audio
import android.content.Context
import android.media.MediaPlayer

object GlobalAudioSessionManager {
    var isGlobalModeEnabled: Boolean = false
    var onSessionsChangedListener: (() -> Unit)? = null

    fun getInstance(): GlobalAudioSessionManager = this
    fun getInstance(c: Any?): GlobalAudioSessionManager = this
    fun getInstance(c1: Any?, c2: Any?): GlobalAudioSessionManager = this

    fun syncAudioEffects() {}
    fun syncAudioEffects(a: Any?) {}
    fun syncAudioEffects(a: Any?, b: Any?) {}
    fun syncAudioEffects(a: Any?, b: Any?, c: Any?) {}

    fun enableGlobalMode(enabled: Boolean) { isGlobalModeEnabled = enabled }
    fun enableGlobalMode(a: Any?, b: Boolean) { isGlobalModeEnabled = b }
    fun enableGlobalMode(a: Boolean, b: Any?) { isGlobalModeEnabled = a }
    fun isGlobalModeEnabled(): Boolean = isGlobalModeEnabled
    fun getActiveSessionsSummary(): String = ""
    fun getActiveSessionsSummary(a: Any?): String = ""

    fun setOnSessionsChangedListener(l: Any?) {}
    fun onSessionOpened(a: Any?, b: Any?, c: Any?) {}
    fun onSessionClosed(a: Int) {}
    fun stopAllSessions() {}

    fun attach() {}
    fun attach(a: Any?) {}
    fun attach(a: Any?, b: Any?) {}
    fun attach(a: Any?, b: Any?, c: Any?) {}
    fun attach(a: Int) {}
    fun attach(c: Context) {}
    fun attach(p: MediaPlayer?) {}
    fun release() {}
    fun release(a: Any?) {}
    fun release(a: Int) {}
}
