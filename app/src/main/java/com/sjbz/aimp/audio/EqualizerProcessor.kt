package com.sjbz.aimp.audio

import com.sjbz.aimp.model.EqPreset

class EqualizerProcessor {

    companion object {
        const val BAND_COUNT = 32
        val BAND_LABELS = arrayOf(
            "20","25","32","40","50","63","80","100","125","160","200","250",
            "315","400","500","630","800","1k","1.25k","1.6k","2k","2.5k","3.15k","4k",
            "5k","6.3k","8k","10k","12.5k","16k","18k","20k"
        )
        val BAND_FREQUENCIES = floatArrayOf(
            20f,25f,32f,40f,50f,63f,80f,100f,125f,160f,200f,250f,
            315f,400f,500f,630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,
            5000f,6300f,8000f,10000f,12500f,16000f,18000f,20000f
        )
    }

    var preampDb: Float = 0f
    private val bands = MutableList(BAND_COUNT) { 0f }

    fun getBandGain(index: Int): Float = bands.getOrElse(index) { 0f }
    fun setBandGain(index: Int, gain: Float) {
        if (index in 0 until BAND_COUNT) {
            bands[index] = gain.coerceIn(-12f, 12f)
        }
    }

    fun setAllBands(gains: List<Float>) {
        for (i in 0 until BAND_COUNT) {
            bands[i] = gains.getOrElse(i) { 0f }.coerceIn(-12f, 12f)
        }
    }

    fun loadFromPreset(preset: EqPreset) {
        preampDb = preset.preampDb
        setAllBands(preset.bandGains)
    }

    fun toEqPreset(name: String, isCustom: Boolean = false): EqPreset {
        return EqPreset(
            name = name,
            bandGains = bands.toList(),
            preampDb = preampDb,
            isCustom = isCustom
        )
    }

    fun getBuiltInPresets(): List<EqPreset> {
        return listOf(
            EqPreset("Flat", List(BAND_COUNT) { 0f }),
            EqPreset("Rock", listOf(3f,2.5f,2f,1f,0.5f,0f,-0.5f,-1f,-0.5f,0f,0.5f,1f,1.5f,1f,0.5f,0f,0f,0.5f,1f,1.2f,1.5f,1.2f,1f,0.8f,1f,1.2f,1.5f,1.5f,1f,0.5f,0.5f,0.8f)),
            EqPreset("Pop", listOf(-1f,-0.5f,0f,0.5f,1f,1.5f,1.8f,1.5f,1f,0.5f,0f,-0.5f,-0.8f,-0.5f,0f,0.5f,1f,1.2f,1f,0.8f,0.5f,0.3f,0f,-0.2f,-0.5f,-0.3f,0f,0.5f,1f,1.2f,1f,0.8f)),
            EqPreset("Dance", listOf(4f,3.5f,3f,2f,1f,0f,-0.5f,-1f,-0.8f,-0.5f,0f,0.5f,1f,1.2f,1f,0.5f,0f,-0.2f,-0.5f,-0.3f,0f,0.5f,1f,1.2f,1f,0.8f,0.5f,0f,-0.5f,0f,0.5f,1f)),
            EqPreset("Hip-Hop", listOf(4.5f,4f,3.5f,2.5f,1.5f,0.5f,0f,-0.5f,-0.8f,-0.5f,0f,0.3f,0.5f,0.3f,0f,-0.2f,-0.5f,-0.3f,0f,0.3f,0.5f,0.8f,1f,1f,0.8f,0.5f,0.3f,0f,0.5f,1f,1.2f,1f)),
            EqPreset("Jazz", listOf(2f,1.5f,1f,0.5f,0f,-0.5f,-0.8f,-0.5f,0f,0.5f,1f,1.2f,1f,0.8f,0.5f,0.3f,0f,-0.2f,-0.3f,0f,0.5f,0.8f,1f,1.2f,1f,0.8f,0.5f,0.3f,0.5f,1f,1.2f,1f)),
            EqPreset("Bass Boost", listOf(6f,5.5f,5f,4f,3f,2f,1f,0.5f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f)),
            // Harman corregido sin error de parentesis
            EqPreset("Harman", listOf(1.5f,1.2f,1f,0.8f,0.5f,0.3f,0f,-0.2f,-0.3f,-0.2f,0f,0.2f,0.4f,0.5f,0.4f,0.2f,0f,-0.2f,-0.4f,-0.5f,-0.3f,0f,0.5f,0.8f,0.6f,0.3f,0f,-0.5f,-1f,-1.2f,-1.5f))
        )
    }
}
