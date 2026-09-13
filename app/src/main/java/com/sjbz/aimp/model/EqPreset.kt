package com.sjbz.aimp.model

import com.google.gson.annotations.SerializedName

/**
 * Data model for SjbZ Equalizer presets (32 bands + preamp + MDRC settings + ATS2835P hardware profile + custom vibrant color).
 * Compatible with JSON serialization and .sjbz file export/import.
 */
data class EqPreset(
    @SerializedName("name")
    val name: String,

    @SerializedName("preampDb")
    val preampDb: Float = 0.0f,

    @SerializedName("bandGains")
    val bandGains: List<Float> = List(32) { 0.0f },

    @SerializedName("isCustom")
    val isCustom: Boolean = false,

    @SerializedName("mdrcSettings")
    val mdrcSettings: MDRCSettings = MDRCSettings(),

    @SerializedName("ats2835pProfileEnabled")
    val ats2835pProfileEnabled: Boolean = true,

    @SerializedName("color")
    val color: Int = generateRandomColor()
) {
    fun withColor(newColor: Int): EqPreset = copy(color = newColor)

    companion object {
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
            0xFF651FFF.toInt()  // Morado
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
)

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
)
