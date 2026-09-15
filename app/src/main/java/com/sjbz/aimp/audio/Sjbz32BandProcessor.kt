package com.sjbz.aimp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Sjbz32BandProcessor : BaseAudioProcessor() {

    var equalizer: EqualizerProcessor? = null

    private val gains = FloatArray(32) { 1f }
    @Volatile private var dirty = true

    fun refresh() {
        dirty = true
    }

    private fun updateGains() {
        val eq = equalizer?: return
        for (i in 0 until 32) {
            val db: Float = try {
                eq.getBandGain(i) + eq.preampDb
            } catch (_: Throwable) { 0f }
            val clamped = db.coerceIn(-24f, 24f).toDouble()
            // Math.pow evita la ambigüedad de kotlin.math.pow
            gains[i] = Math.pow(10.0, clamped / 20.0).toFloat()
        }
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding!= C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (dirty) {
            updateGains()
            dirty = false
        }
        if (!inputBuffer.hasRemaining()) return

        val remaining = inputBuffer.remaining()
        if (remaining % 8!= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val inFloat = inputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()

        var avg = 0f
        for (g in gains) avg += g
        avg /= 32f
        val g = avg.coerceIn(0.01f, 8f)

        val n = remaining / 4
        for (i in 0 until n) {
            out.putFloat(inFloat.get(i) * g)
        }
        out.flip()
        inputBuffer.position(inputBuffer.limit())
    }
}
