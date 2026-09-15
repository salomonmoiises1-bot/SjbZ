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
 * - 32 vertical ISO sliders (-12dB.. +12dB) con debounce 60ms
 * - Bass Boost RBJ low-shelf + Preamp + Master DSP
 * - Emulación ATS2835P con Wet/Dry y auto-bypass BT
 * - Spectrum 60fps vía PlaybackService.spectrumListener (no pisa fftListener del DSP)
 */
class EqActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "sjbz_dsp_pro"
        private const val DEBOUNCE_MS = 60L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var presetManager: PresetManager
    private var dspProcessor: SjbzDspProcessor? = null
    private var playbackService: PlaybackService? = null

    private lateinit var toolbar: Toolbar
    private lateinit var switchMasterDsp: SwitchCompat
    private lateinit var visualizerView: AudioSpectrumVisualizerView
    private lateinit var tvRecognizedGenre: TextView
    private lateinit var tvGenreTrackInfo: TextView
    private lateinit var btnAutoEqGenre: Button

    private lateinit var btnPresetFlat: Button
    private lateinit var btnPresetBass: Button
    private lateinit var btnPresetRock: Button
    private lateinit var btnPresetVocal: Button
    private lateinit var spinnerPresets: Spinner
    private lateinit var btnSavePreset: ImageButton
    private lateinit var btnExportPreset: ImageButton

    private lateinit var tvPreampValue: TextView
    private lateinit var seekBarPreamp: SeekBar
    private lateinit var switchBassBoost: SwitchCompat
    private lateinit var tvBassBoostValue: TextView
    private lateinit var spinnerBassFreq: Spinner
    private lateinit var seekBarBassBoost: SeekBar

    private lateinit var switchEmu: SwitchCompat
    private lateinit var tvEmuStatus: TextView
    private lateinit var tvEmuAmountValue: TextView
    private lateinit var seekBarEmuAmount: SeekBar
    private lateinit var switchBtAutoBypass: SwitchCompat
    private lateinit var tvBtBypassStatus: TextView

    private lateinit var llEqBandsContainer: LinearLayout
    private lateinit var btnResetEq: Button
    private val bandSeekBars = ArrayList<SeekBar>()
    private val bandValueLabels = ArrayList<TextView>()

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val bandDebounceRunnables = arrayOfNulls<Runnable>(EqualizerProcessor.BAND_COUNT)
    private var preampDebounceRunnable: Runnable? = null
    private var bassDebounceRunnable: Runnable? = null
    private var emuDebounceRunnable: Runnable? = null

    private var isUpdatingUiFromCode = false
    private val cyanColor = Color.parseColor("#00E5FF")

    // Spectrum bridge - se registra en PlaybackService, no en dspProcessor directamente
    private val spectrumBridge = object : SjbzDspProcessor.FftListener {
        override fun onAudioData(samples: FloatArray) {
            visualizerView.onAudioData(samples)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        presetManager = PresetManager(this)

        // Referencia obligatoria al servicio. No se crea DSP huérfano.
        playbackService = PlaybackService.instance
        if (playbackService == null) {
            Toast.makeText(this, "Servicio de audio no disponible", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        dspProcessor = playbackService?.getDsp()

        bindViews()
        setupToolbar()
        setupGenreRecognition()
        setupMasterControls()
        setupEmuControls()
        setupPresetControls()
        build32BandSliders()
        restoreAllDspParameters()
    }

    override fun onResume() {
        super.onResume()
        // Registrar bridge de espectro en el servicio (el servicio ya hace fftListener -> spectrumListener)
        playbackService?.setSpectrumListener(spectrumBridge)
        updateEmuStatus()
    }

    override fun onPause() {
        super.onPause()
        // Desregistrar para no leakear la vista
        if (playbackService?.spectrumListener === spectrumBridge) {
            playbackService?.setSpectrumListener(null)
        }
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

    private fun setupGenreRecognition() {
        val currentTrack = playbackService?.getCurrentTrack()
        val genre = currentTrack?.genre?: "Studio"
        val title = currentTrack?.title?: "Reproducción activa"
        tvRecognizedGenre.text = genre
        tvGenreTrackInfo.text = "$title • Adaptación DSP disponible"
        btnAutoEqGenre.setOnClickListener {
            applyGenrePreset(genre)
            Toast.makeText(this, "EQ adaptado al género: $genre", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyBassBoostToDsp() {
        val enabled = switchBassBoost.isChecked
        val freq = getSelectedBassFreq()
        val gain = seekBarBassBoost.progress / 10.0f
        // API correcta: enabled separado de freq/gain
        dspProcessor?.setBassBoostEnabled(enabled)
        dspProcessor?.setBassBoost(freq, gain)
        // También via servicio para persistencia si se requiere
        playbackService?.setBassBoostEnabled(enabled)
        playbackService?.setBassBoost(freq, gain)
    }

    private fun setupMasterControls() {
        seekBarPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvPreampValue.text = String.format("%+.1f dB", db)
                if (fromUser &&!isUpdatingUiFromCode) {
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

        val freqOptions = arrayOf("60 Hz (Sub Bass)", "85 Hz (Punch Bass)", "120 Hz (Mid Bass)")
        val freqAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, freqOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBassFreq.adapter = freqAdapter
        spinnerBassFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUiFromCode) return
                val freq = when (position) { 0 -> 60f; 1 -> 85f; else -> 120f }
                val gain = seekBarBassBoost.progress / 10f
                val enabled = switchBassBoost.isChecked
                dspProcessor?.setBassBoost(freq, gain)
                dspProcessor?.setBassBoostEnabled(enabled)
                prefs.edit().putFloat("bass_freq", freq).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            applyBassBoostToDsp()
            prefs.edit().putBoolean("bass_enabled", isChecked).apply()
            seekBarBassBoost.isEnabled = isChecked
            spinnerBassFreq.isEnabled = isChecked
        }

        seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progress / 10f
                tvBassBoostValue.text = String.format("+%.1f dB", db)
                if (fromUser &&!isUpdatingUiFromCode) {
                    bassDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        applyBassBoostToDsp()
                        prefs.edit().putFloat("bass_gain", db).apply()
                    }
                    bassDebounceRunnable = r
                    debounceHandler.postDelayed(r, DEBOUNCE_MS)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnResetEq.setOnClickListener { resetAllBandsToZero() }
    }

    private fun setupEmuControls() {
        switchEmu.setOnCheckedChangeListener { _, isChecked ->
            // Via servicio para que persista y actualice notificación
            playbackService?.setEmulationEnabled(isChecked)
               ?: dspProcessor?.setEmulationEnabled(isChecked)
            prefs.edit().putBoolean("emu_enabled", isChecked).apply()
            val masterActive = dspProcessor?.masterEnabled!= false
            seekBarEmuAmount.isEnabled = isChecked && masterActive
            updateEmuStatus()
        }

        seekBarEmuAmount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val amount = progress / 100f
                tvEmuAmountValue.text = "$progress%"
                if (fromUser &&!isUpdatingUiFromCode) {
                    emuDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        playbackService?.setEmulationAmount(amount)
                           ?: dspProcessor?.setEmulationAmount(amount)
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
        when {
            isBt && isAutoBypass -> {
                tvEmuStatus.text = "Auto-Bypass activo (Bluetooth A2DP)"
                tvEmuStatus.setTextColor(Color.parseColor("#F59E0B"))
                tvBtBypassStatus.text = "BT conectado: emulación omitida para evitar doble procesado"
            }
            isEnabled -> {
                tvEmuStatus.text = "Activo (4 Biquads + Limiter -6dB + Wet/Dry)"
                tvEmuStatus.setTextColor(cyanColor)
                tvBtBypassStatus.text = if (isBt) "BT conectado (bypass manual OFF)" else "Auto-bypass listo"
            }
            else -> {
                tvEmuStatus.text = "Desactivado (bypass manual)"
                tvEmuStatus.setTextColor(Color.parseColor("#94A3B8"))
                tvBtBypassStatus.text = "Actívalo para color ATS2835P en salida local"
            }
        }
    }

    private fun getSelectedBassFreq(): Float = when (spinnerBassFreq.selectedItemPosition) {
        0 -> 60f; 1 -> 85f; else -> 120f
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
                val all = presetManager.getAllPresets()
                if (position in all.indices) loadPresetIntoUi(all[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnSavePreset.setOnClickListener { saveCurrentAsCustomPreset() }
        btnExportPreset.setOnClickListener {
            Toast.makeText(this, "Presets en $PREFS_NAME", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPresetSpinner() {
        val names = presetManager.getAllPresets().map { it.name }
        spinnerPresets.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun build32BandSliders() {
        llEqBandsContainer.removeAllViews()
        bandSeekBars.clear(); bandValueLabels.clear()
        val bandCount = EqualizerProcessor.BAND_COUNT
        val labels = EqualizerProcessor.BAND_LABELS
        val density = resources.displayMetrics.density
        for (i in 0 until bandCount) {
            val bandCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams((density*52).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(2,6,2,6)
            }
            val tvGain = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = "0.0"; textSize = 9.5f; setTextColor(cyanColor); gravity = Gravity.CENTER
            }
            bandCol.addView(tvGain); bandValueLabels.add(tvGain)
            val faderContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f)
                gravity = Gravity.CENTER
            }
            val seekBar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams((density*150).toInt(), (density*34).toInt())
                rotation = 270f; max = 240; progress = 120
                progressTintList = ColorStateList.valueOf(cyanColor)
                thumbTintList = ColorStateList.valueOf(cyanColor)
            }
            val bandIndex = i
            seekBar.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gainDb = (progress - 120) / 10f
                    tvGain.text = String.format("%+.1f", gainDb)
                    if (fromUser &&!isUpdatingUiFromCode) {
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
            faderContainer.addView(seekBar); bandCol.addView(faderContainer); bandSeekBars.add(seekBar)
            val tvFreq = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = labels[i]; textSize = 9.5f; setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER
            }
            bandCol.addView(tvFreq); llEqBandsContainer.addView(bandCol)
        }
    }

    private fun restoreAllDspParameters() {
        isUpdatingUiFromCode = true
        try {
            val masterEnabled = prefs.getBoolean("master_enabled", true)
            switchMasterDsp.isChecked = masterEnabled
            dspProcessor?.masterEnabled = masterEnabled
            updateControlsAlpha(masterEnabled)

            val preampDb = prefs.getFloat("preamp_db", 0f)
            seekBarPreamp.progress = (preampDb*10f+120).toInt().coerceIn(0,240)
            tvPreampValue.text = String.format("%+.1f dB", preampDb)
            dspProcessor?.setPreamp(preampDb)

            val bassEnabled = prefs.getBoolean("bass_enabled", true)
            val bassFreq = prefs.getFloat("bass_freq", 85f)
            val bassGain = prefs.getFloat("bass_gain", 4f)
            switchBassBoost.isChecked = bassEnabled
            spinnerBassFreq.setSelection(when(bassFreq){60f->0;85f->1;else->2})
            seekBarBassBoost.progress = (bassGain*10f).toInt().coerceIn(0,120)
            tvBassBoostValue.text = String.format("+%.1f dB", bassGain)
            dspProcessor?.setBassBoostEnabled(bassEnabled)
            dspProcessor?.setBassBoost(bassFreq, bassGain)

            for (i in 0 until EqualizerProcessor.BAND_COUNT) {
                val g = prefs.getFloat("band_gain_$i", 0f)
                bandSeekBars.getOrNull(i)?.progress = (g*10f+120).toInt().coerceIn(0,240)
                bandValueLabels.getOrNull(i)?.text = String.format("%+.1f", g)
                dspProcessor?.setBandGain(i, g)
            }

            val emuEnabled = prefs.getBoolean("emu_enabled", false)
            val emuAmount = prefs.getFloat("emu_amount", 0.8f)
            val btBypass = prefs.getBoolean("bt_auto_bypass", true)
            switchEmu.isChecked = emuEnabled
            seekBarEmuAmount.progress = (emuAmount*100f).toInt().coerceIn(0,100)
            tvEmuAmountValue.text = "${(emuAmount*100).toInt()}%"
            switchBtAutoBypass.isChecked = btBypass
            dspProcessor?.setEmulationEnabled(emuEnabled)
            dspProcessor?.setEmulationAmount(emuAmount)
            dspProcessor?.setBluetoothAutoBypass(btBypass)
            updateEmuStatus()
        } finally { isUpdatingUiFromCode = false }
    }

    private fun applyPresetByName(name: String) {
        val all = presetManager.getAllPresets()
        val match = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (match!= null) loadPresetIntoUi(match)
        else {
            val helper = EqualizerProcessor(); helper.applyPreset(name)
            loadPresetIntoUi(helper.toEqPreset(name, name=="Bass"||name=="Rock", 85f, if(name=="Bass") 8f else 4f))
        }
    }

    private fun loadPresetIntoUi(preset: EqPreset) {
        isUpdatingUiFromCode = true
        try {
            seekBarPreamp.progress = (preset.preampDb*10f+120).toInt().coerceIn(0,240)
            tvPreampValue.text = String.format("%+.1f dB", preset.preampDb)
            dspProcessor?.setPreamp(preset.preampDb)

            switchBassBoost.isChecked = preset.bassBoostEnabled
            spinnerBassFreq.setSelection(when(preset.bassBoostFreq){60f->0;85f->1;else->2})
            seekBarBassBoost.progress = (preset.bassBoostGain*10f).toInt().coerceIn(0,120)
            tvBassBoostValue.text = String.format("+%.1f dB", preset.bassBoostGain)
            dspProcessor?.setBassBoostEnabled(preset.bassBoostEnabled)
            dspProcessor?.setBassBoost(preset.bassBoostFreq, preset.bassBoostGain)

            val editor = prefs.edit()
            editor.putFloat("preamp_db", preset.preampDb)
            editor.putBoolean("bass_enabled", preset.bassBoostEnabled)
            editor.putFloat("bass_freq", preset.bassBoostFreq)
            editor.putFloat("bass_gain", preset.bassBoostGain)
            for (i in 0 until minOf(preset.bandGains.size, bandSeekBars.size)) {
                val g = preset.bandGains[i]
                bandSeekBars[i].progress = (g*10f+120).toInt().coerceIn(0,240)
                bandValueLabels[i].text = String.format("%+.1f", g)
                dspProcessor?.setBandGain(i, g)
                editor.putFloat("band_gain_$i", g)
            }
            editor.putString("active_preset", preset.name); editor.apply()
            presetManager.setActivePresetName(preset.name)
        } finally { isUpdatingUiFromCode = false }
    }

    private fun applyGenrePreset(genre: String) {
        when (genre.lowercase()) {
            "rock","metal" -> applyPresetByName("Rock")
            "electronic","bass","hip-hop" -> applyPresetByName("Bass")
            "vocal","acoustic" -> applyPresetByName("Vocal")
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
                bandSeekBars[i].progress = 120; bandValueLabels[i].text = "0.0"
                dspProcessor?.setBandGain(i, 0f); editor.putFloat("band_gain_$i", 0f)
            }
            editor.apply()
            Toast.makeText(this, "Bandas a 0 dB", Toast.LENGTH_SHORT).show()
        } finally { isUpdatingUiFromCode = false }
    }

    private fun saveCurrentAsCustomPreset() {
        val gains = ArrayList<Float>()
        for (i in 0 until bandSeekBars.size) gains.add((bandSeekBars[i].progress-120)/10f)
        val preamp = (seekBarPreamp.progress-120)/10f
        val preset = EqPreset(
            name = "Custom ${System.currentTimeMillis()%1000}",
            preampDb = preamp, bandGains = gains, isCustom = true,
            bassBoostEnabled = switchBassBoost.isChecked,
            bassBoostFreq = getSelectedBassFreq(),
            bassBoostGain = seekBarBassBoost.progress/10f
        )
        presetManager.saveCustomPreset(preset); refreshPresetSpinner()
        Toast.makeText(this, "Preset guardado: ${preset.name}", Toast.LENGTH_SHORT).show()
    }

    private fun updateControlsAlpha(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.4f
        llEqBandsContainer.alpha = alpha
        seekBarPreamp.isEnabled = enabled
        seekBarBassBoost.isEnabled = enabled && switchBassBoost.isChecked
        spinnerBassFreq.isEnabled = enabled && switchBassBoost.isChecked
        switchEmu.isEnabled = enabled
        seekBarEmuAmount.isEnabled = enabled && switchEmu.isChecked
        switchBtAutoBypass.isEnabled = enabled
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        // No tocar dspProcessor.fftListener: pertenece al servicio.
        // Solo desregistrar nuestro bridge si sigue activo.
        if (playbackService?.spectrumListener === spectrumBridge) {
            playbackService?.setSpectrumListener(null)
        }
        debounceHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
