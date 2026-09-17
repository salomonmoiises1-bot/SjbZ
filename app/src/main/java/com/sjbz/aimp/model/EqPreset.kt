package com.sjbz.aimp.model

import com.google.gson.annotations.SerializedName

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
    init {
        require(bandGains.size == 32) { "bandGains must have exactly 32 values" }
    }

    fun withColor(newColor: Int): EqPreset = copy(color = newColor)

    fun sanitized(): EqPreset {
        return copy(
            preampDb = preampDb.coerceIn(-12f, 12f),
            bandGains = bandGains.map { it.coerceIn(-12f, 12f) },
            bassBoostFreq = bassBoostFreq.coerceIn(20f, 500f),
            bassBoostGain = bassBoostGain.coerceIn(0f, 12f)
        )
    }

    companion object {
        val PALETTE = intArrayOf(
            0xFF00E5FF.toInt(),
            0xFF38BDF8.toInt(),
            0xFF00E676.toInt(),
            0xFFFFB300.toInt(),
            0xFFFF1744.toInt(),
            0xFFD500F9.toInt(),
            0xFF00B0FF.toInt(),
            0xFF7C4DFF.toInt(),
            0xFF1DE9B6.toInt(),
            0xFFFF9100.toInt()
        )

        fun generateRandomColor(): Int = PALETTE.random()
    }
}
