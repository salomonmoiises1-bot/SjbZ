package com.sb.dsp

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/** Metering for the same session-0 system audio path used by DynamicsProcessing. */
class ExternalVisualizerManager {
    companion object { private const val TAG = "SB-VIS" }
    private var visualizer: Visualizer? = null
    @Volatile var peakDb: Float = -60f; private set
    @Volatile var rmsDb: Float = -60f; private set

    @Synchronized
    fun start(sessionId: Int = 0): Boolean {
        release()
        return try {
            val v = Visualizer(sessionId)
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) {
                    if (waveform.isEmpty()) return
                    var sum = 0.0
                    var peak = 0.0
                    for (b in waveform) {
                        val x = ((b.toInt() and 0xFF) - 128) / 128.0
                        val a = abs(x)
                        sum += x * x
                        if (a > peak) peak = a
                    }
                    val rms = sqrt(sum / waveform.size).coerceAtLeast(1e-6)
                    rmsDb = (20.0 * kotlin.math.log10(rms)).toFloat().coerceIn(-60f, 0f)
                    peakDb = (20.0 * kotlin.math.log10(peak.coerceAtLeast(1e-6))).toFloat().coerceIn(-60f, 0f)
                }
                override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
            }, Visualizer.getMaxCaptureRate(), true, false)
            v.enabled = true
            visualizer = v
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Visualizer session=$sessionId unavailable", t)
            release(); false
        }
    }

    fun release() { try { visualizer?.release() } catch (_: Throwable) {}; visualizer = null }
}
