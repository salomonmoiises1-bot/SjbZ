package com.sb.dsp.jni

import java.nio.ByteBuffer

object NativeDspBridge {
    private var loaded = false

    init {
        try {
            System.loadLibrary("sb_dsp_engine")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    fun isLoaded(): Boolean = loaded

    external fun initEngine(sampleRate: Int, channelCount: Int, bufferCapacityFrames: Int): Long
    external fun updateEngineConfig(
        enginePtr: Long,
        masterEnabled: Boolean,
        eqEnabled: Boolean,
        eqMode: Int,
        eqGains: FloatArray,
        pregainLinear: Float,
        bassBoostStrength: Float,
        mdrcEnabled: Boolean,
        toneBassDb: Float,
        toneMidDb: Float,
        toneTrebleDb: Float,
        headroomMarginLinear: Float
    )
    external fun processDirectBuffer(enginePtr: Long, directBuffer: ByteBuffer, frameCount: Int)
    external fun processShortArray(enginePtr: Long, inputOutput: ShortArray, offset: Int, count: Int)
    external fun startOboeStream(enginePtr: Long): Boolean
    external fun stopOboeStream(enginePtr: Long)
    external fun destroyEngine(enginePtr: Long)
}
