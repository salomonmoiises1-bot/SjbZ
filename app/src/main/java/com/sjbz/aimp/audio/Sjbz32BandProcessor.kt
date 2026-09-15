package com.sjbz.aimp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class Sjbz32BandProcessor : AudioProcessor {

    var equalizer: EqualizerProcessor? = null
        set(value) {
            field = value
            updateCoeffs()
        }

    private var sampleRate = 44100
    private var channelCount = 2
    private var inputEnded = false

    private data class Biquad(var b0: Double=1.0, var b1: Double=0.0, var b2: Double=0.0,
                             var a1: Double=0.0, var a2: Double=0.0)
    private val coeffs = Array(32) { Biquad() }
    private var states = Array(32) { Array(2) { DoubleArray(4) } }

    private var outputBuffer = AudioProcessor.EMPTY_BUFFER

    fun refresh() { updateCoeffs() }

    private fun updateCoeffs() {
        val eq = equalizer?: return
        for (i in 0 until 32) {
            val freq = EqualizerProcessor.ISO_FREQUENCIES[i].toDouble()
            val gainDb = try { eq.getEffectiveGain(i, eq.bassBoostProcessor).toDouble() } catch(_: Throwable) { 0.0 }
            val q = 4.318
            val w0 = 2 * PI * freq / sampleRate.coerceAtLeast(8000)
            val alpha = sin(w0) / (2 * q)
            val A = 10.0.pow(gainDb / 40.0)
            val cosw = cos(w0)
            val b0 = 1 + alpha * A
            val b1 = -2 * cosw
            val b2 = 1 - alpha * A
            val a0 = 1 + alpha / A
            val a1 = -2 * cosw
            val a2 = 1 - alpha / A
            coeffs[i].b0 = b0 / a0
            coeffs[i].b1 = b1 / a0
            coeffs[i].b2 = b2 / a0
            coeffs[i].a1 = a1 / a0
            coeffs[i].a2 = a2 / a0
        }
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        states = Array(32) { Array(channelCount.coerceAtLeast(1)) { DoubleArray(4) } }
        updateCoeffs()
        return AudioProcessor.AudioFormat(sampleRate, channelCount, C.ENCODING_PCM_32BIT_FLOAT)
    }

    override fun isActive(): Boolean = true
    override fun isEnded(): Boolean = inputEnded && outputBuffer.remaining() == 0
    override fun getOutput(): ByteBuffer = outputBuffer

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val eq = equalizer
        val bytes = inputBuffer.remaining()
        val out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        if (eq == null ||!eq.isEnabled) {
            out.put(inputBuffer); out.flip()
            outputBuffer = out; return
        }
        val frames = bytes / (4 * channelCount)
        val preampLin = 10.0.pow(eq.preampDb / 20.0)
        for (f in 0 until frames) {
            for (ch in 0 until channelCount) {
                var s = inputBuffer.float.toDouble() * preampLin
                for (b in 0 until 32) {
                    val c = coeffs[b]; val st = states[b][ch]
                    val y0 = c.b0*s + c.b1*st[0] + c.b2*st[1] - c.a1*st[2] - c.a2*st[3]
                    st[1]=st[0]; st[0]=s; st[3]=st[2]; st[2]=y0
                    s = y0
                }
                out.putFloat(tanh(s.coerceIn(-3.0,3.0)).toFloat())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip(); outputBuffer = out
    }

    override fun queueEndOfStream() { inputEnded = true }
    override fun flush() { inputEnded=false; outputBuffer=AudioProcessor.EMPTY_BUFFER
        states = Array(32) { Array(channelCount.coerceAtLeast(1)) { DoubleArray(4) } } }
    override fun reset() { flush() }
}
