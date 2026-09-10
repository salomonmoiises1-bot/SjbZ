package com.sjbz.aimp

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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
import com.sjbz.aimp.audio.GlobalAudioSessionManager
import com.sjbz.aimp.audio.LimiterProcessor
import com.sjbz.aimp.audio.MDRCProcessor
import com.sjbz.aimp.audio.PresetManager
import com.sjbz.aimp.model.EqPreset
import com.sjbz.aimp.service.PlaybackService

class EqActivity : AppCompatActivity() {

    private lateinit var presetManager: PresetManager
    private lateinit var equalizerProcessor: EqualizerProcessor
    private lateinit var mdrcProcessor: MDRCProcessor
    private lateinit var globalSessionManager: GlobalAudioSessionManager

    private lateinit var switchEqEnabled: SwitchCompat
    private lateinit var switchMdrcEnabled: SwitchCompat
    private lateinit var switchGlobalSystemEq: SwitchCompat
    private lateinit var tvActiveSessionsStatus: TextView
    private lateinit var btnGlobalHelp: Button
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

    private lateinit var btnSavePreset: Button
    private lateinit var btnDeletePreset: Button
    private lateinit var btnExportSjbz: Button
    private lateinit var btnImportSjbz: Button

    private val bandSeekBars = ArrayList<SeekBar>()
    private val bandValueLabels = ArrayList<TextView>()
    private val mdrcSeekBars = ArrayList<SeekBar>()
    private val mdrcValueLabels = ArrayList<TextView>()

    private var isUpdatingUiFromPreset = false
    private var currentThemeColor: Int = 0xFFFF7700.toInt()

