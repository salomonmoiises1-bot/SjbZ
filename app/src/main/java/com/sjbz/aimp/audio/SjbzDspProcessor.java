package com.sjbz.aimp.audio;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class SjbzDspProcessor {

    public static final int BAND_COUNT = 32;

    // ALINEADO 1:1 con GlobalAudioSessionManager.MY_FREQS para que no suene otra frecuencia
    public static final float[] BAND_FREQS = {
        16.0f, 20.0f, 25.0f, 31.0f, 40.0f, 50.0f, 63.0f, 80.0f, 100.0f,
        125.0f, 160.0f, 200.0f, 250.0f, 315.0f, 400.0f, 500.0f, 630.0f,
        800.0f, 1000.0f, 1250.0f, 1600.0f, 2000.0f, 2500.0f, 3150.0f, 4000.0f,
        5000.0f, 6300.0f, 8000.0f, 10000.0f, 12500.0f, 16000.0f, 20000.0f
    };

    public static final float[] ISO_FREQUENCIES = BAND_FREQS;

    public static final String[] BAND_LABELS = {
        "16", "20", "25", "31", "40", "50", "63", "80", "100",
        "125", "160", "200", "250", "315", "400", "500", "630",
        "800", "1k", "1.2k", "1.6k", "2k", "2.5k", "3.1k", "4k",
        "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k"
    };

    public interface FftListener { void onFftData(float[] samples); }
    public interface ClippingListener { void onClipping(boolean isClipping); }

    public static class Biquad {
        public float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
        public float a1 = 0.0f, a2 = 0.0f;
        private float x1_0 = 0.0f, x2_0 = 0.0f, y1_0 = 0.0f, y2_0 = 0.0f;
        private float x1_1 = 0.0f, x2_1 = 0.0f, y1_1 = 0.0f, y2_1 = 0.0f;

        public void reset() {
            x1_0 = x2_0 = y1_0 = y2_0 = 0.0f;
            x1_1 = x2_1 = y1_1 = y2_1 = 0.0f;
        }

        public float processChannel(float in, int ch) {
            float out;
            if (ch == 0) {
                out = b0 * in + b1 * x1_0 + b2 * x2_0 - a1 * y1_0 - a2 * y2_0;
                x2_0 = x1_0; x1_0 = in; y2_0 = y1_0; y1_0 = out;
            } else {
                out = b0 * in + b1 * x1_1 + b2 * x2_1 - a1 * y1_1 - a2 * y2_1;
                x2_1 = x1_1; x1_1 = in; y2_1 = y1_1; y1_1 = out;
            }
            if (Float.isNaN(out) || Float.isInfinite(out)) { reset(); return in; }
            return out;
        }

        public void setPeaking(float f0, float Fs, float gainDb, float Q) {
            if (Math.abs(gainDb) < 0.01f) { setBypass(); return; }
            f0 = Math.max(10.0f, Math.min(f0, Fs * 0.49f));
            Q = Math.max(0.1f, Math.min(Q, 10f));
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * f0 / Fs;
            double alpha = Math.sin(w0) / (2.0 * Q);
            double cosw0 = Math.cos(w0);
            double a0 = 1.0 + alpha / A;
            if (Math.abs(a0) < 1e-9) { setBypass(); return; }
            b0 = (float) ((1.0 + alpha * A) / a0);
            b1 = (float) ((-2.0 * cosw0) / a0);
            b2 = (float) ((1.0 - alpha * A) / a0);
            a1 = (float) ((-2.0 * cosw0) / a0);
            a2 = (float) ((1.0 - alpha / A) / a0);
        }

        // FIX CRITICO: LowShelf estable con parametro S, no Q, evita NaN en graves
        public void setLowShelf(float f0, float Fs, float gainDb, float S) {
            if (Math.abs(gainDb) < 0.01f) { setBypass(); return; }
            f0 = Math.max(10.0f, Math.min(f0, Fs * 0.49f));
            S = Math.max(0.1f, Math.min(S, 5f));
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * f0 / Fs;
            double cosw0 = Math.cos(w0);
            double sinw0 = Math.sin(w0);
            double alpha = sinw0 / 2.0 * Math.sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0);
            double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;
            double a0 = (A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAAlpha;
            if (Math.abs(a0) < 1e-9) { setBypass(); return; }
            b0 = (float) ((A * ((A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAAlpha)) / a0);
            b1 = (float) ((2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)) / a0);
            b2 = (float) ((A * ((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAAlpha)) / a0);
            a1 = (float) ((-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0);
            a2 = (float) (((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAAlpha) / a0);
        }

        public void setHighShelf(float f0, float Fs, float gainDb, float S) {
            if (Math.abs(gainDb) < 0.01f) { setBypass(); return; }
            f0 = Math.max(10.0f, Math.min(f0, Fs * 0.49f));
            S = Math.max(0.1f, Math.min(S, 5f));
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * f0 / Fs;
            double cosw0 = Math.cos(w0);
            double sinw0 = Math.sin(w0);
            double alpha = sinw0 / 2.0 * Math.sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0);
            double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;
            double a0 = (A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAAlpha;
            if (Math.abs(a0) < 1e-9) { setBypass(); return; }
            b0 = (float) ((A * ((A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAAlpha)) / a0);
            b1 = (float) ((-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0);
            b2 = (float) ((A * ((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAAlpha)) / a0);
            a1 = (float) ((2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0);
            a2 = (float) (((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAAlpha) / a0);
        }

        public void setLowPass(float f0, float Fs, float Q) {
            f0 = Math.max(10.0f, Math.min(f0, Fs * 0.49f));
            Q = Math.max(0.1f, Q);
            double w0 = 2.0 * Math.PI * f0 / Fs;
            double cosw0 = Math.cos(w0);
            double sinw0 = Math.sin(w0);
            double alpha = sinw0 / (2.0 * Q);
            double a0 = 1.0 + alpha;
            if (Math.abs(a0) < 1e-9) { setBypass(); return; }
            b0 = (float) (((1.0 - cosw0) / 2.0) / a0);
            b1 = (float) ((1.0 - cosw0) / a0);
            b2 = (float) (((1.0 - cosw0) / 2.0) / a0);
            a1 = (float) ((-2.0 * cosw0) / a0);
            a2 = (float) ((1.0 - alpha) / a0);
        }

        public void setBypass() { b0 = 1.0f; b1 = 0.0f; b2 = 0.0f; a1 = 0.0f; a2 = 0.0f; }
    }

    private float sampleRate = 48000.0f;
    private volatile boolean masterEnabled = true;
    private volatile boolean globalBypass = false;
    private volatile float preGainDb = 0.0f;
    private volatile float linearEffectivePreamp = 1.0f;
    private volatile boolean bassBoostEnabled = true;
    private volatile float bassBoostFreq = 85.0f;
    private volatile float bassBoostGainDb = 4.0f;
    private final Biquad bassBoostBiquad = new Biquad();
    private volatile boolean virtualizerEnabled = false;
    private volatile float virtualizerStrength = 0.0f;
    private final Biquad crossfeedLowPassL = new Biquad();
    private final Biquad crossfeedLowPassR = new Biquad();
    private final float[] crossfeedDelayL = new float[32];
    private final float[] crossfeedDelayR = new float[32];
    private int crossfeedWritePos = 0;
    private final float[] bandGainsDb = new float[BAND_COUNT];
    private final Biquad[] eqBiquads = new Biquad[BAND_COUNT];
    private volatile float toneBassDb = 0.0f, toneMidDb = 0.0f, toneTrebleDb = 0.0f;
    private final Biquad toneBassFilter = new Biquad(), toneMidFilter = new Biquad(), toneTrebleFilter = new Biquad();
    private volatile boolean mdrcEnabled = true;
    private volatile float mdrcThresholdDb = -18.0f, mdrcRatio = 2.5f;
    private final float[] mdrcBandGainsDb = new float[5];
    private volatile float mdrcGainReduction = 0.0f;
    private float mdrcEnvelope = 0.0f;
    private volatile boolean emulationEnabled = true;
    private volatile float emulationAmount = 0.8f;
    private volatile boolean bluetoothAutoBypass = false;
    private final Biquad emuLowBiquad = new Biquad(), emuMidDipBiquad = new Biquad(), emuPresenceBiquad = new Biquad(), emuAirBiquad = new Biquad();
    private volatile float masterGainDb = 0.0f, masterGainLin = 1.0f;
    private volatile boolean limiterEnabled = true;
    private volatile float limiterThresholdDb = -1.0f;
    private float limiterThresholdLin = 0.89125f, limiterEnvelope = 0.0f;
    private final float limiterAttackCoeff, limiterReleaseCoeff;
    private FftListener fftListener; private ClippingListener clippingListener;
    private final float[] visualizerBuffer = new float[256]; private int visualizerIndex = 0;

    public SjbzDspProcessor() { this(48000.0f); }
    public SjbzDspProcessor(float sampleRate) {
        this.sampleRate = (sampleRate > 8000.0f)? sampleRate : 48000.0f;
        this.limiterAttackCoeff = (float) Math.exp(-1.0 / (0.001 * this.sampleRate));
        this.limiterReleaseCoeff = (float) Math.exp(-1.0 / (0.080 * this.sampleRate));
        for (int i = 0; i < BAND_COUNT; i++) { eqBiquads[i] = new Biquad(); bandGainsDb[i] = 0.0f; updateEqBandFilter(i); }
        updateToneFilters(); updateBassBoostFilter();
        crossfeedLowPassL.setLowPass(700.0f, this.sampleRate, 0.707f);
        crossfeedLowPassR.setLowPass(700.0f, this.sampleRate, 0.707f);
        updateEmulationFilters(); updateAutoHeadroom();
    }

    public boolean isMasterEnabled() { return masterEnabled; }
    public synchronized void setMasterEnabled(boolean enabled) { this.masterEnabled = enabled; }
    public boolean isGlobalBypass() { return globalBypass; }
    public synchronized void setGlobalBypass(boolean bypass) { this.globalBypass = bypass; }
    public float getSampleRate() { return sampleRate; }
    public synchronized void setSampleRate(float rate) {
        if (rate > 8000.0f && Math.abs(rate - this.sampleRate) > 1.0f) {
            this.sampleRate = rate; for (int i = 0; i < BAND_COUNT; i++) updateEqBandFilter(i);
            updateToneFilters(); updateBassBoostFilter();
            crossfeedLowPassL.setLowPass(700.0f, this.sampleRate, 0.707f);
            crossfeedLowPassR.setLowPass(700.0f, this.sampleRate, 0.707f);
            updateEmulationFilters(); resetFilterStates();
        }
    }

    public float getPreGain() { return preGainDb; }
    public float getPreamp() { return preGainDb; }
    public synchronized void setPreGain(float db) { this.preGainDb = Math.max(-12.0f, Math.min(12.0f, db)); updateAutoHeadroom(); }
    public synchronized void setPreamp(float db) { setPreGain(db); }
    public float getEffectivePreampDb() { return preGainDb; }
    public synchronized void updateAutoHeadroom() {
        float maxEqBoost = 0.0f; for (float g : bandGainsDb) if (g > maxEqBoost) maxEqBoost = g;
        float bassPenalty = (bassBoostEnabled? (bassBoostGainDb * 0.5f) : 0.0f);
        float tonePenalty = Math.max(0.0f, toneBassDb * 0.4f) + Math.max(0.0f, toneMidDb * 0.3f) + Math.max(0.0f, toneTrebleDb * 0.3f);
        float totalBoost = Math.max(0.0f, maxEqBoost) + bassPenalty + tonePenalty;
        float headroomReduction = totalBoost * 0.4f;
        float effective = Math.max(-24.0f, Math.min(12.0f, preGainDb - headroomReduction));
        this.linearEffectivePreamp = (float) Math.pow(10.0, effective / 20.0);
    }

    public boolean isBassBoostEnabled() { return bassBoostEnabled; }
    public float getBassBoostFreq() { return bassBoostFreq; }
    public float getBassBoostGain() { return bassBoostGainDb; }
    public synchronized void setBassBoost(boolean enabled, float freqHz, float gainDb) {
        this.bassBoostEnabled = enabled; this.bassBoostFreq = Math.max(20f, Math.min(freqHz, 250f));
        this.bassBoostGainDb = Math.max(0.0f, Math.min(12.0f, gainDb)); updateBassBoostFilter(); updateAutoHeadroom();
    }
    private void updateBassBoostFilter() {
        if (bassBoostEnabled && bassBoostGainDb > 0.05f) bassBoostBiquad.setLowShelf(bassBoostFreq, sampleRate, bassBoostGainDb, 0.9f);
        else bassBoostBiquad.setBypass();
    }

    public boolean isVirtualizerEnabled() { return virtualizerEnabled; }
    public float getVirtualizerStrength() { return virtualizerStrength; }
    public synchronized void setVirtualizer(boolean enabled, float strength) { this.virtualizerEnabled = enabled; this.virtualizerStrength = Math.max(0.0f, Math.min(1.0f, strength)); }

    public float getBandGain(int bandIndex) { if (bandIndex >= 0 && bandIndex < BAND_COUNT) return bandGainsDb[bandIndex]; return 0.0f; }
    public synchronized void setBandLevel(int bandIndex, float gainDb) {
        if (bandIndex >= 0 && bandIndex < BAND_COUNT) { bandGainsDb[bandIndex] = Math.max(-12.0f, Math.min(12.0f, gainDb)); updateEqBandFilter(bandIndex); updateAutoHeadroom(); }
    }
    public synchronized void setBandGain(int bandIndex, float gainDb) { setBandLevel(bandIndex, gainDb); }
    public float[] getBandGains() { return Arrays.copyOf(bandGainsDb, BAND_COUNT); }
    private void updateEqBandFilter(int bandIndex) { eqBiquads[bandIndex].setPeaking(BAND_FREQS[bandIndex], sampleRate, bandGainsDb[bandIndex], 1.414f); }

    public float getToneBass() { return toneBassDb; }
    public float getToneMid() { return toneMidDb; }
    public float getToneTreble() { return toneTrebleDb; }
    public synchronized void setToneBass(float gainDb) { this.toneBassDb = Math.max(-12.0f, Math.min(12.0f, gainDb)); toneBassFilter.setLowShelf(200.0f, sampleRate, toneBassDb, 0.9f); updateAutoHeadroom(); }
    public synchronized void setToneMid(float gainDb) { this.toneMidDb = Math.max(-12.0f, Math.min(12.0f, gainDb)); toneMidFilter.setPeaking(1000.0f, sampleRate, toneMidDb, 1.0f); updateAutoHeadroom(); }
    public synchronized void setToneTreble(float gainDb) { this.toneTrebleDb = Math.max(-12.0f, Math.min(12.0f, gainDb)); toneTrebleFilter.setHighShelf(6000.0f, sampleRate, toneTrebleDb, 0.9f); updateAutoHeadroom(); }

    public boolean isMdrcEnabled() { return mdrcEnabled; }
    public synchronized void setMdrcEnabled(boolean enabled) { this.mdrcEnabled = enabled; }
    public synchronized void setMdrcDynamics(float thresholdDb, float ratio) { this.mdrcThresholdDb = thresholdDb; this.mdrcRatio = Math.max(1.0f, ratio); }
    public synchronized void setMdrcBandGain(int bandIndex, float gainDb) { if (bandIndex >= 0 && bandIndex < 5) mdrcBandGainsDb[bandIndex] = gainDb; }
    public float getMdrcBandGain(int bandIndex) { if (bandIndex >= 0 && bandIndex < 5) return mdrcBandGainsDb[bandIndex]; return 0.0f; }

    public boolean isEmulationEnabled() { return emulationEnabled; }
    public float getEmulationAmount() { return emulationAmount; }
    public boolean isBluetoothAutoBypass() { return bluetoothAutoBypass; }
    public synchronized void setEmulationEnabled(boolean enabled) { this.emulationEnabled = enabled; updateEmulationFilters(); updateAutoHeadroom(); }
    public synchronized void setEmulationAmount(float amount) { this.emulationAmount = Math.max(0.0f, Math.min(1.0f, amount)); updateEmulationFilters(); updateAutoHeadroom(); }
    public synchronized void setBluetoothAutoBypass(boolean bypass) { this.bluetoothAutoBypass = bypass; updateAutoHeadroom(); }
    private void updateEmulationFilters() {
        if (emulationEnabled &&!bluetoothAutoBypass) {
            float amt = emulationAmount;
            emuLowBiquad.setLowShelf(110.0f, sampleRate, 3.5f * amt, 0.9f);
            emuMidDipBiquad.setPeaking(650.0f, sampleRate, -1.8f * amt, 1.5f);
            emuPresenceBiquad.setPeaking(3800.0f, sampleRate, 2.2f * amt, 1.2f);
            emuAirBiquad.setHighShelf(11500.0f, sampleRate, 1.8f * amt, 0.9f);
        } else { emuLowBiquad.setBypass(); emuMidDipBiquad.setBypass(); emuPresenceBiquad.setBypass(); emuAirBiquad.setBypass(); }
    }

    public float getMasterGain() { return masterGainDb; }
    public synchronized void setMasterGain(float gainDb) { this.masterGainDb = gainDb; this.masterGainLin = (float) Math.pow(10.0, gainDb / 20.0); }
    public synchronized void setLimiter(boolean enabled, float thresholdDb) { this.limiterEnabled = enabled; this.limiterThresholdDb = Math.min(-0.1f, thresholdDb); this.limiterThresholdLin = (float) Math.pow(10.0, this.limiterThresholdDb / 20.0); }
    public void setFftListener(FftListener listener) { this.fftListener = listener; }
    public void setClippingListener(ClippingListener listener) { this.clippingListener = listener; }

    public synchronized void resetFilterStates() {
        toneBassFilter.reset(); toneMidFilter.reset(); toneTrebleFilter.reset();
        for (int i = 0; i < BAND_COUNT; i++) eqBiquads[i].reset();
        bassBoostBiquad.reset(); crossfeedLowPassL.reset(); crossfeedLowPassR.reset();
        Arrays.fill(crossfeedDelayL, 0.0f); Arrays.fill(crossfeedDelayR, 0.0f); crossfeedWritePos = 0;
        emuLowBiquad.reset(); emuMidDipBiquad.reset(); emuPresenceBiquad.reset(); emuAirBiquad.reset();
        limiterEnvelope = 0.0f; mdrcEnvelope = 0.0f;
    }

    private void updateToneFilters() {
        toneBassFilter.setLowShelf(200.0f, sampleRate, toneBassDb, 0.9f);
        toneMidFilter.setPeaking(1000.0f, sampleRate, toneMidDb, 1.0f);
        toneTrebleFilter.setHighShelf(6000.0f, sampleRate, toneTrebleDb, 0.9f);
    }

    // FUNCIONAL Y SEGURO: synchronized evita crackle cuando cambias banda en UI
    public synchronized void process(float[] buffer, int channels, int frameCount) {
        if (!masterEnabled || globalBypass || buffer == null || frameCount <= 0 || channels <= 0) return;
        boolean clippingDetected = false;
        final float linEffectivePreamp = this.linearEffectivePreamp;
        final boolean runBassBoost = this.bassBoostEnabled;
        final boolean runVirt = (this.virtualizerEnabled && channels >= 2 && this.virtualizerStrength > 0.01f);
        final float virtAmount = this.virtualizerStrength * 0.35f;
        final boolean runEmu = (this.emulationEnabled &&!this.bluetoothAutoBypass);
        final boolean runMdrc = this.mdrcEnabled;
        final float mdrcThreshLin = (float) Math.pow(10.0, mdrcThresholdDb / 20.0);
        final float linMasterGain = this.masterGainLin;
        final boolean runLimiter = this.limiterEnabled;
        final float limitThresh = this.limiterThresholdLin;
        final int delayLength = crossfeedDelayL.length;
        int writePos = this.crossfeedWritePos;
        int totalSamples = frameCount * channels;
        for (int i = 0; i < totalSamples; i += channels) {
            float s0 = buffer[i]; float s1 = (channels > 1)? buffer[i + 1] : s0;
            if (Float.isNaN(s0) || Float.isInfinite(s0)) s0 = 0.0f;
            if (Float.isNaN(s1) || Float.isInfinite(s1)) s1 = 0.0f;
            s0 *= linEffectivePreamp; s1 *= linEffectivePreamp;
            if (runBassBoost) { s0 = bassBoostBiquad.processChannel(s0, 0); if (channels > 1) s1 = bassBoostBiquad.processChannel(s1, 1); }
            if (runVirt) {
                crossfeedDelayL[writePos] = s0; crossfeedDelayR[writePos] = s1;
                int readPos = (writePos + 1) % delayLength;
                float delayedL = crossfeedDelayL[readPos]; float delayedR = crossfeedDelayR[readPos];
                writePos = (writePos + 1) % delayLength;
                float xfeedFromR = crossfeedLowPassR.processChannel(delayedR, 0);
                float xfeedFromL = crossfeedLowPassL.processChannel(delayedL, 1);
                s0 = s0 * (1.0f - virtAmount * 0.2f) + xfeedFromR * virtAmount;
                s1 = s1 * (1.0f - virtAmount * 0.2f) + xfeedFromL * virtAmount;
            }
            for (int b = 0; b < BAND_COUNT; b++) { s0 = eqBiquads[b].processChannel(s0, 0); if (channels > 1) s1 = eqBiquads[b].processChannel(s1, 1); }
            s0 = toneBassFilter.processChannel(s0, 0); s0 = toneMidFilter.processChannel(s0, 0); s0 = toneTrebleFilter.processChannel(s0, 0);
            if (channels > 1) { s1 = toneBassFilter.processChannel(s1, 1); s1 = toneMidFilter.processChannel(s1, 1); s1 = toneTrebleFilter.processChannel(s1, 1); }
            if (runEmu) {
                s0 = emuLowBiquad.processChannel(s0, 0); s0 = emuMidDipBiquad.processChannel(s0, 0); s0 = emuPresenceBiquad.processChannel(s0, 0); s0 = emuAirBiquad.processChannel(s0, 0);
                if (s0 > 0.5f) s0 = 0.5f + (float) Math.tanh(s0 - 0.5f) * 0.5f; else if (s0 < -0.5f) s0 = -0.5f + (float) Math.tanh(s0 + 0.5f) * 0.5f;
                if (channels > 1) {
                    s1 = emuLowBiquad.processChannel(s1, 1); s1 = emuMidDipBiquad.processChannel(s1, 1); s1 = emuPresenceBiquad.processChannel(s1, 1); s1 = emuAirBiquad.processChannel(s1, 1);
                    if (s1 > 0.5f) s1 = 0.5f + (float) Math.tanh(s1 - 0.5f) * 0.5f; else if (s1 < -0.5f) s1 = -0.5f + (float) Math.tanh(s1 + 0.5f) * 0.5f;
                }
            }
            if (runMdrc) {
                float peak = Math.max(Math.abs(s0), Math.abs(s1));
                if (peak > mdrcEnvelope) mdrcEnvelope = peak; else mdrcEnvelope = mdrcEnvelope * 0.999f + peak * 0.001f;
                if (mdrcEnvelope > mdrcThreshLin) { float overDb = 20.0f * (float) Math.log10(mdrcEnvelope / mdrcThreshLin); float targetReductionDb = overDb * (1.0f - 1.0f / mdrcRatio); float compGain = (float) Math.pow(10.0, -targetReductionDb / 20.0); s0 *= compGain; s1 *= compGain; }
            }
            s0 *= linMasterGain; s1 *= linMasterGain;
            if (runLimiter) {
                float peak = Math.max(Math.abs(s0), Math.abs(s1));
                if (peak > limiterEnvelope) limiterEnvelope = peak + limiterAttackCoeff * (limiterEnvelope - peak); else limiterEnvelope = peak + limiterReleaseCoeff * (limiterEnvelope - peak);
                if (limiterEnvelope > limitThresh) { float reductionGain = limitThresh / limiterEnvelope; s0 *= reductionGain; s1 *= reductionGain; clippingDetected = true; }
                if (s0 > limitThresh) { float excess = s0 - limitThresh; s0 = limitThresh + (float) Math.tanh(excess * 2.0f) * (1.0f - limitThresh); clippingDetected = true; }
                else if (s0 < -limitThresh) { float excess = -s0 - limitThresh; s0 = -limitThresh - (float) Math.tanh(excess * 2.0f) * (1.0f - limitThresh); clippingDetected = true; }
                if (channels > 1) {
                    if (s1 > limitThresh) { float excess = s1 - limitThresh; s1 = limitThresh + (float) Math.tanh(excess * 2.0f) * (1.0f - limitThresh); clippingDetected = true; }
                    else if (s1 < -limitThresh) { float excess = -s1 - limitThresh; s1 = -limitThresh - (float) Math.tanh(excess * 2.0f) * (1.0f - limitThresh); clippingDetected = true; }
                }
            }
            if (s0 > 1.0f) { s0 = 1.0f; clippingDetected = true; } else if (s0 < -1.0f) { s0 = -1.0f; clippingDetected = true; }
            if (s1 > 1.0f) { s1 = 1.0f; clippingDetected = true; } else if (s1 < -1.0f) { s1 = -1.0f; clippingDetected = true; }
            if (Float.isNaN(s0) || Float.isInfinite(s0)) s0 = 0.0f;
            if (Float.isNaN(s1) || Float.isInfinite(s1)) s1 = 0.0f;
            buffer[i] = s0; if (channels > 1) buffer[i + 1] = s1;
            if (fftListener!= null) { visualizerBuffer[visualizerIndex++] = s0; if (visualizerIndex >= visualizerBuffer.length) { visualizerIndex = 0; fftListener.onFftData(visualizerBuffer.clone()); } }
        }
        this.crossfeedWritePos = writePos;
        if (clippingListener!= null && clippingDetected) clippingListener.onClipping(true);
    }

    public synchronized void processPcm16Short(short[] buffer, int offset, int length, int channels) {
        if (!masterEnabled || globalBypass || buffer == null || length <= 0) return;
        int frameCount = length / channels; float[] floatBuffer = new float[length];
        for (int i = 0; i < length; i++) floatBuffer[i] = buffer[offset + i] / 32768.0f;
        process(floatBuffer, channels, frameCount);
        for (int i = 0; i < length; i++) { float f = Math.max(-1.0f, Math.min(1.0f, floatBuffer[i])); buffer[offset + i] = (short) Math.round(f * 32767.0f); }
    }

    public synchronized void processPcm16(byte[] pcmData, int offset, int length, int channels) {
        if (!masterEnabled || globalBypass || pcmData == null || length <= 0) return;
        int sampleCount = length / 2; int frameCount = sampleCount / channels; float[] floatBuffer = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) { int byteIdx = offset + (i * 2); short val = (short) ((pcmData[byteIdx] & 0xFF) | (pcmData[byteIdx + 1] << 8)); floatBuffer[i] = val / 32768.0f; }
        process(floatBuffer, channels, frameCount);
        for (int i = 0; i < sampleCount; i++) { int byteIdx = offset + (i * 2); float f = Math.max(-1.0f, Math.min(1.0f, floatBuffer[i])); short shortVal = (short) Math.round(f * 32767.0f); pcmData[byteIdx] = (byte) (shortVal & 0xFF); pcmData[byteIdx + 1] = (byte) ((shortVal >> 8) & 0xFF); }
    }

    public synchronized void processByteBuffer(ByteBuffer byteBuffer, int channels) {
        if (!masterEnabled || globalBypass || byteBuffer == null ||!byteBuffer.hasRemaining()) return;
        int remaining = byteBuffer.remaining(); byte[] temp = new byte[remaining]; int pos = byteBuffer.position(); byteBuffer.get(temp);
        processPcm16(temp, 0, remaining, channels); byteBuffer.position(pos); byteBuffer.put(temp);
    }
}
