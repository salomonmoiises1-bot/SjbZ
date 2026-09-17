package com.sjbz.aimp.audio;

import android.content.Context;
import android.util.Log;

import com.sjbz.aimp.service.GlobalAudioService;

import java.nio.ByteBuffer;

/**
 * SjbzAudioEngine - Centralized Singleton Audio Processing Engine.
 *
 * Responsibilities:
 * 1. Strictly adheres to Singleton Pattern: PROHIBITED from instantiating its own processor.
 *    Uses exclusively the single global instance exposed by GlobalAudioSessionManager.getDspProcessor().
 * 2. Correct Global Bypass Logic: When GlobalAudioService is active (globalActive = true),
 *    the DSP processor bypass is explicitly deactivated (dspProcessor.setGlobalBypass(false)),
 *    ensuring real-time software PCM audio processing without inverted logic.
 * 3. Unified API for streaming 16-bit PCM arrays, raw float buffers, and direct ByteBuffers.
 */
public class SjbzAudioEngine {

    private static final String TAG = "SjbzAudioEngine";
    private static volatile SjbzAudioEngine instance;

    // Single global processor reference from GlobalAudioSessionManager
    private final SjbzDspProcessor dspProcessor;
    private boolean initialized = false;

    private SjbzAudioEngine() {
        // Obtains exclusively the single global instance. Strictly no 'new SjbzDspProcessor()'.
        this.dspProcessor = GlobalAudioSessionManager.getDspProcessor();
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
     * Lógica de bypass corregida:
     * Cuando el servicio de audio global esté activo (globalActive = true),
     * el bypass del procesador DSP DEBE estar desactivado (dspProcessor.setGlobalBypass(false)).
     * Evita cualquier lógica invertida donde activar el servicio inhabilite el motor de audio.
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
            if (globalActive) {
                // When global service is running, DSP must process audio, so bypass is FALSE
                dspProcessor.setGlobalBypass(false);
            }
        }
        return globalActive;
    }

    /**
     * Process audio in float format.
     */
    public void process(float[] buffer, int channels, int frameCount) {
        checkAndApplyGlobalBypass();
        dspProcessor.process(buffer, channels, frameCount);
    }

    /**
     * Process 16-bit signed PCM short array in place.
     */
    public void processPcm16Short(short[] buffer, int offset, int length, int channels) {
        checkAndApplyGlobalBypass();
        dspProcessor.processPcm16Short(buffer, offset, length, channels);
    }

    /**
     * Process 16-bit signed PCM byte array in place.
     */
    public void processPcm16(byte[] pcmData, int offset, int length, int channels) {
        checkAndApplyGlobalBypass();
        dspProcessor.processPcm16(pcmData, offset, length, channels);
    }

    /**
     * Process direct ByteBuffer of 16-bit PCM in place.
     */
    public void processByteBuffer(ByteBuffer byteBuffer, int channels) {
        checkAndApplyGlobalBypass();
        dspProcessor.processByteBuffer(byteBuffer, channels);
    }

    public boolean isInitialized() {
        return initialized;
    }
}
