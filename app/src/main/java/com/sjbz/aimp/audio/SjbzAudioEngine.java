package com.sjbz.aimp.audio;

import android.content.Context;
import android.util.Log;
import com.sjbz.aimp.service.GlobalAudioService;
import java.nio.ByteBuffer;

/**
 * SjbzAudioEngine - Centralized Singleton Audio Processing Engine.
 * FIX: Unifica DSP con GlobalAudioSessionManager para evitar doble instancia.
 */
public class SjbzAudioEngine {

    private static final String TAG = "SjbzAudioEngine";
    private static volatile SjbzAudioEngine instance;
    private static Context appContextRef;

    private boolean initialized = false;

    private SjbzAudioEngine(Context ctx) {
        if (ctx != null) appContextRef = ctx.getApplicationContext();
        this.initialized = true;
        checkAndApplyGlobalBypass();
    }

    public static SjbzAudioEngine getInstance() {
        if (instance == null) {
            synchronized (SjbzAudioEngine.class) {
                if (instance == null) {
                    instance = new SjbzAudioEngine(appContextRef);
                }
            }
        }
        return instance;
    }

    public static SjbzAudioEngine getInstance(Context context) {
        if (context != null) appContextRef = context.getApplicationContext();
        return getInstance();
    }

    public static void init(Context context) {
        if (context != null) appContextRef = context.getApplicationContext();
        getInstance(context);
    }

    /**
     * Obtains the guaranteed single DSP processor instance.
     * FIX: No crea new, devuelve el del Manager = 1 solo DSP en toda la app.
     */
    public SjbzDspProcessor getDspProcessor() {
        checkAndApplyGlobalBypass();
        // Si aún no hay contexto, fallback seguro
        Context ctx = appContextRef;
        if (ctx == null) {
            try {
                ctx = android.app.ActivityThread.currentApplication();
            } catch (Exception e) {
                Log.e(TAG, "No app context yet");
            }
        }
        return GlobalAudioSessionManager.getInstance(ctx).getDspProcessor();
    }

    /**
     * Checks if GlobalAudioService has system-wide effects enabled.
     * FIX: Ya no hace setGlobalBypass(true) que muteaba el EqActivity.
     * El bypass lo controla EqActivity con setGlobalBypass(false).
     */
    public boolean checkAndApplyGlobalBypass() {
        boolean globalActive = false;
        try {
            globalActive = GlobalAudioService.isGlobalAudioEnabled() ||
                           GlobalAudioService.Companion.isServiceRunning();
        } catch (Throwable t) {
            Log.w(TAG, "Error checking GlobalAudioService state: " + t.getMessage());
        }

        // FIX CRITICO: no mutear el DSP local, solo reportar estado
        // Si ponemos true acá, EqActivity queda en silencio (tu bug 3)
        try {
            getDspProcessor().setGlobalBypass(false);
        } catch (Exception e) {}

        return globalActive;
    }

    public void process(float[] buffer, int channels, int frameCount) {
        if (buffer == null) return;
        getDspProcessor().process(buffer, channels, frameCount);
    }

    public void processPcm16(byte[] pcmData, int offset, int length, int channels) {
        if (pcmData == null) return;
        getDspProcessor().processPcm16(pcmData, offset, length, channels);
    }

    public void processByteBuffer(ByteBuffer byteBuffer, int channels) {
        if (byteBuffer == null) return;
        getDspProcessor().processByteBuffer(byteBuffer, channels);
    }

    public void setSampleRate(float sampleRate) {
        getDspProcessor().setSampleRate(sampleRate);
    }

    public void reset() {
        getDspProcessor().resetFilterStates();
    }

    public boolean isInitialized() {
        return initialized;
    }
}
