package com.sjbz.aimp.audio

import com.sjbz.aimp.model.MDRCBandConfig
import com.sjbz.aimp.model.MDRCSettings

/**
 * 5-Band Multi-band Dynamic Range Compression (MDRC) Processor.
 * Modeled after the ATS2835P audio DSP hardware architecture.
 *
 * Bands:
 * 1. Sub: 20 Hz - 120 Hz
 * 2. Low: 120 Hz - 500 Hz
 * 3. Mid: 500 Hz - 2,000 Hz
 * 4. High: 2,000 Hz - 8,000 Hz
 * 5. Air: 8,000 Hz - 20,000 Hz
 */
class MDRCProcessor {

    data class Band(
        val name: String,
        var cutoffHz: Float,
        var thresholdDb: Float,
        var ratio: Float,
        var attackMs: Float,
        var releaseMs: Float,
        var kneeWidthDb: Float = 3.0f,
        var gainDb: Float = 0.0f,
        var isEnabled: Boolean = true
    )

    var isEnabled: Boolean = true
    var isGentleBluetoothMode: Boolean = false
        set(value) {
            field = value
            applyGentleConstraints()
        }

    val bands: Array<Band> = arrayOf(
        Band("Sub", 120f, -18.0f, 3.2f, 4.0f, 75.0f, 3.0f, 1.0f, true),
        Band("Low", 500f, -16.0f, 2.8f, 8.0f, 90.0f, 3.0f, 0.5f, true),
        Band("Mid", 2000f, -14.0f, 2.2f, 12.0f, 110.0f, 3.0f, 0.0f, true),
        Band("High", 8000f, -12.0f, 2.0f, 18.0f, 140.0f, 3.0f, 0.5f, true),
        Band("Air", 20000f, -10.0f, 2.4f, 22.0f, 160.0f, 3.0f, 1.0f, true)
    )

    fun getBandCount(): Int = bands.size

    fun getBand(index: Int): Band? {
        return if (index in bands.indices) bands[index] else null
    }

    private fun applyGentleConstraints() {
        if (isGentleBluetoothMode) {
            for (band in bands) {
                if (band.ratio > 3.0f) {
                    band.ratio = 3.0f
                }
                if (band.thresholdDb < -14.0f) {
                    band.thresholdDb = -14.0f
                }
            }
        }
    }

    fun getSettings(): MDRCSettings {
        return toMDRCSettings()
    }

    fun toMDRCSettings(): MDRCSettings {
        val bandConfigs = bands.map {
            MDRCBandConfig(
                name = it.name,
                cutoffHz = it.cutoffHz,
                thresholdDb = it.thresholdDb,
                ratio = it.ratio,
                attackMs = it.attackMs,
                releaseMs = it.releaseMs,
                gainDb = it.gainDb,
                kneeWidthDb = it.kneeWidthDb,
                enabled = it.isEnabled
            )
        }
        return MDRCSettings(enabled = isEnabled, bands = bandConfigs)
    }

    fun loadFromSettings(settings: MDRCSettings) {
        isEnabled = settings.enabled
        for (i in 0 until minOf(settings.bands.size, bands.size)) {
            val cfg = settings.bands[i]
            val b = bands[i]
            b.cutoffHz = cfg.cutoffHz
            b.thresholdDb = cfg.thresholdDb
            b.ratio = cfg.ratio
            b.attackMs = cfg.attackMs
            b.releaseMs = cfg.releaseMs
            b.gainDb = cfg.gainDb
            b.kneeWidthDb = cfg.kneeWidthDb
            b.isEnabled = cfg.enabled
        }
        applyGentleConstraints()
    }
}
