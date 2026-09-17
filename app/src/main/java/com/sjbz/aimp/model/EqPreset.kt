package com.sjbz.aimp.model

import com.google.gson.annotations.SerializedName

/**
 * Data model for SjbZ Equalizer presets (32 bands + preamp + bass boost settings + custom vibrant color).
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

    @SerializedName("bassBoostEnabled")
    val bassBoostEnabled: Boolean = true,

    @SerializedName("bassBoostFreq")
    val bassBoostFreq: Float = 85.0f,

    @SerializedName("bassBoostGain")
    val bassBoostGain: Float = 4.0f,

    @SerializedName("color")
    val color: Int = generateRandomColor()
) {
    fun withColor(newColor: Int): EqPreset = copy(color = newColor)

    companion object {
        val PALETTE = intArrayOf(
            0xFF00E5FF.toInt(), // Electric Cyan
            0xFF38BDF8.toInt(), // Sky Blue
            0xFF00E676.toInt(), // Neon Green
            0xFFFFB300.toInt(), // Amber
            0xFFFF1744.toInt(), // Crimson Red
            0xFFD500F9.toInt(), // Magenta
            0xFF00B0FF.toInt(), // Light Blue
            0xFF7C4DFF.toInt(), // Deep Purple
            0xFF1DE9B6.toInt(), // Teal
            0xFFFF9100.toInt()  // Deep Orange
        )

        fun generateRandomColor(): Int = PALETTE.random()
    }
}
