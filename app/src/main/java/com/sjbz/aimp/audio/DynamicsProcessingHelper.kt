package com.sjbz.aimp.audio

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log

class DynamicsProcessingHelper {

    companion object {
        private const val TAG = "DynamicsProcHelper"
        fun createEqBand(enabled: Boolean, centerFreq: Float, gain: Float): DynamicsProcessing.EqBand {
            return DynamicsProcessing.EqBand(enabled, centerFreq, gain)
        }
    }

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

    // PARCHE: helper para linkear bassBoost -> eq -> dsp
    fun bindBassBoost(equalizerProcessor: EqualizerProcessor, bassBoost: BassBoostProcessor) {
        equalizerProcessor.linkBassBoost(bassBoost) {
            applyEqualizer(equalizerProcessor, bassBoost)
        }
    }

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

                val config = configBuilder.build()
                dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config).apply { enabled = true }

                applyEqualizer(equalizerProcessor, bassBoostProcessor)
                applyMDRC(mdrcProcessor)
                applyLimiter(limiterProcessor)

                isHardwareDspActive = true
                isLegacyFallbackActive = false
                Log.i(TAG, "Hardware DynamicsProcessing (32 PreEq + 5 MDRC) attached to session $audioSessionId")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "DynamicsProcessing init failed, fallback: ${t.message}")
                dynamicsProcessing = null
                isHardwareDspActive = false
            }
        }
        fallbackToLegacyEqualizer(audioSessionId, equalizerProcessor, bassBoostProcessor)
    }

    private fun fallbackToLegacyEqualizer(audioSessionId: Int, equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor? = null) {
        try {
            legacyEqualizer = Equalizer(0, audioSessionId).apply { enabled = equalizerProcessor.isEnabled }
            applyLegacyEqualizer(equalizerProcessor, bassBoostProcessor)
            isLegacyFallbackActive = true
            isHardwareDspActive = false
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy Equalizer failed: ${t.message}")
            legacyEqualizer = null
            isLegacyFallbackActive = false
        }
    }

    fun applyEqualizer(equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor? = null) {
        val dp = dynamicsProcessing
        val effectiveBb = bassBoostProcessor?: equalizerProcessor.bassBoostProcessor
        if (dp!= null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                for (b in 0 until EqualizerProcessor.BAND_COUNT) {
                    val cutoff = EqualizerProcessor.ISO_FREQUENCIES[b]
                    val gain = if (equalizerProcessor.isEnabled) equalizerProcessor.getEffectiveGain(b, effectiveBb) else 0.0f
                    val eqBand = DynamicsProcessing.EqBand(equalizerProcessor.isEnabled, cutoff, gain)
                    dp.setPreEqBandByChannelIndex(0, b, eqBand)
                    dp.setPreEqBandByChannelIndex(1, b, eqBand)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error updating PreEq: ${t.message}")
            }
            return
        }
        legacyEqualizer?.let { applyLegacyEqualizer(equalizerProcessor, effectiveBb) }
    }

    private fun applyLegacyEqualizer(equalizerProcessor: EqualizerProcessor, bassBoostProcessor: BassBoostProcessor? = null) {
        val eq = legacyEqualizer?: return
        try {
            eq.enabled = equalizerProcessor.isEnabled
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange?: return
            val isoFreqs = EqualizerProcessor.ISO_FREQUENCIES
            val effectiveBb = bassBoostProcessor?: equalizerProcessor.bassBoostProcessor
            for (b in 0 until numBands) {
                val centerFreqHz = eq.getCenterFreq(b.toShort()) / 1000.0f
                var weightSum = 0.0; var weightedGainSum = 0.0
                for (i in isoFreqs.indices) {
                    val logDist = kotlin.math.abs(kotlin.math.log10(isoFreqs[i].toDouble()) - kotlin.math.log10(centerFreqHz.toDouble()))
                    val weight = 1.0 / (1.0 + (logDist / 0.35) * (logDist / 0.35))
                    val g = if (equalizerProcessor.isEnabled) equalizerProcessor.getEffectiveGain(i, effectiveBb).toDouble() else 0.0
                    weightedGainSum += g * weight; weightSum += weight
                }
                val targetDb = if (weightSum > 0.0) (weightedGainSum / weightSum).toFloat() else 0.0f
                val milliBels = (targetDb * 100.0f).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                eq.setBandLevel(b.toShort(), milliBels)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error updating legacy EQ: ${t.message}")
        }
    }

    fun applyMDRC(mdrcProcessor: MDRCProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            for (ch in 0..1) for (b in 0 until mdrcProcessor.getBandCount()) {
                val band = mdrcProcessor.getBand(b)?: continue
                val mbcBand = DynamicsProcessing.MbcBand(
                    band.isEnabled && mdrcProcessor.isEnabled,
                    band.cutoffHz, band.attackMs, band.releaseMs, band.ratio,
                    band.thresholdDb, band.kneeWidthDb, 0.0f, 1.0f, band.gainDb, 0.0f
                )
                dp.setMbcBandByChannelIndex(ch, b, mbcBand)
            }
        } catch (t: Throwable) { Log.w(TAG, "Error MBC: ${t.message}") }
    }

    fun applyLimiter(limiterProcessor: LimiterProcessor) {
        val dp = dynamicsProcessing
        if (dp == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val active = limiterProcessor.isEffectivelyActive()
            for (ch in 0..1) {
                val limiter = DynamicsProcessing.Limiter(active, active, 0, limiterProcessor.attackMs, limiterProcessor.releaseMs, limiterProcessor.thresholdDb, limiterProcessor.ratio, 0.0f)
                dp.setLimiterByChannelIndex(ch, limiter)
            }
        } catch (t: Throwable) { Log.w(TAG, "Error Limiter: ${t.message}") }
    }

    fun release() {
        try { dynamicsProcessing?.release() } catch (_: Throwable) {}
        dynamicsProcessing = null
        try { legacyEqualizer?.release() } catch (_: Throwable) {}
        legacyEqualizer = null
        isHardwareDspActive = false; isLegacyFallbackActive = false; currentSessionId = 0
    }
}
