package com.sb.dsp

import kotlin.math.abs

class ToneManager(private val sampleRate: Int = 48000) {
    @Volatile
    var isEnabled: Boolean = false
    private var bassDb: Float = 0.0f
    private var midDb: Float = 0.0f
    private var trebleDb: Float = 0.0f
    private val bassShelf = BiquadFilter()
    private val midPeaking = BiquadFilter()
    private val trebleShelf = BiquadFilter()

    init {
        configureFilters()
    }

    private fun configureFilters() {
        val sr = sampleRate.toFloat()
        bassShelf.configure(BiquadFilter.Type.LOW_SHELF, sr, 100.0f, 0.7071f, bassDb)
        midPeaking.configure(BiquadFilter.Type.PEAKING_EQ, sr, 1000.0f, 1.0f, midDb)
        trebleShelf.configure(BiquadFilter.Type.HIGH_SHELF, sr, 10000.0f, 0.7071f, trebleDb)
    }

    fun updateConfig(config: DspConfig) {
        this.isEnabled = config.toneEnabled
        if (!isEnabled) return
        this.bassDb = config.bassToneDb.coerceIn(-12.0f, 12.0f)
        this.midDb = config.midToneDb.coerceIn(-12.0f, 12.0f)
        this.trebleDb = config.trebleToneDb.coerceIn(-12.0f, 12.0f)
        configureFilters()
    }

    fun process(buffer: FloatArray, frameCount: Int, channels: Int) {
        if (!isEnabled) return
        val hasBass = abs(bassDb) >= 0.05f
        val hasMid = abs(midDb) >= 0.05f
        val hasTreble = abs(trebleDb) >= 0.05f
        if (!hasBass && !hasMid && !hasTreble) return

        if (channels == 2) {
            if (hasBass) bassShelf.processStereoInterleaved(buffer, frameCount)
            if (hasMid) midPeaking.processStereoInterleaved(buffer, frameCount)
            if (hasTreble) trebleShelf.processStereoInterleaved(buffer, frameCount)
        } else {
            for (i in 0 until frameCount) {
                var s = buffer[i]
                if (hasBass) s = bassShelf.processSampleMono(s)
                if (hasMid) s = midPeaking.processSampleMono(s)
                if (hasTreble) s = trebleShelf.processSampleMono(s)
                buffer[i] = s
            }
        }
    }

    fun reset() {
        bassShelf.reset()
        midPeaking.reset()
        trebleShelf.reset()
    }
}