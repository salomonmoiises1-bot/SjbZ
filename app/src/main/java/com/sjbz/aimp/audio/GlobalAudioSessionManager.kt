package com.sjbz.aimp.audio;

import android.content.Context;
import android.util.Log;
import com.sjbz.aimp.service.GlobalAudioService;
import java.nio.ByteBuffer;

public class SjbzAudioEngine {
    private static final String TAG = "SjbzAudioEngine";
    private static volatile SjbzAudioEngine instance;
    private static Context appCtxRef;

    private final SjbzDspProcessor dspProcessor;
    private boolean initialized = false;

    private SjbzAudioEngine(Context ctx) {
        if (ctx!= null) appCtxRef = ctx.getApplicationContext();
        // FIX UNICO: no new, usa el del Manager
        Context c = appCtxRef!= null? appCtxRef : android.app.ActivityThread.currentApplication();
        this.dspProcessor = GlobalAudioSessionManager.getInstance(c).getDspProcessor();
        this.initialized = true;
        checkAndApplyGlobalBypass();
    }

    public static SjbzAudioEngine getInstance() {
        if (instance == null) {
            synchronized (SjbzAudioEngine.class) {
                if (instance == null) instance = new SjbzAudioEngine(appCtxRef);
            }
        }
        return instance;
    }
    public static SjbzAudioEngine getInstance(Context context) {
        if (context!= null) appCtxRef = context.getApplicationContext();
        return getInstance();
    }
    public static void init(Context context) {
        if (context!= null) appCtxRef = context.getApplicationContext();
        getInstance();
    }

    public SjbzDspProcessor getDspProcessor() {
        checkAndApplyGlobalBypass();
        return dspProcessor;
    }

    public boolean checkAndApplyGlobalBypass() {
        boolean globalActive = false;
        try {
            globalActive = GlobalAudioService.isGlobalAudioEnabled() ||
                           GlobalAudioService.Companion.isServiceRunning();
        } catch (Throwable t) {
            Log.w(TAG, "Error checking GlobalAudioService state: " + t.getMessage());
        }
        // FIX: nunca mutear el DSP unificado
        if (dspProcessor!= null) dspProcessor.setGlobalBypass(false);
        return globalActive;
    }

    public void process(float[] buffer, int channels, int frameCount) {
        if (buffer == null) return;
        dspProcessor.process(buffer, channels, frameCount);
    }
    public void processPcm16(byte[] pcmData, int offset, int length, int channels) {
        dspProcessor.processPcm16(pcmData, offset, length, channels);
    }
    public void processByteBuffer(ByteBuffer byteBuffer, int channels) {
        dspProcessor.processByteBuffer(byteBuffer, channels);
    }
    public void setSampleRate(float sampleRate) { dspProcessor.setSampleRate(sampleRate); }
    public void reset() { dspProcessor.resetFilterStates(); }
    public boolean isInitialized() { return initialized; }
}
