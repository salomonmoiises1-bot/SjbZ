package com.sjbz.aimp

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.sjbz.aimp.audio.EqualizerProcessor
import com.sjbz.aimp.audio.GlobalAudioSessionManager
import com.sjbz.aimp.audio.SjbzDspProcessor
import com.sjbz.aimp.data.AudioSettingsDataStore
import com.sjbz.aimp.model.AppProfile
import com.sjbz.aimp.service.GlobalAudioService
import com.sjbz.aimp.ui.AudioSpectrumVisualizerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var audioSessionManager: GlobalAudioSessionManager
    private lateinit var dataStore: AudioSettingsDataStore
    private val scope = CoroutineScope(Dispatchers.Main)

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenuDrawer: ImageButton
    private lateinit var btnOpenEqualizer: ImageButton
    private lateinit var etSearchTracks: EditText
    private lateinit var tvDspActiveStatus: TextView
    private lateinit var viewDspIndicator: View
    private lateinit var switchMasterDsp: SwitchCompat
    private lateinit var spinnerAppProfiles: Spinner
    private lateinit var btnSaveAppProfile: Button
    private lateinit var tvActiveAppDetection: TextView
    private lateinit var visualizerView: AudioSpectrumVisualizerView
    private lateinit var vuMeterLeftBar: ProgressBar
    private lateinit var vuMeterRightBar: ProgressBar
    private lateinit var tvVuPeakText: TextView
    private lateinit var seekBarGlobalGain: SeekBar
    private lateinit var tvGlobalGainValue: TextView
    private lateinit var switchLimiter: SwitchCompat
    private lateinit var seekBarLimiterThreshold: SeekBar
    private lateinit var tvLimiterThresholdValue: TextView
    private lateinit var switchAutoGain: SwitchCompat
    private lateinit var seekBarAutoGainTarget: SeekBar
    private lateinit var tvAutoGainTargetValue: TextView
    private lateinit var switchBassBoost: SwitchCompat
    private lateinit var spinnerBassFreq: Spinner
    private lateinit var seekBarBassBoost: SeekBar
    private lateinit var tvBassBoostValue: TextView
    private lateinit var seekBarVirtualizer: SeekBar
    private lateinit var tvVirtualizerValue: TextView
    private lateinit var btnPresetFlat: Button
    private lateinit var btnPresetBass: Button
    private lateinit var btnPresetRock: Button
    private lateinit var btnPresetVocal: Button
    private lateinit var btnResetEq: Button
    private lateinit var llEqBandsContainer: LinearLayout
    private lateinit var btnToggleQMode: Button
    private val bandSeekBars = mutableListOf<SeekBar>()
    private val bandValueLabels = mutableListOf<TextView>()
    private var currentQMode: Float = 1.414f
    private lateinit var switchAutoStartBoot: SwitchCompat
    private lateinit var seekBarSystemVolume: SeekBar
    private var isUpdatingUiProgrammatically = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val vuMeterRunnable = object : Runnable {
        override fun run() {
            updateVuMeters()
            uiHandler.postDelayed(this, 80)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audioSessionManager = GlobalAudioSessionManager.getInstance(this)
        dataStore = AudioSettingsDataStore(this)
        bindViews()
        setupDrawerAndToolbar()
        setupMasterSwitch()
        setupAppProfiles()
        setupMasterDynamicsControls()
        setupBassAndVirtualizer()
        setupPresetButtons()
        build32BandSliders()
        setupDrawerSettings()
        syncAllUiFromManager()
        // PARCHE: cargar bandas guardadas al manager para no arrancar flat
        scope.launch {
            try {
                val (gains, qs) = dataStore.loadBands()
                gains.forEachIndexed { i, g -> audioSessionManager.setBandGain(i, g) }
                qs.forEachIndexed { i, q -> audioSessionManager.setBandQ(i, q) }
                syncBandSlidersOnly()
            } catch (_: Exception) {}
        }
        audioSessionManager.onProfileChangedListener = { profile ->
            runOnUiThread { syncAllUiFromManager() }
        }
        audioSessionManager.onActiveSessionsChangedListener = { count, pkgs ->
            runOnUiThread { updateSessionStatusBanner(count, pkgs) }
        }
        if (audioSessionManager.isGlobalAudioEnabled) {
            GlobalAudioService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        visualizerView.startMockVisualizer()
        uiHandler.post(vuMeterRunnable)
        syncAllUiFromManager()
    }

    override fun onPause() {
        super.onPause()
        visualizerView.stopMockVisualizer()
        uiHandler.removeCallbacks(vuMeterRunnable)
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        btnMenuDrawer = findViewById(R.id.btnMenuDrawer)
        btnOpenEqualizer = findViewById(R.id.btnOpenEqualizer)
        etSearchTracks = findViewById(R.id.etSearchTracks)
        tvDspActiveStatus = findViewById(R.id.tvDspActiveStatus)
        viewDspIndicator = findViewById(R.id.viewDspIndicator)
        switchMasterDsp = findViewById(R.id.switchMasterDsp)
        spinnerAppProfiles = findViewById(R.id.spinnerAppProfiles)
        btnSaveAppProfile = findViewById(R.id.btnSaveAppProfile)
        tvActiveAppDetection = findViewById(R.id.tvActiveAppDetection)
        visualizerView = findViewById(R.id.visualizerView)
        vuMeterLeftBar = findViewById(R.id.vuMeterLeftBar)
        vuMeterRightBar = findViewById(R.id.vuMeterRightBar)
        tvVuPeakText = findViewById(R.id.tvVuPeakText)
        seekBarGlobalGain = findViewById(R.id.seekBarGlobalGain)
        tvGlobalGainValue = findViewById(R.id.tvGlobalGainValue)
        switchLimiter = findViewById(R.id.switchLimiter)
        seekBarLimiterThreshold = findViewById(R.id.seekBarLimiterThreshold)
        tvLimiterThresholdValue = findViewById(R.id.tvLimiterThresholdValue)
        switchAutoGain = findViewById(R.id.switchAutoGain)
        seekBarAutoGainTarget = findViewById(R.id.seekBarAutoGainTarget)
        tvAutoGainTargetValue = findViewById(R.id.tvAutoGainTargetValue)
        switchBassBoost = findViewById(R.id.switchBassBoost)
        spinnerBassFreq = findViewById(R.id.spinnerBassFreq)
        seekBarBassBoost = findViewById(R.id.seekBarBassBoost)
        tvBassBoostValue = findViewById(R.id.tvBassBoostValue)
        seekBarVirtualizer = findViewById(R.id.seekBarVirtualizer)
        tvVirtualizerValue = findViewById(R.id.tvVirtualizerValue)
        btnPresetFlat = findViewById(R.id.btnPresetFlat)
        btnPresetBass = findViewById(R.id.btnPresetBass)
        btnPresetRock = findViewById(R.id.btnPresetRock)
        btnPresetVocal = findViewById(R.id.btnPresetVocal)
        btnResetEq = findViewById(R.id.btnResetEq)
        llEqBandsContainer = findViewById(R.id.llEqBandsContainer)
        btnToggleQMode = findViewById(R.id.btnToggleQMode)
        switchAutoStartBoot = findViewById(R.id.switchAutoStartBoot)
        seekBarSystemVolume = findViewById(R.id.seekBarSystemVolume)
        // PARCHE: max correctos
        seekBarGlobalGain.max = 240
        seekBarLimiterThreshold.max = 120
        seekBarBassBoost.max = 120
        seekBarAutoGainTarget.max = 14
        seekBarVirtualizer.max = 1000
    }

    private fun setupDrawerAndToolbar() {
        btnMenuDrawer.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
            else drawerLayout.openDrawer(GravityCompat.START)
        }
        btnOpenEqualizer.setOnClickListener {
            Toast.makeText(this, "Ecualizador de 32 Bandas ISO Activo", Toast.LENGTH_SHORT).show()
        }
        etSearchTracks.hint = "Buscar perfiles (Spotify, YouTube...)"
    }

    private fun setupMasterSwitch() {
        switchMasterDsp.isChecked = audioSessionManager.isGlobalAudioEnabled
        switchMasterDsp.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiProgrammatically) return@setOnCheckedChangeListener
            audioSessionManager.setGlobalAudioEnabled(isChecked)
            if (isChecked) {
                GlobalAudioService.start(this)
                viewDspIndicator.setBackgroundColor(Color.parseColor("#00E5FF"))
                tvDspActiveStatus.text = "SB-Z Global DSP ACTIVO • Session 0 (Mezcla Global)"
                tvDspActiveStatus.setTextColor(Color.parseColor("#00E5FF"))
            } else {
                GlobalAudioService.stop(this)
                viewDspIndicator.setBackgroundColor(Color.parseColor("#64748B"))
                tvDspActiveStatus.text = "SB-Z Global DSP EN PAUSA (Bypass)"
                tvDspActiveStatus.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun setupAppProfiles() {
        refreshProfilesSpinner()
        spinnerAppProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUiProgrammatically) return
                val profile = audioSessionManager.allProfiles.getOrNull(position)
                if (profile!= null && profile.id!= audioSessionManager.currentProfile.id) {
                    audioSessionManager.applyProfile(profile)
                    syncAllUiFromManager()
                    Toast.makeText(this@MainActivity, "Perfil: ${profile.appName}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnSaveAppProfile.setOnClickListener { showSaveProfileDialog() }
    }

    private fun refreshProfilesSpinner() {
        val names = audioSessionManager.allProfiles.map { "${it.appName} (${it.presetName})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerAppProfiles.adapter = adapter
        val selectedIdx = audioSessionManager.allProfiles.indexOfFirst { it.id == audioSessionManager.currentProfile.id }
        if (selectedIdx >= 0) spinnerAppProfiles.setSelection(selectedIdx)
    }

    private fun showSaveProfileDialog() {
        val input = EditText(this).apply { hint = "Nombre del perfil (ej. Spotify Bass, Podcast YouTube)" }
        AlertDialog.Builder(this)
           .setTitle("Guardar Nuevo Perfil")
           .setMessage("Guarda la configuración actual de 32 bandas, Gain, Limiter y AutoGain.")
           .setView(input)
           .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    audioSessionManager.saveCurrentAsProfile(name)
                    refreshProfilesSpinner()
                    Toast.makeText(this, "Perfil guardado: $name", Toast.LENGTH_SHORT).show()
                }
            }
           .setNegativeButton("Cancelar", null)
           .show()
    }

    private fun setupMasterDynamicsControls() {
        seekBarGlobalGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvGlobalGainValue.text = String.format("%+.1f dB", db)
                if (fromUser &&!isUpdatingUiProgrammatically) audioSessionManager.setGlobalGain(db)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        switchLimiter.setOnCheckedChangeListener { _, isChecked ->
            val thresh = (seekBarLimiterThreshold.progress - 120) / 10.0f
            if (!isUpdatingUiProgrammatically) audioSessionManager.setLimiter(isChecked, thresh)
            seekBarLimiterThreshold.isEnabled = isChecked
        }
        seekBarLimiterThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvLimiterThresholdValue.text = String.format("%.1f dB", db)
                if (fromUser &&!isUpdatingUiProgrammatically) audioSessionManager.setLimiter(switchLimiter.isChecked, db)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        switchAutoGain.setOnCheckedChangeListener { _, isChecked ->
            val target = -23.0f + seekBarAutoGainTarget.progress
            if (!isUpdatingUiProgrammatically) audioSessionManager.setAutoGain(isChecked, target)
            seekBarAutoGainTarget.isEnabled = isChecked
        }
        seekBarAutoGainTarget.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val lufs = -23.0f + progress
                tvAutoGainTargetValue.text = String.format("%.0f LUFS", lufs)
                if (fromUser &&!isUpdatingUiProgrammatically) audioSessionManager.setAutoGain(switchAutoGain.isChecked, lufs)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupBassAndVirtualizer() {
        val freqOptions = arrayOf("60 Hz (Sub Bass)", "85 Hz (Punch Bass)", "120 Hz (Mid Bass)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, freqOptions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBassFreq.adapter = adapter
        spinnerBassFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUiProgrammatically) return
                val freq = when (position) { 0 -> 60.0f; 1 -> 85.0f; else -> 120.0f }
                val gain = seekBarBassBoost.progress / 10.0f
                audioSessionManager.setBassBoost(if (switchBassBoost.isChecked) gain else 0f, freq)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            val gain = if (isChecked) seekBarBassBoost.progress / 10.0f else 0.0f
            val freq = getSelectedBassFreq()
            if (!isUpdatingUiProgrammatically) audioSessionManager.setBassBoost(gain, freq)
            seekBarBassBoost.isEnabled = isChecked
            spinnerBassFreq.isEnabled = isChecked
        }
        seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progress / 10.0f
                tvBassBoostValue.text = String.format("+%.1f dB", db)
                if (fromUser &&!isUpdatingUiProgrammatically) audioSessionManager.setBassBoost(if (switchBassBoost.isChecked) db else 0f, getSelectedBassFreq())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekBarVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress / 10
                tvVirtualizerValue.text = "$pct%"
                if (fromUser &&!isUpdatingUiProgrammatically) audioSessionManager.setVirtualizer(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun getSelectedBassFreq(): Float {
        return when (spinnerBassFreq.selectedItemPosition) { 0 -> 60.0f; 1 -> 85.0f; else -> 120.0f }
    }

    private fun setupPresetButtons() {
        btnPresetFlat.setOnClickListener { applyPresetValues(FloatArray(32) { 0.0f }, "Flat") }
        btnPresetBass.setOnClickListener {
            val b = FloatArray(32) { 0.0f }
            b[0]=6.0f; b[1]=5.5f; b[2]=5.0f; b[3]=4.0f; b[4]=3.0f; b[5]=2.0f; b[6]=1.0f
            applyPresetValues(b, "Bass Boost")
        }
        btnPresetRock.setOnClickListener {
            val r = FloatArray(32) { 0.0f }
            r[0]=4.5f; r[1]=4.0f; r[2]=3.5f; r[3]=2.5f
            r[14]=-1.5f; r[15]=-2.0f; r[16]=-1.5f
            r[27]=3.5f; r[28]=4.0f; r[29]=4.5f; r[30]=5.0f
            applyPresetValues(r, "Rock")
        }
        btnPresetVocal.setOnClickListener {
            val v = FloatArray(32) { 0.0f }
            v[0]=-2.0f; v[1]=-1.5f; v[2]=-1.0f
            for (i in 12..22) v[i]=3.5f
            applyPresetValues(v, "Vocal")
        }
        btnResetEq.setOnClickListener { applyPresetValues(FloatArray(32) { 0.0f }, "Reset 0 dB") }
        btnToggleQMode.setOnClickListener {
            currentQMode = when (currentQMode) { 1.414f -> 2.828f; 2.828f -> 0.707f; else -> 1.414f }
            val label = when (currentQMode) { 2.828f -> "Modo Q: Estrecho (2.8)"; 0.707f -> "Modo Q: Amplio (0.7)"; else -> "Modo Q: Estándar (1.4)" }
            btnToggleQMode.text = label
            for (i in 0 until 32) audioSessionManager.setBandQ(i, currentQMode)
            Toast.makeText(this, "Factor Q actualizado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPresetValues(gains: FloatArray, name: String) {
        for (i in 0 until minOf(32, gains.size)) audioSessionManager.setBandGain(i, gains[i])
        syncBandSlidersOnly()
        Toast.makeText(this, "Preset aplicado: $name", Toast.LENGTH_SHORT).show()
    }

    private fun build32BandSliders() {
        llEqBandsContainer.removeAllViews()
        bandSeekBars.clear(); bandValueLabels.clear()
        val density = resources.displayMetrics.density
        val cyanColor = Color.parseColor("#00E5FF")
        for (i in 0 until 32) {
            val bandCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams((density*52).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(2,6,2,6)
            }
            val tvGain = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text="0.0"; textSize=9.5f; setTextColor(cyanColor); gravity=Gravity.CENTER
            }
            bandCol.addView(tvGain)
            val seekBarContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1.0f); gravity=Gravity.CENTER
            }
            val seekBar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams((density*160).toInt(),(density*36).toInt())
                rotation=270f; max=240; progress=120
                progressTintList = android.content.res.ColorStateList.valueOf(cyanColor)
                thumbTintList = android.content.res.ColorStateList.valueOf(cyanColor)
            }
            val bandIndex=i
            seekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(sb:SeekBar?,p:Int,fu:Boolean){
                    val g=(p-120)/10.0f; tvGain.text=String.format("%+.1f",g)
                    if(fu &&!isUpdatingUiProgrammatically) audioSessionManager.setBandGain(bandIndex,g)
                }
                override fun onStartTrackingTouch(sb:SeekBar?){}
                override fun onStopTrackingTouch(sb:SeekBar?){}
            })
            seekBarContainer.addView(seekBar); bandCol.addView(seekBarContainer)
            val tvFreq=TextView(this).apply{
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT)
                text=EqualizerProcessor.BAND_LABELS[i]; textSize=9.0f; setTextColor(Color.parseColor("#94A3B8")); gravity=Gravity.CENTER
            }
            bandCol.addView(tvFreq); llEqBandsContainer.addView(bandCol)
            bandSeekBars.add(seekBar); bandValueLabels.add(tvGain)
        }
    }

    private fun setupDrawerSettings() {
        scope.launch { switchAutoStartBoot.isChecked = dataStore.loadAutoStartBoot() }
        switchAutoStartBoot.setOnCheckedChangeListener { _, isChecked -> scope.launch { dataStore.saveAutoStartBoot(isChecked) } }
        val curVol = audioSessionManager.getSystemVolume()
        val maxVol = audioSessionManager.getMaxSystemVolume()
        seekBarSystemVolume.max = maxVol; seekBarSystemVolume.progress = curVol
        seekBarSystemVolume.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb:SeekBar?,p:Int,fu:Boolean){ if(fu) audioSessionManager.setSystemVolume(p) }
            override fun onStartTrackingTouch(sb:SeekBar?){}
            override fun onStopTrackingTouch(sb:SeekBar?){}
        })
        findViewById<View>(R.id.drawerExportM3U8).setOnClickListener {
            AlertDialog.Builder(this)
               .setTitle("Reiniciar DSP a Valores de Fábrica")
               .setMessage("¿Deseas restaurar todas las 32 bandas, Gain, Limiter y AutoGain?")
               .setPositiveButton("Restaurar"){_,_->
                    val d=AppProfile.createDefaultProfiles().first()
                    audioSessionManager.applyProfile(d); syncAllUiFromManager()
                    Toast.makeText(this,"DSP Restaurado",Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancelar",null).show()
        }
    }

    private fun syncAllUiFromManager() {
        isUpdatingUiProgrammatically=true
        switchMasterDsp.isChecked=audioSessionManager.isGlobalAudioEnabled
        seekBarGlobalGain.progress=((audioSessionManager.globalGainDb*10)+120).toInt().coerceIn(0,240)
        tvGlobalGainValue.text=String.format("%+.1f dB",audioSessionManager.globalGainDb)
        switchLimiter.isChecked=audioSessionManager.isLimiterEnabled
        seekBarLimiterThreshold.progress=((audioSessionManager.limiterThresholdDb*10)+120).toInt().coerceIn(0,120)
        tvLimiterThresholdValue.text=String.format("%.1f dB",audioSessionManager.limiterThresholdDb)
        seekBarLimiterThreshold.isEnabled=audioSessionManager.isLimiterEnabled
        switchAutoGain.isChecked=audioSessionManager.isAutoGainEnabled
        seekBarAutoGainTarget.progress=(audioSessionManager.autoGainTargetLufs+23.0f).toInt().coerceIn(0,14)
        tvAutoGainTargetValue.text=String.format("%.0f LUFS",audioSessionManager.autoGainTargetLufs)
        seekBarAutoGainTarget.isEnabled=audioSessionManager.isAutoGainEnabled
        switchBassBoost.isChecked=audioSessionManager.bassBoostDb>0.1f
        seekBarBassBoost.progress=(audioSessionManager.bassBoostDb*10).toInt().coerceIn(0,120)
        tvBassBoostValue.text=String.format("+%.1f dB",audioSessionManager.bassBoostDb)
        val fi=when{audioSessionManager.bassFreqHz<=65f->0;audioSessionManager.bassFreqHz<=95f->1;else->2}
        spinnerBassFreq.setSelection(fi)
        seekBarVirtualizer.progress=audioSessionManager.virtualizerStrength
        tvVirtualizerValue.text="${audioSessionManager.virtualizerStrength/10}%"
        syncBandSlidersOnly(); refreshProfilesSpinner()
        isUpdatingUiProgrammatically=false
    }

    private fun syncBandSlidersOnly() {
        for(i in 0 until minOf(32,bandSeekBars.size)){
            val g=audioSessionManager.bandGains[i]
            bandSeekBars[i].progress=((g*10)+120).toInt().coerceIn(0,240)
            bandValueLabels[i].text=String.format("%+.1f",g)
        }
    }

    private fun updateSessionStatusBanner(count:Int,pkgs:List<String>){
        if(!audioSessionManager.isGlobalAudioEnabled){
            tvDspActiveStatus.text="SB-Z Global DSP EN PAUSA"; tvDspActiveStatus.setTextColor(Color.parseColor("#94A3B8")); return
        }
        val d=if(pkgs.isNotEmpty())" (${pkgs.joinToString(", ")})" else ""
        tvDspActiveStatus.text="Procesando Mezcla Global (0)$d • Limiter ON"
        tvDspActiveStatus.setTextColor(Color.parseColor("#00E5FF"))
    }

    private fun updateVuMeters(){
        if(!audioSessionManager.isGlobalAudioEnabled){
            vuMeterLeftBar.progress=0; vuMeterRightBar.progress=0; tvVuPeakText.text="-inf dB"; return
        }
        vuMeterLeftBar.progress=40+Random.nextInt(25); vuMeterRightBar.progress=42+Random.nextInt(25)
        val peak=if(audioSessionManager.isLimiterEnabled) audioSessionManager.limiterThresholdDb else -1.2f+(Random.nextFloat()*0.8f)
        tvVuPeakText.text=String.format("%.1f dB",peak)
    }
}
