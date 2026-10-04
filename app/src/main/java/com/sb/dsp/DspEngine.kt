package com.sb.dsp

import android.util.Log
import com.sb.dsp.jni.NativeDspBridge
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class DspEngine {
    companion object {
        private const val TAG = "SB-ENGINE"
        const val GLOBAL_SESSION_ID = 0
        @Volatile var lastPeakDb: Float = -60f
        @Volatile var lastRmsDb: Float = -60f
    }

    private val effects = AudioEffectManager()
    private val visualizer = ExternalVisualizerManager()
    private var pcmPipeline = PcmAudioPipeline()
    @Volatile private var config = DspConfig().validate()
    private var nativeEnginePtr = 0L
    private var currentSession = -1
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "SB-DspControl").apply { isDaemon = true } }
    private var autoGainTask: ScheduledFuture<*>? = null
    private var autoGainCurrentDb = 0f

    @Synchronized
    fun start(sessionId: Int = GLOBAL_SESSION_ID): Boolean {
        val target = GLOBAL_SESSION_ID
        currentSession = target
        val ok = effects.start(target, config)
        visualizer.start(target)
        restartAutoGainTask()
        Log.i(TAG, "start globalSession=$target requestedSession=$sessionId externalBackend=$ok physicalPreEq=${effects.physicalEqBandCount}")
        return ok
    }

    @Synchronized
    fun updateConfig(newConfig: DspConfig) {
        val next = newConfig.validate()
        config = next
        if (pcmPipeline.sampleRate != next.sampleRate || pcmPipeline.channels != next.channels) {
            pcmPipeline = PcmAudioPipeline(next.sampleRate, next.channels)
        }
        pcmPipeline.updateConfig(next)
        effects.applyConfig(next)
        restartAutoGainTask()
        if (nativeEnginePtr != 0L && NativeDspBridge.isLoaded()) {
            NativeDspBridge.updateEngineConfig(nativeEnginePtr, next.masterEnabled, next.eqEnabled, next.eqMode.bandCount,
                next.activeGains(), dbToLinear(if (next.pregainEnabled) next.pregainDb else 0f), next.bassBoostStrength,
                next.mdrcEnabled, next.bassToneDb, next.midToneDb, next.trebleToneDb, dbToLinear(next.headroomDb), dbToLinear(next.masterGainDb))
        }
    }

    private fun restartAutoGainTask() {
        autoGainTask?.cancel(false)
        if (!config.autoGainEnabled) autoGainCurrentDb = 0f
        autoGainTask = if (config.autoGainEnabled) scheduler.scheduleAtFixedRate({
            lastPeakDb = visualizer.peakDb
            lastRmsDb = visualizer.rmsDb
            val measured = visualizer.rmsDb
            val error = config.autoGainTargetRmsDb - measured
            val desired = error.coerceIn(-12f, 12f)
            autoGainCurrentDb = autoGainCurrentDb * 0.9f + desired * 0.1f
            effects.setAutoGainOffsetDb(autoGainCurrentDb)
        }, 150, 100, TimeUnit.MILLISECONDS) else null
    }

    fun peakDb(): Float = visualizer.peakDb
    fun rmsDb(): Float = visualizer.rmsDb

    @Synchronized fun startOboe(sampleRate: Int = 48000, channels: Int = 2): Boolean {
        if (!NativeDspBridge.isLoaded()) return false
        if (nativeEnginePtr != 0L) return true
        nativeEnginePtr = NativeDspBridge.initEngine(sampleRate, channels, 1920)
        if (nativeEnginePtr == 0L) return false
        val effective = config.copy(sampleRate = sampleRate, channels = channels).validate()
        NativeDspBridge.updateEngineConfig(nativeEnginePtr, effective.masterEnabled, effective.eqEnabled, effective.eqMode.bandCount,
            effective.activeGains(), dbToLinear(if (effective.pregainEnabled) effective.pregainDb else 0f), effective.bassBoostStrength,
            effective.mdrcEnabled, effective.bassToneDb, effective.midToneDb, effective.trebleToneDb, dbToLinear(effective.headroomDb), dbToLinear(effective.masterGainDb))
        if (!NativeDspBridge.startOboeStream(nativeEnginePtr)) { NativeDspBridge.destroyEngine(nativeEnginePtr); nativeEnginePtr = 0L; return false }
        return true
    }

    @Synchronized fun stopOboe() {
        if (nativeEnginePtr != 0L) { NativeDspBridge.stopOboeStream(nativeEnginePtr); NativeDspBridge.destroyEngine(nativeEnginePtr); nativeEnginePtr = 0L }
    }

    @Synchronized fun release() {
        autoGainTask?.cancel(false); autoGainTask = null
        visualizer.release(); stopOboe(); effects.release(); currentSession = -1
    }

    fun processPcm(pcm: ShortArray, offset: Int, count: Int) = pcmPipeline.process(pcm, offset, count)
    fun getConfig(): DspConfig = config
    fun getSessionId(): Int = currentSession

    private fun dbToLinear(db: Float) = 10f.pow(db / 20f)
}
