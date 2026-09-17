package com.sjbz.aimp.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * SjbzDspProcessor - Professional 100% Software 32-Band ISO Audio DSP Engine.
 *
 * Implements:
 * 1. RBJ Audio EQ Cookbook 32 Peaking Biquad Filters (Q = 1.414)
 *    Exact 1:1 Mapping with BAND_FREQS:
 *    [20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
 *     800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000,
 *     12500, 14000, 16000, 20000] Hz.
 * 2. Independent Pre-Gains (Pre-EQ Stage):
 *    - Bass: Low-Shelf 200 Hz (-12 dB to +12 dB)
 *    - Mid: Peaking 1000 Hz, Q = 1.0 (-12 dB to +12 dB)
 *    - Treble: High-Shelf 6000 Hz (-12 dB to +12 dB)
 * 3. Mandatory Processing Chain:
 *    Input -> Pre-Gains -> EQ 32 Bandas -> BassBoost -> EMU ATS2835P (4 biquads) -> MDRC -> Limiter Final (-1.0 dBFS).
 * 4. Auto-Headroom Engine:
 *    preampEfectivo = preampDb - (bassBoostDb * 0.7f) - (emuLevel * 4.0f);
 * 5. Final Limiter at -1.0 dBFS with NaN / Infinity zero-fault guard.
 * 6. Global Bypass when system-wide GlobalAudioService is enabled.
 */
public class SjbzDspProcessor {

    public static final int BAND_COUNT = 32;

    public static final float[] BAND_FREQS = {
        20.0f, 25.0f, 31.0f, 40.0f, 50.0f, 63.0f, 80.0f, 100.0f,
        125.0f, 160.0f, 200.0f, 250.0f, 315.0f, 400.0f, 500.0f, 630.0f,
        800.0f, 1000.0f, 1250.0f, 1600.0f, 2000.0f, 2500.0f, 3150.0f, 4000.0f,
        5000.0f, 6300.0f, 8000.0f, 10000.0f, 12500.0f, 14000.0f, 16000.0f, 20000.0f
    };

    public static final float[] ISO_FREQUENCIES = BAND_FREQS;

    public static final String[] BAND_LABELS = {
        "20", "25", "31", "40", "50", "63", "80", "100",
        "125", "160", "200", "250", "315", "400", "500", "630",
        "800", "1k", "1.2k", "1.6k", "2k", "2.5k", "3.1k", "4k",
        "5k", "6.3k", "8k", "10k", "12.5k", "14k", "16k", "20k"
    };

    public interface FftListener {
        void onFftData(float[] samples);
    }

    public interface ClippingListener {
        void onClipping(boolean isClipping);
    }

    private static class Biquad {
        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
        float a1 = 0.0f, a2 = 0.0f;

        // Channel 0 (Left/Mono) state
        float x1_0 = 0.0f, x2_0 = 0.0f;
        float y1_0 = 0.0f, y2_0 = 0.0f;

        // Channel 1 (Right) state
        float x1_1 = 0.0f, x2_1 = 0.0f;
        float y1_1 = 0.0f, y2_1 = 0.0f;

        void reset() {
            x1_0 = 0.0f; x2_0 = 0.0f; y1_0 = 0.0f; y2_0 = 0.0f;
            x1_1 = 0.0f; x2_1 = 0.0f; y1_1 = 0.0f; y2_1 = 0.0f;
        }

        void checkSanity() {
            if (Float.isNaN(y1_0) || Float.isInfinite(y1_0) || Float.isNaN(y2_0) || Float.isInfinite(y2_0)) {
                reset();
            }
            if (Float.isNaN(y1_1) || Float.isInfinite(y1_1) || Float.isNaN(y2_1) || Float.isInfinite(y2_1)) {
                reset();
            }
        }

        float processChannel(float in, int ch) {
            float out;
            if (ch == 0) {
                out = b0 * in + b1 * x1_0 + b2 * x2_0 - a1 * y1_0 - a2 * y2_0;
                x2_0 = x1_0;
                x1_0 = in;
                y2_0 = y1_0;
                y1_0 = out;
            } else {
                out = b0 * in + b1 * x1_1 + b2 * x2_1 - a1 * y1_1 - a2 * y2_1;
                x2_1 = x1_1;
                x1_1 = in;
                y2_1 = y1_1;
                y1_1 = out;
            }
            return out;
        }

