package com.sjbz.aimp.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class Sjbz32BandProcessor : AudioProcessor {
  var equalizer: EqualizerProcessor? = null
  private var sampleRateHz = 48000
  private var channelCount = 2
  private var outputFormat = AudioFormat.NOT_SET
  private var inputEnded = false
  private var outputBuffer = AudioProcessor.EMPTY_BUFFER
  private val freqs = floatArrayOf(
    20f,25f,31.5f,40f,50f,63f,80f,100f,125f,160f,200f,250f,315f,400f,500f,
    630f,800f,1000f,1250f,1600f,2000f,2500f,3150f,4000f,5000f,6300f,8000f,
    10000f,12500f,16000f,18000f,20000f
  )
  private var coeffs = Array(32) { DoubleArray(6) }
  private var states = Array(32) { Array(2) { DoubleArray(4) } }

  fun refresh() { updateCoeffs() }

  private fun updateCoeffs() {
    val eq = equalizer?: return
    for (i in 0 until 32) {
      val f = freqs[i].toDouble().coerceIn(20.0, sampleRateHz * 0.45)
      val gDb = eq.getBandLevel(i).toDouble()
      val A = 10.0.pow(gDb / 40.0)
      val w0 = 2.0 * PI * f / sampleRateHz
      val alpha = sin(w0) / (2.0 * 2.0)
      val cw = cos(w0)
      var b0 = 1.0 + alpha * A; var b1 = -2.0 * cw; var b2 = 1.0 - alpha * A
      var a0 = 1.0 + alpha / A; var a1 = -2.0 * cw; var a2 = 1.0 - alpha / A
      b0/=a0; b1/=a0; b2/=a0; a1/=a0; a2/=a0
      coeffs[i][0]=b0; coeffs[i][1]=b1; coeffs[i][2]=b2
      coeffs[i][3]=a1; coeffs[i][4]=a2; coeffs[i][5]=1.0
    }
  }

  override fun configure(inputFormat: AudioFormat): AudioFormat {
    sampleRateHz = if (inputFormat.sampleRate!= AudioFormat.NO_VALUE) inputFormat.sampleRate else 48000
    channelCount = if (inputFormat.channelCount!= AudioFormat.NO_VALUE) inputFormat.channelCount else 2
    updateCoeffs()
    states = Array(32) { Array(channelCount.coerceAtLeast(1)) { DoubleArray(4) } }
    outputFormat = AudioFormat.Builder().setSampleRate(sampleRateHz).setChannelCount(channelCount).setEncoding(C.ENCODING_PCM_FLOAT).build()
    return outputFormat
  }
  override fun isActive(): Boolean = true
  override fun getOutput(): ByteBuffer = outputBuffer
  override fun isEnded(): Boolean = inputEnded &&!outputBuffer.hasRemaining()
  override fun queueEndOfStream() { inputEnded = true }
  override fun flush() {
    inputEnded = false
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    states = Array(32) { Array(channelCount.coerceAtLeast(1)) { DoubleArray(4) } }
  }
  override fun reset() { flush() }

  override fun queueInput(inputBuffer: ByteBuffer) {
    if (!inputBuffer.hasRemaining()) return
    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
    val frames = inputBuffer.remaining() / (4 * channelCount)
    val out = ByteBuffer.allocateDirect(frames * channelCount * 4).order(ByteOrder.LITTLE_ENDIAN)
    val b0a = DoubleArray(32); val b1a = DoubleArray(32); val b2a = DoubleArray(32)
    val a1a = DoubleArray(32); val a2a = DoubleArray(32)
    for (i in 0 until 32) { b0a[i]=coeffs[i][0]; b1a[i]=coeffs[i][1]; b2a[i]=coeffs[i][2]; a1a[i]=coeffs[i][3]; a2a[i]=coeffs[i][4] }
    repeat(frames) {
      repeat(channelCount) { ch ->
        var s = inputBuffer.float.toDouble()
        for (b in 0 until 32) {
          val st = states[b][ch]
          val y = b0a[b]*s + b1a[b]*st[0] + b2a[b]*st[1] - a1a[b]*st[2] - a2a[b]*st[3]
          st[1]=st[0]; st[0]=s; st[3]=st[2]; st[2]=y
          s = y.coerceIn(-8.0, 8.0)
        }
        out.putFloat(s.toFloat())
      }
    }
    inputBuffer.position(inputBuffer.limit())
    out.flip()
    outputBuffer = out
  }
}
