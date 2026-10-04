package com.sb.dsp

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * System-audio DSP coordinator. The production path is AudioEffectManager on
 * Android audio session 0; there is deliberately no separate app-owned PCM path.
 */
class DspEngine(private val context: Context) {
    companion object {
        private const val TAG = "SB-ENGINE"
        const val GLOBAL_SESSION_ID = 0
        @Volatile var lastPeakDb: Float = -60f
        @Volatile var lastRmsDb: Float = -60f
        @Volatile var lastBackendActive: Boolean = false
        @Volatile var lastDynamicsAvailable: Boolean = false
    }

    private val effects = AudioEffectManager()
    private val visualizer = ExternalVisualizerManager()
    @Volatile private var config = DspConfig().validate()
    private var currentSession = -1
    private var started = false
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SB-DspControl").apply { isDaemon = true }
    }
    private var controlTask: ScheduledFuture<*>? = null
    private var autoGainCurrentDb = 0f

    @Synchronized
    fun start(sessionId: Int = GLOBAL_SESSION_ID): Boolean {
        val target = GLOBAL_SESSION_ID
        if (started && currentSession == target && effects.dynamicsAvailable) {
            effects.applyConfig(runtimeConfig(config))
            return true
        }
        currentSession = target
        val ok = effects.start(target, runtimeConfig(config))
        visualizer.start(target)
        restartAutoGainTask()
        started = ok
        lastBackendActive = ok && config.masterEnabled
        lastDynamicsAvailable = effects.dynamicsAvailable
        Log.i(TAG, "start globalSession=$target requestedSession=$sessionId externalBackend=$ok dynamics=${effects.dynamicsAvailable} physicalPreEq=${effects.physicalEqBandCount} postEq=${effects.postEqBandCount}")
        return ok
    }

    @Synchronized
    fun updateConfig(newConfig: DspConfig) {
        val next = newConfig.validate()
        config = next
        if (!started) {
            start(GLOBAL_SESSION_ID)
            return
        }
        effects.applyConfig(runtimeConfig(next))
        lastBackendActive = started && next.masterEnabled && effects.dynamicsAvailable
        lastDynamicsAvailable = effects.dynamicsAvailable
        restartAutoGainTask()
    }

    private fun restartAutoGainTask() {
        controlTask?.cancel(false)
        autoGainCurrentDb = if (config.autoGainEnabled) autoGainCurrentDb else 0f
        if (!config.autoGainEnabled) effects.setAutoGainOffsetDb(0f)
        controlTask = scheduler.scheduleAtFixedRate({
            // Metering is independent of AutoGain; the dashboard must keep working
            // when AutoGain is disabled.
            visualizer.updateMeasurement()
            lastPeakDb = visualizer.peakDb
            lastRmsDb = visualizer.rmsDb
            if (config.autoGainEnabled) {
                val error = config.autoGainTargetRmsDb - visualizer.rmsDb
                val desired = error.coerceIn(-12f, 12f)
                autoGainCurrentDb = autoGainCurrentDb * 0.9f + desired * 0.1f
                effects.setAutoGainOffsetDb(autoGainCurrentDb)
            }
        }, 150, 100, TimeUnit.MILLISECONDS)
    }

    private fun runtimeConfig(base: DspConfig): DspConfig {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val detected = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()?.takeIf { it > 0 }?.coerceIn(8000, 192000) ?: base.sampleRate
        return base.copy(sampleRate = detected, channels = 2).validate()
    }

    fun peakDb(): Float = visualizer.peakDb
    fun rmsDb(): Float = visualizer.rmsDb

    @Synchronized
    fun release() {
        controlTask?.cancel(false)
        controlTask = null
        visualizer.release()
        effects.release()
        started = false
        currentSession = -1
        lastBackendActive = false
        lastDynamicsAvailable = false
    }

    fun getConfig(): DspConfig = config
    fun getSessionId(): Int = currentSession
}
