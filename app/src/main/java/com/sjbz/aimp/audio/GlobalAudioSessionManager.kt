package com.sjbz.aimp.audio
import android.content.Context
import android.media.audiofx.Equalizer
import android.media.MediaPlayer

object GlobalAudioSessionManager {
    private var equalizer: Equalizer? = null
    private var currentSessionId: Int = 0
    private var context: Context? = null

    // --- Propiedades que pide EqActivity ---
    var isGlobalModeEnabled: Boolean = false
    var onSessionsChangedListener: (() -> Unit)? = null
    var onSessionsChangedListener2: Any? = null // por si lo usa como interfaz

    // --- Metodos viejos ---
    fun onSessionOpened(s: Int, c: Context?, a: Context?) { currentSessionId = s; context = a ?: c }
    fun onSessionClosed(s: Int) { try{ equalizer?.release() }catch(_:Exception){}; equalizer = null }
    fun stopAllSessions() { try{ equalizer?.release() }catch(_:Exception){}; equalizer = null; currentSessionId = 0 }

    // --- Lo que te pide el error nuevo ---
    @JvmStatic fun getInstance(): GlobalAudioSessionManager = this
    @JvmStatic fun syncAudioEffects() {}
    @JvmStatic fun getActiveSessionsSummary(): String = "Sessions: $currentSessionId"
    @JvmStatic fun enableGlobalMode(enabled: Boolean) { isGlobalModeEnabled = enabled }
    @JvmStatic fun isGlobalModeEnabled(): Boolean = isGlobalModeEnabled

    // --- Lo que pide PlaybackService ---
    @JvmStatic fun attach(sessionId: Int) { onSessionOpened(sessionId, null, null) }
    @JvmStatic fun attach(context: Context) { attach(0) }
    @JvmStatic fun attach() { attach(currentSessionId) }
    @JvmStatic fun attach(p: MediaPlayer?) { p?.audioSessionId?.let { attach(it) } }
    @JvmStatic fun release() { stopAllSessions() }
    @JvmStatic fun release(sessionId: Int) { onSessionClosed(sessionId) }

    // Para los listeners que aparecen como onSessionsChangedListener
    fun setOnSessionsChangedListener(l: (() -> Unit)?) { onSessionsChangedListener = l }
}
