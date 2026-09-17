package com.sjbz.aimp;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import com.sjbz.aimp.audio.GlobalAudioSessionManager;
import com.sjbz.aimp.audio.SjbzAudioEngine;
import com.sjbz.aimp.audio.SjbzDspProcessor;
import com.sjbz.aimp.service.GlobalAudioService;
import com.sjbz.aimp.ui.AudioSpectrumVisualizerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * EqActivity - Professional Single-Window 32-Band Studio Equalizer & DSP Controller.
 *
 * Implements 5 collapsible sections inside a vertical ScrollView:
 * a) Master On/Off + Visualizador + Preamp + Clip LED
 * b) Pre-ganancias Graves/Medios/Agudos (3 sliders: 200Hz Low-Shelf, 1kHz Peaking, 6kHz High-Shelf)
 * c) EQ 32 bandas (100% Software RBJ Peaking, exact 1:1 mapping with BAND_FREQS)
 * d) BassBoost + EMU ATS2835P + MDRC (Integrated Dynamics Stage)
 * e) Presets Guardar/Cargar & Adaptación de Género
 */
public class EqActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "sjbz_dsp_pro_prefs";
    private static final String KEY_MASTER_ENABLED = "master_enabled";
    private static final String KEY_PREAMP = "preamp_db";
    private static final String KEY_PREGAIN_BASS = "pregain_bass_db";
    private static final String KEY_PREGAIN_MID = "pregain_mid_db";
    private static final String KEY_PREGAIN_TREBLE = "pregain_treble_db";
    private static final String KEY_BASSBOOST_ENABLED = "bassboost_enabled";
    private static final String KEY_BASSBOOST_FREQ = "bassboost_freq";
    private static final String KEY_BASSBOOST_GAIN = "bassboost_gain";
    private static final String KEY_EMU_ENABLED = "emu_enabled";
    private static final String KEY_EMU_AMOUNT = "emu_amount";
    private static final String KEY_EMU_BT_BYPASS = "emu_bt_bypass";
    private static final String KEY_MDRC_ENABLED = "mdrc_enabled";
    private static final String KEY_MDRC_THRESHOLD = "mdrc_threshold";
    private static final String KEY_MDRC_RATIO = "mdrc_ratio";

    // Core DSP Engine instance
    private SjbzDspProcessor dspProcessor;
    private GlobalAudioSessionManager audioSessionManager;
    private SharedPreferences prefs;

    // UI Widgets - Section A: Master & Visualizer
    private Toolbar eqToolbar;
    private SwitchCompat switchMasterDsp;
    private SwitchCompat switchMasterDspSection;
    private LinearLayout bannerGlobalBypass;
    private LinearLayout headerMaster;
    private LinearLayout contentMaster;
    private ImageView chevronMaster;
    private AudioSpectrumVisualizerView visualizerView;
    private SeekBar seekPreamp;
    private TextView tvPreampValue;
    private TextView tvEffectivePreamp;
    private TextView tvClippingIndicator;
    private Button btnResetPreamp;
    private SwitchCompat switchGlobalAudioMode;
    private TextView tvCaptureSessionCount;

    // UI Widgets - Section B: Pre-Gains
    private LinearLayout headerPreGains;
    private LinearLayout contentPreGains;
    private ImageView chevronPreGains;
    private Button btnResetPreGains;
    private SeekBar seekPreGainBass;
    private TextView tvPreGainBassValue;
    private SeekBar seekPreGainMid;
    private TextView tvPreGainMidValue;
    private SeekBar seekPreGainTreble;
    private TextView tvPreGainTrebleValue;

    // UI Widgets - Section C: 32-Band EQ
    private LinearLayout headerEq32;
    private LinearLayout contentEq32;
    private ImageView chevronEq32;
    private Button btnResetEq;
    private HorizontalScrollView scrollFaders;
    private ScrollView scrollEqRoot;
    private LinearLayout llFadersContainer;
    private final SeekBar[] eqSeekBars = new SeekBar[SjbzDspProcessor.BAND_COUNT];
    private final TextView[] eqValueTexts = new TextView[SjbzDspProcessor.BAND_COUNT];

    // UI Widgets - Section D: Dynamics (BassBoost + EMU + MDRC)
    private LinearLayout headerDynamics;
    private LinearLayout contentDynamics;
    private ImageView chevronDynamics;

    // D.1 Bass Boost
    private SwitchCompat switchBassBoost;
    private Spinner spinnerBassFreq;
    private SeekBar seekBassGain;
    private TextView tvBassGainValue;

    // D.2 ATS2835P Hardware Emulation
    private SwitchCompat switchHardwareEmulation;
    private SeekBar seekEmulationAmount;
    private TextView tvEmulationAmount;
    private SwitchCompat switchBluetoothBypass;
    private TextView tvBluetoothStatus;

    // D.3 MDRC Multiband Compressor
    private SwitchCompat switchMdrc;
    private TextView tvMdrcGrMeter;
    private SeekBar seekMdrcThreshold;
    private TextView tvMdrcThresholdValue;
    private SeekBar seekMdrcRatio;
    private TextView tvMdrcRatioValue;
    private SeekBar seekMdrcSub;
    private TextView tvMdrcSub;
    private SeekBar seekMdrcLow;
    private TextView tvMdrcLow;
    private SeekBar seekMdrcMid;
    private TextView tvMdrcMid;
    private SeekBar seekMdrcHigh;
    private TextView tvMdrcHigh;
    private SeekBar seekMdrcAir;
    private TextView tvMdrcAir;

    // UI Widgets - Section E: Presets & Genre Detection
    private LinearLayout headerPresets;
    private LinearLayout contentPresets;
    private ImageView chevronPresets;
    private Button btnPresetFlat;
    private Button btnPresetBass;
    private Button btnPresetRock;
    private Button btnPresetVocal;
    private Spinner spinnerPresets;
    private Button btnSaveCustomPreset;
    private TextView tvDetectedGenre;
    private TextView tvDetectedGenreConfidence;
    private Button btnApplyGenreEq;

    // Handlers and Timers
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable clippingResetRunnable = new Runnable() {
        @Override
        public void run() {
            if (tvClippingIndicator != null) {
                tvClippingIndicator.setText("HEADROOM ÓPTIMO");
                tvClippingIndicator.setTextColor(Color.parseColor("#00E5FF"));
                tvClippingIndicator.setBackgroundColor(Color.parseColor("#2000E5FF"));
            }
        }
    };

    private final Runnable mdrcMeterRunnable = new Runnable() {
        @Override
        public void run() {
            if (dspProcessor != null && tvMdrcGrMeter != null) {
                float gr = dspProcessor.getMdrcGainReduction();
                tvMdrcGrMeter.setText(String.format(Locale.US, "GR: -%.1f dB", gr));
            }
            mainHandler.postDelayed(this, 100);
        }
    };

    // Synthetic audio preview generator for offline visualizer animation
    private final Runnable audioPreviewRunnable = new Runnable() {
        private float phase = 0.0f;
        private final float[] testBuffer = new float[256];

        @Override
        public void run() {
            if (dspProcessor != null && dspProcessor.isMasterEnabled()) {
                for (int i = 0; i < testBuffer.length; i++) {
                    phase += 0.05f;
                    float sample = (float) (Math.sin(phase) * 0.3 + Math.sin(phase * 2.3) * 0.2 + Math.sin(phase * 0.4) * 0.25);
                    testBuffer[i] = sample;
                }
                dspProcessor.process(testBuffer, 1, testBuffer.length);
            }
            mainHandler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_eq);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        audioSessionManager = GlobalAudioSessionManager.getInstance(this);

        // Initialize DSP Engine
        initDspEngine();

        // Bind UI Components
        bindViews();

        // Configure Collapsible Sections
        setupCollapsibleSections();

        // Setup 32-Band Faders Container
        setup32BandFaders();

        // Setup Event Listeners
        setupEventListeners();

        // Load Persisted Settings
        loadSavedSettings();

        // Update Auto-Headroom and Status
        updateHeadroomDisplay();
        checkGlobalBypassStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkGlobalBypassStatus();
        mainHandler.post(mdrcMeterRunnable);
        mainHandler.post(audioPreviewRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(mdrcMeterRunnable);
        mainHandler.removeCallbacks(audioPreviewRunnable);
        saveSettings();
    }

    private void initDspEngine() {
        dspProcessor = SjbzAudioEngine.getInstance().getDspProcessor();

        // Connect Visualizer
        dspProcessor.setFftListener(new SjbzDspProcessor.FftListener() {
            @Override
            public void onFftData(final float[] samples) {
                if (visualizerView != null) {
                    visualizerView.post(new Runnable() {
                        @Override
                        public void run() {
                            visualizerView.onAudioData(samples);
                        }
                    });
                }
            }
        });

        // Connect Limiter Clipping Listener
        dspProcessor.setClippingListener(new SjbzDspProcessor.ClippingListener() {
            @Override
            public void onClipping(final boolean isClipping) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isClipping && tvClippingIndicator != null) {
                            tvClippingIndicator.setText("¡CLIPPING DETECTADO! (Limiter -1dB)");
                            tvClippingIndicator.setTextColor(Color.parseColor("#F43F5E"));
                            tvClippingIndicator.setBackgroundColor(Color.parseColor("#40F43F5E"));
                            mainHandler.removeCallbacks(clippingResetRunnable);
                            mainHandler.postDelayed(clippingResetRunnable, 600);
                        }
                    }
                });
            }
        });
    }

    private void bindViews() {
        eqToolbar = findViewById(R.id.eqToolbar);
        if (eqToolbar != null) {
            eqToolbar.setNavigationOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        switchMasterDsp = findViewById(R.id.switchMasterDsp);
        switchMasterDspSection = findViewById(R.id.switchMasterDspSection);
        bannerGlobalBypass = findViewById(R.id.bannerGlobalBypass);
        scrollEqRoot = findViewById(R.id.scrollEqRoot);

        // Section A
        headerMaster = findViewById(R.id.headerMaster);
        contentMaster = findViewById(R.id.contentMaster);
        chevronMaster = findViewById(R.id.chevronMaster);
        visualizerView = findViewById(R.id.visualizerView);
        seekPreamp = findViewById(R.id.seekPreamp);
        tvPreampValue = findViewById(R.id.tvPreampValue);
        tvEffectivePreamp = findViewById(R.id.tvEffectivePreamp);
        tvClippingIndicator = findViewById(R.id.tvClippingIndicator);
        btnResetPreamp = findViewById(R.id.btnResetPreamp);
        switchGlobalAudioMode = findViewById(R.id.switchGlobalAudioMode);
        tvCaptureSessionCount = findViewById(R.id.tvCaptureSessionCount);

        // Section B
        headerPreGains = findViewById(R.id.headerPreGains);
        contentPreGains = findViewById(R.id.contentPreGains);
        chevronPreGains = findViewById(R.id.chevronPreGains);
        btnResetPreGains = findViewById(R.id.btnResetPreGains);
        seekPreGainBass = findViewById(R.id.seekPreGainBass);
        tvPreGainBassValue = findViewById(R.id.tvPreGainBassValue);
        seekPreGainMid = findViewById(R.id.seekPreGainMid);
        tvPreGainMidValue = findViewById(R.id.tvPreGainMidValue);
        seekPreGainTreble = findViewById(R.id.seekPreGainTreble);
        tvPreGainTrebleValue = findViewById(R.id.tvPreGainTrebleValue);

        // Section C
        headerEq32 = findViewById(R.id.headerEq32);
        contentEq32 = findViewById(R.id.contentEq32);
        chevronEq32 = findViewById(R.id.chevronEq32);
        btnResetEq = findViewById(R.id.btnResetEq);
        scrollFaders = findViewById(R.id.scrollFaders);
        llFadersContainer = findViewById(R.id.llFadersContainer);

        // Section D
        headerDynamics = findViewById(R.id.headerDynamics);
        contentDynamics = findViewById(R.id.contentDynamics);
        chevronDynamics = findViewById(R.id.chevronDynamics);

        switchBassBoost = findViewById(R.id.switchBassBoost);
        spinnerBassFreq = findViewById(R.id.spinnerBassFreq);
        seekBassGain = findViewById(R.id.seekBassGain);
        tvBassGainValue = findViewById(R.id.tvBassGainValue);

        switchHardwareEmulation = findViewById(R.id.switchHardwareEmulation);
        seekEmulationAmount = findViewById(R.id.seekEmulationAmount);
        tvEmulationAmount = findViewById(R.id.tvEmulationAmount);
        switchBluetoothBypass = findViewById(R.id.switchBluetoothBypass);
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus);

        switchMdrc = findViewById(R.id.switchMdrc);
        tvMdrcGrMeter = findViewById(R.id.tvMdrcGrMeter);
        seekMdrcThreshold = findViewById(R.id.seekMdrcThreshold);
        tvMdrcThresholdValue = findViewById(R.id.tvMdrcThresholdValue);
        seekMdrcRatio = findViewById(R.id.seekMdrcRatio);
        tvMdrcRatioValue = findViewById(R.id.tvMdrcRatioValue);

        seekMdrcSub = findViewById(R.id.seekMdrcSub);
        tvMdrcSub = findViewById(R.id.tvMdrcSub);
        seekMdrcLow = findViewById(R.id.seekMdrcLow);
        tvMdrcLow = findViewById(R.id.tvMdrcLow);
        seekMdrcMid = findViewById(R.id.seekMdrcMid);
        tvMdrcMid = findViewById(R.id.tvMdrcMid);
        seekMdrcHigh = findViewById(R.id.seekMdrcHigh);
        tvMdrcHigh = findViewById(R.id.tvMdrcHigh);
        seekMdrcAir = findViewById(R.id.seekMdrcAir);
        tvMdrcAir = findViewById(R.id.tvMdrcAir);

        // Section E
        headerPresets = findViewById(R.id.headerPresets);
        contentPresets = findViewById(R.id.contentPresets);
        chevronPresets = findViewById(R.id.chevronPresets);
        btnPresetFlat = findViewById(R.id.btnPresetFlat);
        btnPresetBass = findViewById(R.id.btnPresetBass);
        btnPresetRock = findViewById(R.id.btnPresetRock);
        btnPresetVocal = findViewById(R.id.btnPresetVocal);
        spinnerPresets = findViewById(R.id.spinnerPresets);
        btnSaveCustomPreset = findViewById(R.id.btnSaveCustomPreset);
        tvDetectedGenre = findViewById(R.id.tvDetectedGenre);
        tvDetectedGenreConfidence = findViewById(R.id.tvDetectedGenreConfidence);
        btnApplyGenreEq = findViewById(R.id.btnApplyGenreEq);
    }

    private void setupCollapsibleSections() {
        setupSectionToggle(headerMaster, contentMaster, chevronMaster, true);
        setupSectionToggle(headerPreGains, contentPreGains, chevronPreGains, true);
        setupSectionToggle(headerEq32, contentEq32, chevronEq32, true);
        setupSectionToggle(headerDynamics, contentDynamics, chevronDynamics, true);
        setupSectionToggle(headerPresets, contentPresets, chevronPresets, true);
    }

    private void setupSectionToggle(final View header, final View content, final ImageView chevron, boolean initiallyExpanded) {
        content.setVisibility(initiallyExpanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(initiallyExpanded ? 0f : -90f);

        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean isExpanded = content.getVisibility() == View.VISIBLE;
                content.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
                chevron.animate().rotation(isExpanded ? -90f : 0f).setDuration(200).start();
            }
        });
    }

    /**
     * Builds the 32 vertical sliders dynamically with exact 1:1 mapping to SjbzDspProcessor.BAND_FREQS.
     * Each SeekBar updates only its corresponding biquad filter.
     */
    private void setup32BandFaders() {
        llFadersContainer.removeAllViews();
        int bandCount = SjbzDspProcessor.BAND_COUNT;

        for (int i = 0; i < bandCount; i++) {
            final int bandIndex = i;
            float freq = SjbzDspProcessor.BAND_FREQS[i];
            String label = SjbzDspProcessor.BAND_LABELS[i];

            // Column Layout for each band
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(8, 0, 8, 0);

            // dB Value display text (+0.0 dB)
            final TextView tvGain = new TextView(this);
            tvGain.setTextSize(10f);
            tvGain.setTextColor(Color.parseColor("#38BDF8"));
            tvGain.setText("0.0");
            tvGain.setGravity(Gravity.CENTER);
            tvGain.setPadding(0, 0, 0, 4);
            eqValueTexts[i] = tvGain;
            col.addView(tvGain);

            // Container for rotated vertical SeekBar (190dp height, 48dp width)
            FrameLayout sliderFrame = new FrameLayout(this);
            LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(190));
            sliderFrame.setLayoutParams(frameLp);

            // Vertical SeekBar
            final SeekBar seekBar = new SeekBar(this);
            FrameLayout.LayoutParams seekLp = new FrameLayout.LayoutParams(dpToPx(190), dpToPx(48));
            seekLp.gravity = Gravity.CENTER;
            seekBar.setLayoutParams(seekLp);
            seekBar.setRotation(270f); // Rotate to vertical position
            seekBar.setMax(240);       // Range -12.0 dB to +12.0 dB (center = 120)
            seekBar.setProgress(120);

            // Styling
            seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
            seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
            seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#334155")));

            // Prevent horizontal & vertical scroll interception while touching vertical faders
            seekBar.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                        case MotionEvent.ACTION_MOVE:
                            if (scrollFaders != null) scrollFaders.requestDisallowInterceptTouchEvent(true);
                            if (scrollEqRoot != null) scrollEqRoot.requestDisallowInterceptTouchEvent(true);
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (scrollFaders != null) scrollFaders.requestDisallowInterceptTouchEvent(false);
                            if (scrollEqRoot != null) scrollEqRoot.requestDisallowInterceptTouchEvent(false);
                            break;
                    }
                    return false;
                }
            });

            // 1:1 Listener: Only updates this band's biquad
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    float gainDb = (progress - 120) / 10.0f;
                    tvGain.setText(String.format(Locale.US, "%+.1f", gainDb));
                    dspProcessor.setBandLevel(bandIndex, gainDb);

                    // Sync to global session manager
                    if (audioSessionManager != null) {
                        audioSessionManager.setBandGain(bandIndex, gainDb);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {}

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    saveSettings();
                }
            });

            sliderFrame.addView(seekBar);
            col.addView(sliderFrame);

            // Frequency Label (e.g., 20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200...)
            TextView tvFreq = new TextView(this);
            tvFreq.setTextSize(10f);
            tvFreq.setTextColor(Color.parseColor("#94A3B8"));
            tvFreq.setText(label);
            tvFreq.setGravity(Gravity.CENTER);
            tvFreq.setPadding(0, 6, 0, 0);
            col.addView(tvFreq);

            llFadersContainer.addView(col);
            eqSeekBars[i] = seekBar;
        }
    }

    private void setupEventListeners() {
        // Master Switch Toolbar
        switchMasterDsp.setOnCheckedChangeListener(new SwitchCompat.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchCompat buttonView, boolean isChecked) {
                dspProcessor.setMasterEnabled(isChecked);
                if (switchMasterDspSection.isChecked() != isChecked) {
                    switchMasterDspSection.setChecked(isChecked);
                }
                saveSettings();
            }
        });

        // Master Switch Section A
        switchMasterDspSection.setOnCheckedChangeListener(new SwitchCompat.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchCompat buttonView, boolean isChecked) {
                dspProcessor.setMasterEnabled(isChecked);
                if (switchMasterDsp.isChecked() != isChecked) {
                    switchMasterDsp.setChecked(isChecked);
                }
                saveSettings();
            }
        });

        // Master Preamp
        seekPreamp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float preampDb = (progress - 120) / 10.0f;
                tvPreampValue.setText(String.format(Locale.US, "%+.1f dB", preampDb));
                dspProcessor.setPreamp(preampDb);
                if (audioSessionManager != null) {
                    audioSessionManager.setPreampGain(preampDb);
                }
                updateHeadroomDisplay();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
            }
        });

        btnResetPreamp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekPreamp.setProgress(120);
                dspProcessor.setPreamp(0.0f);
                updateHeadroomDisplay();
                saveSettings();
            }
        });

        // Global Audio Service Mode Switch
        switchGlobalAudioMode.setOnCheckedChangeListener(new SwitchCompat.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchCompat buttonView, boolean isChecked) {
                if (isChecked) {
                    GlobalAudioService.Companion.start(EqActivity.this);
                } else {
                    GlobalAudioService.Companion.stop(EqActivity.this);
                }
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkGlobalBypassStatus();
                    }
                }, 300);
            }
        });

        // Section B: Pre-Gains Listeners
        seekPreGainBass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float gain = (progress - 120) / 10.0f;
                tvPreGainBassValue.setText(String.format(Locale.US, "%+.1f dB", gain));
                dspProcessor.setBassGain(gain);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
            }
        });

        seekPreGainMid.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float gain = (progress - 120) / 10.0f;
                tvPreGainMidValue.setText(String.format(Locale.US, "%+.1f dB", gain));
                dspProcessor.setMidGain(gain);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
            }
        });

        seekPreGainTreble.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float gain = (progress - 120) / 10.0f;
                tvPreGainTrebleValue.setText(String.format(Locale.US, "%+.1f dB", gain));
                dspProcessor.setTrebleGain(gain);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
            }
        });

        btnResetPreGains.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekPreGainBass.setProgress(120);
                seekPreGainMid.setProgress(120);
                seekPreGainTreble.setProgress(120);
                dspProcessor.setBassGain(0.0f);
                dspProcessor.setMidGain(0.0f);
                dspProcessor.setTrebleGain(0.0f);
                saveSettings();
            }
        });

        // Section C: Reset 32-Band EQ
        btnResetEq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < SjbzDspProcessor.BAND_COUNT; i++) {
                    eqSeekBars[i].setProgress(120);
                    dspProcessor.setBandLevel(i, 0.0f);
                    if (audioSessionManager != null) {
                        audioSessionManager.setBandGain(i, 0.0f);
                    }
                }
                saveSettings();
                Toast.makeText(EqActivity.this, "EQ 32 Bandas reseteado a Plano (0 dB)", Toast.LENGTH_SHORT).show();
            }
        });

        // Section D: Bass Boost
        switchBassBoost.setOnCheckedChangeListener(new SwitchCompat.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchCompat buttonView, boolean isChecked) {
                dspProcessor.setBassBoost(isChecked, dspProcessor.getBassBoostFreq(), dspProcessor.getBassBoostGain());
                updateHeadroomDisplay();
                saveSettings();
            }
        });

        final float[] bassFreqValues = {60.0f, 85.0f, 120.0f};
        ArrayAdapter<String> bassFreqAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"60 Hz (Sub)", "85 Hz (Punch)", "120 Hz (Mid-Bass)"});
        spinnerBassFreq.setAdapter(bassFreqAdapter);
        spinnerBassFreq.setSelection(1); // 85 Hz default
        spinnerBassFreq.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                dspProcessor.setBassBoost(switchBassBoost.isChecked(), bassFreqValues[position], dspProcessor.getBassBoostGain());
                saveSettings();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        seekBassGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float gain = progress / 10.0f;
                tvBassGainValue.setText(String.format(Locale.US, "+%.1f dB", gain));
                dspProcessor.setBassBoost(switchBassBoost.isChecked(), dspProcessor.getBassBoostFreq(), gain);
                if (audioSessionManager != null) {
                    audioSessionManager.setBassBoostGain(gain);
                }
                updateHeadroomDisplay();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
            }
        });

        // Section D: ATS2835P Hardware Emulation
        switchHardwareEmulation.setOnCheckedChangeListener(new SwitchCompat.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchCompat buttonView, boolean isChecked) {
                dspProcessor.setEmulationEnabled(isChecked);
                updateHeadroomDisplay();
                saveSettings();
            }
        });

        seekEmulationAmount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float amount = progress / 100.0f;
                tvEmulationAmount.setText(progress + "%");
                dspProcessor.setEmulationAmount(amount);
                updateHeadroomDisplay();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
            }
        });

        switchBluetoothBypass.setOnCheckedChangeListener(new SwitchCompat.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchCompat buttonView, boolean isChecked) {
                dspProcessor.setBluetoothAutoBypass(isChecked);
                updateHeadroomDisplay();
                saveSettings();
            }
        });

        // Section D: MDRC
        switchMdrc.setOnCheckedChangeListener(new SwitchCompat.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchCompat buttonView, boolean isChecked) {
                dspProcessor.setMdrcEnabled(isChecked);
                if (audioSessionManager != null) {
                    audioSessionManager.setMdrcEnabled(isChecked);
                }
                saveSettings();
            }
        });

        seekMdrcThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float thresh = -36.0f + progress;
                tvMdrcThresholdValue.setText(String.format(Locale.US, "Umbral: %.0f dB", thresh));
                dspProcessor.setMdrcDynamics(thresh, dspProcessor.getMdrcRatio());
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
            }
        });

        seekMdrcRatio.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float ratio = 1.0f + (progress / 10.0f);
                tvMdrcRatioValue.setText(String.format(Locale.US, "Ratio: %.1f:1", ratio));
                dspProcessor.setMdrcDynamics(dspProcessor.getMdrcThreshold(), ratio);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
            }
        });

        setupMdrcBandSlider(seekMdrcSub, tvMdrcSub, 0);
        setupMdrcBandSlider(seekMdrcLow, tvMdrcLow, 1);
        setupMdrcBandSlider(seekMdrcMid, tvMdrcMid, 2);
        setupMdrcBandSlider(seekMdrcHigh, tvMdrcHigh, 3);
        setupMdrcAirSlider(seekMdrcAir, tvMdrcAir, 4);

        // Section E: Presets
        setupPresetButtons();
    }

    private void setupMdrcBandSlider(SeekBar sb, final TextView tv, final int bandIndex) {
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                float gain = (progress - 120) / 10.0f;
                tv.setText(String.format(Locale.US, "%+.1f dB", gain));
                dspProcessor.setMdrcBandGain(bandIndex, gain);
                if (audioSessionManager != null) {
                    audioSessionManager.setMdrcBandGain(bandIndex, gain);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seek) {}

            @Override
            public void onStopTrackingTouch(SeekBar seek) {
                saveSettings();
            }
        });
    }

    private void setupMdrcAirSlider(SeekBar sb, final TextView tv, final int bandIndex) {
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                float gain = (progress - 120) / 10.0f;
                tv.setText(String.format(Locale.US, "%+.1f dB", gain));
                dspProcessor.setMdrcBandGain(bandIndex, gain);
                if (audioSessionManager != null) {
                    audioSessionManager.setMdrcBandGain(bandIndex, gain);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seek) {}

            @Override
            public void onStopTrackingTouch(SeekBar seek) {
                saveSettings();
            }
        });
    }

    private void setupPresetButtons() {
        btnPresetFlat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyPresetCurve(new float[32]);
                Toast.makeText(EqActivity.this, "Preset Flat Aplicado", Toast.LENGTH_SHORT).show();
            }
        });

        btnPresetBass.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float[] bassCurve = new float[32];
                // Boost 20Hz - 250Hz
                bassCurve[0] = 5.0f; bassCurve[1] = 5.0f; bassCurve[2] = 4.5f; bassCurve[3] = 4.0f;
                bassCurve[4] = 3.5f; bassCurve[5] = 3.0f; bassCurve[6] = 2.5f; bassCurve[7] = 2.0f;
                bassCurve[8] = 1.5f; bassCurve[9] = 1.0f; bassCurve[10] = 0.5f;
                applyPresetCurve(bassCurve);
                seekBassGain.setProgress(80); // +8.0 dB
                Toast.makeText(EqActivity.this, "Preset Bass Boost Aplicado", Toast.LENGTH_SHORT).show();
            }
        });

        btnPresetRock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float[] rockCurve = new float[32];
                // V-shaped classic rock curve
                rockCurve[0] = 4.0f; rockCurve[1] = 4.0f; rockCurve[2] = 3.5f; rockCurve[3] = 3.0f;
                rockCurve[4] = 2.0f; rockCurve[5] = 1.5f; rockCurve[6] = 0.5f; rockCurve[7] = 0.0f;
                rockCurve[16] = -1.0f; rockCurve[17] = -1.5f; rockCurve[18] = -1.0f;
                rockCurve[26] = 2.5f; rockCurve[27] = 3.0f; rockCurve[28] = 3.5f; rockCurve[29] = 4.0f;
                applyPresetCurve(rockCurve);
                Toast.makeText(EqActivity.this, "Preset Rock Aplicado", Toast.LENGTH_SHORT).show();
            }
        });

        btnPresetVocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float[] vocalCurve = new float[32];
                // Cut sub bass, boost 1k - 4k speech intelligibility
                vocalCurve[0] = -3.0f; vocalCurve[1] = -3.0f; vocalCurve[2] = -2.5f;
                vocalCurve[16] = 2.0f; vocalCurve[17] = 3.0f; vocalCurve[18] = 3.5f;
                vocalCurve[19] = 3.0f; vocalCurve[20] = 2.5f; vocalCurve[21] = 2.0f;
                applyPresetCurve(vocalCurve);
                Toast.makeText(EqActivity.this, "Preset Vocal Boost Aplicado", Toast.LENGTH_SHORT).show();
            }
        });

        // Preset Spinner
        final List<String> presetNames = new ArrayList<>(Arrays.asList(
                "Studio Master (Flat)", "Bass Heavy Sub", "Rock & Roll V-Shape", "Acoustic / Vocal", "Electronic & EDM"
        ));
        final ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presetNames);
        spinnerPresets.setAdapter(presetAdapter);
        spinnerPresets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0: btnPresetFlat.performClick(); break;
                    case 1: btnPresetBass.performClick(); break;
                    case 2: btnPresetRock.performClick(); break;
                    case 3: btnPresetVocal.performClick(); break;
                    case 4:
                        float[] edm = new float[32];
                        edm[0] = 5f; edm[1] = 5f; edm[2] = 4.5f; edm[3] = 4f; edm[4] = 3f;
                        edm[28] = 2.5f; edm[29] = 3f; edm[30] = 3.5f; edm[31] = 4f;
                        applyPresetCurve(edm);
                        break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnSaveCustomPreset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSavePresetDialog(presetNames, presetAdapter);
            }
        });

        btnApplyGenreEq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnPresetRock.performClick();
                Toast.makeText(EqActivity.this, "Curva adaptada al género Rock / Metal", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void applyPresetCurve(float[] curve) {
        if (curve == null) return;
        int count = Math.min(curve.length, SjbzDspProcessor.BAND_COUNT);
        for (int i = 0; i < count; i++) {
            float gain = curve[i];
            int progress = Math.round((gain * 10.0f) + 120.0f);
            if (eqSeekBars[i] != null) {
                eqSeekBars[i].setProgress(progress);
            }
            dspProcessor.setBandLevel(i, gain);
            if (audioSessionManager != null) {
                audioSessionManager.setBandGain(i, gain);
            }
        }
        saveSettings();
    }

    private void showSavePresetDialog(final List<String> presetNames, final ArrayAdapter<String> adapter) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Guardar Preset de Estudio");

        final EditText input = new EditText(this);
        input.setHint("Nombre del Preset (ej. Mi Mezcla Pro)");
        builder.setView(input);

        builder.setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    presetNames.add(name);
                    adapter.notifyDataSetChanged();
                    spinnerPresets.setSelection(presetNames.size() - 1);
                    Toast.makeText(EqActivity.this, "Preset '" + name + "' guardado", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancelar", null);
        builder.show();
    }

    private void updateHeadroomDisplay() {
        if (dspProcessor == null || tvEffectivePreamp == null) return;
        float effPreamp = dspProcessor.getEffectivePreampDb();
        tvEffectivePreamp.setText(String.format(Locale.US, "Auto-Headroom: Preamp Efectivo = %+.1f dB", effPreamp));
    }

    private void checkGlobalBypassStatus() {
        boolean isGlobalRunning = GlobalAudioService.Companion.isServiceRunning() ||
                GlobalAudioService.isGlobalAudioEnabled() ||
                (audioSessionManager != null && audioSessionManager.isGlobalAudioEnabled());

        dspProcessor.setGlobalBypass(isGlobalRunning);

        if (bannerGlobalBypass != null) {
            bannerGlobalBypass.setVisibility(isGlobalRunning ? View.VISIBLE : View.GONE);
        }

        if (switchGlobalAudioMode != null) {
            switchGlobalAudioMode.setChecked(isGlobalRunning);
        }

        if (tvCaptureSessionCount != null && audioSessionManager != null) {
            tvCaptureSessionCount.setText("Sesión del Sistema: " + audioSessionManager.getActiveSessionsCount() + " activas");
        }
    }

    private void loadSavedSettings() {
        boolean masterOn = prefs.getBoolean(KEY_MASTER_ENABLED, true);
        switchMasterDsp.setChecked(masterOn);
        switchMasterDspSection.setChecked(masterOn);
        dspProcessor.setMasterEnabled(masterOn);

        float preamp = prefs.getFloat(KEY_PREAMP, 0.0f);
        seekPreamp.setProgress(Math.round((preamp * 10.0f) + 120.0f));
        dspProcessor.setPreamp(preamp);

        float preBass = prefs.getFloat(KEY_PREGAIN_BASS, 0.0f);
        seekPreGainBass.setProgress(Math.round((preBass * 10.0f) + 120.0f));
        dspProcessor.setBassGain(preBass);

        float preMid = prefs.getFloat(KEY_PREGAIN_MID, 0.0f);
        seekPreGainMid.setProgress(Math.round((preMid * 10.0f) + 120.0f));
        dspProcessor.setMidGain(preMid);

        float preTreble = prefs.getFloat(KEY_PREGAIN_TREBLE, 0.0f);
        seekPreGainTreble.setProgress(Math.round((preTreble * 10.0f) + 120.0f));
        dspProcessor.setTrebleGain(preTreble);

        // Load 32 bands
        for (int i = 0; i < SjbzDspProcessor.BAND_COUNT; i++) {
            float gain = prefs.getFloat("eq_band_" + i, 0.0f);
            if (eqSeekBars[i] != null) {
                eqSeekBars[i].setProgress(Math.round((gain * 10.0f) + 120.0f));
            }
            dspProcessor.setBandLevel(i, gain);
        }

        boolean bbOn = prefs.getBoolean(KEY_BASSBOOST_ENABLED, true);
        float bbGain = prefs.getFloat(KEY_BASSBOOST_GAIN, 4.0f);
        switchBassBoost.setChecked(bbOn);
        seekBassGain.setProgress(Math.round(bbGain * 10.0f));
        dspProcessor.setBassBoost(bbOn, 85.0f, bbGain);

        boolean emuOn = prefs.getBoolean(KEY_EMU_ENABLED, true);
        float emuAmt = prefs.getFloat(KEY_EMU_AMOUNT, 0.8f);
        switchHardwareEmulation.setChecked(emuOn);
        seekEmulationAmount.setProgress(Math.round(emuAmt * 100.0f));
        dspProcessor.setEmulationEnabled(emuOn);
        dspProcessor.setEmulationAmount(emuAmt);

        boolean mdrcOn = prefs.getBoolean(KEY_MDRC_ENABLED, true);
        switchMdrc.setChecked(mdrcOn);
        dspProcessor.setMdrcEnabled(mdrcOn);
    }

    private void saveSettings() {
        if (prefs == null || dspProcessor == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_MASTER_ENABLED, dspProcessor.isMasterEnabled());
        editor.putFloat(KEY_PREAMP, dspProcessor.getPreamp());
        editor.putFloat(KEY_PREGAIN_BASS, dspProcessor.getBassGain());
        editor.putFloat(KEY_PREGAIN_MID, dspProcessor.getMidGain());
        editor.putFloat(KEY_PREGAIN_TREBLE, dspProcessor.getTrebleGain());

        for (int i = 0; i < SjbzDspProcessor.BAND_COUNT; i++) {
            editor.putFloat("eq_band_" + i, dspProcessor.getBandGain(i));
        }

        editor.putBoolean(KEY_BASSBOOST_ENABLED, dspProcessor.isBassBoostEnabled());
        editor.putFloat(KEY_BASSBOOST_GAIN, dspProcessor.getBassBoostGain());
        editor.putBoolean(KEY_EMU_ENABLED, dspProcessor.isEmulationEnabled());
        editor.putFloat(KEY_EMU_AMOUNT, dspProcessor.getEmulationAmount());
        editor.putBoolean(KEY_MDRC_ENABLED, dspProcessor.isMdrcEnabled());
        editor.apply();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
