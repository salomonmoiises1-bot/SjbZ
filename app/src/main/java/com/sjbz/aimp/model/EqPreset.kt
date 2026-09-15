package com.sjbz.aimp.model

import com.google.gson.annotations.SerializedName

/**
 * Data model for SjbZ Equalizer presets (32 bands + preamp + MDRC settings + ATS2835P hardware profile + custom vibrant color).
 * Compatible with JSON serialization and.sjbz file export/import.
 */
data class EqPreset(
    @SerializedName("name")
    val name: String,

    @SerializedName("preampDb")
    val preampDb: Float = 0.0f,

    @SerializedName("bandGains")
    val bandGains: List<Float> = List(BAND_COUNT) { 0.0f },

    @SerializedName("isCustom")
    val isCustom: Boolean = false,

    @SerializedName("mdrcSettings")
    val mdrcSettings: MDRCSettings = MDRCSettings(),

    @SerializedName("ats2835pProfileEnabled")
    val ats2835pProfileEnabled: Boolean = true,

    // PATCH: default 0 como "sin color" para que PresetManager pueda detectar
    // el caso inválido. Antes era generateRandomColor() directo en el default,
    // entonces el check `if (color == 0)` en PresetManager nunca se cumplía y
    // además cada deserialización sin campo color daba un color aleatorio
    // no determinista que rompía el theming al reabrir.
    @SerializedName("color")
    val color: Int = 0
) {
    fun withColor(newColor: Int): EqPreset = copy(color = newColor)

    // PATCH: color resuelto para UI. Si es 0 (legacy / JSON sin color),
    // genera uno del palette en vez de mostrar negro transparente.
    fun resolvedColor(): Int = if (color!= 0) color else generateRandomColor()

    // PATCH: sanea ganancias a 32 bandas y ±12dB. Antes un.sjbz corrupto
    // con 10 bandas o valores de ±40dB llegaba crudo a la UI y crasheaba
    // los faders.
    fun sanitized(): EqPreset {
        val fixedGains = when {
            bandGains.size == BAND_COUNT -> bandGains.map { it.coerceIn(-12f, 12f) }
            bandGains.size > BAND_COUNT -> bandGains.take(BAND_COUNT).map { it.coerceIn(-12f, 12f) }
            else -> bandGains.map { it.coerceIn(-12f, 12f) } + List(BAND_COUNT - bandGains.size) { 0f }
        }
        return copy(
            preampDb = preampDb.coerceIn(-12f, 12f),
            bandGains = fixedGains,
            mdrcSettings = mdrcSettings.sanitized()
        )
    }

    companion object {
        const val BAND_COUNT = 32

        // Vibrantly tuned 10-color palette as requested
        val PALETTE = intArrayOf(
            0xFF00BCD4.toInt(), // Cyan
            0xFF4CAF50.toInt(), // Verde
            0xFFFFC107.toInt(), // Amarillo
            0xFFFF7700.toInt(), // Naranja (#FF7700 AIMP)
            0xFFE91E63.toInt(), // Rosa
            0xFF9C27B0.toInt(), // Violeta
            0xFF2196F3.toInt(), // Azul
            0xFF00E676.toInt(), // Flúor
            0xFFFF1744.toInt(), // Rojo
            0xFF651FFF.toInt() // Morado
        )

        fun generateRandomColor(): Int = PALETTE.random()
    }
}

data class MDRCSettings(
    @SerializedName("enabled")
    val enabled: Boolean = true,

    @SerializedName("bands")
    val bands: List<MDRCBandConfig> = listOf(
        MDRCBandConfig(name = "Sub", cutoffHz = 120f, thresholdDb = -18f, ratio = 3.0f, attackMs = 5f, releaseMs = 80f, kneeWidthDb = 3f, gainDb = 0f),
        MDRCBandConfig(name = "Low", cutoffHz = 500f, thresholdDb = -16f, ratio = 2.5f, attackMs = 10f, releaseMs = 100f, kneeWidthDb = 3f, gainDb = 0f),
        MDRCBandConfig(name = "Mid", cutoffHz = 2000f, thresholdDb = -14f, ratio = 2.0f, attackMs = 15f, releaseMs = 120f, kneeWidthDb = 3f, gainDb = 0f),
        MDRCBandConfig(name = "High", cutoffHz = 8000f, thresholdDb = -12f, ratio = 2.0f, attackMs = 20f, releaseMs = 150f, kneeWidthDb = 3f, gainDb = 0f),
        MDRCBandConfig(name = "Air", cutoffHz = 20000f, thresholdDb = -10f, ratio = 2.5f, attackMs = 25f, releaseMs = 180f, kneeWidthDb = 3f, gainDb = 0f)
    )
) {
    // PATCH: garantiza 5 bandas y rangos válidos. Antes un.sjbz con 3 bandas
    // dejaba mdrcProcessor.loadFromSettings con índices faltantes sin aviso.
    fun sanitized(): MDRCSettings {
        val defaults = MDRCSettings().bands
        val fixed = List(5) { i ->
            val b = bands.getOrNull(i)?: defaults[i]
            b.sanitized(defaults[i])
        }
        return copy(bands = fixed)
    }
}

data class MDRCBandConfig(
    @SerializedName("name")
    val name: String,

    @SerializedName("cutoffHz")
    val cutoffHz: Float,

    @SerializedName("thresholdDb")
    val thresholdDb: Float,

    @SerializedName("ratio")
    val ratio: Float,

    @SerializedName("attackMs")
    val attackMs: Float,

    @SerializedName("releaseMs")
    val releaseMs: Float,

    @SerializedName("gainDb")
    val gainDb: Float = 0f,

    @SerializedName("kneeWidthDb")
    val kneeWidthDb: Float = 3f,

    @SerializedName("enabled")
    val enabled: Boolean = true
) {
    // PATCH: clamp de rangos DSP. Antes un.sjbz podía traer ratio 50 o
    // threshold +20dB y el MDRC se comportaba de forma indefinida.
    fun sanitized(fallback: MDRCBandConfig = this): MDRCBandConfig {
        return copy(
            name = name.ifBlank { fallback.name },
            cutoffHz = cutoffHz.takeIf { it > 0f }?: fallback.cutoffHz,
            thresholdDb = thresholdDb.coerceIn(-60f, 0f),
            ratio = ratio.coerceIn(1f, 20f),
            attackMs = attackMs.coerceIn(0.1f, 500f),
            releaseMs = releaseMs.coerceIn(1f, 2000f),
            gainDb = gainDb.coerceIn(-12f, 12f),
            kneeWidthDb = kneeWidthDb.coerceIn(0f, 12f)
        )
    }
}
