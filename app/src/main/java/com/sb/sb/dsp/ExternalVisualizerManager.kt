package com.sb.dsp

import android.media.audiofx.Visualizer
import android.util.Log

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
            v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
                override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
            }, Visualizer.getMaxCaptureRate(), false, false)
            v.enabled = true
            visualizer = v
            updateMeasurement()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Visualizer session=$sessionId unavailable", t)
            release(); false
        }
    }

    /** Refreshes the framework's native peak/RMS measurement for the output mix. */
    fun updateMeasurement() {
        val v = visualizer ?: return
        try {
            val m = Visualizer.MeasurementPeakRms()
            if (v.getMeasurementPeakRms(m) == Visualizer.SUCCESS) {
                peakDb = (m.mPeak / 1000f).coerceIn(-60f, 6f)
                rmsDb = (m.mRms / 1000f).coerceIn(-60f, 6f)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Visualizer measurement failed", t)
        }
    }

    fun release() {
        try { visualizer?.enabled = false } catch (_: Throwable) {}
        try { visualizer?.release() } catch (_: Throwable) {}
        visualizer = null
        peakDb = -60f
        rmsDb = -60f
    }
}
