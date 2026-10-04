package com.sb.dsp

class ConstantQGraphicEq(private val sampleRate: Int = 48000) {
    companion object {
        val FREQUENCIES_10 = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        const val Q_10_BAND = 1.4142f

        val FREQUENCIES_20 = floatArrayOf(
            31.5f, 45f, 63f, 90f, 125f, 180f, 250f, 355f, 500f, 710f,
            1000f, 1400f, 2000f, 2800f, 4000f, 5600f, 8000f, 11200f, 16000f, 20000f
        )
        const val Q_20_BAND = 2.871f

        val FREQUENCIES_32 = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
            200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
            2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f,
            18000f, 20000f
        )
        const val Q_32_BAND = 4.318f
    }

    private val filters10 = Array(10) { BiquadFilter() }
    private val filters20 = Array(20) { BiquadFilter() }
    private val filters32 = Array(32) { BiquadFilter() }

    @Volatile
    private var activeMode: DspConfig.EqMode = DspConfig.EqMode.BANDS_10
    @Volatile
    private var isEnabled: Boolean = true

    fun updateConfig(config: DspConfig) {
        val prevMode = this.activeMode
        this.isEnabled = config.eqEnabled
        this.activeMode = config.eqMode
        val sr = config.sampleRate.toFloat()

        if (prevMode != config.eqMode) {
            when (prevMode) {
                DspConfig.EqMode.BANDS_10 -> filters10.forEach { it.reset() }
                DspConfig.EqMode.BANDS_20 -> filters20.forEach { it.reset() }
                DspConfig.EqMode.BANDS_32 -> filters32.forEach { it.reset() }
            }
        }

        if (!config.eqEnabled) return

        when (config.eqMode) {
            DspConfig.EqMode.BANDS_10 -> {
                for (i in 0 until minOf(10, config.gains10BandDb.size)) {
                    filters10[i].configure(BiquadFilter.Type.PEAKING_EQ, sr, FREQUENCIES_10[i], Q_10_BAND, config.gains10BandDb[i])
                }
            }
            DspConfig.EqMode.BANDS_20 -> {
                for (i in 0 until minOf(20, config.gains20BandDb.size)) {
                    filters20[i].configure(BiquadFilter.Type.PEAKING_EQ, sr, FREQUENCIES_20[i], Q_20_BAND, config.gains20BandDb[i])
                }
            }
            DspConfig.EqMode.BANDS_32 -> {
                for (i in 0 until minOf(32, config.gains32BandDb.size)) {
                    filters32[i].configure(BiquadFilter.Type.PEAKING_EQ, sr, FREQUENCIES_32[i], Q_32_BAND, config.gains32BandDb[i])
                }
            }
        }
    }

    fun process(buffer: FloatArray, frameCount: Int, channels: Int) {
        if (!isEnabled) return
        when (activeMode) {
            DspConfig.EqMode.BANDS_10 -> {
                for (i in 0 until 10) {
                    if (channels == 2) filters10[i].processStereoInterleaved(buffer, frameCount)
                    else {
                        for (f in 0 until frameCount) buffer[f] = filters10[i].processSampleMono(buffer[f])
                    }
                }
            }
            DspConfig.EqMode.BANDS_20 -> {
                for (i in 0 until 20) {
                    if (channels == 2) filters20[i].processStereoInterleaved(buffer, frameCount)
                    else {
                        for (f in 0 until frameCount) buffer[f] = filters20[i].processSampleMono(buffer[f])
                    }
                }
            }
            DspConfig.EqMode.BANDS_32 -> {
                for (i in 0 until 32) {
                    if (channels == 2) filters32[i].processStereoInterleaved(buffer, frameCount)
                    else {
                        for (f in 0 until frameCount) buffer[f] = filters32[i].processSampleMono(buffer[f])
                    }
                }
            }
        }
    }

    fun reset() {
        filters10.forEach { it.reset() }
        filters20.forEach { it.reset() }
        filters32.forEach { it.reset() }
    }
}