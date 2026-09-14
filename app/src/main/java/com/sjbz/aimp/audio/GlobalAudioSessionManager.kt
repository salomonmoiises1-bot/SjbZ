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
 *
 * Intercepts audio sessions broadcasted by external players (Spotify, Deezer, Tidal, Apple Music,
 * etc.) via android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION and applies the ATS2835P
 * 32-band ISO curve, MDRC 5-band compression, and hardware limiter.
 *
 * Also maintains an active master session on audioSessionId = 0 (Global Output Mix) to process
 * general Android system audio (YouTube, Chrome, games, video players).
 */
class GlobalAudioSessionManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "GlobalAudioSessionMgr"
        private const val PREFS_NAME = "sjbz_global_eq_prefs"
        private const val KEY_GLOBAL_MODE_ENABLED = "key_global_mode_enabled"

        @Volatile
        private var instance: GlobalAudioSessionManager? = null

        fun getInstance(context: Context): GlobalAudioSessionManager {
            return instance ?: synchronized(this) {
                instance ?: GlobalAudioSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    var isGlobalModeEnabled: Boolean = prefs.getBoolean(KEY_GLOBAL_MODE_ENABLED, false)
        private set

    // Session ID -> DynamicsProcessingHelper
    val activeSessions = ConcurrentHashMap<Int, DynamicsProcessingHelper>()
    // Session ID -> BassBoostProcessor
    val activeBassBoosts = ConcurrentHashMap<Int, BassBoostProcessor>()
    // Session ID -> Package / App display name
    val sessionAppNames = ConcurrentHashMap<Int, String>()

    // Global Output Mix helper (Session 0)
    private var globalMixHelper: DynamicsProcessingHelper? = null
    private var globalBassBoost: BassBoostProcessor? = null

    // Cached audio parameters for new sessions
    private var cachedEqualizer: EqualizerProcessor = EqualizerProcessor()
    private var cachedMdrc: MDRCProcessor = MDRCProcessor()
    private var cachedLimiter: LimiterProcessor = LimiterProcessor()
    private var cachedBassBoost: BassBoostProcessor = BassBoostProcessor()

    // Listener for UI updates (e.g. EqActivity)
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

        if (enabled) {
            startGlobalMix()
        } else {
            stopAllSessions()
        }
        notifySessionsChanged()
    }

    private fun startGlobalMix() {
        try {
            if (globalMixHelper == null) {
                cachedEqualizer.bassBoostProcessor = cachedBassBoost
                val helper = DynamicsProcessingHelper()
                helper.attachToSession(0, cachedEqualizer, cachedMdrc, cachedLimiter, cachedBassBoost)
                globalMixHelper = helper

                val bb = BassBoostProcessor(0)
                bb.isEnabled = cachedBassBoost.isEnabled
                bb.strength = cachedBassBoost.strength
                bb.centerFrequencyHz = cachedBassBoost.centerFrequencyHz
                globalBassBoost = bb

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
            // Determine friendly app label
            val appLabel = resolveAppLabel(context, packageName) ?: "App externa (ID #$sessionId)"
            sessionAppNames[sessionId] = appLabel

            // If session already exists, release old one
            activeSessions[sessionId]?.release()
            activeBassBoosts[sessionId]?.release()

            cachedEqualizer.bassBoostProcessor = cachedBassBoost
            val helper = DynamicsProcessingHelper()
            helper.attachToSession(sessionId, cachedEqualizer, cachedMdrc, cachedLimiter, cachedBassBoost)
            activeSessions[sessionId] = helper

            val bb = BassBoostProcessor(sessionId)
            bb.isEnabled = cachedBassBoost.isEnabled
            bb.strength = cachedBassBoost.strength
            bb.centerFrequencyHz = cachedBassBoost.centerFrequencyHz
            activeBassBoosts[sessionId] = bb

            Log.i(TAG, "Attached ATS2835P DSP & BassBoost to external session $sessionId for $appLabel")
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
            activeBassBoosts[sessionId]?.release()
            activeBassBoosts.remove(sessionId)
            sessionAppNames.remove(sessionId)
            Log.i(TAG, "Detached ATS2835P DSP from session $sessionId")
            notifySessionsChanged()
        } catch (t: Throwable) {
            Log.e(TAG, "Error closing session $sessionId: ${t.message}", t)
        }
    }

    fun syncAudioEffects(
        equalizer: EqualizerProcessor,
        mdrc: MDRCProcessor,
        limiter: LimiterProcessor,
        bassBoost: BassBoostProcessor? = null
    ) {
        cachedEqualizer = equalizer
        cachedMdrc = mdrc
        cachedLimiter = limiter
        if (bassBoost != null) {
            cachedBassBoost.isEnabled = bassBoost.isEnabled
            cachedBassBoost.strength = bassBoost.strength
            cachedBassBoost.centerFrequencyHz = bassBoost.centerFrequencyHz
        }
        cachedEqualizer.bassBoostProcessor = cachedBassBoost

        // 1. Sync Global Output Mix
        globalMixHelper?.let { helper ->
            try {
                helper.applyEqualizer(equalizer, cachedBassBoost)
                helper.applyMDRC(mdrc)
                helper.applyLimiter(limiter)
            } catch (t: Throwable) {
                Log.w(TAG, "Error syncing global mix helper: ${t.message}")
            }
        }
        globalBassBoost?.let { bb ->
            bb.isEnabled = cachedBassBoost.isEnabled
            bb.strength = cachedBassBoost.strength
            bb.centerFrequencyHz = cachedBassBoost.centerFrequencyHz
            bb.updateNativeEffect()
        }

        // 2. Sync all external app sessions
        for ((sessionId, helper) in activeSessions) {
            try {
                val sessionBb = activeBassBoosts[sessionId] ?: cachedBassBoost
                helper.applyEqualizer(equalizer, sessionBb)
                helper.applyMDRC(mdrc)
                helper.applyLimiter(limiter)
            } catch (t: Throwable) {
                Log.w(TAG, "Error syncing session $sessionId: ${t.message}")
            }
        }
        for ((_, bb) in activeBassBoosts) {
            bb.isEnabled = cachedBassBoost.isEnabled
            bb.strength = cachedBassBoost.strength
            bb.centerFrequencyHz = cachedBassBoost.centerFrequencyHz
            bb.updateNativeEffect()
        }
    }

    private fun stopAllSessions() {
        try {
            globalMixHelper?.release()
            globalMixHelper = null
            globalBassBoost?.release()
            globalBassBoost = null
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing globalMixHelper: ${t.message}")
        }

        for ((_, helper) in activeSessions) {
            try {
                helper.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Error releasing external helper: ${t.message}")
            }
        }
        for ((_, bb) in activeBassBoosts) {
            try {
                bb.release()
            } catch (_: Throwable) {}
        }
        activeSessions.clear()
        activeBassBoosts.clear()
        sessionAppNames.clear()
    }

    fun getActiveSessionsSummary(): List<String> {
        val summary = mutableListOf<String>()
        if (!isGlobalModeEnabled) {
            return summary
        }

        if (globalMixHelper != null) {
            summary.add("● Android System Audio (Sesión 0: YouTube, Chrome, Juegos)")
        }

        for ((sessionId, appName) in sessionAppNames) {
            if (sessionId != 0) {
                summary.add("● $appName (Sesión #$sessionId)")
            }
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
        mainHandler.post {
            onSessionsChangedListener?.invoke()
        }
    }
}
