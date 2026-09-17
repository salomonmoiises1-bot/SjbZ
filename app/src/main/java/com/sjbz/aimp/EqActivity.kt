package com.sjbz.aimp

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.sjbz.aimp.audio.PresetManager
import com.sjbz.aimp.audio.SjbzAudioEngine
import com.sjbz.aimp.audio.SjbzDspProcessor
import com.sjbz.aimp.model.EqPreset
import com.sjbz.aimp.service.GlobalAudioService
import com.sjbz.aimp.ui.AudioSpectrumVisualizerView

private fun atsSetEnabled(enabled: Boolean) {
    try {
        val m = SjbzAudioEngine.atsEngine::class.java.methods.firstOrNull { it.name == "setEnabled" && it.parameterTypes.size == 1 }
        if (m!= null) m.invoke(SjbzAudioEngine.atsEngine, enabled)
        else SjbzAudioEngine.setMasterEnabled(enabled)
    } catch (e: Exception) { Log.w("EqActivity", "ats setEnabled fail: ${e.message}") }
}
private fun atsSetAmount(amount: Float) {
    try {
        val m = SjbzAudioEngine.atsEngine::class.java.methods.firstOrNull { it.name == "setAmount" }
        m?.invoke(SjbzAudioEngine.atsEngine, amount)
    } catch (e: Exception) { Log.w("EqActivity", "ats setAmount fail: ${e.message}") }
}
private fun atsIsEnabled(): Boolean {
    return try {
        val m = SjbzAudioEngine.atsEngine::class.java.methods.firstOrNull { it.name == "isEnabled" }
        (m?.invoke(SjbzAudioEngine.atsEngine) as? Boolean)?: false
    } catch (_: Exception) { false }
}

class EqActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "sjbz_dsp_pro"
        private const val DEBOUNCE_MS = 60L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var presetManager: PresetManager
    private var dspProcessor: SjbzDspProcessor? = null

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

    private lateinit var switchGlobalAudio: SwitchCompat
    private lateinit var tvGlobalAudioStatus: TextView
    private lateinit var tvActiveSessionsCount: TextView
    private lateinit var seekBarSystemVolume: SeekBar
    private lateinit var tvSystemVolumeValue: TextView

    private lateinit var switchMdrc: SwitchCompat
    private lateinit var tvMdrcStatus: TextView
    private lateinit var tvMdrcGainReduction: TextView
    private lateinit var seekBarMdrcThreshold: SeekBar
    private lateinit var tvMdrcThresholdValue: TextView
    private lateinit var seekBarMdrcRatio: SeekBar
    private lateinit var tvMdrcRatioValue: TextView
    private val mdrcBandSeekBars = ArrayList<SeekBar>()
    private val mdrcBandValueLabels = ArrayList<TextView>()

    private lateinit var llEqBandsContainer: LinearLayout
    private lateinit var btnResetEq: Button
    private val bandSeekBars = ArrayList<SeekBar>()
    private val bandValueLabels = ArrayList<TextView>()

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val bandDebounceRunnables = arrayOfNulls<Runnable>(EqualizerProcessor.BAND_COUNT)
    private var preampDebounceRunnable: Runnable? = null
    private var bassDebounceRunnable: Runnable? = null
    private var emuDebounceRunnable: Runnable? = null
    private var mdrcDebounceRunnable: Runnable? = null

    private var isUpdatingUiFromCode = false
    private val cyanColor = Color.parseColor("#00E5FF")

    private val mdrcGainReductionTicker = object : Runnable {
        override fun run() {
            if (!isDestroyed &&!isFinishing) {
                try {
                    val gr = SjbzAudioEngine.getMdrcGainReduction()
                    tvMdrcGainReduction.text = String.format("GR: -%.1f dB", gr)
                } catch (_: Exception) {}
                debounceHandler.postDelayed(this, 120L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        presetManager = PresetManager(this)
        SjbzAudioEngine.ensureInitialized()
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
        restoreAllDspParameters()
    }

    override fun onResume() {
        super.onResume()
        updateEmuStatus()
        debounceHandler.removeCallbacks(mdrcGainReductionTicker)
        debounceHandler.post(mdrcGainReductionTicker)
    }

    override fun onPause() {
        super.onPause()
        debounceHandler.removeCallbacks(mdrcGainReductionTicker)
        syncGlobalAudioDsp()
    }

    override fun onDestroy() {
        debounceHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
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
        switchGlobalAudio = findViewById(R.id.switchGlobalAudio)
        tvGlobalAudioStatus = findViewById(R.id.tvGlobalAudioStatus)
        tvActiveSessionsCount = findViewById(R.id.tvActiveSessionsCount)
        seekBarSystemVolume = findViewById(R.id.seekBarSystemVolume)
        tvSystemVolumeValue = findViewById(R.id.tvSystemVolumeValue)
        switchMdrc = findViewById(R.id.switchMdrc)
        tvMdrcStatus = findViewById(R.id.tvMdrcStatus)
        tvMdrcGainReduction = findViewById(R.id.tvMdrcGainReduction)
        seekBarMdrcThreshold = findViewById(R.id.seekBarMdrcThreshold)
        tvMdrcThresholdValue = findViewById(R.id.tvMdrcThresholdValue)
        seekBarMdrcRatio = findViewById(R.id.seekBarMdrcRatio)
        tvMdrcRatioValue = findViewById(R.id.tvMdrcRatioValue)
        mdrcBandSeekBars.clear(); mdrcBandValueLabels.clear()
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
            if (isUpdatingUiFromCode) return@setOnCheckedChangeListener
            SjbzAudioEngine.setMasterEnabled(isChecked)
            prefs.edit().putBoolean("master_enabled", isChecked).apply()
            updateControlsAlpha(isChecked)
        }
    }

    private fun setupSpectrumVisualizer() {
        dspProcessor?.fftListener = { samples -> runOnUiThread { visualizerView.onAudioData(samples) } }
    }

    private fun setupGenreRecognition() {
        val currentProfile = GlobalAudioSessionManager.getInstance(this).currentProfile
        tvRecognizedGenre.text = currentProfile.presetName
        tvGenreTrackInfo.text = "${currentProfile.appName} • Perfil activo"
        btnAutoEqGenre.setOnClickListener {
            applyGenrePreset(currentProfile.presetName)
            Toast.makeText(this, "EQ adaptado: ${currentProfile.presetName}", Toast.LENGTH_SHORT).show()
        }
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
                        syncGlobalAudioDsp()
                    }
                    preampDebounceRunnable = r
                    debounceHandler.postDelayed(r, DEBOUNCE_MS)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val freqOptions = arrayOf("60 Hz (Sub Bass)", "85 Hz (Punch Bass)", "120 Hz (Mid Bass)")
        spinnerBassFreq.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, freqOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBassFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isUpdatingUiFromCode) return
                val freq = when (pos) { 0 -> 60f; 1 -> 85f; else -> 120f }
                dspProcessor?.setBassBoost(switchBassBoost.isChecked, freq, seekBarBassBoost.progress / 10f)
                prefs.edit().putFloat("bass_freq", freq).apply()
                syncGlobalAudioDsp()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromCode) return@setOnCheckedChangeListener
            dspProcessor?.setBassBoost(isChecked, getSelectedBassFreq(), seekBarBassBoost.progress / 10f)
            prefs.edit().putBoolean("bass_enabled", isChecked).apply()
            seekBarBassBoost.isEnabled = isChecked
            spinnerBassFreq.isEnabled = isChecked
            syncGlobalAudioDsp()
        }
        seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progress / 10f
                tvBassBoostValue.text = String.format("+%.1f dB", db)
                if (fromUser &&!isUpdatingUiFromCode) {
                    bassDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        dspProcessor?.setBassBoost(switchBassBoost.isChecked, getSelectedBassFreq(), db)
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
        btnResetEq.setOnClickListener { resetAllBandsToZero() }
    }

    private fun setupEmuControls() {
        switchEmu.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromCode) return@setOnCheckedChangeListener
            atsSetEnabled(isChecked)
            prefs.edit().putBoolean("emu_enabled", isChecked).apply()
            seekBarEmuAmount.isEnabled = isChecked
            updateEmuStatus()
            syncGlobalAudioDsp()
        }
        seekBarEmuAmount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val amount = progress / 100f
                tvEmuAmountValue.text = "$progress%"
                if (fromUser &&!isUpdatingUiFromCode) {
                    emuDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val r = Runnable {
                        atsSetAmount(amount)
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
            if (isUpdatingUiFromCode) return@setOnCheckedChangeListener
            dspProcessor?.setBluetoothAutoBypass(isChecked)
            prefs.edit().putBoolean("bt_auto_bypass", isChecked).apply()
            updateEmuStatus()
        }
    }

    private fun updateEmuStatus() {
        val dsp = dspProcessor?: return
        val isBt = try { dsp.isBluetoothConnected() } catch (_: Exception) { false }
        val auto = try { dsp.isBluetoothAutoBypass() } catch (_: Exception) { false }
        when {
            isBt && auto -> {
                tvEmuStatus.text = "Auto-Bypass activo (BT detectado)"
                tvEmuStatus.setTextColor(Color.parseColor("#F59E0B"))
                tvBtBypassStatus.text = "BT conectado: emu omitido"
            }
            atsIsEnabled() -> {
                tvEmuStatus.text = "Activo (4 Biquads + Limiter -6dB)"
                tvEmuStatus.setTextColor(cyanColor)
                tvBtBypassStatus.text = "Listo para auto-bypass"
            }
            else -> {
                tvEmuStatus.text = "Desactivado"
                tvEmuStatus.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun setupGlobalAudioControls() {
        val gm = GlobalAudioSessionManager.getInstance(this)
        val maxVol = gm.getMaxSystemVolume()
        seekBarSystemVolume.max = maxVol
        seekBarSystemVolume.progress = gm.getSystemVolume()
        updateSystemVolumeLabel(seekBarSystemVolume.progress, maxVol)
        seekBarSystemVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                updateSystemVolumeLabel(p, maxVol)
                if (fromUser) gm.setSystemVolume(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        gm.onSystemVolumeChangedListener = { vol, max ->
            if (!isFinishing &&!isDestroyed) {
                seekBarSystemVolume.progress = vol
                updateSystemVolumeLabel(vol, max)
            }
        }
        gm.onActiveSessionsChangedListener = { count, _ ->
            if (!isFinishing &&!isDestroyed) tvActiveSessionsCount.text = if (count > 0) "$count activa(s)" else "Inactivo"
        }
        switchGlobalAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromCode) return@setOnCheckedChangeListener
            if (isChecked) {
                GlobalAudioService.start(this)
                tvGlobalAudioStatus.text = "Activo • Procesando sistema"
                tvGlobalAudioStatus.setTextColor(cyanColor)
            } else {
                GlobalAudioService.stop(this)
                tvGlobalAudioStatus.text = "Procesa Spotify, YouTube, Chrome"
                tvGlobalAudioStatus.setTextColor(Color.parseColor("#94A3B8"))
            }
            prefs.edit().putBoolean("global_audio_enabled", isChecked).apply()
            syncGlobalAudioDsp()
        }
    }

    private fun updateSystemVolumeLabel(cur: Int, max: Int) {
        tvSystemVolumeValue.text = "${if (max > 0) cur * 100 / max else 0}%"
    }

    private fun setupMdrcControls() {
        switchMdrc.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiFromCode) return@setOnCheckedChangeListener
            prefs.edit().putBoolean("mdrc_enabled", isChecked).apply()
            updateMdrcControlsAlpha(isChecked)
            syncGlobalAudioDsp()
        }
        seekBarMdrcThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvMdrcThresholdValue.text = "${p - 36} dB"
                if (fromUser &&!isUpdatingUiFromCode) debounceMdrcDynamics()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekBarMdrcRatio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvMdrcRatioValue.text = String.format("%.1f:1", 1f + p / 10f)
                if (fromUser &&!isUpdatingUiFromCode) debounceMdrcDynamics()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        for (b in 0 until 5) {
            val sb = mdrcBandSeekBars.getOrNull(b)?: continue
            val tv = mdrcBandValueLabels.getOrNull(b)
            val idx = b
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val gain = (p - 120) / 10f
                    tv?.text = String.format("%+.1f dB", gain)
                    if (fromUser &&!isUpdatingUiFromCode) {
                        prefs.edit().putFloat("mdrc_band_$idx", gain).apply()
                        syncGlobalAudioDsp()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }

    private fun debounceMdrcDynamics() {
        mdrcDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        val r = Runnable {
            val thresh = (seekBarMdrcThreshold.progress - 36).toFloat()
            val ratio = 1f + seekBarMdrcRatio.progress / 10f
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
        tvMdrcStatus.text = if (enabled) "Crossover 5 vías activo" else "MDRC desactivado"
    }

    private fun syncGlobalAudioDsp() {
        val gains = FloatArray(EqualizerProcessor.BAND_COUNT) { i ->
            bandSeekBars.getOrNull(i)?.let { (it.progress - 120) / 10f }?: 0f
        }
        val preamp = (seekBarPreamp.progress - 120) / 10f
        val bassGain = if (switchBassBoost.isChecked) seekBarBassBoost.progress / 10f else 0f
        val bassFreq = getSelectedBassFreq()
        val mdrcEnabled = switchMdrc.isChecked
        val mdrcGains = FloatArray(5) { i -> mdrcBandSeekBars.getOrNull(i)?.let { (it.progress - 120) / 10f }?: 0f }
        val thresh = (seekBarMdrcThreshold.progress - 36).toFloat()
        val ratio = 1f + seekBarMdrcRatio.progress / 10f
        GlobalAudioSessionManager.getInstance(this).updateEqParams(gains, preamp, bassGain, bassFreq, mdrcEnabled, mdrcGains, thresh, ratio)
        SjbzAudioEngine.syncMdrcFromGlobal(mdrcEnabled, mdrcGains, thresh, ratio)
        SjbzAudioEngine.setAllBandGains(gains)
        dspProcessor?.setPreamp(preamp)
    }

    private fun getSelectedBassFreq() = when (spinnerBassFreq.selectedItemPosition) {
        0 -> 60f; 1 -> 85f; else -> 120f
    }

    private fun setupPresetControls() {
        btnPresetFlat.setOnClickListener { applyPresetByName("Flat") }
        btnPresetBass.setOnClickListener { applyPresetByName("Bass") }
        btnPresetRock.setOnClickListener { applyPresetByName("Rock") }
        btnPresetVocal.setOnClickListener { applyPresetByName("Vocal") }
        refreshPresetSpinner()
        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isUpdatingUiFromCode) return
                presetManager.getAllPresets().getOrNull(pos)?.let { loadPresetIntoUi(it) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnSavePreset.setOnClickListener { saveCurrentAsCustomPreset() }
        btnExportPreset.setOnClickListener { Toast.makeText(this, "Presets en sjbz_dsp_pro", Toast.LENGTH_SHORT).show() }
    }

    private fun refreshPresetSpinner() {
        spinnerPresets.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            presetManager.getAllPresets().map { it.name }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun build32BandSliders() {
        llEqBandsContainer.removeAllViews()
        bandSeekBars.clear(); bandValueLabels.clear()
        val density = resources.displayMetrics.density
        for (i in 0 until EqualizerProcessor.BAND_COUNT) {
            val col = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams((density * 52).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(2, 6, 2, 6)
            }
            val tvGain = TextView(this).apply { text = "0.0"; textSize = 9.5f; setTextColor(cyanColor); gravity = Gravity.CENTER }
            col.addView(tvGain); bandValueLabels.add(tvGain)
            val faderContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f)
                gravity = Gravity.CENTER
            }
            val sb = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams((density * 150).toInt(), (density * 34).toInt())
                rotation = 270f; max = 240; progress = 120
                progressTintList = ColorStateList.valueOf(cyanColor)
                thumbTintList = ColorStateList.valueOf(cyanColor)
            }
            val idx = i
            sb.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val g = (p - 120) / 10f
                    tvGain.text = String.format("%+.1f", g)
                    if (fromUser &&!isUpdatingUiFromCode) {
                        bandDebounceRunnables[idx]?.let { debounceHandler.removeCallbacks(it) }
                        val r = Runnable {
                            dspProcessor?.setBandGain(idx, g)
                            prefs.edit().putFloat("band_gain_$idx", g).apply()
                            syncGlobalAudioDsp()
                        }
                        bandDebounceRunnables[idx] = r
                        debounceHandler.postDelayed(r, DEBOUNCE_MS)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            faderContainer.addView(sb); col.addView(faderContainer); bandSeekBars.add(sb)
            val tvFreq = TextView(this).apply {
                text = EqualizerProcessor.BAND_LABELS[i]; textSize = 9.5f
                setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER
            }
            col.addView(tvFreq); llEqBandsContainer.addView(col)
        }
    }

    private fun restoreAllDspParameters() {
        isUpdatingUiFromCode = true
        try {
            val master = prefs.getBoolean("master_enabled", true)
            switchMasterDsp.isChecked = master
            SjbzAudioEngine.setMasterEnabled(master)
            val preamp = prefs.getFloat("preamp_db", 0f)
            seekBarPreamp.progress = (preamp * 10f + 120).toInt().coerceIn(0, 240)
            tvPreampValue.text = String.format("%+.1f dB", preamp)
            val bassEn = prefs.getBoolean("bass_enabled", true)
            val bassFreq = prefs.getFloat("bass_freq", 85f)
            val bassGain = prefs.getFloat("bass_gain", 4f)
            switchBassBoost.isChecked = bassEn
            spinnerBassFreq.setSelection(when (bassFreq) { 60f -> 0; 85f -> 1; else -> 2 })
            seekBarBassBoost.progress = (bassGain * 10f).toInt().coerceIn(0, 120)
            tvBassBoostValue.text = String.format("+%.1f dB", bassGain)
            for (i in 0 until EqualizerProcessor.BAND_COUNT) {
                val g = prefs.getFloat("band_gain_$i", 0f)
                bandSeekBars.getOrNull(i)?.progress = (g * 10f + 120).toInt().coerceIn(0, 240)
                bandValueLabels.getOrNull(i)?.text = String.format("%+.1f", g)
            }
            val emuEn = prefs.getBoolean("emu_enabled", true)
            val emuAmt = prefs.getFloat("emu_amount", 1f)
            switchEmu.isChecked = emuEn
            seekBarEmuAmount.progress = (emuAmt * 100).toInt().coerceIn(0, 100)
            tvEmuAmountValue.text = "${seekBarEmuAmount.progress}%"
            switchBtAutoBypass.isChecked = prefs.getBoolean("bt_auto_bypass", true)
            val mdrcEn = prefs.getBoolean("mdrc_enabled", true)
            val thresh = prefs.getFloat("mdrc_threshold", -14f)
            val ratio = prefs.getFloat("mdrc_ratio", 3f)
            switchMdrc.isChecked = mdrcEn
            seekBarMdrcThreshold.progress = (thresh + 36).toInt().coerceIn(0, 36)
            tvMdrcThresholdValue.text = "${thresh.toInt()} dB"
            seekBarMdrcRatio.progress = ((ratio - 1f) * 10f).toInt().coerceIn(0, 70)
            tvMdrcRatioValue.text = String.format("%.1f:1", ratio)
            for (b in 0 until 5) {
                val g = prefs.getFloat("mdrc_band_$b", 0f)
                mdrcBandSeekBars.getOrNull(b)?.progress = (g * 10f + 120).toInt().coerceIn(0, 240)
                mdrcBandValueLabels.getOrNull(b)?.text = String.format("%+.1f dB", g)
            }
            atsSetEnabled(emuEn)
            atsSetAmount(emuAmt)
            dspProcessor?.setBassBoost(bassEn, bassFreq, bassGain)
            dspProcessor?.setBluetoothAutoBypass(switchBtAutoBypass.isChecked)
            updateEmuStatus()
            updateMdrcControlsAlpha(mdrcEn)
            updateControlsAlpha(master)
            switchGlobalAudio.isChecked = prefs.getBoolean("global_audio_enabled", false)
            if (switchGlobalAudio.isChecked) GlobalAudioService.start(this)
            syncGlobalAudioDsp()
        } finally { isUpdatingUiFromCode = false }
    }

    private fun applyPresetByName(name: String) {
        val match = presetManager.getAllPresets().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (match!= null) {
            loadPresetIntoUi(match)
        } else {
            try { SjbzAudioEngine.eqWrapper.applyPreset(name) } catch (_: Exception) {}
            val isBass = name.equals("Bass", ignoreCase = true)
            val gains = List(EqualizerProcessor.BAND_COUNT) { i ->
                when {
                    isBass && i < 6 -> 6f
                    isBass && i < 10 -> 3f
                    else -> 0f
                }
            }
            val preset = EqPreset(name, 0f, gains, true, isBass, 85f, if (isBass) 8f else 4f)
            loadPresetIntoUi(preset)
        }
    }

    private fun loadPresetIntoUi(preset: EqPreset) {
        isUpdatingUiFromCode = true
        try {
            seekBarPreamp.progress = (preset.preampDb * 10f + 120).toInt().coerceIn(0, 240)
            tvPreampValue.text = String.format("%+.1f dB", preset.preampDb)
            switchBassBoost.isChecked = preset.bassBoostEnabled
            spinnerBassFreq.setSelection(when (preset.bassBoostFreq) { 60f -> 0; 85f -> 1; else -> 2 })
            seekBarBassBoost.progress = (preset.bassBoostGain * 10f).toInt().coerceIn(0, 120)
            tvBassBoostValue.text = String.format("+%.1f dB", preset.bassBoostGain)
            val ed = prefs.edit()
            for (i in 0 until minOf(preset.bandGains.size, bandSeekBars.size)) {
                val g = preset.bandGains[i]
                bandSeekBars[i].progress = (g * 10f + 120).toInt().coerceIn(0, 240)
                bandValueLabels[i].text = String.format("%+.1f", g)
                ed.putFloat("band_gain_$i", g)
            }
            ed.putFloat("preamp_db", preset.preampDb); ed.apply()
            presetManager.setActivePresetName(preset.name)
        } finally { isUpdatingUiFromCode = false }
        syncGlobalAudioDsp()
    }

    private fun applyGenrePreset(genre: String) {
        when (genre.lowercase()) {
            "rock", "metal" -> applyPresetByName("Rock")
            "electronic", "hip-hop" -> applyPresetByName("Bass")
            "vocal", "acoustic" -> applyPresetByName("Vocal")
            else -> applyPresetByName("Studio Master")
        }
    }

    private fun resetAllBandsToZero() {
        isUpdatingUiFromCode = true
        try {
            val ed = prefs.edit()
            for (i in bandSeekBars.indices) {
                bandSeekBars[i].progress = 120
                bandValueLabels[i].text = "0.0"
                ed.putFloat("band_gain_$i", 0f)
            }
            ed.apply()
        } finally { isUpdatingUiFromCode = false }
        syncGlobalAudioDsp()
        Toast.makeText(this, "Bandas a 0 dB", Toast.LENGTH_SHORT).show()
    }

    private fun saveCurrentAsCustomPreset() {
        val gains = ArrayList<Float>()
        for (sb in bandSeekBars) gains.add((sb.progress - 120) / 10f)
        val preset = EqPreset(
            "Custom ${System.currentTimeMillis() % 1000}",
            (seekBarPreamp.progress - 120) / 10f,
            gains, true, switchBassBoost.isChecked, getSelectedBassFreq(), seekBarBassBoost.progress / 10f
        )
        presetManager.saveCustomPreset(preset)
        refreshPresetSpinner()
        Toast.makeText(this, "Guardado: ${preset.name}", Toast.LENGTH_SHORT).show()
    }

    private fun updateControlsAlpha(enabled: Boolean) {
        val a = if (enabled) 1f else 0.4f
        llEqBandsContainer.alpha = a
        seekBarPreamp.isEnabled = enabled
        seekBarBassBoost.isEnabled = enabled && switchBassBoost.isChecked
        spinnerBassFreq.isEnabled = enabled && switchBassBoost.isChecked
        switchEmu.isEnabled = enabled
        seekBarEmuAmount.isEnabled = enabled && switchEmu.isChecked
        switchMdrc.isEnabled = enabled
        updateMdrcControlsAlpha(enabled && switchMdrc.isChecked)
    }
}
