package com.sjbz.aimp

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import com.sjbz.aimp.audio.*
import com.sjbz.aimp.model.EqPreset
import com.sjbz.aimp.service.PlaybackService
import com.sjbz.aimp.ui.VerticalSeekBar
import java.util.concurrent.Executors

class EqActivity : AppCompatActivity() {

    private lateinit var presetManager: PresetManager
    private lateinit var equalizerProcessor: EqualizerProcessor
    private lateinit var mdrcProcessor: MDRCProcessor
    private lateinit var bassProcessor: BassBoostProcessor
    private lateinit var globalSessionManager: GlobalAudioSessionManager

    private lateinit var switchEqEnabled: SwitchCompat
    private lateinit var switchMdrcEnabled: SwitchCompat
    private lateinit var switchGlobalSystemEq: SwitchCompat
    private lateinit var tvActiveSessionsStatus: TextView
    private lateinit var spinnerPresets: Spinner
    private lateinit var seekBarPreamp: SeekBar
    private lateinit var seekBarSpeed: SeekBar
    private lateinit var seekBarCrossfade: SeekBar
    private lateinit var tvPreampValue: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvCrossfadeValue: TextView
    private lateinit var container32Bands: LinearLayout
    private lateinit var seekBassBoost: SeekBar
    private lateinit var tvBassBoostValue: TextView
    private lateinit var tvBassGainSide: TextView
    private lateinit var sbMdrcGainSub: SeekBar
    private lateinit var sbMdrcGainLow: SeekBar
    private lateinit var sbMdrcGainMid: SeekBar
    private lateinit var sbMdrcGainHigh: SeekBar
    private lateinit var sbMdrcGainAir: SeekBar
    private lateinit var tvMdrcGainSub: TextView
    private lateinit var tvMdrcGainLow: TextView
    private lateinit var tvMdrcGainMid: TextView
    private lateinit var tvMdrcGainHigh: TextView
    private lateinit var tvMdrcGainAir: TextView

    private val bandSeekBars = ArrayList<SeekBar>()
    private val bandValueLabels = ArrayList<TextView>()
    private var isUpdatingUiFromPreset = false
    private var currentThemeColor: Int = 0xFFFF7700.toInt()
    private var currentBassFreq = 85

    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAudioRunnable: Runnable? = null

    private fun postAudioUpdateDebounced(delayMs: Long = 80, forceSyncGlobal: Boolean = false, action: () -> Unit) {
        pendingAudioRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            audioExecutor.execute {
                try {
                    action()
                    if (forceSyncGlobal) syncAllEffectsInternal()
                } catch (_: Exception) {}
            }
        }
        pendingAudioRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun syncAllEffectsInternal() {
        try {
            val limiter = PlaybackService.instance?.atsEngine?.limiter?: LimiterProcessor()
            globalSessionManager.syncAudioEffects(equalizerProcessor, mdrcProcessor, limiter)
        } catch (_: Exception) {}
    }

