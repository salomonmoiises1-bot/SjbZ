package com.sjbz.aimp.audio
import android.content.Context
import android.media.audiofx.Equalizer
import android.media.MediaPlayer

object GlobalAudioSessionManager {
    private var equalizer: Equalizer? = null
    private var currentSessionId: Int = 0
    fun onSessionOpened(s: Int, c: Context?, a: Context?) { currentSessionId = s }
    fun onSessionClosed(s: Int) { try{ equalizer?.release() }catch(_:Exception){}; equalizer = null }
    fun stopAllSessions() { try{ equalizer?.release() }catch(_:Exception){}; equalizer = null; currentSessionId = 0 }
    @JvmStatic fun attach(sessionId: Int) { onSessionOpened(sessionId, null, null) }
    @JvmStatic fun attach(context: Context) { attach(0) }
    @JvmStatic fun attach() { attach(currentSessionId) }
    @JvmStatic fun attach(p: MediaPlayer?) { p?.audioSessionId?.let { attach(it) } }
    @JvmStatic fun release() { stopAllSessions() }
    @JvmStatic fun release(sessionId: Int) { onSessionClosed(sessionId) }
}
