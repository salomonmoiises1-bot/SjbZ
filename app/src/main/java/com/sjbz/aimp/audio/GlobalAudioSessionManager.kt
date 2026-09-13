package com.sjbz.aimp.audio

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class GlobalAudioSessionManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "GlobalAudioSessionMgr"
        private const val PREFS_NAME = "sjbz_global_eq_prefs"
        private const val KEY_GLOBAL_MODE_ENABLED = "key_global_mode_enabled"
        @Volatile private var instance: GlobalAudioSessionManager? = null
        fun getInstance(context: Context): GlobalAudioSessionManager {
            return instance?: synchronized(this) {
                instance?: GlobalAudioSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    var isGlobalModeEnabled: Boolean = prefs.getBoolean(KEY_GLOBAL_MODE_ENABLED, false)
        private set
    val activeSessions = ConcurrentHashMap<Int, DynamicsProcessingHelper>()
    val sessionAppNames = ConcurrentHashMap<Int, String>()
    private var globalMixHelper: DynamicsProcessingHelper? = null
    private var cachedEqualizer: EqualizerProcessor = EqualizerProcessor()
    private var cachedMdrc: MDRCProcessor = MDRCProcessor()
    private var cachedLimiter: LimiterProcessor = LimiterProcessor()
    var onSessionsChangedListener: (() -> Unit)? = null

    init { if (isGlobalModeEnabled) startGlobalMix() }

    // METODOS QUE PIDE PlaybackService - ESTO TE FALTA
    fun attach(sessionId: Int) { onSessionOpened(sessionId, null, appContext) }
    fun attach(context: Context) { attach(0) }
    fun attach() { attach(0) }
    fun release() { stopAllSessions() }
    fun release(sessionId: Int) { onSessionClosed(sessionId) }

    fun enableGlobalMode(enabled: Boolean, context: Context) {
        isGlobalModeEnabled = enabled
        prefs.edit().putBoolean(KEY_GLOBAL_MODE_ENABLED, enabled).apply()
        if (enabled) startGlobalMix() else stopAllSessions()
        notifySessionsChanged()
    }

    private fun startGlobalMix() {
        try {
            if (globalMixHelper == null) {
                val helper = DynamicsProcessingHelper()
                helper.attachToSession(0, cachedEqualizer, cachedMdrc, cachedLimiter)
                globalMixHelper = helper
                sessionAppNames[0] = "Audio Global de Android (Mezclador Maestro #0)"
            }
        } catch (t: Throwable) {}
    }

    fun onNewAudioSessionOpened(sessionId: Int, packageName: String?, context: Context = appContext) { onSessionOpened(sessionId, packageName, context) }
    fun onAudioSessionClosed(sessionId: Int) { onSessionClosed(sessionId) }

    fun onSessionOpened(sessionId: Int, packageName: String?, context: Context = appContext) {
        if (!isGlobalModeEnabled || sessionId < 0) return
        try {
            val appLabel = resolveAppLabel(context, packageName)
            sessionAppNames[sessionId] = appLabel
            activeSessions[sessionId]?.release()
            val helper = DynamicsProcessingHelper()
            helper.attachToSession(sessionId, cachedEqualizer, cachedMdrc, cachedLimiter)
            activeSessions[sessionId] = helper
            notifySessionsChanged()
        } catch (t: Throwable) {}
    }

    fun onSessionClosed(sessionId: Int) {
        if (sessionId < 0) return
        try {
            activeSessions[sessionId]?.release()
            activeSessions.remove(sessionId)
            sessionAppNames.remove(sessionId)
            notifySessionsChanged()
        } catch (t: Throwable) {}
    }

    fun syncAudioEffects(equalizer: EqualizerProcessor, mdrc: MDRCProcessor, limiter: LimiterProcessor) {
        cachedEqualizer = equalizer; cachedMdrc = mdrc; cachedLimiter = limiter
        try { globalMixHelper?.applyEqualizer(equalizer); globalMixHelper?.applyMDRC(mdrc); globalMixHelper?.applyLimiter(limiter) } catch (t: Throwable) {}
        for ((_, helper) in activeSessions) { try { helper.applyEqualizer(equalizer); helper.applyMDRC(mdrc); helper.applyLimiter(limiter) } catch (t: Throwable) {} }
    }

    private fun stopAllSessions() {
        try { globalMixHelper?.release(); globalMixHelper = null } catch (t: Throwable) {}
        for ((_, helper) in activeSessions) { try { helper.release() } catch (t: Throwable) {} }
        activeSessions.clear(); sessionAppNames.clear()
    }

    fun getActiveSessionsSummary(): List<String> {
        val summary = mutableListOf<String>()
        if (!isGlobalModeEnabled) return summary
        if (globalMixHelper!= null) summary.add("● Android System Audio (Sesión 0)")
        for ((sid, name) in sessionAppNames) if (sid!= 0) summary.add("● $name (#$sid)")
        return summary
    }

    private fun resolveAppLabel(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "App externa"
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai)?.toString()?: packageName
        } catch (e: Exception) { packageName }
    }

    private fun notifySessionsChanged() { mainHandler.post { onSessionsChangedListener?.invoke() } }
}