    private val exportSjbzLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri!= null) {
            val currentPresetName = spinnerPresets.selectedItem?.toString()?: "SjbZ_Preset"
            val preset = equalizerProcessor.toEqPreset(
                name = currentPresetName,
                isCustom = true,
                mdrcSettings = mdrcProcessor.toMDRCSettings(),
                color = currentThemeColor,
                bassBoostFreq = currentBassFreq,
                bassBoostGain = seekBassBoost.progress / 1000f * 15f
            )
            val success = presetManager.exportPresetToSjbz(preset, uri)
            Toast.makeText(this, if (success) "Exportado SJBZ" else "Error export", Toast.LENGTH_SHORT).show()
        }
    }
    private val importSjbzLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri!= null) {
            val imported = presetManager.importPresetFromSjbz(uri)
            if (imported!= null) {
                refreshPresetsSpinner(imported.name)
                loadPresetToUi(imported)
            } else Toast.makeText(this, "Error import", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)
        presetManager = PresetManager(this)
        val liveEngine = PlaybackService.instance?.atsEngine
        equalizerProcessor = liveEngine?.equalizer?: EqualizerProcessor()
        mdrcProcessor = liveEngine?.mdrc?: MDRCProcessor()
        bassProcessor = liveEngine?.bassBoost?: BassBoostProcessor()
        globalSessionManager = GlobalAudioSessionManager.getInstance(this)

        initViews()
        setupToolbar()
        setupGlobalSystemEq()
        setupBassBoost()
        setup32BandSliders()
        setupPreampAndControls()
        setupMdrcControls()
        setupPresetsSpinner()
        setupButtons()

        val active = presetManager.getAllPresets().find { it.name.equals(presetManager.getActivePresetName(), true) }?: presetManager.getFactoryPresets().first()
        loadPresetToUi(active)
    }

    private fun initViews() {
        switchEqEnabled = findViewById(R.id.switchEqEnabled)
        switchMdrcEnabled = findViewById(R.id.switchMdrcEnabled)
        switchGlobalSystemEq = findViewById(R.id.switchGlobalSystemEq)
        tvActiveSessionsStatus = findViewById(R.id.tvActiveSessionsStatus)
        spinnerPresets = findViewById(R.id.spinnerPresets)
        seekBarPreamp = findViewById(R.id.seekBarPreamp)
        seekBarSpeed = findViewById(R.id.seekBarSpeed)
        seekBarCrossfade = findViewById(R.id.seekBarCrossfade)
        tvPreampValue = findViewById(R.id.tvPreampValue)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvCrossfadeValue = findViewById(R.id.tvCrossfadeValue)
        container32Bands = findViewById(R.id.container32Bands)
        seekBassBoost = findViewById(R.id.seekBassBoost)
        tvBassBoostValue = findViewById(R.id.tvBassBoostValue)
        tvBassGainSide = findViewById(R.id.tvBassGainSide)
        sbMdrcGainSub = findViewById(R.id.sbMdrcGainSub)
        sbMdrcGainLow = findViewById(R.id.sbMdrcGainLow)
        sbMdrcGainMid = findViewById(R.id.sbMdrcGainMid)
        sbMdrcGainHigh = findViewById(R.id.sbMdrcGainHigh)
        sbMdrcGainAir = findViewById(R.id.sbMdrcGainAir)
        tvMdrcGainSub = findViewById(R.id.tvMdrcGainSub)
        tvMdrcGainLow = findViewById(R.id.tvMdrcGainLow)
        tvMdrcGainMid = findViewById(R.id.tvMdrcGainMid)
        tvMdrcGainHigh = findViewById(R.id.tvMdrcGainHigh)
        tvMdrcGainAir = findViewById(R.id.tvMdrcGainAir)
        switchEqEnabled.isChecked = equalizerProcessor.isEnabled
        switchMdrcEnabled.isChecked = mdrcProcessor.isEnabled
    }

    private fun setup32BandSliders() {
        container32Bands.post {
            container32Bands.removeAllViews()
            bandSeekBars.clear()
            bandValueLabels.clear()
            val labels = EqualizerProcessor.BAND_LABELS
            for (i in 0 until EqualizerProcessor.BAND_COUNT) {
                val col = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.density * 58).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val tvGain = TextView(this).apply { text = "+0.0"; textSize = 10f; setTextColor(currentThemeColor); gravity = Gravity.CENTER }
                col.addView(tvGain); bandValueLabels.add(tvGain)
                val sb = VerticalSeekBar(this).apply {
                    layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.density * 44).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                    max = 240; progress = (equalizerProcessor.getBandGain(i) * 10f + 120).toInt().coerceIn(0,240)
                }
                val idx = i
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val gain = (p - 120) / 10f
                        tvGain.text = String.format("%+.1f", gain)
                        if (fromUser &&!isUpdatingUiFromPreset) {
                            equalizerProcessor.setBandGain(idx, gain)
                            postAudioUpdateDebounced(50, false) { PlaybackService.instance?.atsEngine?.updateEqualizer() }
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) { (s?.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true) }
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        (s?.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        postAudioUpdateDebounced(0, true) { PlaybackService.instance?.atsEngine?.updateEqualizer() }
                    }
                })
                col.addView(sb); bandSeekBars.add(sb)
                col.addView(TextView(this).apply { text = labels[i]; textSize = 9f; setTextColor(Color.GRAY); gravity = Gravity.CENTER })
                container32Bands.addView(col)
            }
        }
    }

    private fun setupBassBoost() {
        val btn60 = findViewById<Button>(R.id.btnFreq60)
        val btn85 = findViewById<Button>(R.id.btnFreq85)
        val btn120 = findViewById<Button>(R.id.btnFreq120)
        val btnOff = findViewById<Button>(R.id.btnBassOff)
        val btn3 = findViewById<Button>(R.id.btnBass3)
        val btn6 = findViewById<Button>(R.id.btnBass6)
        val btn9 = findViewById<Button>(R.id.btnBass9)
        val btn12 = findViewById<Button>(R.id.btnBass12)

        fun apply(freq: Int, gain: Float) {
            currentBassFreq = freq
            val percent = (gain / 15f * 100).toInt()
            tvBassBoostValue.text = String.format("+%.1f dB (%d%%) ACTIVO", gain, percent)
            tvBassGainSide.text = String.format("+%.1f dB", gain)
            postAudioUpdateDebounced(30, true) { PlaybackService.instance?.atsEngine?.setBassBoost(freq, gain) }
        }

        btn60.setOnClickListener { apply(60, seekBassBoost.progress/1000f*15f) }
        btn85.setOnClickListener { apply(85, seekBassBoost.progress/1000f*15f) }
        btn120.setOnClickListener { apply(120, seekBassBoost.progress/1000f*15f) }
        btnOff.setOnClickListener { seekBassBoost.progress = 0 }
        btn3.setOnClickListener { seekBassBoost.progress = 200 }
        btn6.setOnClickListener { seekBassBoost.progress = 400 }
        btn9.setOnClickListener { seekBassBoost.progress = 600 }
        btn12.setOnClickListener { seekBassBoost.progress = 800 }

        seekBassBoost.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val g = p/1000f*15f
                val percent = (g / 15f * 100).toInt()
                tvBassBoostValue.text = String.format("+%.1f dB (%d%%) ACTIVO", g, percent)
                tvBassGainSide.text = String.format("+%.1f dB", g)
                if(fromUser) postAudioUpdateDebounced(50, false) { PlaybackService.instance?.atsEngine?.setBassBoost(currentBassFreq, g) }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                postAudioUpdateDebounced(0, true) { PlaybackService.instance?.atsEngine?.setBassBoost(currentBassFreq, (s?.progress?:0)/1000f*15f) }
            }
        })
    }

    private fun setupToolbar() {
        val tb: Toolbar = findViewById(R.id.eqToolbar)
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        tb.setNavigationOnClickListener { finish() }
    }

    private fun setupGlobalSystemEq() {
        switchGlobalSystemEq.isChecked = globalSessionManager.isGlobalModeEnabled
        tvActiveSessionsStatus.text = if(switchGlobalSystemEq.isChecked) "Global: Activo" else "Estado: Modo local SjbZ AIMP"
        switchGlobalSystemEq.setOnCheckedChangeListener { _, c ->
            globalSessionManager.enableGlobalMode(c, this)
            tvActiveSessionsStatus.text = if(c) "Global: Activo - Spotify/Deezer" else "Estado: Modo local SjbZ AIMP"
            postAudioUpdateDebounced(0, true) {}
        }
        // FIX: listener sin parametro
        globalSessionManager.onSessionsChangedListener = {
            runOnUiThread {
                val summary = globalSessionManager.getActiveSessionsSummary()
                tvActiveSessionsStatus.text = if (summary.isEmpty()) "Estado: Modo local SjbZ AIMP" else summary.joinToString("\n")
            }
        }
        findViewById<Button>(R.id.btnGlobalHelp).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Modo Global").setMessage("Aplica EQ a Spotify, YouTube, Deezer. Requiere permiso de audio. Si hay ANR, desactívalo.").setPositiveButton("OK", null).show()
        }
    }

    private fun setupPreampAndControls() {
        seekBarPreamp.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val db = (p-120)/10f
                tvPreampValue.text = String.format("%+.1f dB", db)
                if(fromUser) { equalizerProcessor.setPreamp(db); postAudioUpdateDebounced(60, false){ PlaybackService.instance?.atsEngine?.updateEqualizer() } }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { postAudioUpdateDebounced(0, true){ PlaybackService.instance?.atsEngine?.updateEqualizer() } }
        })
        seekBarSpeed.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val speed = 0.5f + p/100f
                tvSpeedValue.text = String.format("%.2fx", speed)
                if(fromUser) postAudioUpdateDebounced(60, false){ PlaybackService.instance?.setPlaybackSpeed(speed) }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekBarCrossfade.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { tvCrossfadeValue.text = "${p}s" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun setupMdrcControls() {
        // FIX linea 264: tipo explicito para inferencia
        val pairs: List<Pair<TextView, SeekBar>> = listOf(
            Pair(tvMdrcGainSub, sbMdrcGainSub),
            Pair(tvMdrcGainLow, sbMdrcGainLow),
            Pair(tvMdrcGainMid, sbMdrcGainMid),
            Pair(tvMdrcGainHigh, sbMdrcGainHigh),
            Pair(tvMdrcGainAir, sbMdrcGainAir)
        )
        for((tv, sb) in pairs) {
            sb.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val db = (p-120)/10f
                    tv.text = String.format("%+.1f dB", db)
                    if(fromUser) {
                        val idx = pairs.indexOfFirst { it.second == sb }
                        if (idx >= 0) mdrcProcessor.setGainForIndex(idx, db)
                        postAudioUpdateDebounced(60, false){ PlaybackService.instance?.atsEngine?.updateMDRC() }
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { postAudioUpdateDebounced(0, true){ PlaybackService.instance?.atsEngine?.updateMDRC() } }
            })
        }
        switchMdrcEnabled.setOnCheckedChangeListener { _, c ->
            mdrcProcessor.isEnabled = c
            postAudioUpdateDebounced(0, true){ PlaybackService.instance?.atsEngine?.updateMDRC() }
        }
        switchEqEnabled.setOnCheckedChangeListener { _, c ->
            equalizerProcessor.isEnabled = c
            postAudioUpdateDebounced(0, true){ PlaybackService.instance?.atsEngine?.updateEqualizer() }
        }
    }

    private fun setupPresetsSpinner() {
        refreshPresetsSpinner(null)
        spinnerPresets.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if(isUpdatingUiFromPreset) return
                val name = p?.getItemAtPosition(pos) as? String?: return
                val preset = presetManager.getAllPresets().find { it.name == name }?: return
                loadPresetToUi(preset)
                presetManager.setActivePresetName(name)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    fun refreshPresetsSpinner(selectName: String?) {
        val names = presetManager.getAllPresets().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerPresets.adapter = adapter
        selectName?.let { val idx = names.indexOf(it); if(idx>=0) spinnerPresets.setSelection(idx) }
    }

    private fun loadPresetToUi(preset: EqPreset) {
        isUpdatingUiFromPreset = true
        try {
            currentThemeColor = preset.color
            equalizerProcessor.loadFromPreset(preset)
            mdrcProcessor.loadFromSettings(preset.mdrcSettings)
            currentBassFreq = preset.bassBoostFreq
            val bassG = preset.bassBoostGain

            seekBarPreamp.progress = (preset.preampDb * 10f + 120).toInt().coerceIn(0,240)
            tvPreampValue.text = String.format("%+.1f dB", preset.preampDb)

            for(i in 0 until EqualizerProcessor.BAND_COUNT.coerceAtMost(bandSeekBars.size)) {
                val g = preset.bandGains.getOrNull(i)?: 0f
                bandSeekBars[i].progress = (g*10f+120).toInt().coerceIn(0,240)
                bandValueLabels[i].text = String.format("%+.1f", g)
            }

            seekBassBoost.progress = (bassG/15f*1000).toInt().coerceIn(0,1000)
            tvBassBoostValue.text = String.format("+%.1f dB (%d%%) ACTIVO", bassG, (bassG/15f*100).toInt())
            tvBassGainSide.text = String.format("+%.1f dB", bassG)

            val sbs = listOf(sbMdrcGainSub, sbMdrcGainLow, sbMdrcGainMid, sbMdrcGainHigh, sbMdrcGainAir)
            val tvs = listOf(tvMdrcGainSub, tvMdrcGainLow, tvMdrcGainMid, tvMdrcGainHigh, tvMdrcGainAir)
            for(i in sbs.indices) {
                val g = preset.mdrcSettings.bands.getOrNull(i)?.gainDb?: 0f
                sbs[i].progress = (g*10f+120).toInt().coerceIn(0,240)
                tvs[i].text = String.format("%+.1f dB", g)
            }
            postAudioUpdateDebounced(0, true) {
                PlaybackService.instance?.atsEngine?.apply {
                    updateEqualizer()
                    updateMDRC()
                    setBassBoost(currentBassFreq, bassG)
                }
            }
        } finally { isUpdatingUiFromPreset = false }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnSavePreset).setOnClickListener {
            val input = EditText(this); input.hint = "Nombre preset"
            AlertDialog.Builder(this).setTitle("Guardar preset").setView(input)
             .setPositiveButton("Guardar") { _, _ ->
                    val name = input.text.toString().ifEmpty { "Custom ${System.currentTimeMillis()}" }
                    val preset = equalizerProcessor.toEqPreset(
                        name = name,
                        isCustom = true,
                        mdrcSettings = mdrcProcessor.toMDRCSettings(),
                        color = currentThemeColor,
                        bassBoostFreq = currentBassFreq,
                        bassBoostGain = seekBassBoost.progress/1000f*15f
                    )
                    presetManager.saveCustomPreset(preset)
                    refreshPresetsSpinner(name)
                }.setNegativeButton("Cancel", null).show()
        }
        findViewById<Button>(R.id.btnDeletePreset).setOnClickListener {
            val name = spinnerPresets.selectedItem as? String?: return@setOnClickListener
            AlertDialog.Builder(this).setTitle("Borrar $name?").setPositiveButton("Borrar") { _, _ -> presetManager.deleteCustomPreset(name); refreshPresetsSpinner(null) }.setNegativeButton("No", null).show()
        }
        findViewById<Button>(R.id.btnExportSjbz).setOnClickListener { exportSjbzLauncher.launch("sjbz_${System.currentTimeMillis()}.json") }
        findViewById<Button>(R.id.btnImportSjbz).setOnClickListener { importSjbzLauncher.launch(arrayOf("application/json")) }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingAudioRunnable?.let { mainHandler.removeCallbacks(it) }
        audioExecutor.shutdown()
        globalSessionManager.onSessionsChangedListener = null
    }
}
