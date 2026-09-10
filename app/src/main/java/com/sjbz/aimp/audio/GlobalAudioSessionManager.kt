package com.sjbz.aimp.audio

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * GlobalAudioSessionManager
 * Implements non-root global system audio equalization (Wavelet / Poweramp Equalizer style).
 */
class GlobalAudioSessionManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "GlobalAudioSessionMgr"
        private const val PREFS_NAME = "sjbz_global_eq_prefs"
        private const val KEY_GLOBAL_MODE_ENABLED = "key_global_mode_enabled"

        @Volatile
        private var instance: GlobalAudioSessionManager? = null

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

    init {
        if (isGlobalModeEnabled) {
            startGlobalMix()
        }
    }

    fun enableGlobalMode(enabled: Boolean, context: Context) {
        isGlobalModeEnabled = enabled
        prefs.edit().putBoolean(KEY_GLOBAL_MODE_ENABLED, enabled).apply()
        Log.i(TAG, "Global System Equalizer Mode (Wavelet style) changed: $enabled")
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
                Log.i(TAG, "Global Output Mix (Session 0) successfully attached")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach to Global Output Mix: ${t.message}", t)
        }
    }

    fun onNewAudioSessionOpened(sessionId: Int, packageName: String?, context: Context = appContext) {
        onSessionOpened(sessionId, packageName, context)
    }

    fun onAudioSessionClosed(sessionId: Int) {
        onSessionClosed(sessionId)
    }

    fun onSessionOpened(sessionId: Int, packageName: String?, context: Context = appContext) {
        if (!isGlobalModeEnabled || sessionId < 0) return
        try {
            val appLabel = resolveAppLabel(context, packageName)?: "App externa (ID #$sessionId)"
            sessionAppNames[sessionId] = appLabel
            activeSessions[sessionId]?.release()
            val helper = DynamicsProcessingHelper()
            helper.attachToSession(sessionId, cachedEqualizer, cachedMdrc, cachedLimiter)
            activeSessions[sessionId] = helper
            Log.i(TAG, "Attached ATS2835P DSP to external session $sessionId for $appLabel")
            notifySessionsChanged()
        } catch (t: Throwable) {
            Log.e(TAG, "Error attaching to session $sessionId: ${t.message}", t)
        }
    }

    fun onSessionClosed(sessionId: Int) {
        if (sessionId < 0) return
        try {
            activeSessions[sessionId]?.release()
            activeSessions.remove(sessionId)
            sessionAppNames.remove(sessionId)
            Log.i(TAG, "Detached ATS2835P DSP from session $sessionId")
            notifySessionsChanged()
        } catch (t: Throwable) {
            Log.e(TAG, "Error closing session $sessionId: ${t.message}", t)
        }
    }

    // ORIGINAL - lo respetamos 100%
    fun syncAudioEffects(
        equalizer: EqualizerProcessor,
        mdrc: MDRCProcessor,
        limiter: LimiterProcessor
    ) {
        cachedEqualizer = equalizer
        cachedMdrc = mdrc
        cachedLimiter = limiter

        globalMixHelper?.let { helper ->
            try {
                helper.applyEqualizer(equalizer)
                helper.applyMDRC(mdrc)
                helper.applyLimiter(limiter)
            } catch (t: Throwable) {
                Log.w(TAG, "Error syncing global mix helper: ${t.message}")
            }
        }

        for ((sessionId, helper) in activeSessions) {
            try {
                helper.applyEqualizer(equalizer)
                helper.applyMDRC(mdrc)
                helper.applyLimiter(limiter)
            } catch (t: Throwable) {
                Log.w(TAG, "Error syncing session $sessionId: ${t.message}")
            }
        }
    }

    // --- PARCHES COMPAT PARA QUE COMPILE EQACTIVITY SIN ROMPER TU ORIGINAL ---
    // EqActivity a veces pasa Any? o pasa atsEngine, con esto no explota
    fun syncAudioEffects(equalizer: Any?, mdrc: Any?, limiter: Any?) {
        if (equalizer is EqualizerProcessor && mdrc is MDRCProcessor && limiter is LimiterProcessor) {
            syncAudioEffects(equalizer, mdrc, limiter)
        }
    }

    fun syncAudioEffects(equalizer: Any?, engine: Any?) {
        // compat con versiones viejas de EqActivity que mandan 2 params
        syncAudioEffects(equalizer, engine, null)
    }

    private fun stopAllSessions() {
        try {
            globalMixHelper?.release()
            globalMixHelper = null
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing globalMixHelper: ${t.message}")
        }
        for ((_, helper) in activeSessions) {
            try { helper.release() } catch (t: Throwable) {
                Log.w(TAG, "Error releasing external helper: ${t.message}")
            }
        }
        activeSessions.clear()
        sessionAppNames.clear()
    }

    fun getActiveSessionsSummary(): List<String> {
        val summary = mutableListOf<String>()
        if (!isGlobalModeEnabled) return summary
        if (globalMixHelper!= null) {
            summary.add("● Android System Audio (Sesión 0: YouTube, Chrome, Juegos)")
        }
        for ((sessionId, appName) in sessionAppNames) {
            if (sessionId!= 0) summary.add("● $appName (Sesión #$sessionId)")
        }
        return summary
    }

    private fun resolveAppLabel(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun notifySessionsChanged() {
        mainHandler.post { onSessionsChangedListener?.invoke() }
    }
}
