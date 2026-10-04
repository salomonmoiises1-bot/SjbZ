package com.sb

import com.sb.dsp.*
import org.junit.Assert.*
import org.junit.Test

class DspConfigTest {

    @Test
    fun testDefaultConfigSanity() {
        val config = DspConfig.DEFAULT
        assertTrue(config.dspEnabled)
        assertEquals(0f, config.preGain, 0.001f)
        assertEquals(EqMode.EQ32, config.eqMode)
        assertEquals(10, config.eq10Gains.size)
        assertEquals(20, config.eq20Gains.size)
        assertEquals(32, config.eq32Gains.size)
    }

    @Test
    fun testValidationClampsNaNAndInfinity() {
        val corruptedConfig = DspConfig(
            preGain = Float.NaN,
            toneBass = Float.POSITIVE_INFINITY,
            toneTreble = -999f,
            eq10Gains = listOf(Float.NaN, 50f, -50f),
            mdrcCutoff1 = Float.NaN,
            mdrcCutoff2 = 100f, // Inválido respecto a cutoff1
            limiterThreshold = Float.NaN
        ).validate()

        assertFalse(corruptedConfig.preGain.isNaN())
        assertEquals(0f, corruptedConfig.preGain, 0.001f)
        assertEquals(0f, corruptedConfig.toneBass, 0.001f)
        assertEquals(-15f, corruptedConfig.toneTreble, 0.001f)

        // Verificación estricta de orden en Crossovers MDRC
        assertTrue(corruptedConfig.mdrcCutoff1 < corruptedConfig.mdrcCutoff2)
        assertTrue(corruptedConfig.mdrcCutoff2 < corruptedConfig.mdrcCutoff3)
        assertTrue(corruptedConfig.mdrcCutoff3 < corruptedConfig.mdrcCutoff4)
    }

    @Test
    fun testIndependentEqGainsRetention() {
        val config = DspConfig(
            eqMode = EqMode.EQ32,
            eq10Gains = List(10) { 1f },
            eq20Gains = List(20) { 2f },
            eq32Gains = List(32) { 3f }
        )

        // EQ32 es el único banco activo del DSP; los bancos EQ10/EQ20 se
        // mantienen intactos como datos de compatibilidad.
        assertEquals(3f, config.activeEqGains()[0], 0.001f)
        assertEquals(32, config.activeEqGains().size)
        assertEquals(List(10) { 1f }, config.eq10Gains)
        assertEquals(List(20) { 2f }, config.eq20Gains)
        assertEquals(List(32) { 3f }, config.eq32Gains)

        // Cambiar el selector legado no debe cambiar el banco DSP activo ni
        // destruir los tres conjuntos de ganancias almacenados.
        val switchedTo10 = config.copy(eqMode = EqMode.EQ10)
        assertEquals(EqMode.EQ10, switchedTo10.eqMode)
        assertEquals(3f, switchedTo10.activeEqGains()[0], 0.001f)
        assertEquals(32, switchedTo10.activeEqGains().size)
        assertEquals(List(10) { 1f }, switchedTo10.eq10Gains)
        assertEquals(List(20) { 2f }, switchedTo10.eq20Gains)
        assertEquals(List(32) { 3f }, switchedTo10.eq32Gains)

        val restored32 = switchedTo10.copy(eqMode = EqMode.EQ32)
        assertEquals(3f, restored32.activeEqGains()[0], 0.001f)
        assertEquals(32, restored32.activeEqGains().size)
        assertEquals(List(10) { 1f }, restored32.eq10Gains)
        assertEquals(List(20) { 2f }, restored32.eq20Gains)
        assertEquals(List(32) { 3f }, restored32.eq32Gains)
    }

    @Test
    fun testExact32FrequenciesPresent() {
        assertEquals(32, DspConfig.FREQUENCIES_EQ32.size)
        // La frecuencia 32 debe ser 16 Hz (anclaje sub-grave 1/3 octava ISO)
        assertEquals(16f, DspConfig.FREQUENCIES_EQ32[0], 0.001f)
        assertEquals(20f, DspConfig.FREQUENCIES_EQ32[1], 0.001f)
        assertEquals(20000f, DspConfig.FREQUENCIES_EQ32.last(), 0.001f)
    }
}
