package com.sjbz.aimp.audio

import android.content.Context

typealias ATSEngine = ATS2835PEngine
typealias StudioDspEngine = ATS2835PEngine

/**
 * SjbZ Studio Audio Engine Bridge.
 * Direct bridge to SjbzDspProcessor pipeline.
 */
class ATS2835PEngine(
    val dspProcessor: SjbzDspProcessor = SjbzDspProcessor()
) {
    constructor(context: Context?) : this(SjbzDspProcessor())

    // DSP Parameters
    var balance: Float = 0.0f // -1.0 (Left) to +1.0 (Right)
    var pitch: Float = 1.0f   // 0.5x to 2.0x
    var speed: Float = 1.0f   // 0.5x to 2.0x
    var crossfadeSeconds: Int = 3 // 0 to 10s

    var isMasterEnabled: Boolean
        get() = dspProcessor.masterEnabled
        set(value) {
            dspProcessor.masterEnabled = value
        }

    fun setPreamp(gainDb: Float) {
        dspProcessor.setPreamp(gainDb)
    }

    fun getPreamp(): Float = dspProcessor.getPreamp()

    fun setBassBoost(enabled: Boolean, freqHz: Float, gainDb: Float) {
        dspProcessor.setBassBoost(enabled, freqHz, gainDb)
    }

    fun isBassBoostEnabled(): Boolean = dspProcessor.isBassBoostEnabled()
    fun getBassBoostFreq(): Float = dspProcessor.getBassBoostFreq()
    fun getBassBoostGain(): Float = dspProcessor.getBassBoostGain()

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        dspProcessor.setBandGain(bandIndex, gainDb)
    }

    fun getBandGain(bandIndex: Int): Float {
        return dspProcessor.getBandGain(bandIndex)
    }

    fun setEmulationEnabled(enabled: Boolean) {
        dspProcessor.setEmulationEnabled(enabled)
    }

    fun isEmulationEnabled(): Boolean = dspProcessor.isEmulationEnabled()

    fun setEmulationAmount(amount: Float) {
        dspProcessor.setEmulationAmount(amount)
    }

    fun getEmulationAmount(): Float = dspProcessor.getEmulationAmount()

    fun setBluetoothAutoBypass(enabled: Boolean) {
        dspProcessor.setBluetoothAutoBypass(enabled)
    }

    fun isBluetoothAutoBypass(): Boolean = dspProcessor.isBluetoothAutoBypass()

    fun setBluetoothConnected(connected: Boolean) {
        dspProcessor.setBluetoothConnected(connected)
    }

    fun isBluetoothConnected(): Boolean = dspProcessor.isBluetoothConnected()

    // MDRC 5-Band Dynamics
    fun setMdrcEnabled(enabled: Boolean) {
        dspProcessor.setMdrcEnabled(enabled)
    }

    fun isMdrcEnabled(): Boolean = dspProcessor.isMdrcEnabled()

    fun setMdrcBandGain(bandIndex: Int, gainDb: Float) {
        dspProcessor.setMdrcBandGain(bandIndex, gainDb)
    }

    fun getMdrcBandGain(bandIndex: Int): Float = dspProcessor.getMdrcBandGain(bandIndex)

    fun setMdrcDynamics(thresholdDb: Float, ratio: Float) {
        dspProcessor.setMdrcDynamics(thresholdDb, ratio)
    }

    fun getMdrcThreshold(): Float = dspProcessor.getMdrcThreshold()
    fun getMdrcRatio(): Float = dspProcessor.getMdrcRatio()
    fun getMdrcGainReduction(): Float = dspProcessor.getMdrcGainReduction()
}
