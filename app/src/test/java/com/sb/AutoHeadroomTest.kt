package com.sb

import com.sb.dsp.*
import org.junit.Assert.*
import org.junit.Test

class AutoHeadroomTest {

    @Test
    fun testHeadroomZeroWhenFlat() {
        val flatConfig = DspConfig.DEFAULT.copy(autoHeadroomEnabled = true)
        val headroom = HeadroomManager.calculateRequiredHeadroomDb(flatConfig)
        assertEquals(0f, headroom, 0.001f)
    }

    @Test
    fun testHeadroomReducesOnPreGainBoost() {
        val boostedConfig = DspConfig.DEFAULT.copy(
            autoHeadroomEnabled = true,
            preGain = 6.0f
        )
        val headroom = HeadroomManager.calculateRequiredHeadroomDb(boostedConfig)
        assertTrue(headroom < 0f)
        // Debe compensar al menos los 6 dB de ganancia
        assertTrue(headroom <= -6.0f)
    }

    @Test
    fun testHeadroomReducesOnEqBoost() {
        // SB usa actualmente EQ32 como único banco activo. EQ10/EQ20 se
        // conservan solamente para compatibilidad con configuraciones antiguas.
        val eqBoosted = DspConfig.DEFAULT.copy(
            autoHeadroomEnabled = true,
            eq32Gains = List(32) { index -> if (index == 2) 8.0f else 0f }
        )
        val headroom = HeadroomManager.calculateRequiredHeadroomDb(eqBoosted)
        assertTrue(headroom <= -8.0f)
    }
}
