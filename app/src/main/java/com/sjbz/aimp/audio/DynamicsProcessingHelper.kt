package com.sjbz.aimp.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10

/**
 * DynamicsProcessing helper for Android 9.0+ (API 28+).
 * Handles real hardware Multi-band Dynamic Range Compression (MDRC), 32-band PreEq,
 * and ATS2835P hardware limiter.
 *
 * Provides completely crash-free fallback to legacy Equalizer on older devices or OEM failures.
 */
class DynamicsProcessingHelper {

    companion object {
        private const val TAG = "DynamicsProcHelper"
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var legacyEqualizer: Equalizer? = null
    private var currentSessionId: Int = 0

    var isHardwareDspActive: Boolean = false
        private set
    var isLegacyFallbackActive: Boolean = false
        private set

    /**
     * Attaches audio processing to the specified ExoPlayer audioSessionId.
     */
    fun attachToSession(
        audioSessionId: Int,
        equalizerProcessor: EqualizerProcessor,
        mdrcProcessor: MDRCProcessor,
        limiterProcessor: LimiterProcessor
    ) {
        if (audioSessionId <= 0) return
        release()
        currentSessionId = audioSessionId

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val preEqBandCount = EqualizerProcessor.BAND_COUNT // 32 bands
                val mbcBandCount = mdrcProcessor.getBandCount()     // 5 bands

                val configBuilder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,                  // Stereo: 2 channels
                    true,               // preEqInUse (32-band PreEq)
                    preEqBandCount,     // preEqBandCount
                    true,               // mbcInUse (5-band MDRC)
                    mbcBandCount,       // mbcBandCount
                    false,              // postEqInUse
                    0,                  // postEqBandCount
                    limiterProcessor.isEffectivelyActive() // limiterInUse
                )

                val config = configBuilder.build()
                dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config).apply {
                    enabled = true
                }

                // Apply initial parameters
                applyEqualizer(equalizerProcessor)
                applyMDRC(mdrcProcessor)
                applyLimiter(limiterProcessor)

                isHardwareDspActive = true
                isLegacyFallbackActive = false
                Log.i(TAG, "Hardware DynamicsProcessing (32 PreEq + 5 MDRC) successfully attached to session $audioSessionId")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "DynamicsProcessing initialization failed, falling back to legacy Equalizer: ${t.message}")
                dynamicsProcessing = null
                isHardwareDspActive = false
            }
        }

        // Safe Fallback to legacy Equalizer
        fallbackToLegacyEqualizer(audioSessionId, equalizerProcessor)
    }

    private fun fallbackToLegacyEqualizer(audioSessionId: Int, equalizerProcessor: EqualizerProcessor) {
        try {
            legacyEqualizer = Equalizer(0, audioSessionId).apply {
                enabled = equalizerProcessor.isEnabled
            }
            applyLegacyEqualizer(equalizerProcessor)
            isLegacyFallbackActive = true
            isHardwareDspActive = false
            Log.i(TAG, "Legacy Equalizer attached safely to session $audioSessionId")
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy Equalizer failed safely: ${t.message}")
            legacyEqualizer = null
            isLegacyFallbackActive = false
        }
    }

    /**
     * Updates the 32 PreEq bands on both stereo channels (0 and 1).
     */
    fun applyEqualizer(equalizerProcessor: EqualizerProcessor) {
        val dp = dynamicsProcessing
        if (dp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val bandCount = EqualizerProcessor.BAND_COUNT
                val preamp = equalizerProcessor.preampDb

                for (ch in 0..1) {
                    for (b in 0 until bandCount) {
                        val cutoff = EqualizerProcessor.ISO_FREQUENCIES[b]
                        val gain = if (equalizerProcessor.isEnabled) {
                            (equalizerProcessor.getBandGain(b) + preamp).coerceIn(-12.0f, 12.0f)
                        } else {
                            0.0f
                        }

                        val eqBand = DynamicsProcessing.EqBand(equalizerProcessor.isEnabled, cutoff, gain)
                        dp.setPreEqBandByChannelIndex(ch, b, eqBand)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error updating DynamicsProcessing PreEq: ${t.message}")
            }
            return
        }

        // Legacy fallback
        val eq = legacyEqualizer
        if (eq != null) {
            applyLegacyEqualizer(equalizerProcessor)
        }
    }

    private fun applyLegacyEqualizer(equalizerProcessor: EqualizerProcessor) {
        val eq = legacyEqualizer ?: return
        try {
            eq.enabled = equalizerProcessor.isEnabled
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange ?: return
            val minLevel = range[0]
            val maxLevel = range[1]

            val isoFreqs = EqualizerProcessor.ISO_FREQUENCIES
            val bandGains = equalizerProcessor.getBandGains()
            val preamp = equalizerProcessor.preampDb

            for (b in 0 until numBands) {
                val centerFreqHz = eq.getCenterFreq(b.toShort()) / 1000.0f

                // Find closest band among 32 ISO frequencies using log distance
                var closestIdx = 0
                var minDiff = Float.MAX_VALUE
                for (i in isoFreqs.indices) {
                    val diff = abs(log10(isoFreqs[i]) - log10(centerFreqHz))
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIdx = i
                    }
                }

                val targetDb = (bandGains[closestIdx] + preamp).coerceIn(-12.0f, 12.0f)
                val milliBels = (targetDb * 100.0f).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
                eq.setBandLevel(b.toShort(), milliBels)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating legacy Equalizer: ${t.message}")
        }
    }

    /**
     * Updates the 5 MDRC compression bands on both stereo channels.
     */
    fun applyMDRC(mdrcProcessor: MDRCProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            val bandCount = mdrcProcessor.getBandCount()
            for (ch in 0..1) {
                for (b in 0 until bandCount) {
                    val band = mdrcProcessor.getBand(b) ?: continue
                    val mbcBand = DynamicsProcessing.MbcBand(
                        band.isEnabled && mdrcProcessor.isEnabled,
                        band.cutoffHz,
                        band.attackMs,
                        band.releaseMs,
                        band.ratio,
                        band.thresholdDb,
                        band.kneeWidthDb,
                        0.0f, // noiseGateThreshold
                        1.0f, // expanderRatio
                        band.gainDb, // preGain
                        0.0f  // postGain
                    )
                    dp.setMbcBandByChannelIndex(ch, b, mbcBand)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating DynamicsProcessing MBC: ${t.message}")
        }
    }

    /**
     * Updates the hardware Limiter.
     */
    fun applyLimiter(limiterProcessor: LimiterProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            val limiterActive = limiterProcessor.isEffectivelyActive()
            for (ch in 0..1) {
                val limiter = DynamicsProcessing.Limiter(
                    limiterActive,
                    limiterActive,
                    0, // linkGroup
                    limiterProcessor.attackMs,
                    limiterProcessor.releaseMs,
                    limiterProcessor.ratio,
                    limiterProcessor.thresholdDb,
                    0.0f // postGain
                )
                dp.setLimiterByChannelIndex(ch, limiter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating DynamicsProcessing Limiter: ${t.message}")
        }
    }

    fun release() {
        try {
            dynamicsProcessing?.release()
        } catch (_: Throwable) {}
        dynamicsProcessing = null

        try {
            legacyEqualizer?.release()
        } catch (_: Throwable) {}
        legacyEqualizer = null

        isHardwareDspActive = false
        isLegacyFallbackActive = false
        currentSessionId = 0
    }
}
