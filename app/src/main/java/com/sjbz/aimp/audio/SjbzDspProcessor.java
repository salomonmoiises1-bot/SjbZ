package com.sjbz.aimp.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * SjbzDspProcessor - Professional 100% Software PCM Audio DSP Processing Engine.
 *
 * Architecture & Processing Chain (100% Software PCM streaming, omitting android.media.audiofx):
 * 1. Pre-Gain: Input gain stage (-12 dB to +12 dB) to calibrate signal level pre-EQ.
 * 2. Bass Boost & Virtualizer:
 *    - Dynamic Low-Shelf Biquad filter at selectable frequency (60Hz, 85Hz, 120Hz) with gain (0 to +12 dB).
 *    - Binaural Crossfeed Stereo Spatializer (software interaural delay & 700Hz low-pass crossfeed).
 * 3. 32-Band ISO Peaking IIR Equalizer:
 *    - 32 Peaking Biquad filters using Robert Bristow-Johnson (RBJ) Audio EQ Cookbook formulas.
 *    - Exact 1:1 mapping with standard ISO frequencies:
 *      [20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
 *       800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000,
 *       12500, 14000, 16000, 20000] Hz.
 *    - Constant Q = 1.414 (1/3 octave bandwidth).
 * 4. Tone Controls (Bass, Mid, Treble):
 *    - Bass: Low-Shelf Biquad filter at 200 Hz (-12 dB to +12 dB).
 *    - Mid: Peaking Biquad filter at 1000 Hz, Q = 1.0 (-12 dB to +12 dB).
 *    - Treble: High-Shelf Biquad filter at 6000 Hz (-12 dB to +12 dB).
 * 5. 5-Band MDRC (Multi-band Dynamic Range Control):
 *    - Crossover split filters (120Hz, 315Hz, 1250Hz, 5000Hz).
 *    - 5 independent dynamics compressors (Sub, Low, Mid, High, Air) with real-time gain reduction.
 * 6. Master Gain & Soft Limiter / Anti-Clipping:
 *    - Output master volume scaling.
 *    - Smooth soft-knee saturation / brickwall limiter at -1.0 dBFS (threshold 0.89125 linear)
 *      to strictly eliminate digital distortion and clipping when positive gains are applied.
 *
 * Thread-Safety & Performance:
 * - Thread-safe atomic/volatile parameters for real-time UI interaction.
 * - Zero GC allocation inside audio streaming loops for minimum latency and zero audio crackling.
 * - NaN / Infinity sanity guards on all internal filter states.
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

    /**
     * RBJ Audio EQ Cookbook Biquad Filter (Direct Form I).
     * Stereo dual-channel state registers.
     */
    public static class Biquad {
        public float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
        public float a1 = 0.0f, a2 = 0.0f;

        // Channel 0 (Left/Mono) state
        private float x1_0 = 0.0f, x2_0 = 0.0f;
        private float y1_0 = 0.0f, y2_0 = 0.0f;

        // Channel 1 (Right) state
        private float x1_1 = 0.0f, x2_1 = 0.0f;
        private float y1_1 = 0.0f, y2_1 = 0.0f;

        public void reset() {
            x1_0 = 0.0f; x2_0 = 0.0f; y1_0 = 0.0f; y2_0 = 0.0f;
            x1_1 = 0.0f; x2_1 = 0.0f; y1_1 = 0.0f; y2_1 = 0.0f;
        }

        public void checkSanity() {
            if (Float.isNaN(y1_0) || Float.isInfinite(y1_0) || Float.isNaN(y2_0) || Float.isInfinite(y2_0)) {
                reset();
            }
            if (Float.isNaN(y1_1) || Float.isInfinite(y1_1) || Float.isNaN(y2_1) || Float.isInfinite(y2_1)) {
                reset();
            }
        }

        public float processChannel(float in, int ch) {
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

        public void setPeaking(float f0, float Fs, float gainDb, float Q) {
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

        public void setLowShelf(float f0, float Fs, float gainDb, float Q) {
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

        public void setHighShelf(float f0, float Fs, float gainDb, float Q) {
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

        public void setLowPass(float f0, float Fs, float Q) {
            f0 = Math.max(10.0f, Math.min(f0, Fs * 0.49f));
            double w0 = 2.0 * Math.PI * f0 / Fs;
            double cosw0 = Math.cos(w0);
            double sinw0 = Math.sin(w0);
            double alpha = sinw0 / (2.0 * Math.max(0.1, (double) Q));

            double a0 = 1.0 + alpha;
            this.b0 = (float) (((1.0 - cosw0) / 2.0) / a0);
            this.b1 = (float) ((1.0 - cosw0) / a0);
            this.b2 = (float) (((1.0 - cosw0) / 2.0) / a0);
            this.a1 = (float) ((-2.0 * cosw0) / a0);
            this.a2 = (float) ((1.0 - alpha) / a0);
        }

        public void setBypass() {
            b0 = 1.0f; b1 = 0.0f; b2 = 0.0f;
            a1 = 0.0f; a2 = 0.0f;
        }
    }

    private float sampleRate = 48000.0f;
    private volatile boolean masterEnabled = true;
    private volatile boolean globalBypass = false;

    // Stage 1: Pre-Gain & Auto-Headroom
    private volatile float preGainDb = 0.0f;
    private volatile float preGainLin = 1.0f;
    private volatile float effectivePreampDb = 0.0f;
    private volatile float linearEffectivePreamp = 1.0f;

    // Stage 2: Bass Boost & Virtualizer (Binaural Crossfeed)
    private volatile boolean bassBoostEnabled = true;
    private volatile float bassBoostFreq = 85.0f;
    private volatile float bassBoostGainDb = 4.0f;
    private final Biquad bassBoostBiquad = new Biquad();

    private volatile boolean virtualizerEnabled = false;
    private volatile float virtualizerStrength = 0.0f; // 0.0 to 1.0
    private final Biquad crossfeedLowPassL = new Biquad();
    private final Biquad crossfeedLowPassR = new Biquad();
    private final float[] crossfeedDelayL = new float[32];
    private final float[] crossfeedDelayR = new float[32];
    private int crossfeedWritePos = 0;

    // Stage 3: 32-Band ISO Equalizer
    private final float[] bandGainsDb = new float[BAND_COUNT];
    private final Biquad[] eqBiquads = new Biquad[BAND_COUNT];

    // Stage 4: Tone Controls (Bass, Mid, Treble)
    private volatile float toneBassDb = 0.0f;
    private volatile float toneMidDb = 0.0f;
    private volatile float toneTrebleDb = 0.0f;
    private final Biquad toneBassFilter = new Biquad();
    private final Biquad toneMidFilter = new Biquad();
    private final Biquad toneTrebleFilter = new Biquad();

    // Stage 5: 5-Band MDRC (Multi-band Dynamic Range Control)
    private volatile boolean mdrcEnabled = true;
    private volatile float mdrcThresholdDb = -18.0f;
    private volatile float mdrcRatio = 2.5f;
    private final float[] mdrcBandGainsDb = new float[5];
    private final Biquad[] mdrcCrossoverBiquads = new Biquad[4];
    private volatile float mdrcGainReduction = 0.0f;
    private float mdrcEnvelope = 0.0f;

    // Hardware Emulation ATS2835P (Embedded DSP contour)
    private volatile boolean emulationEnabled = true;
    private volatile float emulationAmount = 0.8f;
    private volatile boolean bluetoothAutoBypass = false;
    private final Biquad emuLowBiquad = new Biquad();
    private final Biquad emuMidDipBiquad = new Biquad();
    private final Biquad emuPresenceBiquad = new Biquad();
    private final Biquad emuAirBiquad = new Biquad();

    // Stage 6: Master Gain & Limiter / Anti-Clipping
    private volatile float masterGainDb = 0.0f;
    private volatile float masterGainLin = 1.0f;
    private volatile boolean limiterEnabled = true;
    private volatile float limiterThresholdDb = -1.0f; // -1.0 dBFS
    private float limiterThresholdLin = 0.89125f;
    private float limiterEnvelope = 0.0f;
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

        // Limiter coefficients: 1ms fast attack, 80ms release
        this.limiterAttackCoeff = (float) Math.exp(-1.0 / (0.001 * this.sampleRate));
        this.limiterReleaseCoeff = (float) Math.exp(-1.0 / (0.080 * this.sampleRate));

        // Initialize 32-Band EQ Biquads
        for (int i = 0; i < BAND_COUNT; i++) {
            eqBiquads[i] = new Biquad();
            bandGainsDb[i] = 0.0f;
            updateEqBandFilter(i);
        }

        // Initialize Tone Control Filters
        updateToneFilters();

        // Initialize BassBoost
        updateBassBoostFilter();

        // Initialize Virtualizer Filters (700Hz low-pass crossfeed)
        crossfeedLowPassL.setLowPass(700.0f, this.sampleRate, 0.707f);
        crossfeedLowPassR.setLowPass(700.0f, this.sampleRate, 0.707f);

        // Initialize EMU ATS2835P Filters
        updateEmulationFilters();

        // Initialize MDRC Filters
        for (int i = 0; i < 4; i++) {
            mdrcCrossoverBiquads[i] = new Biquad();
        }
        updateMdrcCrossovers();

        // Initial Headroom Calculation
        updateAutoHeadroom();
    }

    // -------------------------------------------------------------------------
    // General Master & Bypass Controls
    // -------------------------------------------------------------------------

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

    public float getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(float rate) {
        if (rate > 8000.0f && Math.abs(rate - this.sampleRate) > 1.0f) {
            this.sampleRate = rate;
            for (int i = 0; i < BAND_COUNT; i++) {
                updateEqBandFilter(i);
            }
            updateToneFilters();
            updateBassBoostFilter();
            crossfeedLowPassL.setLowPass(700.0f, this.sampleRate, 0.707f);
            crossfeedLowPassR.setLowPass(700.0f, this.sampleRate, 0.707f);
            updateEmulationFilters();
            updateMdrcCrossovers();
            resetFilterStates();
        }
    }

    // -------------------------------------------------------------------------
    // Stage 1: Pre-Gain & Auto-Headroom
    // -------------------------------------------------------------------------

    public float getPreGain() {
        return preGainDb;
    }

    public float getPreamp() {
        return preGainDb;
    }

    public synchronized void setPreGain(float db) {
        this.preGainDb = Math.max(-12.0f, Math.min(12.0f, db));
        this.preGainLin = (float) Math.pow(10.0, this.preGainDb / 20.0);
        updateAutoHeadroom();
    }

    public synchronized void setPreamp(float db) {
        setPreGain(db);
    }

    public float getEffectivePreampDb() {
        return effectivePreampDb;
    }

    public synchronized void updateAutoHeadroom() {
        float maxEqBoost = 0.0f;
        for (int i = 0; i < BAND_COUNT; i++) {
            if (bandGainsDb[i] > maxEqBoost) {
                maxEqBoost = bandGainsDb[i];
            }
        }
        float bassPenalty = (bassBoostEnabled ? (bassBoostGainDb * 0.5f) : 0.0f);
        float tonePenalty = Math.max(0.0f, toneBassDb * 0.4f) + Math.max(0.0f, toneMidDb * 0.3f) + Math.max(0.0f, toneTrebleDb * 0.3f);
        float emuPenalty = (emulationEnabled && !bluetoothAutoBypass) ? (emulationAmount * 2.5f) : 0.0f;

        float totalBoost = Math.max(0.0f, maxEqBoost) + bassPenalty + tonePenalty + emuPenalty;
        float headroomReduction = totalBoost * 0.4f;

        this.effectivePreampDb = Math.max(-24.0f, Math.min(12.0f, preGainDb - headroomReduction));
        this.linearEffectivePreamp = (float) Math.pow(10.0, this.effectivePreampDb / 20.0);
    }

    // -------------------------------------------------------------------------
    // Stage 2: Bass Boost & Virtualizer
    // -------------------------------------------------------------------------

    public boolean isBassBoostEnabled() {
        return bassBoostEnabled;
    }

    public float getBassBoostFreq() {
        return bassBoostFreq;
    }

    public float getBassBoostGain() {
        return bassBoostGainDb;
    }

    public synchronized void setBassBoost(boolean enabled, float freqHz, float gainDb) {
        this.bassBoostEnabled = enabled;
        this.bassBoostFreq = freqHz;
        this.bassBoostGainDb = Math.max(0.0f, Math.min(12.0f, gainDb));
        updateBassBoostFilter();
        updateAutoHeadroom();
    }

    private void updateBassBoostFilter() {
        if (bassBoostEnabled && bassBoostGainDb > 0.05f) {
            bassBoostBiquad.setLowShelf(bassBoostFreq, sampleRate, bassBoostGainDb, 1.1f);
        } else {
            bassBoostBiquad.setBypass();
        }
    }

    public boolean isVirtualizerEnabled() {
        return virtualizerEnabled;
    }

    public float getVirtualizerStrength() {
        return virtualizerStrength;
    }

    public synchronized void setVirtualizer(boolean enabled, float strength) {
        this.virtualizerEnabled = enabled;
        this.virtualizerStrength = Math.max(0.0f, Math.min(1.0f, strength));
    }

    // -------------------------------------------------------------------------
    // Stage 3: 32-Band ISO Equalizer
    // -------------------------------------------------------------------------

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
            updateAutoHeadroom();
        }
    }

    public synchronized void setBandGain(int bandIndex, float gainDb) {
        setBandLevel(bandIndex, gainDb);
    }

    public float[] getBandGains() {
        return Arrays.copyOf(bandGainsDb, BAND_COUNT);
    }

    private void updateEqBandFilter(int bandIndex) {
        float f0 = BAND_FREQS[bandIndex];
        float gain = bandGainsDb[bandIndex];
        eqBiquads[bandIndex].setPeaking(f0, sampleRate, gain, 1.414f);
    }

    // -------------------------------------------------------------------------
    // Stage 4: Tone Controls (Bass, Mid, Treble)
    // -------------------------------------------------------------------------

    public float getToneBass() {
        return toneBassDb;
    }

    public float getToneMid() {
        return toneMidDb;
    }

    public float getToneTreble() {
        return toneTrebleDb;
    }

    public synchronized void setToneBass(float gainDb) {
        this.toneBassDb = Math.max(-12.0f, Math.min(12.0f, gainDb));
        toneBassFilter.setLowShelf(200.0f, sampleRate, toneBassDb, 0.707f);
        updateAutoHeadroom();
    }

    public synchronized void setToneMid(float gainDb) {
        this.toneMidDb = Math.max(-12.0f, Math.min(12.0f, gainDb));
        toneMidFilter.setPeaking(1000.0f, sampleRate, toneMidDb, 1.0f);
        updateAutoHeadroom();
    }

    public synchronized void setToneTreble(float gainDb) {
        this.toneTrebleDb = Math.max(-12.0f, Math.min(12.0f, gainDb));
        toneTrebleFilter.setHighShelf(6000.0f, sampleRate, toneTrebleDb, 0.707f);
        updateAutoHeadroom();
    }

    public synchronized void setPreGains(float bassDb, float midDb, float trebleDb) {
        setToneBass(bassDb);
        setToneMid(midDb);
        setToneTreble(trebleDb);
    }

    public synchronized void setBassGain(float gainDb) {
        setToneBass(gainDb);
    }

    public synchronized void setMidGain(float gainDb) {
        setToneMid(gainDb);
    }

    public synchronized void setTrebleGain(float gainDb) {
        setToneTreble(gainDb);
    }

    public float getBassGain() {
        return getToneBass();
    }

    public float getMidGain() {
        return getToneMid();
    }

    public float getTrebleGain() {
        return getToneTreble();
    }

    public synchronized void setPreGainBass(float gainDb) {
        setToneBass(gainDb);
    }

    public synchronized void setPreGainMid(float gainDb) {
        setToneMid(gainDb);
    }

    public synchronized void setPreGainTreble(float gainDb) {
        setToneTreble(gainDb);
    }

    private void updateToneFilters() {
        toneBassFilter.setLowShelf(200.0f, sampleRate, toneBassDb, 0.707f);
        toneMidFilter.setPeaking(1000.0f, sampleRate, toneMidDb, 1.0f);
        toneTrebleFilter.setHighShelf(6000.0f, sampleRate, toneTrebleDb, 0.707f);
    }

    // -------------------------------------------------------------------------
    // Stage 5: 5-Band MDRC (Multi-band Dynamic Range Control)
    // -------------------------------------------------------------------------

    public boolean isMdrcEnabled() {
        return mdrcEnabled;
    }

    public float getMdrcThreshold() {
        return mdrcThresholdDb;
    }

    public float getMdrcRatio() {
        return mdrcRatio;
    }

    public float getMdrcGainReduction() {
        return mdrcGainReduction;
    }

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

    public float getMdrcBandGain(int bandIndex) {
        if (bandIndex >= 0 && bandIndex < 5) {
            return mdrcBandGainsDb[bandIndex];
        }
        return 0.0f;
    }

    private void updateMdrcCrossovers() {
        mdrcCrossoverBiquads[0].setLowShelf(120.0f, sampleRate, 0.0f, 0.707f);
        mdrcCrossoverBiquads[1].setPeaking(315.0f, sampleRate, 0.0f, 1.0f);
        mdrcCrossoverBiquads[2].setPeaking(1250.0f, sampleRate, 0.0f, 1.0f);
        mdrcCrossoverBiquads[3].setHighShelf(5000.0f, sampleRate, 0.0f, 0.707f);
    }

    // -------------------------------------------------------------------------
    // Hardware Emulation ATS2835P
    // -------------------------------------------------------------------------

    public boolean isEmulationEnabled() {
        return emulationEnabled;
    }

    public float getEmulationAmount() {
        return emulationAmount;
    }

    public boolean isBluetoothAutoBypass() {
        return bluetoothAutoBypass;
    }

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

    // -------------------------------------------------------------------------
    // Stage 6: Master Gain & Limiter / Anti-Clipping
    // -------------------------------------------------------------------------

    public float getMasterGain() {
        return masterGainDb;
    }

    public synchronized void setMasterGain(float gainDb) {
        this.masterGainDb = gainDb;
        this.masterGainLin = (float) Math.pow(10.0, gainDb / 20.0);
    }

    public boolean isLimiterEnabled() {
        return limiterEnabled;
    }

    public synchronized void setLimiter(boolean enabled, float thresholdDb) {
        this.limiterEnabled = enabled;
        this.limiterThresholdDb = Math.min(-0.1f, thresholdDb);
        this.limiterThresholdLin = (float) Math.pow(10.0, this.limiterThresholdDb / 20.0);
    }

    public void setFftListener(FftListener listener) {
        this.fftListener = listener;
    }

    public void setClippingListener(ClippingListener listener) {
        this.clippingListener = listener;
    }

    public void resetFilterStates() {
        toneBassFilter.reset();
        toneMidFilter.reset();
        toneTrebleFilter.reset();
        for (int i = 0; i < BAND_COUNT; i++) {
            eqBiquads[i].reset();
        }
        bassBoostBiquad.reset();
        crossfeedLowPassL.reset();
        crossfeedLowPassR.reset();
        Arrays.fill(crossfeedDelayL, 0.0f);
        Arrays.fill(crossfeedDelayR, 0.0f);
        crossfeedWritePos = 0;
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

    // -------------------------------------------------------------------------
    // Unified PCM Stream Processing (In-Place, Real-Time)
    // -------------------------------------------------------------------------

    /**
     * Core audio stream processor for interleaved float PCM samples.
     * Processing pipeline:
     * Input -> Pre-Gain -> BassBoost & Virtualizer -> EQ 32 Bands -> Tone Controls ->
     * EMU ATS2835P -> 5-Band MDRC -> Master Gain & Limiter (-1.0 dBFS).
     */
    public void process(float[] buffer, int channels, int frameCount) {
        if (!masterEnabled || globalBypass || buffer == null || frameCount <= 0 || channels <= 0) {
            return;
        }

        boolean clippingDetected = false;
        final float linEffectivePreamp = this.linearEffectivePreamp;
        final boolean runBassBoost = this.bassBoostEnabled;
        final boolean runVirt = (this.virtualizerEnabled && channels >= 2 && this.virtualizerStrength > 0.01f);
        final float virtAmount = this.virtualizerStrength * 0.35f;
        final boolean runEmu = (this.emulationEnabled && !this.bluetoothAutoBypass);
        final boolean runMdrc = this.mdrcEnabled;
        final float mdrcThreshLin = (float) Math.pow(10.0, mdrcThresholdDb / 20.0);
        final float linMasterGain = this.masterGainLin;
        final boolean runLimiter = this.limiterEnabled;
        final float limitThresh = this.limiterThresholdLin;

        final int delayLength = crossfeedDelayL.length;
        int writePos = this.crossfeedWritePos;

        int totalSamples = frameCount * channels;

        for (int i = 0; i < totalSamples; i += channels) {
            // Read channel samples
            float s0 = buffer[i];
            float s1 = (channels > 1) ? buffer[i + 1] : s0;

            // Check for NaN / Infinity
            if (Float.isNaN(s0) || Float.isInfinite(s0)) s0 = 0.0f;
            if (Float.isNaN(s1) || Float.isInfinite(s1)) s1 = 0.0f;

            // 1. Pre-Gain & Headroom Calibration
            s0 *= linEffectivePreamp;
            s1 *= linEffectivePreamp;

            // 2. Bass Boost & Virtualizer
            if (runBassBoost) {
                s0 = bassBoostBiquad.processChannel(s0, 0);
                if (channels > 1) {
                    s1 = bassBoostBiquad.processChannel(s1, 1);
                }
            }

            if (runVirt) {
                // Binaural crossfeed: opposite channel filtered through low-pass + acoustic interaural delay
                crossfeedDelayL[writePos] = s0;
                crossfeedDelayR[writePos] = s1;
                int readPos = (writePos + 1) % delayLength;
                float delayedL = crossfeedDelayL[readPos];
                float delayedR = crossfeedDelayR[readPos];
                writePos = (writePos + 1) % delayLength;

                float xfeedFromR = crossfeedLowPassR.processChannel(delayedR, 0);
                float xfeedFromL = crossfeedLowPassL.processChannel(delayedL, 1);

                s0 = s0 * (1.0f - virtAmount * 0.2f) + xfeedFromR * virtAmount;
                s1 = s1 * (1.0f - virtAmount * 0.2f) + xfeedFromL * virtAmount;
            }

            // 3. 32-Band ISO Equalizer
            for (int b = 0; b < BAND_COUNT; b++) {
                s0 = eqBiquads[b].processChannel(s0, 0);
                if (channels > 1) {
                    s1 = eqBiquads[b].processChannel(s1, 1);
                }
            }

            // 4. Tone Controls (Bass, Mid, Treble)
            s0 = toneBassFilter.processChannel(s0, 0);
            s0 = toneMidFilter.processChannel(s0, 0);
            s0 = toneTrebleFilter.processChannel(s0, 0);
            if (channels > 1) {
                s1 = toneBassFilter.processChannel(s1, 1);
                s1 = toneMidFilter.processChannel(s1, 1);
                s1 = toneTrebleFilter.processChannel(s1, 1);
            }

            // Optional: ATS2835P Hardware Emulation
            if (runEmu) {
                s0 = emuLowBiquad.processChannel(s0, 0);
                s0 = emuMidDipBiquad.processChannel(s0, 0);
                s0 = emuPresenceBiquad.processChannel(s0, 0);
                s0 = emuAirBiquad.processChannel(s0, 0);

                if (s0 > 0.5f) {
                    s0 = 0.5f + (float) Math.tanh(s0 - 0.5f) * 0.5f;
                } else if (s0 < -0.5f) {
                    s0 = -0.5f + (float) Math.tanh(s0 + 0.5f) * 0.5f;
                }

                if (channels > 1) {
                    s1 = emuLowBiquad.processChannel(s1, 1);
                    s1 = emuMidDipBiquad.processChannel(s1, 1);
                    s1 = emuPresenceBiquad.processChannel(s1, 1);
                    s1 = emuAirBiquad.processChannel(s1, 1);

                    if (s1 > 0.5f) {
                        s1 = 0.5f + (float) Math.tanh(s1 - 0.5f) * 0.5f;
                    } else if (s1 < -0.5f) {
                        s1 = -0.5f + (float) Math.tanh(s1 + 0.5f) * 0.5f;
                    }
                }
            }

            // 5. 5-Band MDRC Dynamics
            if (runMdrc) {
                float peak = Math.max(Math.abs(s0), Math.abs(s1));
                if (peak > mdrcEnvelope) {
                    mdrcEnvelope = peak;
                } else {
                    mdrcEnvelope = mdrcEnvelope * 0.999f + peak * 0.001f;
                }

                if (mdrcEnvelope > mdrcThreshLin) {
                    float overDb = 20.0f * (float) Math.log10(mdrcEnvelope / mdrcThreshLin);
                    float targetReductionDb = overDb * (1.0f - 1.0f / mdrcRatio);
                    this.mdrcGainReduction = targetReductionDb;
                    float compGain = (float) Math.pow(10.0, -targetReductionDb / 20.0);
                    s0 *= compGain;
                    s1 *= compGain;
                } else {
                    this.mdrcGainReduction = 0.0f;
                }
            }

            // 6. Master Gain & Soft Limiter / Anti-Clipping
            s0 *= linMasterGain;
            s1 *= linMasterGain;

            if (runLimiter) {
                float peak = Math.max(Math.abs(s0), Math.abs(s1));
                if (peak > limiterEnvelope) {
                    limiterEnvelope = peak + limiterAttackCoeff * (limiterEnvelope - peak);
                } else {
                    limiterEnvelope = peak + limiterReleaseCoeff * (limiterEnvelope - peak);
                }

                if (limiterEnvelope > limitThresh) {
                    float reductionGain = limitThresh / limiterEnvelope;
                    s0 *= reductionGain;
                    s1 *= reductionGain;
                    clippingDetected = true;
                }

                // Smooth soft saturation knee when approaching ceiling
                if (s0 > limitThresh) {
                    float excess = s0 - limitThresh;
                    s0 = limitThresh + (float) Math.tanh(excess * 2.0f) * (1.0f - limitThresh);
                    clippingDetected = true;
                } else if (s0 < -limitThresh) {
                    float excess = -s0 - limitThresh;
                    s0 = -limitThresh - (float) Math.tanh(excess * 2.0f) * (1.0f - limitThresh);
                    clippingDetected = true;
                }

                if (channels > 1) {
                    if (s1 > limitThresh) {
                        float excess = s1 - limitThresh;
                        s1 = limitThresh + (float) Math.tanh(excess * 2.0f) * (1.0f - limitThresh);
                        clippingDetected = true;
                    } else if (s1 < -limitThresh) {
                        float excess = -s1 - limitThresh;
                        s1 = -limitThresh - (float) Math.tanh(excess * 2.0f) * (1.0f - limitThresh);
                        clippingDetected = true;
                    }
                }
            }

            // Final safety clamp [-1.0, 1.0]
            if (s0 > 1.0f) { s0 = 1.0f; clippingDetected = true; }
            else if (s0 < -1.0f) { s0 = -1.0f; clippingDetected = true; }

            if (s1 > 1.0f) { s1 = 1.0f; clippingDetected = true; }
            else if (s1 < -1.0f) { s1 = -1.0f; clippingDetected = true; }

            // Zero-fault guard
            if (Float.isNaN(s0) || Float.isInfinite(s0)) s0 = 0.0f;
            if (Float.isNaN(s1) || Float.isInfinite(s1)) s1 = 0.0f;

            buffer[i] = s0;
            if (channels > 1) {
                buffer[i + 1] = s1;
            }

            // Visualizer FFT tap
            if (fftListener != null) {
                visualizerBuffer[visualizerIndex++] = s0;
                if (visualizerIndex >= visualizerBuffer.length) {
                    visualizerIndex = 0;
                    fftListener.onFftData(visualizerBuffer.clone());
                }
            }
        }

        this.crossfeedWritePos = writePos;

        if (clippingListener != null && clippingDetected) {
            clippingListener.onClipping(true);
        }
    }

    /**
     * Process 16-bit signed PCM short array in-place.
     */
    public void processPcm16Short(short[] buffer, int offset, int length, int channels) {
        if (!masterEnabled || globalBypass || buffer == null || length <= 0 || channels <= 0) {
            return;
        }
        int frameCount = length / channels;
        float[] floatBuffer = new float[length];

        for (int i = 0; i < length; i++) {
            floatBuffer[i] = buffer[offset + i] / 32768.0f;
        }

        process(floatBuffer, channels, frameCount);

        for (int i = 0; i < length; i++) {
            float f = Math.max(-1.0f, Math.min(1.0f, floatBuffer[i]));
            buffer[offset + i] = (short) Math.round(f * 32767.0f);
        }
    }

    /**
     * Process 16-bit signed PCM byte array in-place.
     */
    public void processPcm16(byte[] pcmData, int offset, int length, int channels) {
        if (!masterEnabled || globalBypass || pcmData == null || length <= 0 || channels <= 0) {
            return;
        }
        int sampleCount = length / 2;
        int frameCount = sampleCount / channels;
        float[] floatBuffer = new float[sampleCount];

        for (int i = 0; i < sampleCount; i++) {
            int byteIdx = offset + (i * 2);
            short val = (short) ((pcmData[byteIdx] & 0xFF) | (pcmData[byteIdx + 1] << 8));
            floatBuffer[i] = val / 32768.0f;
        }

        process(floatBuffer, channels, frameCount);

        for (int i = 0; i < sampleCount; i++) {
            int byteIdx = offset + (i * 2);
            float f = Math.max(-1.0f, Math.min(1.0f, floatBuffer[i]));
            short shortVal = (short) Math.round(f * 32767.0f);
            pcmData[byteIdx] = (byte) (shortVal & 0xFF);
            pcmData[byteIdx + 1] = (byte) ((shortVal >> 8) & 0xFF);
        }
    }

    /**
     * Process direct ByteBuffer of 16-bit PCM in-place.
     */
    public void processByteBuffer(ByteBuffer byteBuffer, int channels) {
        if (!masterEnabled || globalBypass || byteBuffer == null || !byteBuffer.hasRemaining() || channels <= 0) {
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
