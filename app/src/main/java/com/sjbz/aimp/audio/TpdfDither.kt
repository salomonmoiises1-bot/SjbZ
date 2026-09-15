package com.sjbz.aimp.audio

class TpdfDither {
    private var seed: Long = 22222L
    private var prevErrorL = 0f
    private var prevErrorR = 0f

    private fun nextFloat(): Float {
        // LCG rápido, sin alloc
        seed = seed * 1664525L + 1013904223L
        return ((seed ushr 16) and 0xFFFF).toFloat() / 32768f - 1f
    }

    // Para futuro: si bajas a 16-bit
    fun processFloatTo16Bit(input: FloatArray, output: ShortArray, totalFloats: Int, channels: Int) {
        for (i in 0 until totalFloats) {
            val ch = i % channels
            var s = input[i] * 32767f
            val tpdf = nextFloat() - nextFloat() // triangular aprox
            val prevErr = if (ch == 0) prevErrorL else prevErrorR
            // noise shaping 1er orden
            s += tpdf * 0.5f - prevErr * 0.3f
            val q = s.coerceIn(-32768f, 32767f).toInt().toShort()
            output[i] = q
            val err = s - q.toFloat()
            if (ch == 0) prevErrorL = err else if (ch == 1) prevErrorR = err
        }
    }

    fun reset() {
        prevErrorL = 0f; prevErrorR = 0f
    }
}