    private val exportSjbzLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri!= null) {
            val currentPresetName = spinnerPresets.selectedItem?.toString()?: "SjbZ_Preset"
            val preset = equalizerProcessor.toEqPreset(
                name = currentPresetName,
                isCustom = true,
                mdrcSettings = mdrcProcessor.toMDRCSettings(),
                color = currentThemeColor
            )
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

        initViews()
        setupToolbar()
        setupGlobalSystemEq()

        // PARCHE 3 - RESPETA ORIGINAL PERO SACA ANR
        // Tu código original creaba 32 bandas en onCreate y daba "SjbZ no responde"
        // Ahora se crean postergadas 16ms, sin perder ni 1 banda
        container32Bands.post {
            setup32BandSliders()
            setupPreampAndControls()
            setupMdrcGainControls()
            setupPresetsSpinner()
            setupButtons()
            val activeName = presetManager.getActivePresetName()
            val activePreset = presetManager.getAllPresets().find { it.name.equals(activeName, ignoreCase = true) }
               ?: presetManager.getFactoryPresets().first()
            loadPresetToUi(activePreset)
        }
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
        switchGlobalSystemEq = findViewById(R.id.switchGlobalSystemEq)
        tvActiveSessionsStatus = findViewById(R.id.tvActiveSessionsStatus)
        btnGlobalHelp = findViewById(R.id.btnGlobalHelp)
        globalSessionManager = GlobalAudioSessionManager.getInstance(this)

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

        btnSavePreset = findViewById(R.id.btnSavePreset)
        btnDeletePreset = findViewById(R.id.btnDeletePreset)
        btnExportSjbz = findViewById(R.id.btnExportSjbz)
        btnImportSjbz = findViewById(R.id.btnImportSjbz)

        switchEqEnabled.isChecked = equalizerProcessor.isEnabled
        switchEqEnabled.setOnCheckedChangeListener { _, isChecked ->
            equalizerProcessor.isEnabled = isChecked
            PlaybackService.instance?.atsEngine?.updateEqualizer()
            syncAllEffects()
            updateSlidersEnabled(isChecked)
        }
    }

    private fun updateSlidersEnabled(enabled: Boolean) {
        seekBarPreamp.isEnabled = enabled
        for (sb in bandSeekBars) sb.isEnabled = enabled
    }

    private fun setupGlobalSystemEq() {
        switchGlobalSystemEq.isChecked = globalSessionManager.isGlobalModeEnabled
        updateGlobalSessionsUi()
        switchGlobalSystemEq.setOnCheckedChangeListener { _, isChecked ->
            globalSessionManager.enableGlobalMode(isChecked, this)
            syncAllEffects()
            updateGlobalSessionsUi()
            val msg = if (isChecked) "Modo Global Activado: Ecualizando audio de Android y apps externas" else "Modo Global Desactivado: Solo reproductor local SjbZ AIMP"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        globalSessionManager.onSessionsChangedListener = { updateGlobalSessionsUi() }
        btnGlobalHelp.setOnClickListener { showGlobalHelpDialog() }
    }

    private fun updateGlobalSessionsUi() {
        if (!globalSessionManager.isGlobalModeEnabled) {
            tvActiveSessionsStatus.text = "Estado: Modo local SjbZ AIMP (Desactivado para apps externas)"
            tvActiveSessionsStatus.setTextColor(Color.parseColor("#A0A0A0"))
            return
        }
        val sessions = globalSessionManager.getActiveSessionsSummary()
        val text = buildString {
            append("● Modo Global ACTIVO (DSP ATS2835P para el Sistema)\n")
            if (sessions.isEmpty()) append("Esperando reproducción de audio...") else sessions.forEach { append(it).append("\n") }
        }.trimEnd()
        tvActiveSessionsStatus.text = text
        tvActiveSessionsStatus.setTextColor(Color.parseColor("#00E676"))
    }

    private fun syncAllEffects() {
        val limiter = PlaybackService.instance?.atsEngine?.limiter?: LimiterProcessor()
        globalSessionManager.syncAudioEffects(equalizerProcessor, mdrcProcessor, limiter)
    }

    private fun showGlobalHelpDialog() {
        AlertDialog.Builder(this)
           .setTitle("Modo Global (Estilo Wavelet / Sin Root)")
           .setMessage("¿Cómo funciona en Android?\n\n1. Spotify, Deezer, Tidal, Apple Music, VLC, Poweramp:\nEstas apps transmiten su sesión de audio al sistema. SjbZ AIMP la intercepta automáticamente y le aplica la curva de 32 bandas ISO, MDRC y limitador ATS2835P.\n\n2. YouTube, Chrome, TikTok, Juegos:\nAl activar el interruptor, SjbZ AIMP se enlaza al Mezclador Global #0 de Android para procesar el audio del sistema.\n\n3. Detección Avanzada (Opcional vía ADB):\nPara apps que intenten bloquear la sesión, puedes otorgar el permiso DUMP conectando el móvil a una PC y ejecutando:\nadb shell pm grant com.sjbz.aimp android.permission.DUMP")
           .setPositiveButton("Entendido", null).show()
    }

    private fun setup32BandSliders() {
        if (container32Bands.childCount > 0) return
        container32Bands.removeAllViews()
        bandSeekBars.clear()
        bandValueLabels.clear()
        val bandCount = EqualizerProcessor.BAND_COUNT
        val labels = EqualizerProcessor.BAND_LABELS
        for (i in 0 until bandCount) {
            val bandCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.density * 54).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(4, 8, 4, 8)
            }
            val tvGain = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = "0.0"; textSize = 10f; setTextColor(currentThemeColor); gravity = Gravity.CENTER
            }
            bandCol.addView(tvGain); bandValueLabels.add(tvGain)
            val faderContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1.0f); gravity = Gravity.CENTER
            }
            val seekBar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.density * 160).toInt(), (resources.displayMetrics.density * 36).toInt())
                rotation = 270f; max = 240
                val currentG = equalizerProcessor.getBandGain(i)
                progress = (currentG * 10.0f + 120).toInt().coerceIn(0, 240)
                progressTintList = ColorStateList.valueOf(currentThemeColor)
                thumbTintList = ColorStateList.valueOf(currentThemeColor)
            }
            tvGain.text = String.format("%+.1f", equalizerProcessor.getBandGain(i))
            val bandIndex = i
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gainDb = (progress - 120) / 10.0f
                    tvGain.text = String.format("%+.1f", gainDb)
                    if (fromUser &&!isUpdatingUiFromPreset) {
                        equalizerProcessor.setBandGain(bandIndex, gainDb)
                        PlaybackService.instance?.atsEngine?.updateEqualizer()
                        syncAllEffects()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            faderContainer.addView(seekBar); bandCol.addView(faderContainer); bandSeekBars.add(seekBar)
            val tvFreq = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = labels[i]; textSize = 9.5f; setTextColor(Color.parseColor("#CCCCCC")); gravity = Gravity.CENTER
            }
            bandCol.addView(tvFreq); container32Bands.addView(bandCol)
        }
    }

    private fun setupPreampAndControls() {
        seekBarPreamp.progress = (equalizerProcessor.preampDb * 10.0f + 120).toInt().coerceIn(0, 240)
        tvPreampValue.text = String.format("%+.1f dB", equalizerProcessor.preampDb)
        seekBarPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvPreampValue.text = String.format("%+.1f dB", db)
                if (fromUser &&!isUpdatingUiFromPreset) {
                    equalizerProcessor.preampDb = db
                    PlaybackService.instance?.atsEngine?.updateEqualizer()
                    syncAllEffects()
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
                    PlaybackService.instance?.audioChain?.setPlaybackParameters(factor, factor)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekBarCrossfade.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvCrossfadeValue.text = "${progress}s"
                if (fromUser) PlaybackService.instance?.atsEngine?.crossfadeSeconds = progress
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupMdrcGainControls() {
        mdrcSeekBars.clear(); mdrcValueLabels.clear()
        mdrcSeekBars.add(sbMdrcGainSub); mdrcSeekBars.add(sbMdrcGainLow); mdrcSeekBars.add(sbMdrcGainMid); mdrcSeekBars.add(sbMdrcGainHigh); mdrcSeekBars.add(sbMdrcGainAir)
        mdrcValueLabels.add(tvMdrcGainSub); mdrcValueLabels.add(tvMdrcGainLow); mdrcValueLabels.add(tvMdrcGainMid); mdrcValueLabels.add(tvMdrcGainHigh); mdrcValueLabels.add(tvMdrcGainAir)
        for (i in 0 until 5) {
            val sb = mdrcSeekBars[i]; val tv = mdrcValueLabels[i]; val bandIndex = i
            val band = mdrcProcessor.getBand(bandIndex)
            val currentGain = band?.gainDb?: 0.0f
            sb.progress = (currentGain * 10.0f + 120).toInt().coerceIn(0, 240)
            tv.text = String.format("%+.1f dB", currentGain)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gainDb = (progress - 120) / 10.0f
                    tv.text = String.format("%+.1f dB", gainDb)
                    if (fromUser &&!isUpdatingUiFromPreset) {
                        mdrcProcessor.getBand(bandIndex)?.gainDb = gainDb
                        PlaybackService.instance?.atsEngine?.updateMDRC()
                        syncAllEffects()
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
            syncAllEffects()
            for (sb in mdrcSeekBars) sb.isEnabled = isChecked
        }
    }

    private fun setupPresetsSpinner() {
        refreshPresetsSpinner(presetManager.getActivePresetName())
        spinnerPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedName = spinnerPresets.selectedItem?.toString()?: return
                presetManager.setActivePresetName(selectedName)
                val targetPreset = presetManager.getAllPresets().find { it.name.equals(selectedName, ignoreCase = true) }
                if (targetPreset!= null) loadPresetToUi(targetPreset)
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
        if (idx >= 0) spinnerPresets.setSelection(idx)
    }

    private fun applyPresetColor(color: Int) {
        currentThemeColor = color
        val colorList = ColorStateList.valueOf(color)
        seekBarPreamp.progressTintList = colorList; seekBarPreamp.thumbTintList = colorList; tvPreampValue.setTextColor(color)
        seekBarSpeed.progressTintList = colorList; seekBarSpeed.thumbTintList = colorList
        seekBarCrossfade.progressTintList = colorList; seekBarCrossfade.thumbTintList = colorList
        for (sb in bandSeekBars) { sb.progressTintList = colorList; sb.thumbTintList = colorList }
        for (tv in bandValueLabels) tv.setTextColor(color)
        for (sb in mdrcSeekBars) { sb.progressTintList = colorList; sb.thumbTintList = colorList }
        for (tv in mdrcValueLabels) tv.setTextColor(color)
        btnSavePreset.setTextColor(color)
        switchEqEnabled.thumbTintList = colorList; switchMdrcEnabled.thumbTintList = colorList
    }

    private fun loadPresetToUi(preset: EqPreset) {
        isUpdatingUiFromPreset = true
        try {
            equalizerProcessor.loadFromPreset(preset)
            applyPresetColor(preset.color)
            val preampProgress = (preset.preampDb * 10.0f + 120).toInt().coerceIn(0, 240)
            seekBarPreamp.progress = preampProgress
            tvPreampValue.text = String.format("%+.1f dB", preset.preampDb)
            val gains = preset.bandGains
            for (i in 0 until minOf(gains.size, bandSeekBars.size)) {
                val g = gains[i]; val p = (g * 10.0f + 120).toInt().coerceIn(0, 240)
                bandSeekBars[i].progress = p; bandValueLabels[i].text = String.format("%+.1f", g)
            }
            val mdrcSettings = preset.mdrcSettings
            mdrcProcessor.loadFromSettings(mdrcSettings)
            switchMdrcEnabled.isChecked = mdrcSettings.enabled
            for (i in 0 until minOf(5, mdrcSettings.bands.size, mdrcSeekBars.size)) {
                val bandCfg = mdrcSettings.bands[i]
                val p = (bandCfg.gainDb * 10.0f + 120).toInt().coerceIn(0, 240)
                mdrcSeekBars[i].progress = p; mdrcValueLabels[i].text = String.format("%+.1f dB", bandCfg.gainDb)
            }
            PlaybackService.instance?.atsEngine?.updateEqualizer()
            PlaybackService.instance?.atsEngine?.updateMDRC()
            syncAllEffects()
        } finally { isUpdatingUiFromPreset = false }
    }

    private fun setupButtons() {
        btnSavePreset.setOnClickListener { showSavePresetDialog() }
        btnSavePreset.setOnLongClickListener {
            showColorPicker { chosenColor -> applyPresetColor(chosenColor); Toast.makeText(this, "Color de preset actualizado", Toast.LENGTH_SHORT).show() }; true
        }
        btnDeletePreset.setOnClickListener {
            val selected = spinnerPresets.selectedItem?.toString()?: return@setOnClickListener
            if (presetManager.defaultPresetNames.contains(selected)) { Toast.makeText(this, "No se pueden borrar presets de fábrica", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Borrar Preset").setMessage("¿Deseas eliminar el preset '$selected'?").setPositiveButton("Borrar") { _, _ ->
                presetManager.deletePreset(selected); refreshPresetsSpinner("ATS-2835P Master"); Toast.makeText(this, "Preset eliminado", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancelar", null).show()
        }
        btnExportSjbz.setOnClickListener {
            val currentPresetName = spinnerPresets.selectedItem?.toString()?: "SjbZ_Preset"
            exportSjbzLauncher.launch("$currentPresetName.sjbz")
        }
        btnImportSjbz.setOnClickListener { importSjbzLauncher.launch(arrayOf("*/*")) }
    }

    private fun showColorPicker(onColorSelected: (Int) -> Unit) {
        val palette = EqPreset.PALETTE
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 24, 36, 24); setBackgroundColor(Color.parseColor("#1E1E1E")) }
        val title = TextView(this).apply { text = "Elige Color para el Preset"; setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, 16); gravity = Gravity.CENTER }
        container.addView(title)
        var dialog: AlertDialog? = null
        for (row in 0..1) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 6, 0, 6) }
            for (col in 0..4) {
                val idx = row * 5 + col; val c = palette[idx]
                val btn = Button(this).apply {
                    layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.density * 52).toInt(), (resources.displayMetrics.density * 44).toInt()).apply { setMargins(6, 4, 6, 4) }
                    backgroundTintList = ColorStateList.valueOf(c)
                    setOnClickListener { onColorSelected(c); dialog?.dismiss() }
                }
                rowLayout.addView(btn)
            }
            container.addView(rowLayout)
        }
        dialog = AlertDialog.Builder(this).setView(container).setNegativeButton("Cancelar", null).create(); dialog.show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply { hint = "Nombre del Preset"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        AlertDialog.Builder(this).setTitle("Guardar Preset").setView(input).setPositiveButton("Guardar") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                val existing = presetManager.getCustomPresets().find { it.name.equals(name, ignoreCase = true) }
                val colorToSave = existing?.color?: currentThemeColor
                val currentPreset = equalizerProcessor.toEqPreset(name = name, isCustom = true, mdrcSettings = mdrcProcessor.toMDRCSettings(), color = colorToSave)
                presetManager.saveCustomPreset(currentPreset); refreshPresetsSpinner(name); applyPresetColor(colorToSave)
                Toast.makeText(this, "Preset '$name' guardado", Toast.LENGTH_SHORT).show()
            }
        }.setNegativeButton("Cancelar", null).show()
    }

    override fun onDestroy() { super.onDestroy(); globalSessionManager.onSessionsChangedListener = null }
}
