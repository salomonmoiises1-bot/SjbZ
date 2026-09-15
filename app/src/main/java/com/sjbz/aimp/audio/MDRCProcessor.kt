package com.sjbz.aimp.audio

import android.util.Log
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

    companion object {
        private const val TAG = "MDRCProcessor"
        private const val MIN_GAIN_DB = -12.0f
        private const val MAX_GAIN_DB = 12.0f
    }

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

    // PATCH: callback como en Equalizer/BassBoost/Limiter para que el engine
    // pueda reaplicar sin que la UI tenga que llamar updateMDRC() manual.
    // Antes no existía y todo dependía de llamadas manuales desde EqActivity.
    var onParametersChanged: (() -> Unit)? = null

    var isEnabled: Boolean = true
        set(value) {
            val changed = field!= value
            field = value
            if (!changed) return
            try { onParametersChanged?.invoke() } catch (_: Throwable) {}
        }

    // PATCH: guarda valores originales para restaurar al desconectar Bluetooth.
    // Antes applyGentleConstraints() pisaba ratio/threshold y nunca los restauraba,
    // quedando el MDRC capado para siempre después de un paso por BT.
    private data class OriginalConstraints(val ratio: Float, val thresholdDb: Float)
    private val originalConstraints = mutableMapOf<Int, OriginalConstraints>()

    var isGentleBluetoothMode: Boolean = false
        set(value) {
            val changed = field!= value
            field = value
            if (!changed) return
            applyGentleConstraints()
            try { onParametersChanged?.invoke() } catch (_: Throwable) {}
        }

    val bands: Array<Band> = arrayOf(
        Band("Sub", 120f, -18.0f, 3.0f, 5.0f, 80.0f, 3.0f, 0.0f, true),
        Band("Low", 500f, -16.0f, 2.5f, 10.0f, 100.0f, 3.0f, 0.0f, true),
        Band("Mid", 2000f, -14.0f, 2.0f, 15.0f, 120.0f, 3.0f, 0.0f, true),
        Band("High", 8000f, -12.0f, 2.0f, 20.0f, 150.0f, 3.0f, 0.0f, true),
        Band("Air", 20000f, -10.0f, 2.5f, 25.0f, 180.0f, 3.0f, 0.0f, true)
    )

    fun getBandCount(): Int = bands.size

    fun getBand(index: Int): Band? {
        return if (index in bands.indices) bands[index] else null
    }

    // PATCH: setter con coerce y notificación para gainDb.
    // Antes EqActivity hacía mdrcProcessor.getBand(i)?.gainDb = gainDb directo,
    // sin coerce a -12..12 y sin notificar, dependiendo de updateMDRC() manual.
    fun setBandGainDb(index: Int, gainDb: Float) {
        val b = getBand(index)?: return
        val coerced = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        if (b.gainDb == coerced) return
        b.gainDb = coerced
        try { onParametersChanged?.invoke() } catch (_: Throwable) {}
    }

    private fun applyGentleConstraints() {
        if (isGentleBluetoothMode) {
            for ((idx, band) in bands.withIndex()) {
                // Guarda original solo la primera vez que entra en modo BT
                if (!originalConstraints.containsKey(idx)) {
                    originalConstraints[idx] = OriginalConstraints(band.ratio, band.thresholdDb)
                }
                if (band.ratio > 3.0f) {
                    band.ratio = 3.0f
                }
                if (band.thresholdDb < -14.0f) {
                    band.thresholdDb = -14.0f
                }
            }
            Log.d(TAG, "Gentle BT mode ON: ratios/thresholds capados")
        } else {
            // PATCH: restaura originales al salir de BT
            for ((idx, orig) in originalConstraints) {
                if (idx in bands.indices) {
                    bands[idx].ratio = orig.ratio
                    bands[idx].thresholdDb = orig.thresholdDb
                }
            }
            originalConstraints.clear()
            Log.d(TAG, "Gentle BT mode OFF: valores restaurados")
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
                gainDb = it.gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
                kneeWidthDb = it.kneeWidthDb,
                enabled = it.isEnabled
            )
        }
        return MDRCSettings(enabled = isEnabled, bands = bandConfigs)
    }

    fun loadFromSettings(settings: MDRCSettings) {
        // PATCH: suspende notificaciones durante la carga masiva para no
        // disparar 5+1 callbacks que reaplican el DSP en cada banda.
        val prevCallback = onParametersChanged
        onParametersChanged = null
        try {
            isEnabled = settings.enabled
            for (i in 0 until minOf(settings.bands.size, bands.size)) {
                val cfg = settings.bands[i]
                val b = bands[i]
                b.cutoffHz = cfg.cutoffHz
                b.thresholdDb = cfg.thresholdDb
                b.ratio = cfg.ratio
                b.attackMs = cfg.attackMs
                b.releaseMs = cfg.releaseMs
                // PATCH: coerce consistente con el resto del pipeline
                b.gainDb = cfg.gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
                b.kneeWidthDb = cfg.kneeWidthDb
                b.isEnabled = cfg.enabled
            }
            // PATCH: si estaba en modo BT, limpia los originales guardados porque
            // los valores recién cargados son la nueva base, no los viejos.
            // Antes applyGentleConstraints() reaplicaba el cap sobre el preset
            // recién cargado sin avisar.
            if (isGentleBluetoothMode) {
                originalConstraints.clear()
            }
            applyGentleConstraints()
        } finally {
            onParametersChanged = prevCallback
        }
        try { onParametersChanged?.invoke() } catch (_: Throwable) {}
    }
}
