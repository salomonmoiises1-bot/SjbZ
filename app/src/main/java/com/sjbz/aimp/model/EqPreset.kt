package com.sjbz.aimp.model

import com.google.gson.annotations.SerializedName

/**
 * Data model for SjbZ Equalizer presets.
 *
 * Schema v2: 32 ISO bands + preamp + bass boost + ATS2835P emulation state + color.
 * Backward compatible: new fields have defaults so Gson can parse v1 JSON
 * (without emu_*) without throwing.
 *
 * Compatible with JSON serialization and .sjbz file export/import.
 */
data class EqPreset(
    @SerializedName("name")
    val name: String,

    @SerializedName("schema_version")
    val schemaVersion: Int = SCHEMA_VERSION,

    @SerializedName("preampDb")
    val preampDb: Float = 0.0f,

    @SerializedName("bandGains")
    val bandGains: List<Float> = List(BAND_COUNT) { 0.0f },

    @SerializedName("isCustom")
    val isCustom: Boolean = false,

    @SerializedName("bassBoostEnabled")
    val bassBoostEnabled: Boolean = true,

    @SerializedName("bassBoostFreq")
    val bassBoostFreq: Float = 85.0f,

    @SerializedName("bassBoostGain")
    val bassBoostGain: Float = 4.0f,

    @SerializedName("emu_enabled")
    val emuEnabled: Boolean = false,

    @SerializedName("emu_amount")
    val emuAmount: Float = 0.8f,

    @SerializedName("bt_auto_bypass")
    val btAutoBypass: Boolean = true,

    @SerializedName("color")
    val color: Int = generateRandomColor()
) {
    init {
        require(name.isNotBlank()) { "Preset name must not be blank" }
        require(bandGains.size == BAND_COUNT) {
            "bandGains must contain exactly $BAND_COUNT values, got ${bandGains.size}"
        }
        require(preampDb in -12f..12f) { "preampDb out of range: $preampDb" }
        require(bassBoostFreq in 20f..250f) { "bassBoostFreq out of range: $bassBoostFreq" }
        require(bassBoostGain in 0f..12f) { "bassBoostGain out of range: $bassBoostGain" }
        require(emuAmount in 0f..1f) { "emuAmount out of range: $emuAmount" }
    }

    fun withColor(newColor: Int): EqPreset = copy(color = newColor)

    fun withEmulation(enabled: Boolean, amount: Float): EqPreset =
        copy(emuEnabled = enabled, emuAmount = amount.coerceIn(0f, 1f))

    /** Returns a copy with band gains clamped to ±12 dB and guaranteed 32 entries. */
    fun normalized(): EqPreset {
        val fixed = List(BAND_COUNT) { i ->
            bandGains.getOrNull(i)?.coerceIn(-12f, 12f) ?: 0f
        }
        return copy(
            bandGains = fixed,
            preampDb = preampDb.coerceIn(-12f, 12f),
            bassBoostGain = bassBoostGain.coerceIn(0f, 12f),
            emuAmount = emuAmount.coerceIn(0f, 1f),
            schemaVersion = SCHEMA_VERSION
        )
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val BAND_COUNT = 32

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

        /** Migrates a v1 preset (no emulation fields) to v2 with safe defaults. */
        fun migrateV1toV2(v1: EqPreset): EqPreset =
            v1.copy(schemaVersion = SCHEMA_VERSION)
    }
}
