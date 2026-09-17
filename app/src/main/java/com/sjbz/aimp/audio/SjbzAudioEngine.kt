package com.sjbz.aimp.audio

object SjbzAudioEngine {

    val processor = SjbzDspProcessor()
    val mdrcProcessor = MDRCProcessor()
    val atsEngine = ATS2835PEngine(processor)
    val eqWrapper = EqualizerProcessor(processor)

    @Volatile private var initialized = false

    init {
        try {
            processor.masterEnabled = true
            processor.setPreamp(0f)
            processor.setMdrcEnabled(false)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        try {
            processor.masterEnabled = true
        } catch (_: Exception) {}
    }

    fun isEnabled(): Boolean = try { processor.masterEnabled } catch (_: Exception) { true }
    fun setEnabled(e: Boolean) = setMasterEnabled(e)

    fun syncMdrcFromGlobal(
        enabled: Boolean,
        gains: FloatArray,
        thresholdDb: Float = -14f,
        ratio: Float = 3f
    ) {
        try { processor.setMdrcEnabled(enabled) } catch (_: Exception) {}
        try { processor.setMdrcDynamics(thresholdDb.coerceIn(-36f, 0f), ratio.coerceIn(1f, 8f)) } catch (_: Exception) {}
        for (i in 0 until 5) {
            val g = gains.getOrNull(i)?.coerceIn(-12f, 12f)?: 0f
            try { processor.setMdrcBandGain(i, g) } catch (_: Exception) {}
            try { mdrcProcessor.setBandGain(i, g) } catch (_: Exception) {}
        }
    }

    fun syncMdrcFromGlobal(enabled: Boolean, gains: List<Float>, thresholdDb: Float, ratio: Float) {
        syncMdrcFromGlobal(enabled, gains.toFloatArray(), thresholdDb, ratio)
    }

    fun getMdrcGainReduction(): Float {
        return try {
            val m = processor::class.java.methods.firstOrNull { it.name == "getMdrcGainReduction" }
            (m?.invoke(processor) as? Float)?: 0f
        } catch (_: Exception) { 0f }
    }

    fun setAmount(v: Float) {
        try {
            val m = atsEngine::class.java.methods.firstOrNull { it.name == "setAmount" }
            m?.invoke(atsEngine, v)
        } catch (_: Exception) {}
    }

    fun setMasterEnabled(enabled: Boolean) {
        try { processor.masterEnabled = enabled } catch (_: Exception) {}
        try {
            val m = atsEngine::class.java.methods.firstOrNull { it.name == "setEnabled" }
            if (m!= null) m.invoke(atsEngine, enabled)
            else {
                val m2 = atsEngine::class.java.methods.firstOrNull { it.name == "setMasterEnabled" }
                m2?.invoke(atsEngine, enabled)
            }
        } catch (_: Exception) {}
    }

    fun setAllBandGains(gains: FloatArray) {
        val count = try { EqualizerProcessor.BAND_COUNT } catch (_: Exception) { 32 }
        for (i in gains.indices.take(count)) {
            try { processor.setBandGain(i, gains[i].coerceIn(-12f, 12f)) } catch (_: Exception) {}
        }
    }
}
