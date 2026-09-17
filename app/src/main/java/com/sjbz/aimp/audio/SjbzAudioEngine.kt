package com.sjbz.aimp.audio

object SjbzAudioEngine {

    val processor = SjbzDspProcessor()
    val mdrcProcessor = MDRCProcessor()
    val atsEngine = ATS2835PEngine(processor)
    val eqWrapper = EqualizerProcessor(processor)

    init {
        processor.masterEnabled = true
        processor.setPreamp(0f)
        processor.setMdrcEnabled(false)
        // mdrcProcessor.setEnabled(false) // <- no existe, eliminado
    }

    fun ensureInitialized() {} // stub para EqActivity/MainActivity

    fun isEnabled(): Boolean = processor.masterEnabled
    fun setEnabled(e: Boolean) = setMasterEnabled(e)

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
            try { mdrcProcessor.setBandGain(i, g) } catch (_: Exception) {}
        }
        // Estos 3 no existen en MDRCProcessor, los dejamos safe:
        // mdrcProcessor.setEnabled(enabled)
        // mdrcProcessor.setThreshold(thresholdDb)
        // mdrcProcessor.setRatio(ratio)
    }

    fun getMdrcGainReduction(): Float = 0f // stub para EqActivity
    fun setAmount(v: Float) {} // stub para EqActivity

    fun setMasterEnabled(enabled: Boolean) {
        processor.masterEnabled = enabled
        try {
            val m = atsEngine::class.java.getMethod("setMasterEnabled", Boolean::class.java)
            m.invoke(atsEngine, enabled)
        } catch (_: Exception) {
            // ATS2835PEngine no tiene setMasterEnabled, ignorar
        }
    }

    fun setAllBandGains(gains: FloatArray) {
        for (i in gains.indices.take(EqualizerProcessor.BAND_COUNT)) {
            processor.setBandGain(i, gains[i].coerceIn(-12f, 12f))
        }
    }

    // Para GlobalAudioService: acepta List<Float> también
    fun syncMdrcFromGlobal(enabled: Boolean, gains: List<Float>, thresholdDb: Float, ratio: Float) {
        syncMdrcFromGlobal(enabled, gains.toFloatArray(), thresholdDb, ratio)
    }
}
