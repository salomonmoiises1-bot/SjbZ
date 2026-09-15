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
import com.sjbz.aimp.audio.PresetManager
import com.sjbz.aimp.audio.SjbzDspProcessor
import com.sjbz.aimp.model.EqPreset
import com.sjbz.aimp.service.PlaybackService
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

    private var isUpdatingUiFromCode = false
    private val cyanColor = Color.parseColor("#00E5FF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        presetManager = PresetManager(this)

        // Acquire DSP processor reference from running PlaybackService or fallback
        dspProcessor = PlaybackService.instance?.getDsp() ?: SjbzDspProcessor()

        bindViews()
        setupToolbar()
        setupSpectrumVisualizer()
        setupGenreRecognition()
        setupMasterControls()
        setupEmuControls()
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
        // Connect real-time 60fps mono 2048 FFT listener
        dspProcessor?.fftListener = { samples ->
            visualizerView.onAudioData(samples)
        }
    }

    private fun setupGenreRecognition() {
        val currentTrack = PlaybackService.instance?.getCurrentTrack()
        val genre = currentTrack?.genre ?: "Studio"
        val title = currentTrack?.title ?: "Reproducción activa"

        tvRecognizedGenre.text = genre
        tvGenreTrackInfo.text = "$title • Adaptación DSP disponible"

        btnAutoEqGenre.setOnClickListener {
            applyGenrePreset(genre)
            Toast.makeText(this, "EQ adaptado al género: $genre", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMasterControls() {
        // 1. Preamp Slider (-12 dB to +12 dB, 120 is 0 dB)
        seekBarPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvPreampValue.text = String.format("%+.1f dB", db)

                if (fromUser && !isUpdatingUiFromCode) {
                    preampDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        dspProcessor?.setPreamp(db)
                        prefs.edit().putFloat("preamp_db", db).apply()
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
        val freqAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, freqOptions).apply {
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
        }

        // 4. Bass Boost Gain Slider (0 to 12 dB, 120 is 12 dB)
        seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progress / 10.0f
                tvBassBoostValue.text = String.format("+%.1f dB", db)

                if (fromUser && !isUpdatingUiFromCode) {
                    bassDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        val enabled = switchBassBoost.isChecked
                        val freq = getSelectedBassFreq()
                        dspProcessor?.setBassBoost(enabled, freq, db)
                        prefs.edit().putFloat("bass_gain", db).apply()
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
            val masterActive = dspProcessor?.masterEnabled != false
            seekBarEmuAmount.isEnabled = isChecked && masterActive
            updateEmuStatus()
        }

        seekBarEmuAmount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val amount = progress / 100.0f
                tvEmuAmountValue.text = "$progress%"

                if (fromUser && !isUpdatingUiFromCode) {
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
        val dsp = dspProcessor ?: return
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

                    if (fromUser && !isUpdatingUiFromCode) {
                        bandDebounceRunnables[bandIndex]?.let { debounceHandler.removeCallbacks(it) }
                        val r = Runnable {
                            dspProcessor?.setBandGain(bandIndex, gainDb)
                            prefs.edit().putFloat("band_gain_$bandIndex", gainDb).apply()
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
        } finally {
            isUpdatingUiFromCode = false
        }
    }

    private fun applyPresetByName(name: String) {
        val all = presetManager.getAllPresets()
        val match = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (match != null) {
            loadPresetIntoUi(match)
        } else {
            val helper = EqualizerProcessor()
            helper.applyPreset(name)
            val preset = helper.toEqPreset(
                name = name,
                bassBoostEnabled = (name == "Bass" || name == "Rock"),
                bassBoostFreq = 85f,
                bassBoostGain = if (name == "Bass") 8f else 4f
            )
            loadPresetIntoUi(preset)
        }
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
    }

    override fun onResume() {
        super.onResume()
        updateEmuStatus()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        // Disconnect real-time FFT spectrum listener to avoid leaks
        dspProcessor?.fftListener = null
        debounceHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
