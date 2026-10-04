package com.sb

import com.sb.dsp.*
import org.junit.Assert.*
import org.junit.Test

class ConstantQGraphicEqTest {

    @Test
    fun testFilterStabilityAndNumericalSafety() {
        val eq = ConstantQGraphicEq(EqMode.EQ32, 48000f)
        assertEquals(32, eq.frequencies.size)

        // Probar con muestras de audio
        var sampleL = 0.5f
        var sampleR = -0.5f

        for (i in 0 until 500) {
            val (outL, outR) = eq.processSample(sampleL, sampleR)
            assertFalse("Muestra izquierda resultó NaN", outL.isNaN())
            assertFalse("Muestra izquierda resultó Infinita", outL.isInfinite())
            assertFalse("Muestra derecha resultó NaN", outR.isNaN())
            assertFalse("Muestra derecha resultó Infinita", outR.isInfinite())

            sampleL = outL * 0.95f
            sampleR = outR * 0.95f
        }
    }

    @Test
    fun testBiquadCoefficientsRecalculation() {
        val filter = BiquadFilter(
            type = BiquadFilter.Type.PEAKING,
            frequency = 1000f,
            sampleRate = 48000f,
            q = 1.414f,
            gainDb = 6f
        )

        filter.updateGain(-6f)
        assertEquals(-6f, filter.gainDb, 0.001f)

        val (outL, outR) = filter.processSample(1.0f, 1.0f)
        assertFalse(outL.isNaN())
        assertFalse(outR.isNaN())
    }
}