        void setPeaking(float f0, float Fs, float gainDb, float Q) {
            if (Math.abs(gainDb) < 0.001f) {
                setBypass();
                return;
            }
            f0 = Math.max(10.0f, Math.min(f0, Fs * 0.49f));
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * f0 / Fs;
            double alpha = Math.sin(w0) / (2.0 * Math.max(0.1, (double) Q));
            double cosw0 = Math.cos(w0);

            double a0 = 1.0 + alpha / A;
            this.b0 = (float) ((1.0 + alpha * A) / a0);
            this.b1 = (float) ((-2.0 * cosw0) / a0);
            this.b2 = (float) ((1.0 - alpha * A) / a0);
            this.a1 = (float) ((-2.0 * cosw0) / a0);
            this.a2 = (float) ((1.0 - alpha / A) / a0);
        }

        void setLowShelf(float f0, float Fs, float gainDb, float Q) {
            if (Math.abs(gainDb) < 0.001f) {
                setBypass();
                return;
            }
            f0 = Math.max(10.0f, Math.min(f0, Fs * 0.49f));
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * f0 / Fs;
            double cosw0 = Math.cos(w0);
            double sinw0 = Math.sin(w0);
            double alpha = sinw0 / (2.0 * Math.max(0.1, (double) Q));
            double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

            double a0 = (A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAAlpha;
            this.b0 = (float) ((A * ((A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAAlpha)) / a0);
            this.b1 = (float) ((2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0)) / a0);
            this.b2 = (float) ((A * ((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAAlpha)) / a0);
            this.a1 = (float) ((-2.0 * ((A - 1.0) + (A + 1.0) * cosw0)) / a0);
            this.a2 = (float) (((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAAlpha) / a0);
        }

        void setHighShelf(float f0, float Fs, float gainDb, float Q) {
            if (Math.abs(gainDb) < 0.001f) {
                setBypass();
                return;
            }
            f0 = Math.max(10.0f, Math.min(f0, Fs * 0.49f));
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * f0 / Fs;
            double cosw0 = Math.cos(w0);
            double sinw0 = Math.sin(w0);
            double alpha = sinw0 / (2.0 * Math.max(0.1, (double) Q));
            double twoSqrtAAlpha = 2.0 * Math.sqrt(A) * alpha;

            double a0 = (A + 1.0) - (A - 1.0) * cosw0 + twoSqrtAAlpha;
            this.b0 = (float) ((A * ((A + 1.0) + (A - 1.0) * cosw0 + twoSqrtAAlpha)) / a0);
            this.b1 = (float) ((-2.0 * A * ((A - 1.0) + (A + 1.0) * cosw0)) / a0);
            this.b2 = (float) ((A * ((A + 1.0) + (A - 1.0) * cosw0 - twoSqrtAAlpha)) / a0);
            this.a1 = (float) ((2.0 * ((A - 1.0) - (A + 1.0) * cosw0)) / a0);
            this.a2 = (float) (((A + 1.0) - (A - 1.0) * cosw0 - twoSqrtAAlpha) / a0);
        }

        void setBypass() {
            b0 = 1.0f; b1 = 0.0f; b2 = 0.0f;
            a1 = 0.0f; a2 = 0.0f;
        }
    }

    private float sampleRate = 48000.0f;
    private volatile boolean masterEnabled = true;
    private volatile boolean globalBypass = false;

    // Pre-Gains (Input Stage)
    private float preGainBassDb = 0.0f;
    private float preGainMidDb = 0.0f;
    private float preGainTrebleDb = 0.0f;
    private final Biquad preGainBassFilter = new Biquad();
    private final Biquad preGainMidFilter = new Biquad();
    private final Biquad preGainTrebleFilter = new Biquad();

    // 32-Band EQ
    private final float[] bandGainsDb = new float[BAND_COUNT];
    private final Biquad[] eqBiquads = new Biquad[BAND_COUNT];

    // BassBoost
    private volatile boolean bassBoostEnabled = true;
    private float bassBoostFreq = 85.0f;
    private float bassBoostGainDb = 4.0f;
    private final Biquad bassBoostBiquad = new Biquad();

    // ATS2835P Hardware Emulation (4 Biquads)
    private volatile boolean emulationEnabled = true;
    private float emulationAmount = 0.8f;
    private volatile boolean bluetoothAutoBypass = false;
    private final Biquad emuLowBiquad = new Biquad();
    private final Biquad emuMidDipBiquad = new Biquad();
    private final Biquad emuPresenceBiquad = new Biquad();
    private final Biquad emuAirBiquad = new Biquad();

    // MDRC Dynamic Compression
    private volatile boolean mdrcEnabled = true;
    private float mdrcThresholdDb = -18.0f;
    private float mdrcRatio = 2.5f;
    private final float[] mdrcBandGainsDb = new float[5];
    private final Biquad[] mdrcCrossoverBiquads = new Biquad[4];
    private float mdrcGainReduction = 0.0f;
    private float mdrcEnvelope = 0.0f;

    // Master Preamp & Auto-Headroom
    private float preampDb = 0.0f;
    private float effectivePreampDb = 0.0f;
    private float linearEffectivePreamp = 1.0f;

    // Limiter State
    private float limiterEnvelope = 0.0f;
    private static final float LIMITER_THRESHOLD = 0.89125f; // -1.0 dBFS
    private final float limiterAttackCoeff;
    private final float limiterReleaseCoeff;

    // Listeners
    private FftListener fftListener;
    private ClippingListener clippingListener;
    private final float[] visualizerBuffer = new float[256];
    private int visualizerIndex = 0;

    public SjbzDspProcessor() {
        this(48000.0f);
    }

    public SjbzDspProcessor(float sampleRate) {
        this.sampleRate = (sampleRate > 8000.0f) ? sampleRate : 48000.0f;

        // Limiter coefficients: 2ms attack, 100ms release
        this.limiterAttackCoeff = (float) Math.exp(-1.0 / (0.002 * this.sampleRate));
        this.limiterReleaseCoeff = (float) Math.exp(-1.0 / (0.100 * this.sampleRate));

        // Initialize EQ 32 Biquads
        for (int i = 0; i < BAND_COUNT; i++) {
            eqBiquads[i] = new Biquad();
            bandGainsDb[i] = 0.0f;
            updateEqBandFilter(i);
        }

        // Initialize Pre-Gain Filters
        updatePreGainFilters();

        // Initialize BassBoost
        updateBassBoostFilter();

        // Initialize EMU ATS2835P Filters
        updateEmulationFilters();

        // Initialize MDRC Filters
        for (int i = 0; i < 4; i++) {
            mdrcCrossoverBiquads[i] = new Biquad();
        }
        updateMdrcCrossovers();

        // Calculate initial auto-headroom
        updateAutoHeadroom();
    }

    public synchronized void setSampleRate(float sampleRate) {
        if (sampleRate > 8000.0f && Math.abs(this.sampleRate - sampleRate) > 1.0f) {
            this.sampleRate = sampleRate;
            for (int i = 0; i < BAND_COUNT; i++) {
                updateEqBandFilter(i);
            }
            updatePreGainFilters();
            updateBassBoostFilter();
            updateEmulationFilters();
            updateMdrcCrossovers();
            resetFilterStates();
        }
    }

    public void setFftListener(FftListener listener) {
        this.fftListener = listener;
    }

    public void setClippingListener(ClippingListener listener) {
        this.clippingListener = listener;
    }

    public boolean isMasterEnabled() {
        return masterEnabled;
    }

    public void setMasterEnabled(boolean enabled) {
        this.masterEnabled = enabled;
    }

    public boolean isGlobalBypass() {
        return globalBypass;
    }

    public void setGlobalBypass(boolean bypass) {
        this.globalBypass = bypass;
    }

    // Preamp & Auto-Headroom
    public float getPreamp() {
        return preampDb;
    }

    public synchronized void setPreamp(float db) {
        this.preampDb = Math.max(-12.0f, Math.min(12.0f, db));
        updateAutoHeadroom();
    }

    public float getEffectivePreampDb() {
        return effectivePreampDb;
    }

    private void updateAutoHeadroom() {
        float bbPenalty = bassBoostEnabled ? (bassBoostGainDb * 0.7f) : 0.0f;
        float emuPenalty = (emulationEnabled && !bluetoothAutoBypass) ? (emulationAmount * 4.0f) : 0.0f;
        this.effectivePreampDb = this.preampDb - bbPenalty - emuPenalty;
        this.linearEffectivePreamp = (float) Math.pow(10.0, effectivePreampDb / 20.0);
    }

    // Pre-Gains
    public float getBassGain() { return preGainBassDb; }
    public synchronized void setBassGain(float db) {
        this.preGainBassDb = Math.max(-12.0f, Math.min(12.0f, db));
        preGainBassFilter.setLowShelf(200.0f, sampleRate, preGainBassDb, 0.707f);
    }

    public float getMidGain() { return preGainMidDb; }
    public synchronized void setMidGain(float db) {
        this.preGainMidDb = Math.max(-12.0f, Math.min(12.0f, db));
        preGainMidFilter.setPeaking(1000.0f, sampleRate, preGainMidDb, 1.0f);
    }

    public float getTrebleGain() { return preGainTrebleDb; }
    public synchronized void setTrebleGain(float db) {
        this.preGainTrebleDb = Math.max(-12.0f, Math.min(12.0f, db));
        preGainTrebleFilter.setHighShelf(6000.0f, sampleRate, preGainTrebleDb, 0.707f);
    }

    private void updatePreGainFilters() {
        setBassGain(preGainBassDb);
        setMidGain(preGainMidDb);
        setTrebleGain(preGainTrebleDb);
    }

    // 32-Band EQ (1:1 mapped)
    public float getBandGain(int bandIndex) {
        if (bandIndex >= 0 && bandIndex < BAND_COUNT) {
            return bandGainsDb[bandIndex];
        }
        return 0.0f;
    }

    public synchronized void setBandLevel(int bandIndex, float gainDb) {
        if (bandIndex >= 0 && bandIndex < BAND_COUNT) {
            bandGainsDb[bandIndex] = Math.max(-12.0f, Math.min(12.0f, gainDb));
            updateEqBandFilter(bandIndex);
        }
    }

    private void updateEqBandFilter(int bandIndex) {
        float f0 = BAND_FREQS[bandIndex];
        float gain = bandGainsDb[bandIndex];
        eqBiquads[bandIndex].setPeaking(f0, sampleRate, gain, 1.414f);
    }

    // BassBoost
    public boolean isBassBoostEnabled() { return bassBoostEnabled; }
    public float getBassBoostFreq() { return bassBoostFreq; }
    public float getBassBoostGain() { return bassBoostGainDb; }

    public synchronized void setBassBoost(boolean enabled, float freqHz, float gainDb) {
        this.bassBoostEnabled = enabled;
        this.bassBoostFreq = freqHz;
        this.bassBoostGainDb = Math.max(0.0f, Math.min(12.0f, gainDb));
        updateBassBoostFilter();
        updateAutoHeadroom();
    }

    private void updateBassBoostFilter() {
        if (bassBoostEnabled && bassBoostGainDb > 0.05f) {
            bassBoostBiquad.setPeaking(bassBoostFreq, sampleRate, bassBoostGainDb, 1.1f);
        } else {
            bassBoostBiquad.setBypass();
        }
    }

    // EMU ATS2835P
    public boolean isEmulationEnabled() { return emulationEnabled; }
    public float getEmulationAmount() { return emulationAmount; }
    public boolean isBluetoothAutoBypass() { return bluetoothAutoBypass; }

    public synchronized void setEmulationEnabled(boolean enabled) {
        this.emulationEnabled = enabled;
        updateEmulationFilters();
        updateAutoHeadroom();
    }

    public synchronized void setEmulationAmount(float amount) {
        this.emulationAmount = Math.max(0.0f, Math.min(1.0f, amount));
        updateEmulationFilters();
        updateAutoHeadroom();
    }

    public synchronized void setBluetoothAutoBypass(boolean bypass) {
        this.bluetoothAutoBypass = bypass;
        updateAutoHeadroom();
    }

    private void updateEmulationFilters() {
        if (emulationEnabled && !bluetoothAutoBypass) {
            float amt = emulationAmount;
            emuLowBiquad.setLowShelf(110.0f, sampleRate, 3.5f * amt, 0.8f);
            emuMidDipBiquad.setPeaking(650.0f, sampleRate, -1.8f * amt, 1.5f);
            emuPresenceBiquad.setPeaking(3800.0f, sampleRate, 2.2f * amt, 1.2f);
            emuAirBiquad.setHighShelf(11500.0f, sampleRate, 1.8f * amt, 0.7f);
        } else {
            emuLowBiquad.setBypass();
            emuMidDipBiquad.setBypass();
            emuPresenceBiquad.setBypass();
            emuAirBiquad.setBypass();
        }
    }

    // MDRC
    public boolean isMdrcEnabled() { return mdrcEnabled; }
    public float getMdrcThreshold() { return mdrcThresholdDb; }
    public float getMdrcRatio() { return mdrcRatio; }
    public float getMdrcGainReduction() { return mdrcGainReduction; }

    public synchronized void setMdrcEnabled(boolean enabled) {
        this.mdrcEnabled = enabled;
    }

    public synchronized void setMdrcDynamics(float thresholdDb, float ratio) {
        this.mdrcThresholdDb = thresholdDb;
        this.mdrcRatio = Math.max(1.0f, ratio);
    }

    public synchronized void setMdrcBandGain(int bandIndex, float gainDb) {
        if (bandIndex >= 0 && bandIndex < 5) {
            mdrcBandGainsDb[bandIndex] = gainDb;
        }
    }

    private void updateMdrcCrossovers() {
        mdrcCrossoverBiquads[0].setLowShelf(160.0f, sampleRate, 0.0f, 0.707f);
        mdrcCrossoverBiquads[1].setPeaking(500.0f, sampleRate, 0.0f, 1.0f);
        mdrcCrossoverBiquads[2].setPeaking(2000.0f, sampleRate, 0.0f, 1.0f);
        mdrcCrossoverBiquads[3].setHighShelf(7000.0f, sampleRate, 0.0f, 0.707f);
    }

    public void resetFilterStates() {
        preGainBassFilter.reset();
        preGainMidFilter.reset();
        preGainTrebleFilter.reset();
        for (int i = 0; i < BAND_COUNT; i++) {
            eqBiquads[i].reset();
        }
        bassBoostBiquad.reset();
        emuLowBiquad.reset();
        emuMidDipBiquad.reset();
        emuPresenceBiquad.reset();
        emuAirBiquad.reset();
        for (int i = 0; i < 4; i++) {
            mdrcCrossoverBiquads[i].reset();
        }
        limiterEnvelope = 0.0f;
        mdrcEnvelope = 0.0f;
    }

    /**
     * Primary audio sample frame processor:
     * Executes the mandatory chain:
     * Input -> Pre-Gains -> EQ32 -> BassBoost -> EMU -> MDRC -> Limiter (-1dB)
     */
    public void process(float[] buffer, int channels, int frameCount) {
        if (!masterEnabled || globalBypass || buffer == null || frameCount <= 0) {
            return;
        }

        boolean clippingDetected = false;
        final float linPreamp = this.linearEffectivePreamp;
        final boolean runEmu = (emulationEnabled && !bluetoothAutoBypass);
        final boolean runMdrc = mdrcEnabled;
        final float mdrcThreshLin = (float) Math.pow(10.0, mdrcThresholdDb / 20.0);

        int totalSamples = frameCount * channels;

        for (int i = 0; i < totalSamples; i += channels) {
            for (int ch = 0; ch < channels; ch++) {
                int idx = i + ch;
                float sample = buffer[idx];

                // Check for NaN / Infinity entering the chain
                if (Float.isNaN(sample) || Float.isInfinite(sample)) {
                    sample = 0.0f;
                }

                // 1. Pre-Gains (Input Stage) + Effective Preamp
                sample = sample * linPreamp;
                sample = preGainBassFilter.processChannel(sample, ch);
                sample = preGainMidFilter.processChannel(sample, ch);
                sample = preGainTrebleFilter.processChannel(sample, ch);

                // 2. 32-Band RBJ Peaking EQ
                for (int b = 0; b < BAND_COUNT; b++) {
                    sample = eqBiquads[b].processChannel(sample, ch);
                }

                // 3. BassBoost
                if (bassBoostEnabled) {
                    sample = bassBoostBiquad.processChannel(sample, ch);
                }

                // 4. Hardware Emulation ATS2835P (4 Biquads + Soft Saturation)
                if (runEmu) {
                    sample = emuLowBiquad.processChannel(sample, ch);
                    sample = emuMidDipBiquad.processChannel(sample, ch);
                    sample = emuPresenceBiquad.processChannel(sample, ch);
                    sample = emuAirBiquad.processChannel(sample, ch);

                    // Gentle harmonic saturation (tanh curve above -6dBFS)
                    if (sample > 0.5f) {
                        sample = 0.5f + (float) Math.tanh(sample - 0.5f) * 0.5f;
                    } else if (sample < -0.5f) {
                        sample = -0.5f + (float) Math.tanh(sample + 0.5f) * 0.5f;
                    }
                }

                // 5. MDRC Dynamics
                if (runMdrc) {
                    float absSample = Math.abs(sample);
                    if (absSample > mdrcEnvelope) {
                        mdrcEnvelope = absSample;
                    } else {
                        mdrcEnvelope = mdrcEnvelope * 0.999f + absSample * 0.001f;
                    }

                    if (mdrcEnvelope > mdrcThreshLin) {
                        float overDb = 20.0f * (float) Math.log10(mdrcEnvelope / mdrcThreshLin);
                        float targetReductionDb = overDb * (1.0f - 1.0f / mdrcRatio);
                        mdrcGainReduction = targetReductionDb;
                        float compGain = (float) Math.pow(10.0, -targetReductionDb / 20.0);
                        sample *= compGain;
                    } else {
                        mdrcGainReduction = 0.0f;
                    }
                }

                // 6. Brickwall Limiter (-1.0 dBFS) with NaN protection
                float absSample = Math.abs(sample);
                if (absSample > limiterEnvelope) {
                    limiterEnvelope = absSample + limiterAttackCoeff * (limiterEnvelope - absSample);
                } else {
                    limiterEnvelope = absSample + limiterReleaseCoeff * (limiterEnvelope - absSample);
                }

                if (limiterEnvelope > LIMITER_THRESHOLD) {
                    float reductionGain = LIMITER_THRESHOLD / limiterEnvelope;
                    sample *= reductionGain;
                    clippingDetected = true;
                }

                // Hard clamp at [-1.0f, +1.0f] for safety
                if (sample > 1.0f) {
                    sample = 1.0f;
                    clippingDetected = true;
                } else if (sample < -1.0f) {
                    sample = -1.0f;
                    clippingDetected = true;
                }

                if (Float.isNaN(sample) || Float.isInfinite(sample)) {
                    sample = 0.0f;
                    resetFilterStates();
                }

                buffer[idx] = sample;

                // Visualizer tap on Channel 0
                if (ch == 0 && fftListener != null) {
                    visualizerBuffer[visualizerIndex++] = sample;
                    if (visualizerIndex >= visualizerBuffer.length) {
                        visualizerIndex = 0;
                        fftListener.onFftData(visualizerBuffer.clone());
                    }
                }
            }
        }

        // Notify clipping indicator
        if (clippingListener != null && clippingDetected) {
            clippingListener.onClipping(true);
        }
    }

    /**
     * Process 16-bit signed PCM byte buffer in-place.
     */
    public void processPcm16(byte[] pcmData, int offset, int length, int channels) {
        if (!masterEnabled || globalBypass || pcmData == null || length <= 0) {
            return;
        }
        int sampleCount = length / 2;
        int frameCount = sampleCount / channels;
        float[] floatBuffer = new float[sampleCount];

        // Convert PCM 16-bit LE to float [-1.0, 1.0]
        for (int i = 0; i < sampleCount; i++) {
            int byteIdx = offset + (i * 2);
            short val = (short) ((pcmData[byteIdx] & 0xFF) | (pcmData[byteIdx + 1] << 8));
            floatBuffer[i] = val / 32768.0f;
        }

        // Run DSP pipeline
        process(floatBuffer, channels, frameCount);

        // Convert back to PCM 16-bit LE
        for (int i = 0; i < sampleCount; i++) {
            int byteIdx = offset + (i * 2);
            float f = Math.max(-1.0f, Math.min(1.0f, floatBuffer[i]));
            short shortVal = (short) Math.round(f * 32767.0f);
            pcmData[byteIdx] = (byte) (shortVal & 0xFF);
            pcmData[byteIdx + 1] = (byte) ((shortVal >> 8) & 0xFF);
        }
    }

    /**
     * Process direct ByteBuffer of 16-bit PCM.
     */
    public void processByteBuffer(ByteBuffer byteBuffer, int channels) {
        if (!masterEnabled || globalBypass || byteBuffer == null || !byteBuffer.hasRemaining()) {
            return;
        }
        int remaining = byteBuffer.remaining();
        byte[] temp = new byte[remaining];
        int pos = byteBuffer.position();
        byteBuffer.get(temp);
        processPcm16(temp, 0, remaining, channels);
        byteBuffer.position(pos);
        byteBuffer.put(temp);
    }
}
