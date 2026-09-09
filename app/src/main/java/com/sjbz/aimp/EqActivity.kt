package com.sjbz.aimp

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import com.sjbz.aimp.audio.EqualizerProcessor
import com.sjbz.aimp.audio.LimiterProcessor
import com.sjbz.aimp.audio.MDRCProcessor
import com.sjbz.aimp.audio.PresetManager
import com.sjbz.aimp.model.EqPreset
import com.sjbz.aimp.service.PlaybackService

class EqActivity : AppCompatActivity() {

    private lateinit var presetManager: PresetManager
    private lateinit var equalizerProcessor: EqualizerProcessor
    private lateinit var mdrcProcessor: MDRCProcessor
    private lateinit var limiterProcessor: LimiterProcessor

    private lateinit var switchEqEnabled: SwitchCompat
    private lateinit var switchMdrcEnabled: SwitchCompat
    private lateinit var spinnerPresets: Spinner
    private lateinit var seekBarPreamp: SeekBar
    private lateinit var tvPreampValue: TextView
    private lateinit var seekBarSpeed: SeekBar
    private lateinit var tvSpeedValue: TextView
    private lateinit var seekBarCrossfade: SeekBar
    private lateinit var tvCrossfadeValue: TextView
    private lateinit var container32Bands: LinearLayout

    private lateinit var sbMdrcGainSub: SeekBar
    private lateinit var tvMdrcGainSub: TextView
    private lateinit var sbMdrcGainLow: SeekBar
    private lateinit var tvMdrcGainLow: TextView
    private lateinit var sbMdrcGainMid: SeekBar
    private lateinit var tvMdrcGainMid: TextView
    private lateinit var sbMdrcGainHigh: SeekBar
    private lateinit var tvMdrcGainHigh: TextView
    private lateinit var sbMdrcGainAir: SeekBar
    private lateinit var tvMdrcGainAir: TextView

    private lateinit var sbLimiterThresh: SeekBar
    private lateinit var tvLimiterThresh: TextView
    private lateinit var switchLimiter: SwitchCompat
    private lateinit var sbSoftClip: SeekBar
    private lateinit var tvSoftClip: TextView
    private lateinit var switchSoftClip: SwitchCompat

    private lateinit var btnSavePreset: Button
    private lateinit var btnDeletePreset: Button
    private lateinit var btnExportSjbz: Button
    private lateinit var btnImportSjbz: Button

    private val bandSeekBars = ArrayList<SeekBar>()
    private val bandValueLabels = ArrayList<TextView>()
    private val mdrcSeekBars = ArrayList<SeekBar>()
    private val mdrcValueLabels = ArrayList<TextView>()

    private var isUpdatingUiFromPreset = false

