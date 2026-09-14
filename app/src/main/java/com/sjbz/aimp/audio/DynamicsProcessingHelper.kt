package com.sjbz.aimp.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log

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

        fun createEqBand(enabled: Boolean, centerFreq: Float, gain: Float, q: Float = 1.0f): DynamicsProcessing.EqBand {
            return DynamicsProcessing.EqBand(enabled, centerFreq, gain)
        }
    }

    /**
     * Directly updates a PreEq band across all channels on the active DynamicsProcessing effect.
     * Uses setPreEqBandByChannelIndex for both channels to avoid Unresolved reference on some SDKs.
     */
    fun setPreEqBandAllChannelsTo(band: Int, eqBand: DynamicsProcessing.EqBand) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dynamicsProcessing?.setPreEqBandByChannelIndex(0, band, eqBand)
                dynamicsProcessing?.setPreEqBandByChannelIndex(1, band, eqBand)
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

    fun attachToSession(
        audioSessionId: Int,
        equalizerProcessor: EqualizerProcessor,
        mdrcProcessor: MDRCProcessor,
        limiterProcessor: LimiterProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        if (audioSessionId < 0) return

        if (currentSessionId == audioSessionId && (dynamicsProcessing!= null || legacyEqualizer!= null)) {
            applyEqualizer(equalizerProcessor, bassBoostProcessor)
            applyMDRC(mdrcProcessor)
            applyLimiter(limiterProcessor)
            return
        }

        release()
        currentSessionId = audioSessionId

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val preEqBandCount = EqualizerProcessor.BAND_COUNT
                val mbcBandCount = mdrcProcessor.getBandCount()

                val configBuilder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true,
                    preEqBandCount,
                    true,
                    mbcBandCount,
                    false,
                    0,
                    limiterProcessor.isEffectivelyActive()
                )

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

    fun applyEqualizer(
        equalizerProcessor: EqualizerProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        val dp = dynamicsProcessing
        val effectiveBb = bassBoostProcessor?: equalizerProcessor.bassBoostProcessor
        if (dp!= null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
                    dp.setPreEqBandByChannelIndex(0, b, eqBand)
                    dp.setPreEqBandByChannelIndex(1, b, eqBand)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error updating DynamicsProcessing PreEq: ${t.message}")
            }
            return
        }

        val eq = legacyEqualizer
        if (eq!= null) {
            applyLegacyEqualizer(equalizerProcessor, effectiveBb)
        }
    }

    private fun applyLegacyEqualizer(
        equalizerProcessor: EqualizerProcessor,
        bassBoostProcessor: BassBoostProcessor? = null
    ) {
        val eq = legacyEqualizer?: return
        try {
            eq.enabled = equalizerProcessor.isEnabled
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange?: return
            val minLevel = range[0]
            val maxLevel = range[1]

            val isoFreqs = EqualizerProcessor.ISO_FREQUENCIES
            val effectiveBb = bassBoostProcessor?: equalizerProcessor.bassBoostProcessor

            for (b in 0 until numBands) {
                val centerFreqHz = eq.getCenterFreq(b.toShort()) / 1000.0f
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

    fun applyMDRC(mdrcProcessor: MDRCProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            val bandCount = mdrcProcessor.getBandCount()
            for (ch in 0..1) {
                for (b in 0 until bandCount) {
                    val band = mdrcProcessor.getBand(b)?: continue
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
                    dp.setMbcBandByChannelIndex(ch, b, mbcBand)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating DynamicsProcessing MBC: ${t.message}")
        }
    }

    fun applyLimiter(limiterProcessor: LimiterProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            val limiterActive = limiterProcessor.isEffectivelyActive()
            for (ch in 0..1) {
                val limiter = DynamicsProcessing.Limiter(
                    limiterActive,
                    limiterActive,
                    0,
                    limiterProcessor.attackMs,
                    limiterProcessor.releaseMs,
                    limiterProcessor.ratio,
                    limiterProcessor.thresholdDb,
                    0.0f
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

fun EqBand(enabled: Boolean, centerFreq: Float, gain: Float, q: Float = 1.0f): DynamicsProcessing.EqBand {
    return DynamicsProcessing.EqBand(enabled, centerFreq, gain)
}
