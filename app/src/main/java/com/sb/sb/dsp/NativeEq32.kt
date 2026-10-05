package com.sb.dsp

/**
 * Native PCM EQ32 engine bridge. This is a local engine bridge; it does not
 * by itself register a system-wide AudioFlinger effect. System-wide use is
 * provided by native/sb_eq32/platform inside a platform/vendor image.
 */
object NativeEq32 {
    private var handle: Long = 0L
    private var loaded = false

    fun isAvailable(): Boolean {
        if (!loaded) {
            loaded = try { System.loadLibrary("sb_eq32_jni"); true } catch (_: Throwable) { false }
        }
        return loaded
    }

    @Synchronized fun start(sampleRate: Int, gainsDb: FloatArray): Boolean {
        if (!isAvailable() || gainsDb.size < 32) return false
        if (handle == 0L) handle = nativeCreate()
        nativeConfigure(handle, sampleRate.toFloat(), gainsDb.copyOf(32))
        return true
    }

    @Synchronized fun process(pcm: FloatArray, frames: Int, channels: Int): Boolean {
        if (handle == 0L || frames <= 0 || channels <= 0) return false
        nativeProcess(handle, pcm, frames, channels)
        return true
    }

    @Synchronized fun stop() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeConfigure(handle: Long, sampleRate: Float, gains: FloatArray)
    private external fun nativeProcess(handle: Long, pcm: FloatArray, frames: Int, channels: Int)
}
