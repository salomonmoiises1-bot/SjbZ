package com.sjbz.aimp.audio

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Manages system-wide audio effects, capturing external media player sessions
 * (Spotify, YouTube, Apple Music, Chrome, etc.) and controlling system media volume.
 *
 * Employs:
 * - DynamicsProcessing (API 28+) with Multi-Band Compressor (Mbc) & EQ.
 * - Hardware Equalizer & BassBoost fallback.
 * - AudioManager STREAM_MUSIC volume synchronization.
 */
class GlobalAudioSessionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GlobalAudioSession"

        @Volatile
        private var instance: GlobalAudioSessionManager? = null

        fun getInstance(context: Context): GlobalAudioSessionManager {
            return instance?: synchronized(this) {
                instance?: GlobalAudioSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // System Audio FX Master State
    var isGlobalAudioEnabled: Boolean = false
        private set

    // Active audio sessions (sessionId -> SessionHolder)
    private val activeSessions = mutableMapOf<Int, SessionHolder>()
    private val activePackages = mutableMapOf<Int, String>()

    // Current cached audio parameters to apply to new or existing sessions
    private var cachedBandGains = FloatArray(32)
    private var cachedPreampDb = 0f
    private var cachedBassGainDb = 0f
    private var cachedBassFreqHz = 85f
    private var cachedMdrcEnabled = true
    private var cachedMdrcGains = floatArrayOf(0f, 0f, 0f)
    private var cachedMdrcThresholdDb = -14f
    private var cachedMdrcRatio = 3.0f

    // Volume callbacks
    var onSystemVolumeChangedListener: ((Int, Int) -> Unit)? = null
    var onActiveSessionsChangedListener: ((Int, List<String>) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ContentObserver for system media volume changes
    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val currentVol = getSystemVolume()
            val maxVol = getMaxSystemVolume()
            onSystemVolumeChangedListener?.invoke(currentVol, maxVol)
        }
    }

    init {
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register volume ContentObserver: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // System Media Volume Controls
    // -------------------------------------------------------------------------

    fun getSystemVolume(): Int {
        return try {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            0
        }
    }

    fun getMaxSystemVolume(): Int {
        return try {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            15
        }
    }

    fun setSystemVolume(volume: Int) {
        try {
            val clamped = volume.coerceIn(0, getMaxSystemVolume())
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
            onSystemVolumeChangedListener?.invoke(clamped, getMaxSystemVolume())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting stream volume: ${e.message}")
        }
    }

    fun adjustSystemVolume(increase: Boolean) {
        try {
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val newVol = getSystemVolume()
            onSystemVolumeChangedListener?.invoke(newVol, getMaxSystemVolume())
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting stream volume: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Global Audio Effects Lifecycle
    // -------------------------------------------------------------------------

    fun setGlobalAudioEnabled(enabled: Boolean) {
        if (isGlobalAudioEnabled == enabled) return
        isGlobalAudioEnabled = enabled

        if (enabled) {
            // Attach to global session 0 (affects all system audio on compatible devices)
            openSession(0, "Sistema Global")
        } else {
            // Release all active audio sessions
            releaseAllSessions()
        }
        dispatchSessionsChanged()
    }

    /**
     * Called when a system broadcast or external media app opens an audio session.
     */
    fun openSession(sessionId: Int, packageName: String? = null) {
        if (!isGlobalAudioEnabled) return
        if (activeSessions.containsKey(sessionId)) return

        try {
            val holder = SessionHolder(sessionId)
            initEffectsForSession(holder)
            activeSessions[sessionId] = holder
            packageName?.let { activePackages[sessionId] = it }
            Log.d(TAG, "Opened system audio session $sessionId ($packageName)")
            dispatchSessionsChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open session $sessionId: ${e.message}")
        }
    }

    /**
     * Called when a system broadcast or external media app closes an audio session.
     */
    fun closeSession(sessionId: Int) {
        val holder = activeSessions.remove(sessionId)
        activePackages.remove(sessionId)
        holder?.release()
        Log.d(TAG, "Closed system audio session $sessionId")
        dispatchSessionsChanged()
    }

    private fun releaseAllSessions() {
        for ((_, holder) in activeSessions) {
            holder.release()
        }
        activeSessions.clear()
        activePackages.clear()
    }

    private fun dispatchSessionsChanged() {
        val count = activeSessions.size
        val packages = activePackages.values.distinct()
        mainHandler.post {
            onActiveSessionsChangedListener?.invoke(count, packages)
        }
    }

    // -------------------------------------------------------------------------
    // Parameter Synchronization
    // -------------------------------------------------------------------------

    fun updateEqParams(
        gains: FloatArray,
        preampDb: Float,
        bassDb: Float,
        bassFreqHz: Float,
        mdrcEnabled: Boolean,
        mdrcGains: FloatArray,
        mdrcThresholdDb: Float,
        mdrcRatio: Float
    ) {
        System.arraycopy(gains, 0, cachedBandGains, 0, minOf(gains.size, cachedBandGains.size))
        this.cachedPreampDb = preampDb
        this.cachedBassGainDb = bassDb
        this.cachedBassFreqHz = bassFreqHz
        this.cachedMdrcEnabled = mdrcEnabled
        System.arraycopy(mdrcGains, 0, cachedMdrcGains, 0, minOf(mdrcGains.size, cachedMdrcGains.size))
        this.cachedMdrcThresholdDb = mdrcThresholdDb
        this.cachedMdrcRatio = mdrcRatio

        if (!isGlobalAudioEnabled) return

        for ((_, holder) in activeSessions) {
            applyParamsToHolder(holder)
        }
    }

    private fun initEffectsForSession(holder: SessionHolder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Initialize DynamicsProcessing with 32 EQ bands + 5-Band Multiband Compressor (Mbc)
                val channelCount = 2
                val mbcBandCount = 5
                val eqBandCount = 32

                val builder = DynamicsProcessing.Config.Builder(
                    0,
                    channelCount,
                    true, // preEqInUse
                    eqBandCount,
                    true, // mbcInUse (Multiband Compressor!)
                    mbcBandCount,
                    false, // postEqInUse
                    0,
                    true // limiterInUse
                )
                val config = builder.build()
                val dp = DynamicsProcessing(0, holder.sessionId, config)
                dp.enabled = true
                holder.dynamicsProcessing = dp
            } catch (e: Exception) {
                Log.w(TAG, "DynamicsProcessing not supported on session ${holder.sessionId}: ${e.message}")
            }
        }

        // Standard Equalizer fallback
        try {
            val eq = Equalizer(0, holder.sessionId)
            eq.enabled = true
            holder.equalizer = eq
        } catch (e: Exception) {
            Log.w(TAG, "Standard Equalizer fallback unavailable for session ${holder.sessionId}: ${e.message}")
        }

        // Standard BassBoost fallback
        try {
            val bb = BassBoost(0, holder.sessionId)
            bb.enabled = true
            holder.bassBoost = bb
        } catch (e: Exception) {
            Log.w(TAG, "BassBoost unavailable for session ${holder.sessionId}: ${e.message}")
        }

        applyParamsToHolder(holder)
    }

    private fun applyParamsToHolder(holder: SessionHolder) {
        // 1. DynamicsProcessing (Android 9.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && holder.dynamicsProcessing!= null) {
            val dp = holder.dynamicsProcessing!!
            try {
                // Pre-EQ 32 Bands
                for (i in 0 until minOf(32, cachedBandGains.size)) {
                    val freq = SjbzDspProcessor.ISO_FREQUENCIES[i]
                    val gain = cachedBandGains[i] + cachedPreampDb
                    val eqBand = DynamicsProcessing.EqBand(true, freq, gain)
                    dp.setPreEqBandAllChannelsTo(i, eqBand)
                }

                // 5-Band Multiband Compressor (MDRC)
                val mbcCutoffs = MDRCProcessor.SPLIT_FREQS
                for (b in 0 until 5) {
                    val cutoff = if (b < mbcCutoffs.size) mbcCutoffs[b] else 20000f
                    val mbcGain = if (cachedMdrcEnabled && b < cachedMdrcGains.size) cachedMdrcGains[b] else 0f
                    val mbcBand = DynamicsProcessing.MbcBand(
                        cachedMdrcEnabled,
                        cutoff,
                        10.0f, // attackTime
                        80.0f, // releaseTime
                        cachedMdrcRatio,
                        cachedMdrcThresholdDb,
                        4.0f, // kneeWidth
                        -90.0f, // noiseGateThreshold
                        1.0f, // expanderRatio
                        0.0f, // preGain
                        mbcGain // postGain
                    )
                    dp.setMbcBandAllChannelsTo(b, mbcBand)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying DynamicsProcessing params: ${e.message}")
            }
        }

        // 2. Standard Equalizer fallback
        holder.equalizer?.let { eq ->
            try {
                val numBands = eq.numberOfBands.toInt()
                for (b in 0 until numBands) {
                    val bandFreq = eq.getCenterFreq(b.toShort()) / 1000f // Hz
                    // Map to closest 32-band ISO index
                    var closestIdx = 0
                    var minDist = Float.MAX_VALUE
                    for (i in SjbzDspProcessor.ISO_FREQUENCIES.indices) {
                        val dist = kotlin.math.abs(SjbzDspProcessor.ISO_FREQUENCIES[i] - bandFreq)
                        if (dist < minDist) {
                            minDist = dist
                            closestIdx = i
                        }
                    }
                    val gainMilliBels = ((cachedBandGains[closestIdx] + cachedPreampDb) * 100).toInt().coerceIn(-1200, 1200)
                    eq.setBandLevel(b.toShort(), gainMilliBels.toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error applying Equalizer level: ${e.message}")
            }
        }

        // 3. BassBoost fallback
        holder.bassBoost?.let { bb ->
            try {
                if (cachedBassGainDb > 0.1f) {
                    val strength = (cachedBassGainDb / 12.0f * 1000).toInt().coerceIn(0, 1000)
                    bb.setStrength(strength.toShort())
                    bb.enabled = true
                } else {
                    bb.enabled = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error applying BassBoost: ${e.message}")
            }
        }
    }

    /**
     * Container holding Android AudioEffects for an active session.
     */
    private class SessionHolder(val sessionId: Int) {
        var dynamicsProcessing: DynamicsProcessing? = null
        var equalizer: Equalizer? = null
        var bassBoost: BassBoost? = null

        fun release() {
            try {
                dynamicsProcessing?.enabled = false
                dynamicsProcessing?.release()
            } catch (_: Exception) {}
            dynamicsProcessing = null

            try {
                equalizer?.enabled = false
                equalizer?.release()
            } catch (_: Exception) {}
            equalizer = null

            try {
                bassBoost?.enabled = false
                bassBoost?.release()
            } catch (_: Exception) {}
            bassBoost = null
        }
    }
}
