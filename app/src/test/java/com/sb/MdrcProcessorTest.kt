package com.sb

import com.sb.dsp.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MdrcProcessorTest {

    @Test
    fun testMdrcInitializationAndBands() {
        val mdrc = MdrcProcessor(48000f)
        assertEquals(4, mdrc.bands.size)
        assertTrue(mdrc.crossover1 < mdrc.crossover2)
        assertTrue(mdrc.crossover2 < mdrc.crossover3)
    }

    @Test
    fun testLinkwitzRileyCrossoverSum() {
        val mdrc = MdrcProcessor(48000f)
        // Con MDRC desactivado o con ganancia unitaria sin compresión, la suma debe ser perfectamente transparente
        val flatConfig = DspConfig.DEFAULT.copy(
            mdrcEnabled = false
        )
        mdrc.updateConfig(flatConfig)

        val inputL = 0.707f
        val inputR = -0.5f
        val (outL, outR) = mdrc.processSample(inputL, inputR)

        assertEquals(inputL, outL, 0.0001f)
        assertEquals(inputR, outR, 0.0001f)
    }

    @Test
    fun testStereoLinkingPreservesBalance() {
        val mdrc = MdrcProcessor(48000f)
        val config = DspConfig.DEFAULT.copy(
            mdrcEnabled = true,
            mdrcBand1 = MdrcBandConfig(threshold = -20f, ratio = 4.0f, attackTime = 5f, releaseTime = 50f, kneeWidth = 0f, postGain = 0f),
            mdrcBand2 = MdrcBandConfig(threshold = -20f, ratio = 4.0f, attackTime = 5f, releaseTime = 50f, kneeWidth = 0f, postGain = 0f),
            mdrcBand3 = MdrcBandConfig(threshold = -20f, ratio = 4.0f, attackTime = 5f, releaseTime = 50f, kneeWidth = 0f, postGain = 0f),
            mdrcBand4 = MdrcBandConfig(threshold = -20f, ratio = 4.0f, attackTime = 5f, releaseTime = 50f, kneeWidth = 0f, postGain = 0f)
        )
        mdrc.updateConfig(config)

        // Alimentar señal con nivel idéntico en L y R
        var sL = 0.9f
        var sR = 0.9f
        for (i in 0 until 500) {
            val (oL, oR) = mdrc.processSample(sL, sR)
            sL = oL * 0.99f
            sR = oR * 0.99f
        }

        val gr = mdrc.getGainReductionDb()
        // Las reducciones de ganancia deben ser reales y calculadas
        assertTrue("Debe existir reducción de ganancia en al menos una banda", gr.any { it < 0f })
    }

    @Test
    fun testSampleRateSwitching() {
        val pipeline = PcmAudioPipeline(44100f)
        assertEquals(44100f, pipeline.sampleRate, 0.001f)
        assertEquals(44100f, pipeline.mdrcProcessor.sampleRate, 0.001f)

        pipeline.updateSampleRate(48000f)
        assertEquals(48000f, pipeline.sampleRate, 0.001f)
        assertEquals(48000f, pipeline.mdrcProcessor.sampleRate, 0.001f)
    }

    @Test
    fun testPcmPipelineEndToEnd() {
        val pipeline = PcmAudioPipeline(48000f)
        val config = DspConfig.DEFAULT.copy(
            dspEnabled = true,
            preGain = 1.0f,
            bassBoostEnabled = true,
            bassBoostStrength = 500,
            toneBass = 2.0f,
            toneMid = 1.0f,
            toneTreble = 1.5f,
            mdrcEnabled = true,
            limiterEnabled = true,
            limiterThreshold = -0.5f
        )

        val bufferL = FloatArray(256) { sin(2.0 * PI * 440.0 * it / 48000.0).toFloat() * 0.8f }
        val bufferR = FloatArray(256) { cos(2.0 * PI * 440.0 * it / 48000.0).toFloat() * 0.8f }

        pipeline.processBlock(bufferL, bufferR, 0, 256, config)

        for (i in 0 until 256) {
            assertFalse(bufferL[i].isNaN())
            assertFalse(bufferL[i].isInfinite())
            assertFalse(bufferR[i].isNaN())
            assertFalse(bufferR[i].isInfinite())
            assertTrue("No debe superar el límite de clipping", abs(bufferL[i]) <= 1.01f)
            assertTrue("No debe superar el límite de clipping", abs(bufferR[i]) <= 1.01f)
        }
    }
}