    private val exportSjbzLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri!= null) {
            val currentPresetName = spinnerPresets.selectedItem?.toString()?: "SjbZ_Preset"
            val preset = equalizerProcessor.toEqPreset(currentPresetName, isCustom = true, mdrcSettings = mdrcProcessor.toMDRCSettings())
            val success = presetManager.exportPresetToSjbz(preset, uri)
            Toast.makeText(this, if (success) "Exportado a.sjbz con éxito" else "Error al exportar", Toast.LENGTH_SHORT).show()
        }
    }

    private val importSjbzLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri!= null) {
            val imported = presetManager.importPresetFromSjbz(uri)
            if (imported!= null) {
                Toast.makeText(this, "Preset '${imported.name}' importado", Toast.LENGTH_SHORT).show()
                refreshPresetsSpinner(imported.name)
                loadPresetToUi(imported)
            } else {
                Toast.makeText(this, "Error al importar archivo.sjbz", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)

        presetManager = PresetManager(this)

        val liveEngine = PlaybackService.instance?.atsEngine
        equalizerProcessor = liveEngine?.equalizer?: EqualizerProcessor()
        mdrcProcessor = liveEngine?.mdrc?: MDRCProcessor()
        limiterProcessor = liveEngine?.limiter?: LimiterProcessor()

        initViews()
        setupToolbar()
        setup32BandSliders()
        setupPreampAndControls()
        setupMdrcGainControls()
        setupLimiterControls()
        setupPresetsSpinner()
        setupButtons()
    }

    private fun setupToolbar() {
        val toolbar: Toolbar = findViewById(R.id.eqToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        switchEqEnabled = findViewById(R.id.switchEqEnabled)
        switchMdrcEnabled = findViewById(R.id.switchMdrcEnabled)
        spinnerPresets = findViewById(R.id.spinnerPresets)
        seekBarPreamp = findViewById(R.id.seekBarPreamp)
        tvPreampValue = findViewById(R.id.tvPreampValue)
        seekBarSpeed = findViewById(R.id.seekBarSpeed)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        seekBarCrossfade = findViewById(R.id.seekBarCrossfade)
        tvCrossfadeValue = findViewById(R.id.tvCrossfadeValue)
        container32Bands = findViewById(R.id.container32Bands)

        sbMdrcGainSub = findViewById(R.id.sbMdrcGainSub)
        tvMdrcGainSub = findViewById(R.id.tvMdrcGainSub)
        sbMdrcGainLow = findViewById(R.id.sbMdrcGainLow)
        tvMdrcGainLow = findViewById(R.id.tvMdrcGainLow)
        sbMdrcGainMid = findViewById(R.id.sbMdrcGainMid)
        tvMdrcGainMid = findViewById(R.id.tvMdrcGainMid)
        sbMdrcGainHigh = findViewById(R.id.sbMdrcGainHigh)
        tvMdrcGainHigh = findViewById(R.id.tvMdrcGainHigh)
        sbMdrcGainAir = findViewById(R.id.sbMdrcGainAir)
        tvMdrcGainAir = findViewById(R.id.tvMdrcGainAir)

        sbLimiterThresh = findViewById(R.id.sbLimiterThresh)
        tvLimiterThresh = findViewById(R.id.tvLimiterThresh)
        switchLimiter = findViewById(R.id.switchLimiter)
        sbSoftClip = findViewById(R.id.sbSoftClip)
        tvSoftClip = findViewById(R.id.tvSoftClip)
        switchSoftClip = findViewById(R.id.switchSoftClip)

        btnSavePreset = findViewById(R.id.btnSavePreset)
        btnDeletePreset = findViewById(R.id.btnDeletePreset)
        btnExportSjbz = findViewById(R.id.btnExportSjbz)
        btnImportSjbz = findViewById(R.id.btnImportSjbz)

        switchEqEnabled.isChecked = equalizerProcessor.isEnabled
        switchEqEnabled.setOnCheckedChangeListener { _, isChecked ->
            equalizerProcessor.isEnabled = isChecked
            PlaybackService.instance?.atsEngine?.updateEqualizer()
            updateSlidersEnabled(isChecked)
        }
    }

    private fun updateSlidersEnabled(enabled: Boolean) {
        seekBarPreamp.isEnabled = enabled
        for (sb in bandSeekBars) {
            sb.isEnabled = enabled
        }
    }

    private fun setup32BandSliders() {
        container32Bands.removeAllViews()
        bandSeekBars.clear()
        bandValueLabels.clear()

        val bandCount = EqualizerProcessor.BAND_COUNT
        val labels = EqualizerProcessor.BAND_LABELS

        for (i in 0 until bandCount) {
            val bandCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (resources.displayMetrics.density * 54).toInt(),
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(4, 8, 4, 8)
            }

            val tvGain = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "0.0"
                textSize = 10f
                setTextColor(Color.parseColor("#FF7700"))
                gravity = Gravity.CENTER
            }
            bandCol.addView(tvGain)
            bandValueLabels.add(tvGain)

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
                    (resources.displayMetrics.density * 160).toInt(),
                    (resources.displayMetrics.density * 36).toInt()
                )
                rotation = 270f
                max = 240
                val currentG = equalizerProcessor.getBandGain(i)
                progress = (currentG * 10.0f + 120).toInt().coerceIn(0, 240)
                progressTintList = ColorStateList.valueOf(Color.parseColor("#FF7700"))
                thumbTintList = ColorStateList.valueOf(Color.parseColor("#FF7700"))
            }
            tvGain.text = String.format("%+.1f", equalizerProcessor.getBandGain(i))

            // FIX EQ 32 BANDAS SCROLL: evita que el ScrollView te robe el gesto
            seekBar.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        container32Bands.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                        container32Bands.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }

            val bandIndex = i
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gainDb = (progress - 120) / 10.0f
                    tvGain.text = String.format("%+.1f", gainDb)
                    if (fromUser &&!isUpdatingUiFromPreset) {
                        equalizerProcessor.setBandGain(bandIndex, gainDb)
                        PlaybackService.instance?.atsEngine?.updateEqualizer()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            faderContainer.addView(seekBar)
            bandCol.addView(faderContainer)
            bandSeekBars.add(seekBar)

            val tvFreq = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = labels[i]
                textSize = 9.5f
                setTextColor(Color.parseColor("#CCCCCC"))
                gravity = Gravity.CENTER
            }
            bandCol.addView(tvFreq)

            container32Bands.addView(bandCol)
        }
    }

    private fun setupPreampAndControls() {
        seekBarPreamp.progress = (equalizerProcessor.preampDb * 10.0f + 120).toInt().coerceIn(0, 240)
        tvPreampValue.text = String.format("%+.1f dB", equalizerProcessor.preampDb)

        // FIX: que no se deslice la pagina con preamp
        seekBarPreamp.setOnTouchListener { v, e ->
            when(e.action){ MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true); MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false) }
            false
        }

        seekBarPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvPreampValue.text = String.format("%+.1f dB", db)
                if (fromUser) {
                    equalizerProcessor.preampDb = db
                    PlaybackService.instance?.atsEngine?.updateEqualizer()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val factor = 0.5f + (progress / 100.0f)
                tvSpeedValue.text = String.format("%.2fx", factor)
                if (fromUser) {
                    PlaybackService.instance?.setPlaybackSpeed(factor)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekBarCrossfade.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvCrossfadeValue.text = "${progress}s"
                if (fromUser) {
                    PlaybackService.instance?.atsEngine?.crossfadeSeconds = progress
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupMdrcGainControls() {
        mdrcSeekBars.clear()
        mdrcValueLabels.clear()

        mdrcSeekBars.add(sbMdrcGainSub)
        mdrcSeekBars.add(sbMdrcGainLow)
        mdrcSeekBars.add(sbMdrcGainMid)
        mdrcSeekBars.add(sbMdrcGainHigh)
        mdrcSeekBars.add(sbMdrcGainAir)

        mdrcValueLabels.add(tvMdrcGainSub)
        mdrcValueLabels.add(tvMdrcGainLow)
        mdrcValueLabels.add(tvMdrcGainMid)
        mdrcValueLabels.add(tvMdrcGainHigh)
        mdrcValueLabels.add(tvMdrcGainAir)

        for (i in 0 until 5) {
            val sb = mdrcSeekBars[i]
            val tv = mdrcValueLabels[i]
            val bandIndex = i
            val band = mdrcProcessor.getBand(bandIndex)
            val currentGain = band?.gainDb?: 0.0f
            sb.progress = (currentGain * 10.0f + 120).toInt().coerceIn(0, 240)
            tv.text = String.format("%+.1f dB", currentGain)

            sb.setOnTouchListener { v, e ->
                when(e.action){ MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true); MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false) }
                false
            }

            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gainDb = (progress - 120) / 10.0f
                    tv.text = String.format("%+.1f dB", gainDb)
                    if (fromUser &&!isUpdatingUiFromPreset) {
                        mdrcProcessor.getBand(bandIndex)?.gainDb = gainDb
                        PlaybackService.instance?.atsEngine?.updateMDRC()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        switchMdrcEnabled.isChecked = mdrcProcessor.isEnabled
        switchMdrcEnabled.setOnCheckedChangeListener { _, isChecked ->
            mdrcProcessor.isEnabled = isChecked
            PlaybackService.instance?.atsEngine?.updateMDRC()
            for (sb in mdrcSeekBars) {
                sb.isEnabled = isChecked
            }
        }
    }

    private fun setupLimiterControls() {
        val liveEngine = PlaybackService.instance?.atsEngine
        val currentThresh = liveEngine?.limiterThresholdDb?: limiterProcessor.thresholdDb
        val threshProgress = ((currentThresh + 12f) * 10).toInt().coerceIn(0, 120)
        sbLimiterThresh.progress = threshProgress
        tvLimiterThresh.text = String.format("%.1f dBFS", currentThresh)

        sbLimiterThresh.setOnTouchListener { v, e ->
            when(e.action){ MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true); MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false) }
            false
        }

        sbLimiterThresh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress / 10f) - 12f
                tvLimiterThresh.text = String.format("%.1f dBFS", db)
                if (fromUser) {
                    // FIX LIMITER THRESH: usa engine, no solo processor local
                    limiterProcessor.thresholdDb = db
                    liveEngine?.setLimiterThreshold(db)?: PlaybackService.instance?.atsEngine?.updateLimiter()
                    PlaybackService.instance?.audioChain?.setLimiterThreshold(db)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchLimiter.isChecked = limiterProcessor.isEnabled
        switchLimiter.setOnCheckedChangeListener { _, isChecked ->
            limiterProcessor.isEnabled = isChecked
            sbLimiterThresh.isEnabled = isChecked &&!limiterProcessor.isBypassedForBluetooth
            PlaybackService.instance?.atsEngine?.updateLimiter()
        }

        sbSoftClip.progress = (limiterProcessor.softClipDrive * 100).toInt().coerceIn(0, 100)
        tvSoftClip.text = "${(limiterProcessor.softClipDrive * 100).toInt()}%"

        sbSoftClip.setOnTouchListener { v, e ->
            when(e.action){ MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true); MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false) }
            false
        }

        sbSoftClip.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvSoftClip.text = "$progress%"
                if (fromUser) {
                    limiterProcessor.softClipDrive = progress / 100f
                    PlaybackService.instance?.atsEngine?.updateLimiter()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchSoftClip.isChecked = limiterProcessor.softClipEnabled
        switchSoftClip.setOnCheckedChangeListener { _, isChecked ->
            limiterProcessor.softClipEnabled = isChecked
            sbSoftClip.isEnabled = isChecked
            PlaybackService.instance?.atsEngine?.updateLimiter()
        }

        if (limiterProcessor.isBypassedForBluetooth) {
            sbLimiterThresh.isEnabled = false
            switchLimiter.isEnabled = false
            tvLimiterThresh.text = "BYPASS BT"
        }
    }

    private fun setupPresetsSpinner() {
        refreshPresetsSpinner(presetManager.getActivePresetName())

        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedName = spinnerPresets.selectedItem?.toString()?: return
                presetManager.setActivePresetName(selectedName)
                val allPresets = presetManager.getAllPresets()
                val targetPreset = allPresets.find { it.name.equals(selectedName, ignoreCase = true) }
                if (targetPreset!= null) {
                    loadPresetToUi(targetPreset)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun refreshPresetsSpinner(selectPresetName: String? = null) {
        val allPresets = presetManager.getAllPresets()
        val names = allPresets.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerPresets.adapter = adapter

        val target = selectPresetName?: presetManager.getActivePresetName()
        val idx = names.indexOf(target)
        if (idx >= 0) {
            spinnerPresets.setSelection(idx)
        }
    }

    private fun loadPresetToUi(preset: EqPreset) {
        isUpdatingUiFromPreset = true
        try {
            equalizerProcessor.loadFromPreset(preset)

            val preampProgress = (preset.preampDb * 10.0f + 120).toInt().coerceIn(0, 240)
            seekBarPreamp.progress = preampProgress
            tvPreampValue.text = String.format("%+.1f dB", preset.preampDb)

            val gains = preset.bandGains
            for (i in 0 until minOf(gains.size, bandSeekBars.size)) {
                val g = gains[i]
                val p = (g * 10.0f + 120).toInt().coerceIn(0, 240)
                bandSeekBars[i].progress = p
                bandValueLabels[i].text = String.format("%+.1f", g)
            }

            val mdrcSettings = preset.mdrcSettings
            mdrcProcessor.loadFromSettings(mdrcSettings)
            switchMdrcEnabled.isChecked = mdrcSettings.enabled

            for (i in 0 until minOf(5, mdrcSettings.bands.size, mdrcSeekBars.size)) {
                val bandCfg = mdrcSettings.bands[i]
                val p = (bandCfg.gainDb * 10.0f + 120).toInt().coerceIn(0, 240)
                mdrcSeekBars[i].progress = p
                mdrcValueLabels[i].text = String.format("%+.1f dB", bandCfg.gainDb)
            }

            PlaybackService.instance?.atsEngine?.updateEqualizer()
            PlaybackService.instance?.atsEngine?.updateMDRC()
            PlaybackService.instance?.atsEngine?.updateLimiter()
        } finally {
            isUpdatingUiFromPreset = false
        }
    }

    private fun setupButtons() {
        btnSavePreset.setOnClickListener { showSavePresetDialog() }

        btnDeletePreset.setOnClickListener {
            val selected = spinnerPresets.selectedItem?.toString()?: return@setOnClickListener
            if (presetManager.defaultPresetNames.contains(selected)) {
                Toast.makeText(this, "No se pueden borrar presets de fábrica", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
             .setTitle("Borrar Preset")
             .setMessage("¿Deseas eliminar el preset '$selected'?")
             .setPositiveButton("Borrar") { _, _ ->
                    presetManager.deletePreset(selected)
                    refreshPresetsSpinner("ATS-2835P Master")
                    Toast.makeText(this, "Preset eliminado", Toast.LENGTH_SHORT).show()
                }
             .setNegativeButton("Cancelar", null)
             .show()
        }

        btnExportSjbz.setOnClickListener {
            val currentPresetName = spinnerPresets.selectedItem?.toString()?: "SjbZ_Preset"
            exportSjbzLauncher.launch("$currentPresetName.sjbz")
        }

        btnImportSjbz.setOnClickListener {
            importSjbzLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply {
            hint = "Nombre del Preset"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
         .setTitle("Guardar Preset")
         .setView(input)
         .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val currentPreset = equalizerProcessor.toEqPreset(
                        name = name,
                        isCustom = true,
                        mdrcSettings = mdrcProcessor.toMDRCSettings()
                    )
                    presetManager.saveCustomPreset(currentPreset)
                    refreshPresetsSpinner(name)
                    Toast.makeText(this, "Preset '$name' guardado", Toast.LENGTH_SHORT).show()
                }
            }
         .setNegativeButton("Cancelar", null)
         .show()
    }
}
