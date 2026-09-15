package com.sjbz.aimp.audio

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Crossover filter manager and ATS2835P Hardware SoftClipper.
 * Uses smooth hyperbolic tangent (tanh) & polynomial curve to eliminate harsh digital squaring
 * and introduce warm musical harmonics when audio peaks exceed headroom.
 */
class CrossoverProcessor {

    companion object {
        private const val MIN_DRIVE = 1.0f
        private const val MAX_DRIVE = 2.0f
        private const val MIN_KNEE = 0.5f
        private const val MAX_KNEE = 0.95f
    }

    // 4 crossover cutoffs defining the 5 bands (Sub/Low/Mid/High/Air)
    // PATCH: val -> var con validación para que no queden desordenados o <= 0,
    // que antes se podían pisar desde fuera y romper getBandIndexForFrequency.
    var crossoverPointsHz = floatArrayOf(120f, 500f, 2000f, 8000f)
        set(value) {
            // exige 4 puntos crecientes y positivos; si no, ignora
            if (value.size!= 4 || value.any { it <= 0f }) return
            for (i in 1 until value.size) {
                if (value[i] <= value[i - 1]) return
            }
            field = value.copyOf()
        }

    var isSoftClipperEnabled: Boolean = true

    // PATCH: coerce 1.0..2.0. Antes se podía setear 0 o negativo y el DSP quedaba
    // en silencio o invertía fase sin aviso.
    var driveFactor: Float = 1.15f
        set(value) {
            field = value.coerceIn(MIN_DRIVE, MAX_DRIVE)
        }

    // PATCH: coerce 0.5..0.95. Un knee >= 1.0 dividía por cero en
    // (1.0f - kneeStart) y devolvía NaN que muteaba el audio.
    var kneeStart: Float = 0.707f
        set(value) {
            field = value.coerceIn(MIN_KNEE, MAX_KNEE)
        }

    /**
     * Devuelve el índice de banda (0..4) para una frecuencia dada,
     * usando los crossoverPoints. Antes los puntos estaban sin usar
     * y cada componente los hardcodeaba por su lado.
     */
    fun getBandIndexForFrequency(freqHz: Float): Int {
        for (i in crossoverPointsHz.indices) {
            if (freqHz < crossoverPointsHz[i]) return i
        }
        return crossoverPointsHz.size // 4 = banda Air
    }

    /**
     * ATS2835P hardware-modeled soft clipping transfer curve.
     * Takes an input audio sample normalized [-1.0, 1.0] and returns a smoothly saturated sample.
     */
    fun processSample(inputSample: Float): Float {
        // PATCH: NaN/Infinity de entrada se sanean a 0. Antes un NaN atravesaba
        // todo el pipeline y muteaba el canal hasta reiniciar el DSP.
        val cleanInput = when {
            inputSample.isNaN() || inputSample.isInfinite() -> 0f
            else -> inputSample.coerceIn(-1.0f, 1.0f)
        }
        if (!isSoftClipperEnabled) return cleanInput

        val x = cleanInput * driveFactor
        val absX = abs(x)

        return if (absX < kneeStart) {
            // Linear region (con drive aplicado, continuo en el knee)
            x.coerceIn(-0.999f, 0.999f)
        } else {
            // Smooth polynomial / tanh saturation region
            val sign = if (x >= 0) 1.0f else -1.0f
            val denom = (1.0f - kneeStart).coerceAtLeast(0.05f)
            val compressed = kneeStart + (1.0f - kneeStart) * tanh((absX - kneeStart) / denom)
            val out = sign * compressed.coerceIn(0f, 0.999f)
            // PATCH: sanea NaN por si tanh recibe un valor extremo
            if (out.isNaN() || out.isInfinite()) 0f else out
        }
    }

    /**
     * Procesa un buffer in-place. Evita al llamador el loop manual
     * que antes se repetía en cada integración.
     */
    fun processBuffer(buffer: FloatArray, offset: Int = 0, length: Int = buffer.size - offset) {
        val end = (offset + length).coerceAtMost(buffer.size)
        for (i in offset until end) {
            buffer[i] = processSample(buffer[i])
        }
    }
}
