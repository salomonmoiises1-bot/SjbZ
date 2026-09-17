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
        try { processor.masterEnabled = true } catch (_: Exception) {}
    }

    fun isEnabled(): Boolean = try { processor.masterEnabled } catch (_: Exception) { true }

    fun setEnabled(e: Boolean) = setMasterEnabled(e)

    fun setMasterEnabled(e: Boolean) {
        try { processor.setMasterEnabled(e) } catch (_: Exception) {}
    }

    fun syncMdrcFromGlobal(
        enabled: Boolean,
        gains: FloatArray,
        thresholdDb: Float = -14f,
        ratio: Float = 3f
    ) {
        try { processor.setMdrcEnabled(enabled) } catch (_: Exception) {}
        try { processor.setMdrcDynamics(thresholdDb.coerceIn(-36f, 0f), ratio.coerceIn(1f, 8f)) } catch (_: Exception) {}
        for (i in 0 until 5) {
            val g = gains.getOrNull(i)?.coerceIn(-12f, 12f) ?: 0f
            try { processor.setMdrcBandGain(i, g) } catch (_: Exception) {}
            try { mdrcProcessor.setBandGain(i, g) } catch (_: Exception) {}
        }
    }

    fun process(samples: FloatArray, offset: Int = 0, length: Int = samples.size, channels: Int = 2) {
        try {
            ensureInitialized()
            processor.processFloats(samples, offset, length, channels)
        } catch (_: Exception) {}
    }

    fun reset() {
        try { processor.reset() } catch (_: Exception) {}
    }
}
