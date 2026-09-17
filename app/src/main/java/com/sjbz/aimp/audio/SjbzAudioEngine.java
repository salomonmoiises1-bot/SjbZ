package com.sjbz.aimp.audio;

import android.content.Context;
import android.util.Log;

import com.sjbz.aimp.service.GlobalAudioService;

import java.nio.ByteBuffer;

/**
 * SjbzAudioEngine - Centralized Singleton Audio Processing Engine.
 *
 * Responsibilities:
 * 1. Guarantees a single, thread-safe instance of SjbzDspProcessor across the entire app.
 * 2. Implements automatic global bypass logic when GlobalAudioService.isGlobalAudioEnabled() is true,
 *    strictly preventing duplicate DSP processing or audio degradation.
 * 3. Provides unified API for streaming PCM 16-bit, direct ByteBuffers, and raw float buffers.
 */
public class SjbzAudioEngine {

    private static final String TAG = "SjbzAudioEngine";
    private static volatile SjbzAudioEngine instance;

    private final SjbzDspProcessor dspProcessor;
    private boolean initialized = false;

    private SjbzAudioEngine() {
        this.dspProcessor = new SjbzDspProcessor(48000.0f);
        this.initialized = true;
        checkAndApplyGlobalBypass();
    }

    public static SjbzAudioEngine getInstance() {
        if (instance == null) {
            synchronized (SjbzAudioEngine.class) {
                if (instance == null) {
                    instance = new SjbzAudioEngine();
                }
            }
        }
        return instance;
    }

    public static SjbzAudioEngine getInstance(Context context) {
        return getInstance();
    }

    /**
     * Obtains the guaranteed single DSP processor instance.
     */
    public SjbzDspProcessor getDspProcessor() {
        checkAndApplyGlobalBypass();
        return dspProcessor;
    }

    /**
     * Checks if GlobalAudioService has system-wide effects enabled.
     * If enabled, enables total bypass on local DSP instance to avoid double processing.
     */
    public boolean checkAndApplyGlobalBypass() {
        boolean globalActive = false;
        try {
            globalActive = GlobalAudioService.isGlobalAudioEnabled() ||
                           GlobalAudioService.Companion.isServiceRunning();
        } catch (Throwable t) {
            Log.w(TAG, "Error checking GlobalAudioService state: " + t.getMessage());
        }

        if (dspProcessor != null) {
            dspProcessor.setGlobalBypass(globalActive);
        }
        return globalActive;
    }

    /**
     * Process audio in float format.
     */
    public void process(float[] buffer, int channels, int frameCount) {
        if (checkAndApplyGlobalBypass()) {
            return; // Global system audio service is handling audio
        }
        dspProcessor.process(buffer, channels, frameCount);
    }

    /**
     * Process 16-bit signed PCM byte array in place.
     */
    public void processPcm16(byte[] pcmData, int offset, int length, int channels) {
        if (checkAndApplyGlobalBypass()) {
            return;
        }
        dspProcessor.processPcm16(pcmData, offset, length, channels);
    }

    /**
     * Process direct ByteBuffer of 16-bit PCM in place.
     */
    public void processByteBuffer(ByteBuffer byteBuffer, int channels) {
        if (checkAndApplyGlobalBypass()) {
            return;
        }
        dspProcessor.processByteBuffer(byteBuffer, channels);
    }

    public void setSampleRate(float sampleRate) {
        dspProcessor.setSampleRate(sampleRate);
    }

    public void reset() {
        dspProcessor.resetFilterStates();
    }

    public boolean isInitialized() {
        return initialized;
    }
}
