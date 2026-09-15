package com.sjbz.aimp.audio
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
class TruePeakLimiter(sampleRate: Int, private val lookaheadMs: Float = 2.0f, private val releaseMs: Float = 80f, private val ceilingDb: Float = -1.0f) {
    private var sr = sampleRate
    private var lookaheadSamples = ((sr * lookaheadMs) / 1000f).toInt().coerceAtLeast(32)
    private var delayBuf = FloatArray(lookaheadSamples * 8)
    private var delayPos = 0
    private var currentGain = 1f
    private var releaseCoef = 0f
    private val ceiling = 10f.pow(ceilingDb / 20f)
    init { updateRelease() }
    fun setSampleRate(newSr: Int) {
        if (newSr == sr) return
        sr = newSr.coerceAtLeast(8000)
        lookaheadSamples = ((sr * lookaheadMs) / 1000f).toInt().coerceAtLeast(32)
        delayBuf = FloatArray(lookaheadSamples * 8)
        delayPos = 0; updateRelease(); reset()
    }
    private fun updateRelease() { releaseCoef = exp(-1f / (sr * releaseMs / 1000f)) }
    fun reset() { currentGain = 1f; delayPos = 0; delayBuf.fill(0f) }
    fun process(buf: FloatArray, totalFloats: Int, frames: Int, channels: Int) {
        val ch = channels.coerceIn(1, 8)
        val delaySize = delayBuf.size
        val look = lookaheadSamples * ch
        for (f in 0 until frames) {
            var peak = 0f
            val base = f * ch
            for (c in 0 until ch) peak = max(peak, abs(buf[base + c]))
            val target = if (peak * currentGain > ceiling && peak > 1e-6f) ceiling / peak else 1f
            currentGain = if (target < currentGain) target else currentGain + (1f - currentGain) * (1f - releaseCoef)
            for (c in 0 until ch) {
                delayBuf[delayPos] = buf[base + c]
                val readPos = (delayPos - look + delaySize * 2) % delaySize
                buf[base + c] = delayBuf[readPos] * currentGain
                delayPos = (delayPos + 1) % delaySize
            }
        }
    }
}
