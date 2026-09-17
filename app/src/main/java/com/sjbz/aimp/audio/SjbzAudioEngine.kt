package com.sjbz.aimp.audio

/**
 * Motor compartido SB-Z. Única instancia de DSP para toda la app.
 */
object SjbzAudioEngine {

    val processor = SjbzDspProcessor()
    val mdrcProcessor = MDRCProcessor()
    val atsEngine = ATS2835PEngine(processor)
    val eqWrapper = EqualizerProcessor(processor)

    init {
        processor.masterEnabled = true
        processor.setPreamp(0f)
        processor.setMdrcEnabled(false)
        mdrcProcessor.setEnabled(false)
    }

    fun syncMdrcFromGlobal(
        enabled: Boolean,
        gains: FloatArray,
        thresholdDb: Float = -14f,
        ratio: Float = 3f
    ) {
        processor.setMdrcEnabled(enabled)
        processor.setMdrcDynamics(thresholdDb.coerceIn(-36f, 0f), ratio.coerceIn(1f, 8f))
        for (i in 0 until 5) {
            val g = gains.getOrNull(i)?.coerceIn(-12f, 12f)?: 0f
            processor.setMdrcBandGain(i, g)
            mdrcProcessor.setBandGain(i, g)
        }
        mdrcProcessor.setEnabled(enabled)
        try { mdrcProcessor.setThreshold(thresholdDb) } catch (_: Exception) {}
        try { mdrcProcessor.setRatio(ratio) } catch (_: Exception) {}
    }

    fun setMasterEnabled(enabled: Boolean) {
        processor.masterEnabled = enabled
        atsEngine.setMasterEnabled(enabled)
    }

    fun setAllBandGains(gains: FloatArray) {
        for (i in gains.indices.take(EqualizerProcessor.BAND_COUNT)) {
            processor.setBandGain(i, gains[i].coerceIn(-12f, 12f))
        }
    }
}
