package com.sb.dsp

import android.util.Log
import com.sb.dsp.jni.NativeDspBridge

class DspEngine {
    companion object {
        private const val TAG = "SB-ENGINE"
    }

    private val effects = AudioEffectManager()
    private var pcmPipeline = PcmAudioPipeline()
    private var config = DspConfig().validate()
    private var nativeEnginePtr = 0L
    private var currentSession = -1

    @Synchronized
    fun start(sessionId: Int): Boolean {
        currentSession = sessionId
        val ok = effects.start(sessionId, config)
        Log.i(TAG, "start session=$sessionId externalBackend=$ok physicalEq=${effects.postEqBandCount}")
        return ok
    }

    @Synchronized
    fun updateConfig(newConfig: DspConfig) {
        config = newConfig.validate()
        if (pcmPipeline.sampleRate != config.sampleRate || pcmPipeline.channels != config.channels) {
            pcmPipeline = PcmAudioPipeline(config.sampleRate, config.channels)
            pcmPipeline.updateConfig(config)
        } else {
            pcmPipeline.updateConfig(config)
        }
        effects.applyConfig(config)

        if (nativeEnginePtr != 0L && NativeDspBridge.isLoaded()) {
            NativeDspBridge.updateEngineConfig(
                nativeEnginePtr,
                config.masterEnabled,
                config.eqEnabled,
                config.eqMode.bandCount,
                config.activeGains(),
                if (config.pregainEnabled) 10f.powDb(config.pregainDb) else 1f,
                config.bassBoostStrength,
                config.mdrcEnabled,
                config.bassToneDb,
                config.midToneDb,
                config.trebleToneDb,
                10f.powDb(config.headroomDb),
                10f.powDb(config.masterGainDb)
            )
        }
    }

    @Synchronized
    fun startOboe(sampleRate: Int = 48000, channels: Int = 2): Boolean {
        if (!NativeDspBridge.isLoaded()) return false
        if (nativeEnginePtr != 0L) return true
        nativeEnginePtr = NativeDspBridge.initEngine(sampleRate, channels, 1920)
        if (nativeEnginePtr == 0L) return false
        val effective = config.copy(sampleRate = sampleRate, channels = channels).validate()
        NativeDspBridge.updateEngineConfig(
            nativeEnginePtr,
            effective.masterEnabled,
            effective.eqEnabled,
            effective.eqMode.bandCount,
            effective.activeGains(),
            if (effective.pregainEnabled) 10f.powDb(effective.pregainDb) else 1f,
            effective.bassBoostStrength,
            effective.mdrcEnabled,
            effective.bassToneDb,
            effective.midToneDb,
            effective.trebleToneDb,
            10f.powDb(effective.headroomDb),
            10f.powDb(effective.masterGainDb)
        )
        val started = NativeDspBridge.startOboeStream(nativeEnginePtr)
        if (!started) {
            NativeDspBridge.destroyEngine(nativeEnginePtr)
            nativeEnginePtr = 0L
        }
        return started
    }

    @Synchronized
    fun stopOboe() {
        if (nativeEnginePtr != 0L) {
            NativeDspBridge.stopOboeStream(nativeEnginePtr)
            NativeDspBridge.destroyEngine(nativeEnginePtr)
            nativeEnginePtr = 0L
        }
    }

    @Synchronized
    fun release() {
        stopOboe()
        effects.release()
        currentSession = -1
    }

    fun processPcm(pcm: ShortArray, offset: Int, count: Int) =
        pcmPipeline.process(pcm, offset, count)

    fun getConfig(): DspConfig = config
    fun getSessionId(): Int = currentSession

    private fun Float.powDb(db: Float): Float =
        kotlin.math.exp((db * kotlin.math.ln(10f) / 20f).toDouble()).toFloat()
}
