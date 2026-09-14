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

        /**
         * Factory creating DynamicsProcessing.EqBand instance.
         * Supports (enabled, centerFreq, gain, q = 1f) or (enabled, centerFreq, gain).
         */
        fun createEqBand(enabled: Boolean, centerFreq: Float, gain: Float, q: Float = 1.0f): DynamicsProcessing.EqBand {
            return DynamicsProcessing.EqBand(enabled, centerFreq, gain)
        }
    }

    /**
     * Directly updates a PreEq band across all channels on the active DynamicsProcessing effect.
     * Note: Never calls setPreEqBandAllChannelsTo on Eq (which doesn't support it);
     * calls dynamicsProcessing.setPreEqBandAllChannelsTo(band, eqBand) directly.
     */
    fun setPreEqBandAllChannelsTo(band: Int, eqBand: DynamicsProcessing.EqBand) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dynamicsProcessing?.setPreEqBandAllChannelsTo(band, eqBand)
            } catch (t: Throwable) {
                Log.w(TAG, "Error setting PreEq band $band: ${t.message}")
            }
        }
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
     * Reuses active instance if audioSessionId is unchanged to prevent heavy HAL audio IPC blockages (ANR).
     */
    fun attachToSession(
        audioSessionId: Int,
        equalizerProcessor: EqualizerProcessor,
        mdrcProcessor: MDRCProcessor,
        limiterProcessor: LimiterProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        if (audioSessionId < 0) return
        
        // CRITICAL ANTI-ANR: If already attached to this audioSessionId, never recreate heavy HAL effect
        if (currentSessionId == audioSessionId && (dynamicsProcessing != null || legacyEqualizer != null)) {
            applyEqualizer(equalizerProcessor, bassBoostProcessor)
            applyMDRC(mdrcProcessor)
            applyLimiter(limiterProcessor)
            return
        }

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

                // Pre-configure all 32 ISO PreEq bands with strictly ascending cutoff frequencies
                // including parametric psychoacoustic bass boost (Android 12+ DSP synthesis)
                for (b in 0 until preEqBandCount) {
                    val cutoff = EqualizerProcessor.ISO_FREQUENCIES[b]
                    val gain = equalizerProcessor.getEffectiveGain(b, bassBoostProcessor)
                    val eqBand = createEqBand(equalizerProcessor.isEnabled, cutoff, gain, 1.0f)
                    configBuilder.setPreEqBandAllChannelsTo(b, eqBand)
                }

                val config = configBuilder.build()
                dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config).apply {
                    enabled = true
                }

                // Apply initial parameters
                applyEqualizer(equalizerProcessor, bassBoostProcessor)
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
        fallbackToLegacyEqualizer(audioSessionId, equalizerProcessor, bassBoostProcessor)
    }

    private fun fallbackToLegacyEqualizer(
        audioSessionId: Int,
        equalizerProcessor: EqualizerProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        try {
            legacyEqualizer = Equalizer(0, audioSessionId).apply {
                enabled = equalizerProcessor.isEnabled
            }
            applyLegacyEqualizer(equalizerProcessor, bassBoostProcessor)
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
     * Updates the 32 PreEq bands across all channels atomically, including DSP Bass Boost.
     */
    fun applyEqualizer(
        equalizerProcessor: EqualizerProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        val dp = dynamicsProcessing
        val effectiveBb = bassBoostProcessor ?: equalizerProcessor.bassBoostProcessor
        if (dp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val bandCount = EqualizerProcessor.BAND_COUNT
                for (b in 0 until bandCount) {
                    val cutoff = EqualizerProcessor.ISO_FREQUENCIES[b]
                    val gain = if (equalizerProcessor.isEnabled) {
                        equalizerProcessor.getEffectiveGain(b, effectiveBb)
                    } else {
                        0.0f
                    }
                    val eqBand = DynamicsProcessing.EqBand(equalizerProcessor.isEnabled, cutoff, gain)
                    dp.setPreEqBandAllChannelsTo(b, eqBand)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error updating DynamicsProcessing PreEq: ${t.message}")
            }
            return
        }

        // Legacy fallback
        val eq = legacyEqualizer
        if (eq != null) {
            applyLegacyEqualizer(equalizerProcessor, effectiveBb)
        }
    }

    private fun applyLegacyEqualizer(
        equalizerProcessor: EqualizerProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        val eq = legacyEqualizer ?: return
        try {
            eq.enabled = equalizerProcessor.isEnabled
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange ?: return
            val minLevel = range[0]
            val maxLevel = range[1]

            val isoFreqs = EqualizerProcessor.ISO_FREQUENCIES
            val effectiveBb = bassBoostProcessor ?: equalizerProcessor.bassBoostProcessor

            for (b in 0 until numBands) {
                val centerFreqHz = eq.getCenterFreq(b.toShort()) / 1000.0f

                // Smooth log-distance Gaussian-weighted gain across all 32 bands.
                // Ensures that 25, 31.5, 40, 63, 85, 100, 125, 200 Hz directly and
                // powerfully influence the hardware legacy equalizer's low bands!
                var weightSum = 0.0
                var weightedGainSum = 0.0

                for (i in isoFreqs.indices) {
                    val logDist = kotlin.math.abs(kotlin.math.log10(isoFreqs[i].toDouble()) - kotlin.math.log10(centerFreqHz.toDouble()))
                    val weight = 1.0 / (1.0 + (logDist / 0.35) * (logDist / 0.35))
                    val g = if (equalizerProcessor.isEnabled) {
                        equalizerProcessor.getEffectiveGain(i, effectiveBb).toDouble()
                    } else {
                        0.0
                    }
                    weightedGainSum += g * weight
                    weightSum += weight
                }

                val targetDb = if (weightSum > 0.0) (weightedGainSum / weightSum).toFloat() else 0.0f
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

/**
 * Global factory for DynamicsProcessing.EqBand.
 * Allows calling EqBand(true, centerFreq, gain, 1f) safely.
 * Android DynamicsProcessing.EqBand constructor takes (boolean enabled, float cutoffFrequency, float gain).
 */
fun EqBand(enabled: Boolean, centerFreq: Float, gain: Float, q: Float = 1.0f): DynamicsProcessing.EqBand {
    return DynamicsProcessing.EqBand(enabled, centerFreq, gain)
}

