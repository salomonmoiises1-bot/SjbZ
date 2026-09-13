package com.sjbz.aimp.audio

import com.sjbz.aimp.model.MDRCBandConfig
import com.sjbz.aimp.model.MDRCSettings

/**
 * 5-Band Multi-band Dynamic Range Compression (MDRC) Processor.
 * Modeled after the ATS2835P audio DSP hardware architecture.
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
        Band("Sub", 120f, -18.0f, 3.0f, 5.0f, 80.0f, 3.0f, 0.0f, true),
        Band("Low", 500f, -16.0f, 2.5f, 10.0f, 100.0f, 3.0f, 0.0f, true),
        Band("Mid", 2000f, -14.0f, 2.0f, 15.0f, 120.0f, 3.0f, 0.0f, true),
        Band("High", 8000f, -12.0f, 2.0f, 20.0f, 150.0f, 3.0f, 0.0f, true),
        Band("Air", 20000f, -10.0f, 2.5f, 25.0f, 180.0f, 3.0f, 0.0f, true)
    )

    fun getBandCount(): Int = bands.size
    fun getBand(index: Int): Band? = if (index in bands.indices) bands[index] else null

    // --- FIX PARA EqActivity.kt:304 ---
    fun setGainForIndex(index: Int, gainDb: Float) {
        if (index in bands.indices) {
            bands[index].gainDb = gainDb
        }
    }

    fun setGainForIndex(index: Int, gainDb: Double) {
        setGainForIndex(index, gainDb.toFloat())
    }

    fun getGainForIndex(index: Int): Float {
        return if (index in bands.indices) bands[index].gainDb else 0f
    }

    private fun applyGentleConstraints() {
        if (isGentleBluetoothMode) {
            for (band in bands) {
                if (band.ratio > 3.0f) band.ratio = 3.0f
                if (band.thresholdDb < -14.0f) band.thresholdDb = -14.0f
            }
        }
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

    // --- FIX PARA COMPATIBILIDAD CON CODIGO VIEJO ---
    fun applySettings(settings: MDRCSettings) = loadFromSettings(settings)
}
