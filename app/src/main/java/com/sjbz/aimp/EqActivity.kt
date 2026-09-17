package com.sjbz.aimp

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import com.sjbz.aimp.audio.EqualizerProcessor
import com.sjbz.aimp.audio.GlobalAudioSessionManager
import com.sjbz.aimp.audio.MDRCProcessor
import com.sjbz.aimp.audio.PresetManager
import com.sjbz.aimp.audio.SjbzAudioEngine
import com.sjbz.aimp.audio.SjbzDspProcessor
import com.sjbz.aimp.model.EqPreset
import com.sjbz.aimp.service.GlobalAudioService
import com.sjbz.aimp.ui.AudioSpectrumVisualizerView

/**
 * EqActivity PRO - Professional 32-Band ISO Equalizer Studio.
 *
 * Implements:
 * - HorizontalScrollView with 32 vertical ISO sliders (-12 dB to +12 dB) with 60ms debounce.
 * - Persistent preferences under "sjbz_dsp_pro".
 * - Bass Boost low-shelf RBJ: Spinner (60/85/120Hz) + Slider (0-12 dB) + Enable switch.
 * - Master DSP switch.
 * - Quick preset buttons: Flat, Bass, Rock, Vocal + Genre-adaptive auto EQ.
 * - Real-time 60fps Spectrum Analyzer with 2048 mono FFT streaming.
 */
class EqActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "sjbz_dsp_pro"
        private const val DEBOUNCE_MS = 60L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var presetManager: PresetManager
    private var dspProcessor: SjbzDspProcessor? = null

    // UI Widgets
    private lateinit var toolbar: Toolbar
    private lateinit var switchMasterDsp: SwitchCompat
    private lateinit var visualizerView: AudioSpectrumVisualizerView
    private lateinit var tvRecognizedGenre: TextView
    private lateinit var tvGenreTrackInfo: TextView
    private lateinit var btnAutoEqGenre: Button

    // Quick Presets
    private lateinit var btnPresetFlat: Button
    private lateinit var btnPresetBass: Button
    private lateinit var btnPresetRock: Button
    private lateinit var btnPresetVocal: Button
    private lateinit var spinnerPresets: Spinner
    private lateinit var btnSavePreset: ImageButton
    private lateinit var btnExportPreset: ImageButton

    // Master Controls
    private lateinit var tvPreampValue: TextView
    private lateinit var seekBarPreamp: SeekBar
    private lateinit var switchBassBoost: SwitchCompat
    private lateinit var tvBassBoostValue: TextView
    private lateinit var spinnerBassFreq: Spinner
    private lateinit var seekBarBassBoost: SeekBar

    // ATS2835P Hardware Emulation Controls
    private lateinit var switchEmu: SwitchCompat
    private lateinit var tvEmuStatus: TextView
    private lateinit var tvEmuAmountValue: TextView
    private lateinit var seekBarEmuAmount: SeekBar
    private lateinit var switchBtAutoBypass: SwitchCompat
    private lateinit var tvBtBypassStatus: TextView

    // Global System Audio Controls
    private lateinit var switchGlobalAudio: SwitchCompat
    private lateinit var tvGlobalAudioStatus: TextView
    private lateinit var tvActiveSessionsCount: TextView
    private lateinit var seekBarSystemVolume: SeekBar
    private lateinit var tvSystemVolumeValue: TextView

    // 5-Band MDRC Multiband Compressor Controls
    private lateinit var switchMdrc: SwitchCompat
    private lateinit var tvMdrcStatus: TextView
    private lateinit var tvMdrcGainReduction: TextView
    private lateinit var seekBarMdrcThreshold: SeekBar
    private lateinit var tvMdrcThresholdValue: TextView
    private lateinit var seekBarMdrcRatio: SeekBar
    private lateinit var tvMdrcRatioValue: TextView
    private val mdrcBandSeekBars = ArrayList<SeekBar>()
    private val mdrcBandValueLabels = ArrayList<TextView>()

    private val mdrcGainReductionTicker = object : Runnable {
        override fun run() {
            if (!isDestroyed &&!isFinishing) {
                val gr = dspProcessor?.getMdrcGainReduction()?: 0f
                tvMdrcGainReduction.text = String.format("GR: -%.1f dB", gr)
                debounceHandler.postDelayed(this, 120L)
            }
        }
    }

    // 32-Band EQ container & faders
    private lateinit var llEqBandsContainer: LinearLayout
    private lateinit var btnResetEq: Button
    private val bandSeekBars = ArrayList<SeekBar>()
    private val bandValueLabels = ArrayList<TextView>()

    // 60ms Debounce Handler & Runnables
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val bandDebounceRunnables = arrayOfNulls<Runnable>(EqualizerProcessor.BAND_COUNT)
    private var preampDebounceRunnable: Runnable? = null
    private var bassDebounceRunnable: Runnable? = null
    private var emuDebounceRunnable: Runnable? = null
    private var mdrcDebounceRunnable: Runnable? = null

    private var isUpdatingUiFromCode = false
    private val cyanColor = Color.parseColor("#00E5FF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        presetManager = PresetManager(this)

        // Use shared DSP processor so UI edits the engine that actually renders audio
        dspProcessor = SjbzAudioEngine.processor

        bindViews()
        setupToolbar()
        setupSpectrumVisualizer()
        setupGenreRecognition()
        setupMasterControls()
        setupEmuControls()
        setupGlobalAudioControls()
        setupMdrcControls()
        setupPresetControls()
        build32BandSliders()

        // Restore all parameters from sjbz_dsp_pro
        restoreAllDspParameters()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.eqToolbar)
        switchMasterDsp = findViewById(R.id.switchMasterDsp)
        visualizerView = findViewById(R.id.visualizerView)
        tvRecognizedGenre = findViewById(R.id.tvRecognizedGenre)
        tvGenreTrackInfo = findViewById(R.id.tvGenreTrackInfo)
        btnAutoEqGenre = findViewById(R.id.btnAutoEqGenre)

        btnPresetFlat = findViewById(R.id.btnPresetFlat)
        btnPresetBass = findViewById(R.id.btnPresetBass)
        btnPresetRock = findViewById(R.id.btnPresetRock)
        btnPresetVocal = findViewById(R.id.btnPresetVocal)
        spinnerPresets = findViewById(R.id.spinnerPresets)
        btnSavePreset = findViewById(R.id.btnSavePreset)
        btnExportPreset = findViewById(R.id.btnExportPreset)

        tvPreampValue = findViewById(R.id.tvPreampValue)
        seekBarPreamp = findViewById(R.id.seekBarPreamp)
        switchBassBoost = findViewById(R.id.switchBassBoost)
        tvBassBoostValue = findViewById(R.id.tvBassBoostValue)
        spinnerBassFreq = findViewById(R.id.spinnerBassFreq)
        seekBarBassBoost = findViewById(R.id.seekBarBassBoost)

        switchEmu = findViewById(R.id.switchEmu)
        tvEmuStatus = findViewById(R.id.tvEmuStatus)
        tvEmuAmountValue = findViewById(R.id.tvEmuAmountValue)
        seekBarEmuAmount = findViewById(R.id.seekBarEmuAmount)
        switchBtAutoBypass = findViewById(R.id.switchBtAutoBypass)
        tvBtBypassStatus = findViewById(R.id.tvBtBypassStatus)

        // Global System Audio
        switchGlobalAudio = findViewById(R.id.switchGlobalAudio)
        tvGlobalAudioStatus = findViewById(R.id.tvGlobalAudioStatus)
        tvActiveSessionsCount = findViewById(R.id.tvActiveSessionsCount)
        seekBarSystemVolume = findViewById(R.id.seekBarSystemVolume)
        tvSystemVolumeValue = findViewById(R.id.tvSystemVolumeValue)

        // 5-Band MDRC Multiband Compressor
        switchMdrc = findViewById(R.id.switchMdrc)
        tvMdrcStatus = findViewById(R.id.tvMdrcStatus)
        tvMdrcGainReduction = findViewById(R.id.tvMdrcGainReduction)
        seekBarMdrcThreshold = findViewById(R.id.seekBarMdrcThreshold)
        tvMdrcThresholdValue = findViewById(R.id.tvMdrcThresholdValue)
        seekBarMdrcRatio = findViewById(R.id.seekBarMdrcRatio)
        tvMdrcRatioValue = findViewById(R.id.tvMdrcRatioValue)

        mdrcBandSeekBars.clear()
        mdrcBandValueLabels.clear()
        mdrcBandSeekBars.add(findViewById(R.id.seekBarMdrcBand0))
        mdrcBandSeekBars.add(findViewById(R.id.seekBarMdrcBand1))
        mdrcBandSeekBars.add(findViewById(R.id.seekBarMdrcBand2))
        mdrcBandSeekBars.add(findViewById(R.id.seekBarMdrcBand3))
        mdrcBandSeekBars.add(findViewById(R.id.seekBarMdrcBand4))

        mdrcBandValueLabels.add(findViewById(R.id.tvMdrcBand0Value))
        mdrcBandValueLabels.add(findViewById(R.id.tvMdrcBand1Value))
        mdrcBandValueLabels.add(findViewById(R.id.tvMdrcBand2Value))
        mdrcBandValueLabels.add(findViewById(R.id.tvMdrcBand3Value))
        mdrcBandValueLabels.add(findViewById(R.id.tvMdrcBand4Value))

        llEqBandsContainer = findViewById(R.id.llEqBandsContainer)
        btnResetEq = findViewById(R.id.btnResetEq)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        switchMasterDsp.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor?.masterEnabled = isChecked
            prefs.edit().putBoolean("master_enabled", isChecked).apply()
            updateControlsAlpha(isChecked)
        }
    }

    private fun setupSpectrumVisualizer() {
        // Connect real-time 60fps mono 2048 FFT listener on shared processor.
        // Wrap to post on UI thread to avoid View threading issues.
        dspProcessor?.fftListener = { samples ->
            runOnUiThread { visualizerView.onAudioData(samples) }
        }
    }

    private fun setupGenreRecognition() {
        val currentProfile = GlobalAudioSessionManager.getInstance(this).currentProfile
        val genre = currentProfile.presetName
        val title = currentProfile.appName

        tvRecognizedGenre.text = genre
        tvGenreTrackInfo.text = "$title • Perfil activo"

        btnAutoEqGenre.setOnClickListener {
            applyGenrePreset(genre)
            Toast.makeText(this, "EQ adaptado al perfil: $genre", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMasterControls() {
        // 1. Preamp Slider (-12 dB to +12 dB, 120 is 0 dB)
        seekBarPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvPreampValue.text = String.format("%+.1f dB", db)

                if (fromUser &&!isUpdatingUiFromCode) {
                    preampDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        dspProcessor?.setPreamp(db)
                        prefs.edit().putFloat("preamp_db", db).apply()
                        syncGlobalAudioDsp()
                    }
                    preampDebounceRunnable = r
                    debounceHandler.postDelayed(r, DEBOUNCE_MS)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 2. Bass Boost Frequency Spinner (60 Hz, 85 Hz, 120 Hz)
        val freqOptions = arrayOf("60 Hz (Sub Bass)", "85 Hz (Punch Bass)", "120 Hz (Mid Bass)")
        val freqAdapter = ArrayAdapter(this@EqActivity, android.R.layout.simple_spinner_item, freqOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBassFreq.adapter = freqAdapter

        spinnerBassFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUiFromCode) return
                val freq = when (position) {
                    0 -> 60.0f
                    1 -> 85.0f
                    else -> 120.0f
                }
                val enabled = switchBassBoost.isChecked
                val gain = seekBarBassBoost.progress / 10.0f
                dspProcessor?.setBassBoost(enabled, freq, gain)
                prefs.edit().putFloat("bass_freq", freq).apply()
                syncGlobalAudioDsp()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 3. Bass Boost Enable Switch
        switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            val freq = getSelectedBassFreq()
            val gain = seekBarBassBoost.progress / 10.0f
            dspProcessor?.setBassBoost(isChecked, freq, gain)
            prefs.edit().putBoolean("bass_enabled", isChecked).apply()
            seekBarBassBoost.isEnabled = isChecked
            spinnerBassFreq.isEnabled = isChecked
            syncGlobalAudioDsp()
        }

        // 4. Bass Boost Gain Slider (0 to 12 dB, 120 is 12 dB)
        seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progress / 10.0f
                tvBassBoostValue.text = String.format("+%.1f dB", db)

                if (fromUser &&!isUpdatingUiFromCode) {
                    bassDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        val enabled = switchBassBoost.isChecked
                        val freq = getSelectedBassFreq()
                        dspProcessor?.setBassBoost(enabled, freq, db)
                        prefs.edit().putFloat("bass_gain", db).apply()
                        syncGlobalAudioDsp()
                    }
                    bassDebounceRunnable = r
                    debounceHandler.postDelayed(r, DEBOUNCE_MS)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Reset EQ Button (0 dB on all 32 bands)
        btnResetEq.setOnClickListener {
            resetAllBandsToZero()
        }
    }

    private fun setupEmuControls() {
        switchEmu.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor?.setEmulationEnabled(isChecked)
            prefs.edit().putBoolean("emu_enabled", isChecked).apply()
            val masterActive = dspProcessor?.masterEnabled!= false
            seekBarEmuAmount.isEnabled = isChecked && masterActive
            updateEmuStatus()
        }

        seekBarEmuAmount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val amount = progress / 100.0f
                tvEmuAmountValue.text = "$progress%"

                if (fromUser &&!isUpdatingUiFromCode) {
                    emuDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        dspProcessor?.setEmulationAmount(amount)
                        prefs.edit().putFloat("emu_amount", amount).apply()
                    }
                    emuDebounceRunnable = r
                    debounceHandler.postDelayed(r, DEBOUNCE_MS)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchBtAutoBypass.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor?.setBluetoothAutoBypass(isChecked)
            prefs.edit().putBoolean("bt_auto_bypass", isChecked).apply()
            updateEmuStatus()
        }
    }

    private fun updateEmuStatus() {
        val dsp = dspProcessor?: return
        val isBt = dsp.isBluetoothConnected()
        val isAutoBypass = dsp.isBluetoothAutoBypass()
        val isEnabled = dsp.isEmulationEnabled()

        if (isBt && isAutoBypass) {
            tvEmuStatus.text = "Auto-Bypass activo (Dispositivo Bluetooth detectado)"
            tvEmuStatus.setTextColor(Color.parseColor("#F59E0B"))
            tvBtBypassStatus.text = "Bluetooth A2DP conectado: Emu omitido para evitar doble procesado"
        } else if (isEnabled) {
            tvEmuStatus.text = "Activo (4 Biquads + Limiter -6dB + Wet/Dry Mix)"
            tvEmuStatus.setTextColor(cyanColor)
            tvBtBypassStatus.text = if (isBt) "Bluetooth conectado (Bypass desactivado)" else "Listo para auto-bypass al conectar BT"
        } else {
            tvEmuStatus.text = "Desactivado (Bypass manual)"
            tvEmuStatus.setTextColor(Color.parseColor("#94A3B8"))
            tvBtBypassStatus.text = "Evita doble procesado al conectar auriculares BT"
        }
    }

    private fun setupGlobalAudioControls() {
        val globalManager = GlobalAudioSessionManager.getInstance(this)

        // Volume initialization
        val maxVol = globalManager.getMaxSystemVolume()
        val curVol = globalManager.getSystemVolume()
        seekBarSystemVolume.max = maxVol
        seekBarSystemVolume.progress = curVol
        updateSystemVolumeLabel(curVol, maxVol)

        seekBarSystemVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSystemVolumeLabel(progress, maxVol)
                if (fromUser) {
                    globalManager.setSystemVolume(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Listen for external hardware volume button changes
        globalManager.onSystemVolumeChangedListener = { vol, max ->
            if (!isFinishing &&!isDestroyed) {
                seekBarSystemVolume.progress = vol
                updateSystemVolumeLabel(vol, max)
            }
        }

        // Active sessions listener
        globalManager.onActiveSessionsChangedListener = { count, pkgs ->
            if (!isFinishing &&!isDestroyed) {
                if (count > 0) {
                    val pkgText = if (pkgs.isNotEmpty()) " (${pkgs.joinToString(", ")})" else ""
                    tvActiveSessionsCount.text = "$count activa(s)$pkgText"
                    tvActiveSessionsCount.setTextColor(cyanColor)
                } else {
                    tvActiveSessionsCount.text = if (switchGlobalAudio.isChecked) "1 activa (Global)" else "Inactivo"
                    tvActiveSessionsCount.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        switchGlobalAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                GlobalAudioService.start(this@EqActivity)
                tvGlobalAudioStatus.text = "Activo • Procesando salida de audio del sistema"
                tvGlobalAudioStatus.setTextColor(cyanColor)
                tvActiveSessionsCount.text = "1 activa (Global)"
                tvActiveSessionsCount.setTextColor(cyanColor)
                syncGlobalAudioDsp()
            } else {
                GlobalAudioService.stop(this@EqActivity)
                tvGlobalAudioStatus.text = "Procesa Spotify, YouTube, Chrome y apps del sistema"
                tvGlobalAudioStatus.setTextColor(Color.parseColor("#94A3B8"))
                tvActiveSessionsCount.text = "Inactivo"
                tvActiveSessionsCount.setTextColor(Color.parseColor("#94A3B8"))
            }
            prefs.edit().putBoolean("global_audio_enabled", isChecked).apply()
        }
    }

    private fun updateSystemVolumeLabel(current: Int, max: Int) {
        val pct = if (max > 0) (current * 100) / max else 0
        tvSystemVolumeValue.text = "$pct%"
    }

    private fun setupMdrcControls() {
        switchMdrc.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor?.setMdrcEnabled(isChecked)
            prefs.edit().putBoolean("mdrc_enabled", isChecked).apply()
            updateMdrcControlsAlpha(isChecked)
            syncGlobalAudioDsp()
        }

        // Threshold (-36 dB to 0 dB, progress 0 to 36)
        seekBarMdrcThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 36).toFloat()
                tvMdrcThresholdValue.text = String.format("%.0f dB", db)
                if (fromUser &&!isUpdatingUiFromCode) {
                    debounceMdrcDynamics()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Ratio (1.0:1 to 8.0:1, progress 0 to 70 -> 1.0 + progress / 10f)
        seekBarMdrcRatio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = 1.0f + progress / 10.0f
                tvMdrcRatioValue.text = String.format("%.1f:1", ratio)
                if (fromUser &&!isUpdatingUiFromCode) {
                    debounceMdrcDynamics()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 5 Band Makeup Gains (-12 dB to +12 dB, progress 0 to 240, 120 is 0 dB)
        for (b in 0 until 5) {
            val sb = mdrcBandSeekBars.getOrNull(b)?: continue
            val tv = mdrcBandValueLabels.getOrNull(b)
            val bandIndex = b
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gain = (progress - 120) / 10.0f
                    tv?.text = String.format("%+.1f dB", gain)
                    if (fromUser &&!isUpdatingUiFromCode) {
                        dspProcessor?.setMdrcBandGain(bandIndex, gain)
                        prefs.edit().putFloat("mdrc_band_$bandIndex", gain).apply()
                        syncGlobalAudioDsp()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        // Start GR meter loop
        debounceHandler.post(mdrcGainReductionTicker)
    }

    private fun debounceMdrcDynamics() {
        mdrcDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        val r = Runnable {
            val thresh = (seekBarMdrcThreshold.progress - 36).toFloat()
            val ratio = 1.0f + (seekBarMdrcRatio.progress / 10.0f)
            dspProcessor?.setMdrcDynamics(thresh, ratio)
            prefs.edit().putFloat("mdrc_threshold", thresh).putFloat("mdrc_ratio", ratio).apply()
            syncGlobalAudioDsp()
        }
        mdrcDebounceRunnable = r
        debounceHandler.postDelayed(r, DEBOUNCE_MS)
    }

    private fun updateMdrcControlsAlpha(enabled: Boolean) {
        seekBarMdrcThreshold.isEnabled = enabled
        seekBarMdrcRatio.isEnabled = enabled
        mdrcBandSeekBars.forEach { it.isEnabled = enabled }
        tvMdrcStatus.text = if (enabled) "Crossover 5 Vías + Dinámica ATS2835P" else "MDRC Desactivado (Bypass)"
    }

    private fun syncGlobalAudioDsp() {
        val currentGains = FloatArray(EqualizerProcessor.BAND_COUNT)
        for (i in 0 until minOf(bandSeekBars.size, currentGains.size)) {
            currentGains[i] = (bandSeekBars[i].progress - 120) / 10.0f
        }
        val preamp = (seekBarPreamp.progress - 120) / 10.0f
        val bassEnabled = switchBassBoost.isChecked
        val bassFreq = getSelectedBassFreq()
        val bassGain = if (bassEnabled) seekBarBassBoost.progress / 10.0f else 0f
        val mdrcEnabled = switchMdrc.isChecked
        val mdrcGains = FloatArray(5)
        for (i in 0 until minOf(mdrcBandSeekBars.size, 5)) {
            mdrcGains[i] = (mdrcBandSeekBars[i].progress - 120) / 10.0f
        }
        val thresh = (seekBarMdrcThreshold.progress - 36).toFloat()
        val ratio = 1.0f + (seekBarMdrcRatio.progress / 10.0f)

        GlobalAudioSessionManager.getInstance(this).updateEqParams(
            gains = currentGains,
            preampDb = preamp,
            bassDb = bassGain,
            bassFreqHz = bassFreq,
            mdrcEnabled = mdrcEnabled,
            mdrcGains = mdrcGains,
            mdrcThresholdDb = thresh,
            mdrcRatio = ratio
        )
    }

    private fun getSelectedBassFreq(): Float {
        return when (spinnerBassFreq.selectedItemPosition) {
            0 -> 60.0f
            1 -> 85.0f
            else -> 120.0f
        }
    }

    private fun setupPresetControls() {
        btnPresetFlat.setOnClickListener { applyPresetByName("Flat") }
        btnPresetBass.setOnClickListener { applyPresetByName("Bass") }
        btnPresetRock.setOnClickListener { applyPresetByName("Rock") }
        btnPresetVocal.setOnClickListener { applyPresetByName("Vocal") }

        refreshPresetSpinner()

        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUiFromCode) return
                val allPresets = presetManager.getAllPresets()
                if (position in allPresets.indices) {
                    loadPresetIntoUi(allPresets[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSavePreset.setOnClickListener {
            saveCurrentAsCustomPreset()
        }

        btnExportPreset.setOnClickListener {
            Toast.makeText(this, "Presets guardados en memoria sjbz_dsp_pro", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPresetSpinner() {
        val names = presetManager.getAllPresets().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerPresets.adapter = adapter
    }

    /**
     * Builds 32 vertical sliders in HorizontalScrollView for the 32 ISO center frequencies.
     * Implements 60ms debounce for high-performance zero-lag updates.
     */
    private fun build32BandSliders() {
        llEqBandsContainer.removeAllViews()
        bandSeekBars.clear()
        bandValueLabels.clear()

        val bandCount = EqualizerProcessor.BAND_COUNT
        val labels = EqualizerProcessor.BAND_LABELS
        val density = resources.displayMetrics.density

        for (i in 0 until bandCount) {
            val bandCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (density * 52).toInt(),
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(2, 6, 2, 6)
            }

            // Top: Gain value label (+3.5 dB, 0.0 dB, etc.)
            val tvGain = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "0.0"
                textSize = 9.5f
                setTextColor(cyanColor)
                gravity = Gravity.CENTER
            }
            bandCol.addView(tvGain)
            bandValueLabels.add(tvGain)

            // Middle: Vertical fader container with rotated SeekBar
            val faderContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    0,
                    1.0f
                )
                gravity = Gravity.CENTER
            }

            val seekBar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (density * 150).toInt(),
                    (density * 34).toInt()
                )
                rotation = 270f
                max = 240 // -12.0 dB to +12.0 dB (120 is 0 dB)
                progress = 120
                progressTintList = ColorStateList.valueOf(cyanColor)
                thumbTintList = ColorStateList.valueOf(cyanColor)
            }

            val bandIndex = i
            // Disallow parent HorizontalScrollView intercepting touch while sliding vertical fader
            seekBar.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gainDb = (progress - 120) / 10.0f
                    tvGain.text = String.format("%+.1f", gainDb)

                    if (fromUser &&!isUpdatingUiFromCode) {
                        bandDebounceRunnables[bandIndex]?.let { debounceHandler.removeCallbacks(it) }
                        val r = Runnable {
                            dspProcessor?.setBandGain(bandIndex, gainDb)
                            prefs.edit().putFloat("band_gain_$bandIndex", gainDb).apply()
                            syncGlobalAudioDsp()
                        }
                        bandDebounceRunnables[bandIndex] = r
                        debounceHandler.postDelayed(r, DEBOUNCE_MS)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            faderContainer.addView(seekBar)
            bandCol.addView(faderContainer)
            bandSeekBars.add(seekBar)

            // Bottom: Frequency Label (e.g. "20", "31", "1k", "20k")
            val tvFreq = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = labels[i]
                textSize = 9.5f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
            }
            bandCol.addView(tvFreq)

            llEqBandsContainer.addView(bandCol)
        }
    }

    /**
     * Restores all settings from sjbz_dsp_pro SharedPreferences.
     */
    private fun restoreAllDspParameters() {
        isUpdatingUiFromCode = true
        try {
            val masterEnabled = prefs.getBoolean("master_enabled", true)
            switchMasterDsp.isChecked = masterEnabled
            dspProcessor?.masterEnabled = masterEnabled
            updateControlsAlpha(masterEnabled)

            val preampDb = prefs.getFloat("preamp_db", 0.0f)
            val preampProgress = (preampDb * 10f + 120).toInt().coerceIn(0, 240)
            seekBarPreamp.progress = preampProgress
            tvPreampValue.text = String.format("%+.1f dB", preampDb)
            dspProcessor?.setPreamp(preampDb)

            val bassEnabled = prefs.getBoolean("bass_enabled", true)
            val bassFreq = prefs.getFloat("bass_freq", 85.0f)
            val bassGain = prefs.getFloat("bass_gain", 4.0f)

            switchBassBoost.isChecked = bassEnabled
            val freqPos = when (bassFreq) {
                60.0f -> 0
                85.0f -> 1
                else -> 2
            }
            spinnerBassFreq.setSelection(freqPos)
            val bassProgress = (bassGain * 10f).toInt().coerceIn(0, 120)
            seekBarBassBoost.progress = bassProgress
            tvBassBoostValue.text = String.format("+%.1f dB", bassGain)
            dspProcessor?.setBassBoost(bassEnabled, bassFreq, bassGain)

            for (i in 0 until EqualizerProcessor.BAND_COUNT) {
                val gainDb = prefs.getFloat("band_gain_$i", 0.0f)
                val prog = (gainDb * 10f + 120).toInt().coerceIn(0, 240)
                bandSeekBars.getOrNull(i)?.progress = prog
                bandValueLabels.getOrNull(i)?.text = String.format("%+.1f", gainDb)
                dspProcessor?.setBandGain(i, gainDb)
            }

            // Restore ATS2835P Emulation Mode settings
            val emuEnabled = prefs.getBoolean("emu_enabled", true)
            val emuAmount = prefs.getFloat("emu_amount", 1.0f)
            val btAutoBypass = prefs.getBoolean("bt_auto_bypass", true)

            switchEmu.isChecked = emuEnabled
            val emuProgress = (emuAmount * 100f).toInt().coerceIn(0, 100)
            seekBarEmuAmount.progress = emuProgress
            tvEmuAmountValue.text = "$emuProgress%"
            switchBtAutoBypass.isChecked = btAutoBypass

            dspProcessor?.setEmulationEnabled(emuEnabled)
            dspProcessor?.setEmulationAmount(emuAmount)
            dspProcessor?.setBluetoothAutoBypass(btAutoBypass)
            updateEmuStatus()

            // Restore 5-Band MDRC settings
            val mdrcEnabled = prefs.getBoolean("mdrc_enabled", true)
            val mdrcThresh = prefs.getFloat("mdrc_threshold", -14.0f)
            val mdrcRatio = prefs.getFloat("mdrc_ratio", 3.0f)

            switchMdrc.isChecked = mdrcEnabled
            val threshProg = (mdrcThresh + 36).toInt().coerceIn(0, 36)
            seekBarMdrcThreshold.progress = threshProg
            tvMdrcThresholdValue.text = String.format("%.0f dB", mdrcThresh)

            val ratioProg = ((mdrcRatio - 1.0f) * 10f).toInt().coerceIn(0, 70)
            seekBarMdrcRatio.progress = ratioProg
            tvMdrcRatioValue.text = String.format("%.1f:1", mdrcRatio)

            dspProcessor?.setMdrcEnabled(mdrcEnabled)
            dspProcessor?.setMdrcDynamics(mdrcThresh, mdrcRatio)
            updateMdrcControlsAlpha(mdrcEnabled)

            for (b in 0 until 5) {
                val bandGain = prefs.getFloat("mdrc_band_$b", 0.0f)
                val bandProg = (bandGain * 10f + 120).toInt().coerceIn(0, 240)
                mdrcBandSeekBars.getOrNull(b)?.progress = bandProg
                mdrcBandValueLabels.getOrNull(b)?.text = String.format("%+.1f dB", bandGain)
                dspProcessor?.setMdrcBandGain(b, bandGain)
            }

            // Restore Global System Audio state
            val globalAudioEnabled = prefs.getBoolean("global_audio_enabled", false)
            switchGlobalAudio.isChecked = globalAudioEnabled
            if (globalAudioEnabled) {
                GlobalAudioService.start(this)
                tvGlobalAudioStatus.text = "Activo • Procesando salida de audio del sistema"
                tvGlobalAudioStatus.setTextColor(cyanColor)
                tvActiveSessionsCount.text = "1 activa (Global)"
                tvActiveSessionsCount.setTextColor(cyanColor)
            }

            syncGlobalAudioDsp()
        } finally {
            isUpdatingUiFromCode = false
        }
    }

    private fun applyPresetByName(name: String) {
        val all = presetManager.getAllPresets()
        val match = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (match!= null) {
            loadPresetIntoUi(match)
        } else {
            val helper = EqualizerProcessor(SjbzAudioEngine.processor)
            helper.applyPreset(name)
            val preset = helper.toEqPreset(
                name = name,
                bassBoostEnabled = (name == "Bass" || name == "Rock"),
                bassBoostFreq = 85f,
                bassBoostGain = if (name == "Bass") 8f else 4f
            )
            loadPresetIntoUi(preset)
        }
        syncGlobalAudioDsp()
    }

    private fun loadPresetIntoUi(preset: EqPreset) {
        isUpdatingUiFromCode = true
        try {
            // 1. Preamp
            val pProgress = (preset.preampDb * 10f + 120).toInt().coerceIn(0, 240)
            seekBarPreamp.progress = pProgress
            tvPreampValue.text = String.format("%+.1f dB", preset.preampDb)
            dspProcessor?.setPreamp(preset.preampDb)

            // 2. Bass Boost
            switchBassBoost.isChecked = preset.bassBoostEnabled
            val freqPos = when (preset.bassBoostFreq) {
                60.0f -> 0
                85.0f -> 1
                else -> 2
            }
            spinnerBassFreq.setSelection(freqPos)
            seekBarBassBoost.progress = (preset.bassBoostGain * 10f).toInt().coerceIn(0, 120)
            tvBassBoostValue.text = String.format("+%.1f dB", preset.bassBoostGain)
            dspProcessor?.setBassBoost(preset.bassBoostEnabled, preset.bassBoostFreq, preset.bassBoostGain)

            // 3. 32 Bands
            val editor = prefs.edit()
            editor.putFloat("preamp_db", preset.preampDb)
            editor.putBoolean("bass_enabled", preset.bassBoostEnabled)
            editor.putFloat("bass_freq", preset.bassBoostFreq)
            editor.putFloat("bass_gain", preset.bassBoostGain)

            for (i in 0 until minOf(preset.bandGains.size, bandSeekBars.size)) {
                val gain = preset.bandGains[i]
                val prog = (gain * 10f + 120).toInt().coerceIn(0, 240)
                bandSeekBars[i].progress = prog
                bandValueLabels[i].text = String.format("%+.1f", gain)
                dspProcessor?.setBandGain(i, gain)
                editor.putFloat("band_gain_$i", gain)
            }
            editor.putString("active_preset", preset.name)
            editor.apply()

            presetManager.setActivePresetName(preset.name)
        } finally {
            isUpdatingUiFromCode = false
        }
        syncGlobalAudioDsp()
    }

    private fun applyGenrePreset(genre: String) {
        when (genre.lowercase()) {
            "rock", "metal" -> applyPresetByName("Rock")
            "electronic", "bass", "hip-hop" -> applyPresetByName("Bass")
            "vocal", "acoustic" -> applyPresetByName("Vocal")
            "pop" -> applyPresetByName("Pop")
            "jazz" -> applyPresetByName("Jazz")
            "classical" -> applyPresetByName("Classical")
            else -> applyPresetByName("Studio Master")
        }
    }

    private fun resetAllBandsToZero() {
        isUpdatingUiFromCode = true
        try {
            val editor = prefs.edit()
            for (i in 0 until bandSeekBars.size) {
                bandSeekBars[i].progress = 120
                bandValueLabels[i].text = "0.0"
                dspProcessor?.setBandGain(i, 0.0f)
                editor.putFloat("band_gain_$i", 0.0f)
            }
            editor.apply()
            Toast.makeText(this, "Bandas restablecidas a 0 dB", Toast.LENGTH_SHORT).show()
        } finally {
            isUpdatingUiFromCode = false
        }
        syncGlobalAudioDsp()
    }

    private fun saveCurrentAsCustomPreset() {
        val currentGains = ArrayList<Float>()
        for (i in 0 until bandSeekBars.size) {
            val g = (bandSeekBars[i].progress - 120) / 10.0f
            currentGains.add(g)
        }
        val preamp = (seekBarPreamp.progress - 120) / 10.0f
        val bassEnabled = switchBassBoost.isChecked
        val bassFreq = getSelectedBassFreq()
        val bassGain = seekBarBassBoost.progress / 10.0f

        val customName = "Custom ${System.currentTimeMillis() % 1000}"
        val preset = EqPreset(
            name = customName,
            preampDb = preamp,
            bandGains = currentGains,
            isCustom = true,
            bassBoostEnabled = bassEnabled,
            bassBoostFreq = bassFreq,
            bassBoostGain = bassGain
        )
        presetManager.saveCustomPreset(preset)
        refreshPresetSpinner()
        Toast.makeText(this, "Preset guardado: $customName", Toast.LENGTH_SHORT).show()
    }

    private fun updateControlsAlpha(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.4f
        llEqBandsContainer.alpha = alpha
        seekBarPreamp.isEnabled = enabled
        seekBarBassBoost.isEnabled = enabled && switchBassBoost.isChecked
        spinnerBassFreq.isEnabled = enabled && switchBassBoost.isChecked
        switchEmu.isEnabled = enabled
        seekBarEmuAmount.isEnabled = enabled && switchEmu.isChecked
        switchBtAutoBypass.isEnabled = enabled
        switchMdrc.isEnabled = enabled
        updateMdrcControlsAlpha(enabled && switchMdrc.isChecked)
    }

    override fun onResume() {
        super.onResume()
        updateEmuStatus()
        val globalManager = GlobalAudioSessionManager.getInstance(this)
        val maxVol = globalManager.getMaxSystemVolume()
        val curVol = globalManager.getSystemVolume()
        seekBarSystemVolume.max = maxVol
        seekBarSystemVolume.progress = curVol
        updateSystemVolumeLabel(curVol, maxVol)
        debounceHandler.post(mdrcGainReductionTicker)
    }

    override fun onPause() {
        super.onPause()
        debounceHandler.removeCallbacks(mdrcGainReductionTicker)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        // Do not null the shared processor listener permanently for other components;
        // only detach this Activity's visualizer callback.
        if (dspProcessor?.fftListener!= null) {
            // keep shared processor alive, just detach view
        }
        debounceHandler.removeCallbacks(mdrcGainReductionTicker)
        debounceHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
