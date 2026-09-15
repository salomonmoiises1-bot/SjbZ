package com.sjbz.aimp.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log

/**
 * DynamicsProcessingHelper para SjbZ / ATS2835P.
 */
class DynamicsProcessingHelper {

    companion object {
        private const val TAG = "DynamicsProcHelper"
        private val PRE_EQ_CANDIDATES = intArrayOf(32, 16, 8)
        private const val MAX_PRE_EQ_GAIN_DB = 12.0f
        private const val MIN_PRE_EQ_GAIN_DB = -12.0f
        private const val SLEW_MAX_DB_PER_APPLY = 3.0f
    }

    private val dpLock = Any()

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var legacyEqualizer: Equalizer? = null

    // PATCH anti-clipseo: memoria de ganancias para rampa suave
    private var lastGains = FloatArray(EqualizerProcessor.BAND_COUNT) { 0f }

    @Volatile
    private var currentSessionId: Int = 0

    @Volatile
    var isHardwareDspActive: Boolean = false
        private set
    @Volatile
    var isLegacyFallbackActive: Boolean = false
        private set

    fun attachToSession(
        audioSessionId: Int,
        equalizerProcessor: EqualizerProcessor,
        mdrcProcessor: MDRCProcessor,
        limiterProcessor: LimiterProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        synchronized(dpLock) {
            if (audioSessionId <= 0) {
                Log.w(TAG, "attachToSession: sessionId inválido $audioSessionId, se ignora (consistente con BassBoostProcessor)")
                return
            }

            if (currentSessionId == audioSessionId && (dynamicsProcessing!= null || legacyEqualizer!= null)) {
                Log.d(TAG, "attachToSession: misma sesión $audioSessionId, reaplicando sin recrear")
                if (bassBoostProcessor!= null) {
                    equalizerProcessor.bassBoostProcessor = bassBoostProcessor
                }
                applyEqualizerLocked(equalizerProcessor, bassBoostProcessor)
                applyMDRCLocked(mdrcProcessor)
                applyLimiterLocked(limiterProcessor)
                return
            }

            releaseLocked()
            currentSessionId = audioSessionId
            // reset rampa al cambiar de sesión
            lastGains = FloatArray(EqualizerProcessor.BAND_COUNT) { 0f }

            if (bassBoostProcessor!= null) {
                equalizerProcessor.bassBoostProcessor = bassBoostProcessor
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val mbcBandCount = try {
                    mdrcProcessor.getBandCount()
                } catch (t: Throwable) {
                    Log.w(TAG, "getBandCount() falló: ${t.message}, usando 0")
                    0
                }

                var created = false
                var usedPreEqCount = 0

                for (candidate in PRE_EQ_CANDIDATES) {
                    try {
                        val configBuilder = DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            2,
                            true,
                            candidate,
                            true,
                            mbcBandCount,
                            false,
                            0,
                            limiterProcessor.isEffectivelyActive()
                        )
                        val config = configBuilder.build()
                        val dp = DynamicsProcessing(0, audioSessionId, config).apply { enabled = true }
                        dynamicsProcessing = dp
                        usedPreEqCount = candidate
                        created = true
                        Log.i(TAG, "DynamicsProcessing creado: session=$audioSessionId preEqBands=$candidate mbcBands=$mbcBandCount limiterInUse=${limiterProcessor.isEffectivelyActive()}")
                        break
                    } catch (t: Throwable) {
                        Log.w(TAG, "DynamicsProcessing con $candidate bandas PreEq rechazado por HAL en sesión $audioSessionId: ${t.message}")
                        try { dynamicsProcessing?.release() } catch (_: Throwable) {}
                        dynamicsProcessing = null
                    }
                }

                if (created) {
                    applyEqualizerLocked(equalizerProcessor, bassBoostProcessor)
                    applyMDRCLocked(mdrcProcessor)
                    applyLimiterLocked(limiterProcessor)
                    isHardwareDspActive = true
                    isLegacyFallbackActive = false
                    Log.i(TAG, "Hardware DynamicsProcessing ($usedPreEqCount PreEq + $mbcBandCount MDRC) attached to session $audioSessionId")
                    return
                } else {
                    Log.w(TAG, "Ningún candidato PreEq aceptado por HAL, cayendo a legacy")
                    dynamicsProcessing = null
                    isHardwareDspActive = false
                }
            } else {
                Log.i(TAG, "API < P, directo a legacy")
            }

            fallbackToLegacyEqualizerLocked(audioSessionId, equalizerProcessor, bassBoostProcessor)
        }
    }

    private fun fallbackToLegacyEqualizerLocked(
        audioSessionId: Int,
        equalizerProcessor: EqualizerProcessor,
        bassBoostProcessor: BassBoostProcessor?
    ) {
        try {
            val shouldEnable = equalizerProcessor.isEnabled || (bassBoostProcessor?.isEnabled == true)
            val eq = Equalizer(0, audioSessionId).apply { enabled = shouldEnable }
            legacyEqualizer = eq
            applyLegacyEqualizerLocked(equalizerProcessor, bassBoostProcessor)
            isLegacyFallbackActive = true
            isHardwareDspActive = false
            Log.i(TAG, "Legacy Equalizer fallback en sesión $audioSessionId enabled=$shouldEnable bands=${eq.numberOfBands}")
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy Equalizer failed en sesión $audioSessionId: ${t.message}", t)
            try { legacyEqualizer?.release() } catch (_: Throwable) {}
            legacyEqualizer = null
            isLegacyFallbackActive = false
        }
    }

    fun applyEqualizer(equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor? = null) {
        synchronized(dpLock) {
            applyEqualizerLocked(equalizerProcessor, bassBoostProcessor)
        }
    }

    private fun applyEqualizerLocked(equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor?) {
        val dp = dynamicsProcessing
        val effectiveBb = bassBoostProcessor?: equalizerProcessor.bassBoostProcessor

        if (dp!= null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val nativeActive: Boolean = try {
                    effectiveBb?.isNativeActive() == true
                } catch (_: Throwable) {
                    false
                }

                val preEqCount = EqualizerProcessor.BAND_COUNT

                for (b in 0 until preEqCount) {
                    if (b >= EqualizerProcessor.ISO_FREQUENCIES.size) break
                    val cutoff = EqualizerProcessor.ISO_FREQUENCIES[b]

                    val eqOnlyGain: Float = try {
                        if (equalizerProcessor.isEnabled) equalizerProcessor.getEffectiveGain(b, null) else 0.0f
                    } catch (t: Throwable) {
                        Log.w(TAG, "getEffectiveGain(b, null) no soportado: ${t.message}, usando path alternativo")
                        0.0f
                    }

                    val bbGain: Float = if (nativeActive) {
                        0.0f
                    } else {
                        try {
                            effectiveBb?.getBoostGainForFrequency(cutoff)?: 0.0f
                        } catch (t: Throwable) {
                            Log.w(TAG, "getBoostGainForFrequency($cutoff) falló: ${t.message}")
                            0.0f
                        }
                    }

                    val rawTotal: Float = if (eqOnlyGain == 0.0f && equalizerProcessor.isEnabled) {
                        try {
                            equalizerProcessor.getEffectiveGain(b, effectiveBb)
                        } catch (_: Throwable) {
                            bbGain
                        }
                    } else {
                        eqOnlyGain + bbGain
                    }

                    val totalGain = rawTotal.coerceIn(MIN_PRE_EQ_GAIN_DB, MAX_PRE_EQ_GAIN_DB)

                    // PATCH anti-clipseo: slew-rate limiter 3dB por aplicación
                    val prev = lastGains.getOrElse(b) { 0f }
                    val smoothed = totalGain.coerceIn(prev - SLEW_MAX_DB_PER_APPLY, prev + SLEW_MAX_DB_PER_APPLY)
                    if (b < lastGains.size) lastGains[b] = smoothed

                    val eqBand = DynamicsProcessing.EqBand(true, cutoff, smoothed)
                    try {
                        dp.setPreEqBandByChannelIndex(0, b, eqBand)
                    } catch (t: Throwable) {
                        Log.w(TAG, "setPreEqBand ch0 band=$b cutoff=$cutoff: ${t.message}")
                    }
                    try {
                        dp.setPreEqBandByChannelIndex(1, b, eqBand)
                    } catch (t: Throwable) {
                        Log.w(TAG, "setPreEqBand ch1 band=$b cutoff=$cutoff: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error updating PreEq: ${t.message}", t)
            }
            return
        }

        if (legacyEqualizer!= null) {
            applyLegacyEqualizerLocked(equalizerProcessor, effectiveBb)
        } else {
            Log.w(TAG, "applyEqualizer: sin DP ni legacy, no se aplica nada (sesión $currentSessionId)")
        }
    }

    private fun applyLegacyEqualizerLocked(equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor?) {
        val eq = legacyEqualizer?: return
        try {
            val effectiveBb = bassBoostProcessor?: equalizerProcessor.bassBoostProcessor

            val nativeActive: Boolean = try {
                effectiveBb?.isNativeActive() == true
            } catch (_: Throwable) {
                false
            }

            val shouldEnable = equalizerProcessor.isEnabled || (effectiveBb?.isEnabled == true)
            try {
                eq.enabled = shouldEnable
            } catch (t: Throwable) {
                Log.w(TAG, "legacy eq.enabled=$shouldEnable falló: ${t.message}")
            }

            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange?: run {
                Log.w(TAG, "legacy bandLevelRange null, abortando")
                return
            }
            val isoFreqs = EqualizerProcessor.ISO_FREQUENCIES

            for (b in 0 until numBands) {
                val centerFreqHz: Float = try {
                    eq.getCenterFreq(b.toShort()) / 1000.0f
                } catch (t: Throwable) {
                    Log.w(TAG, "getCenterFreq($b) falló: ${t.message}")
                    continue
                }

                var weightSum = 0.0
                var weightedGainSum = 0.0

                for (i in isoFreqs.indices) {
                    val logDist = kotlin.math.abs(
                        kotlin.math.log10(isoFreqs[i].toDouble()) - kotlin.math.log10(centerFreqHz.toDouble())
                    )
                    val weight = 1.0 / (1.0 + (logDist / 0.35) * (logDist / 0.35))

                    val eqGain: Float = if (equalizerProcessor.isEnabled) {
                        try {
                            equalizerProcessor.getEffectiveGain(i, null)
                        } catch (_: Throwable) {
                            0.0f
                        }
                    } else 0.0f

                    val bbGain: Float = if (nativeActive) 0.0f else {
                        try {
                            effectiveBb?.getBoostGainForFrequency(isoFreqs[i])?: 0.0f
                        } catch (_: Throwable) {
                            0.0f
                        }
                    }

                    val g = (eqGain + bbGain).toDouble()
                    weightedGainSum += g * weight
                    weightSum += weight
                }

                val targetDb = if (weightSum > 0.0) (weightedGainSum / weightSum).toFloat() else 0.0f
                val targetDbClamped = targetDb.coerceIn(MIN_PRE_EQ_GAIN_DB, MAX_PRE_EQ_GAIN_DB)
                val milliBels = (targetDbClamped * 100.0f).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                try {
                    eq.setBandLevel(b.toShort(), milliBels)
                } catch (t: Throwable) {
                    Log.w(TAG, "setBandLevel($b, $milliBels) falló: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating legacy EQ: ${t.message}", t)
        }
    }

    fun applyMDRC(mdrcProcessor: MDRCProcessor) {
        synchronized(dpLock) {
            applyMDRCLocked(mdrcProcessor)
        }
    }

    private fun applyMDRCLocked(mdrcProcessor: MDRCProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val bandCount = try { mdrcProcessor.getBandCount() } catch (t: Throwable) {
                Log.w(TAG, "MDRC getBandCount falló: ${t.message}")
                return
            }
            for (ch in 0..1) {
                for (b in 0 until bandCount) {
                    val band = try { mdrcProcessor.getBand(b) } catch (t: Throwable) {
                        Log.w(TAG, "MDRC getBand($b) falló: ${t.message}")
                        continue
                    }?: continue
                    val mbcBand = DynamicsProcessing.MbcBand(
                        band.isEnabled && mdrcProcessor.isEnabled,
                        band.cutoffHz,
                        band.attackMs,
                        band.releaseMs,
                        band.ratio,
                        band.thresholdDb,
                        band.kneeWidthDb,
                        0.0f,
                        1.0f,
                        band.gainDb,
                        0.0f
                    )
                    try {
                        dp.setMbcBandByChannelIndex(ch, b, mbcBand)
                    } catch (t: Throwable) {
                        Log.w(TAG, "setMbcBand ch=$ch band=$b: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error MBC: ${t.message}", t)
        }
    }

    fun applyLimiter(limiterProcessor: LimiterProcessor) {
        synchronized(dpLock) {
            applyLimiterLocked(limiterProcessor)
        }
    }

    private fun applyLimiterLocked(limiterProcessor: LimiterProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val active = try { limiterProcessor.isEffectivelyActive() } catch (t: Throwable) {
                Log.w(TAG, "isEffectivelyActive falló: ${t.message}")
                false
            }
            // PATCH: usar postGainDb para compensación automática de headroom
            val postGain = try { limiterProcessor.postGainDb } catch (_: Throwable) { 0f }
            for (ch in 0..1) {
                val limiter = DynamicsProcessing.Limiter(
                    active,
                    active,
                    0,
                    limiterProcessor.attackMs,
                    limiterProcessor.releaseMs,
                    limiterProcessor.thresholdDb,
                    limiterProcessor.ratio,
                    postGain
                )
                try {
                    dp.setLimiterByChannelIndex(ch, limiter)
                } catch (t: Throwable) {
                    Log.w(TAG, "setLimiter ch=$ch: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error Limiter: ${t.message}", t)
        }
    }

    fun release() {
        synchronized(dpLock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        try {
            dynamicsProcessing?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release dynamicsProcessing: ${t.message}")
        } finally {
            dynamicsProcessing = null
        }
        try {
            legacyEqualizer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release legacyEqualizer: ${t.message}")
        } finally {
            legacyEqualizer = null
        }
        isHardwareDspActive = false
        isLegacyFallbackActive = false
        currentSessionId = 0
        lastGains = FloatArray(EqualizerProcessor.BAND_COUNT) { 0f }
    }
}
