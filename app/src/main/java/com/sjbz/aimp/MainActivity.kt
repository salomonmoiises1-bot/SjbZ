package com.sjbz.aimp

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
import com.sjbz.aimp.audio.SjbzAudioEngine
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
        override fun run() { updateVuMeters(); uiHandler.postDelayed(this, 80) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        SjbzAudioEngine.ensureInitialized()
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

        val prevProfile = audioSessionManager.onProfileChangedListener
        audioSessionManager.onProfileChangedListener = { p ->
            prevProfile?.invoke(p)
            runOnUiThread { if (!isFinishing &&!isDestroyed) syncAllUiFromManager() }
        }
        val prevSess = audioSessionManager.onActiveSessionsChangedListener
        audioSessionManager.onActiveSessionsChangedListener = { c, pkgs ->
            prevSess?.invoke(c, pkgs)
            runOnUiThread { if (!isFinishing &&!isDestroyed) updateSessionStatusBanner(c, pkgs) }
        }

        if (audioSessionManager.isGlobalAudioEnabled) GlobalAudioService.start(this)

        SjbzAudioEngine.processor.fftListener = { samples ->
            runOnUiThread { visualizerView.onAudioData(samples) }
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
    }

    private fun setupDrawerAndToolbar() {
        btnMenuDrawer.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
            else drawerLayout.openDrawer(GravityCompat.START)
        }
        btnOpenEqualizer.setOnClickListener {
            try { startActivity(Intent(this, EqActivity::class.java)) }
            catch (_: Exception) { Toast.makeText(this, "EQ 32 bandas activo", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setupMasterSwitch() {
        switchMasterDsp.isChecked = audioSessionManager.isGlobalAudioEnabled
        updateMasterBanner(switchMasterDsp.isChecked)
        switchMasterDsp.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUiProgrammatically) return@setOnCheckedChangeListener
            audioSessionManager.setGlobalAudioEnabled(isChecked)
            SjbzAudioEngine.setMasterEnabled(isChecked)
            if (isChecked) GlobalAudioService.start(this) else GlobalAudioService.stop(this)
            updateMasterBanner(isChecked)
        }
    }

    private fun updateMasterBanner(on: Boolean) {
        viewDspIndicator.setBackgroundColor(Color.parseColor(if (on) "#00E5FF" else "#64748B"))
        tvDspActiveStatus.text = if (on) "SB-Z Global DSP ACTIVO • Session 0" else "SB-Z Global DSP EN PAUSA"
        tvDspActiveStatus.setTextColor(Color.parseColor(if (on) "#00E5FF" else "#94A3B8"))
    }

    private fun setupAppProfiles() {
        refreshProfilesSpinner()
        spinnerAppProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isUpdatingUiProgrammatically) return
                audioSessionManager.allProfiles.getOrNull(pos)?.let {
                    if (it.id!= audioSessionManager.currentProfile.id) {
                        audioSessionManager.applyProfile(it)
                        syncAllUiFromManager()
                    }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        btnSaveAppProfile.setOnClickListener { showSaveProfileDialog() }
    }

    private fun refreshProfilesSpinner() {
        spinnerAppProfiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            audioSessionManager.allProfiles.map { "${it.appName} (${it.presetName})" }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val idx = audioSessionManager.allProfiles.indexOfFirst { it.id == audioSessionManager.currentProfile.id }
        if (idx >= 0) spinnerAppProfiles.setSelection(idx)
    }

    private fun showSaveProfileDialog() {
        val input = EditText(this).apply { hint = "Nombre del perfil" }
        AlertDialog.Builder(this).setTitle("Guardar perfil").setView(input)
           .setPositiveButton("Guardar") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    audioSessionManager.saveCurrentAsProfile(n)
                    refreshProfilesSpinner()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun setupMasterDynamicsControls() {
        seekBarGlobalGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val db = (p - 120) / 10f
                tvGlobalGainValue.text = String.format("%+.1f dB", db)
                if (fromUser &&!isUpdatingUiProgrammatically) {
                    audioSessionManager.setGlobalGain(db)
                    SjbzAudioEngine.processor.setPreamp(db)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        switchLimiter.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUiProgrammatically)
                audioSessionManager.setLimiter(isChecked, (seekBarLimiterThreshold.progress - 120) / 10f)
            seekBarLimiterThreshold.isEnabled = isChecked
        }
        seekBarLimiterThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val db = (p - 120) / 10f
                tvLimiterThresholdValue.text = String.format("%.1f dB", db)
                if (fromUser &&!isUpdatingUiProgrammatically)
                    audioSessionManager.setLimiter(switchLimiter.isChecked, db)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        switchAutoGain.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUiProgrammatically)
                audioSessionManager.setAutoGain(isChecked, -23f + seekBarAutoGainTarget.progress)
            seekBarAutoGainTarget.isEnabled = isChecked
        }
        seekBarAutoGainTarget.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvAutoGainTargetValue.text = "${-23 + p} LUFS"
                if (fromUser &&!isUpdatingUiProgrammatically)
                    audioSessionManager.setAutoGain(switchAutoGain.isChecked, -23f + p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupBassAndVirtualizer() {
        spinnerBassFreq.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("60 Hz (Sub)", "85 Hz (Punch)", "120 Hz (Mid)")).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBassFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isUpdatingUiProgrammatically) return
                audioSessionManager.setBassBoost(
                    if (switchBassBoost.isChecked) seekBarBassBoost.progress / 10f else 0f,
                    getSelectedBassFreq()
                )
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUiProgrammatically)
                audioSessionManager.setBassBoost(if (isChecked) seekBarBassBoost.progress / 10f else 0f, getSelectedBassFreq())
            seekBarBassBoost.isEnabled = isChecked
            spinnerBassFreq.isEnabled = isChecked
        }
        seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvBassBoostValue.text = String.format("+%.1f dB", p / 10f)
                if (fromUser &&!isUpdatingUiProgrammatically)
                    audioSessionManager.setBassBoost(if (switchBassBoost.isChecked) p / 10f else 0f, getSelectedBassFreq())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekBarVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvVirtualizerValue.text = "${p / 10}%"
                if (fromUser &&!isUpdatingUiProgrammatically) audioSessionManager.setVirtualizer(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun getSelectedBassFreq() = when (spinnerBassFreq.selectedItemPosition) {
        0 -> 60f; 1 -> 85f; else -> 120f
    }

    private fun setupPresetButtons() {
        btnPresetFlat.setOnClickListener { applyPresetValues(FloatArray(32), "Flat") }
        btnPresetBass.setOnClickListener {
            applyPresetValues(FloatArray(32) { i -> when (i) {
                0 -> 6f; 1 -> 5.5f; 2 -> 5f; 3 -> 4f; 4 -> 3f; 5 -> 2f; 6 -> 1f; else -> 0f
            } }, "Bass")
        }
        btnPresetRock.setOnClickListener {
            applyPresetValues(FloatArray(32) { i -> when (i) {
                0 -> 4.5f; 1 -> 4f; 2 -> 3.5f; 15 -> -2f; 28 -> 4f; 30 -> 5f; else -> 0f
            } }, "Rock")
        }
        btnPresetVocal.setOnClickListener {
            applyPresetValues(FloatArray(32) { i -> if (i in 12..22) 3.5f else 0f }, "Vocal")
        }
        btnResetEq.setOnClickListener { applyPresetValues(FloatArray(32), "Reset") }
        btnToggleQMode.setOnClickListener {
            currentQMode = when (currentQMode) { 1.414f -> 2.828f; 2.828f -> 0.707f; else -> 1.414f }
            btnToggleQMode.text = "Q: ${String.format("%.2f", currentQMode)}"
            for (i in 0 until 32) audioSessionManager.setBandQ(i, currentQMode)
        }
    }

    private fun applyPresetValues(gains: FloatArray, name: String) {
        SjbzAudioEngine.setAllBandGains(gains)
        for (i in gains.indices.take(32)) audioSessionManager.setBandGain(i, gains[i])
        syncBandSlidersOnly()
        Toast.makeText(this, "Preset: $name", Toast.LENGTH_SHORT).show()
    }

    private fun build32BandSliders() {
        llEqBandsContainer.removeAllViews(); bandSeekBars.clear(); bandValueLabels.clear()
        val density = resources.displayMetrics.density
        val cyan = Color.parseColor("#00E5FF")
        for (i in 0 until 32) {
            val col = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams((density * 52).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            }
            val tvGain = TextView(this).apply { text = "0.0"; textSize = 9.5f; setTextColor(cyan); gravity = Gravity.CENTER }
            col.addView(tvGain)
            val container = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                gravity = Gravity.CENTER
            }
            val sb = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams((density * 160).toInt(), (density * 36).toInt())
                rotation = 270f; max = 240; progress = 120
            }
            val idx = i
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val g = (p - 120) / 10f
                    tvGain.text = String.format("%+.1f", g)
                    if (fromUser &&!isUpdatingUiProgrammatically) {
                        audioSessionManager.setBandGain(idx, g)
                        SjbzAudioEngine.processor.setBandGain(idx, g)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            container.addView(sb); col.addView(container)
            val tvFreq = TextView(this).apply {
                text = EqualizerProcessor.BAND_LABELS[i]; textSize = 9f
                setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER
            }
            col.addView(tvFreq); llEqBandsContainer.addView(col)
            bandSeekBars.add(sb); bandValueLabels.add(tvGain)
        }
    }

    private fun setupDrawerSettings() {
        scope.launch { switchAutoStartBoot.isChecked = dataStore.loadAutoStartBoot() }
        switchAutoStartBoot.setOnCheckedChangeListener { _, c -> scope.launch { dataStore.saveAutoStartBoot(c) } }
        seekBarSystemVolume.max = audioSessionManager.getMaxSystemVolume()
        seekBarSystemVolume.progress = audioSessionManager.getSystemVolume()
        seekBarSystemVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) audioSessionManager.setSystemVolume(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun syncAllUiFromManager() {
        isUpdatingUiProgrammatically = true
        switchMasterDsp.isChecked = audioSessionManager.isGlobalAudioEnabled
        updateMasterBanner(switchMasterDsp.isChecked)
        seekBarGlobalGain.progress = ((audioSessionManager.globalGainDb * 10) + 120).toInt().coerceIn(0, 240)
        tvGlobalGainValue.text = String.format("%+.1f dB", audioSessionManager.globalGainDb)
        switchLimiter.isChecked = audioSessionManager.isLimiterEnabled
        seekBarLimiterThreshold.progress = ((audioSessionManager.limiterThresholdDb * 10) + 120).toInt().coerceIn(0, 120)
        tvLimiterThresholdValue.text = String.format("%.1f dB", audioSessionManager.limiterThresholdDb)
        switchAutoGain.isChecked = audioSessionManager.isAutoGainEnabled
        seekBarAutoGainTarget.progress = (audioSessionManager.autoGainTargetLufs + 23f).toInt().coerceIn(0, 14)
        tvAutoGainTargetValue.text = "${audioSessionManager.autoGainTargetLufs.toInt()} LUFS"
        switchBassBoost.isChecked = audioSessionManager.bassBoostDb > 0.1f
        seekBarBassBoost.progress = (audioSessionManager.bassBoostDb * 10).toInt().coerceIn(0, 120)
        tvBassBoostValue.text = String.format("+%.1f dB", audioSessionManager.bassBoostDb)
        seekBarVirtualizer.progress = audioSessionManager.virtualizerStrength
        tvVirtualizerValue.text = "${audioSessionManager.virtualizerStrength / 10}%"
        syncBandSlidersOnly()
        refreshProfilesSpinner()
        isUpdatingUiProgrammatically = false
    }

    private fun syncBandSlidersOnly() {
        for (i in 0 until minOf(32, bandSeekBars.size)) {
            val g = audioSessionManager.bandGains[i]
            bandSeekBars[i].progress = ((g * 10) + 120).toInt().coerceIn(0, 240)
            bandValueLabels[i].text = String.format("%+.1f", g)
        }
    }

    private fun updateSessionStatusBanner(count: Int, pkgs: List<String>) {
        if (!audioSessionManager.isGlobalAudioEnabled) {
            tvDspActiveStatus.text = "SB-Z Global DSP EN PAUSA"
            return
        }
        tvDspActiveStatus.text = "Procesando mezcla global${if (pkgs.isNotEmpty()) " (${pkgs.joinToString(",")})" else ""}"
    }

    private fun updateVuMeters() {
        if (!audioSessionManager.isGlobalAudioEnabled) {
            vuMeterLeftBar.progress = 0; vuMeterRightBar.progress = 0
            tvVuPeakText.text = "-inf dB"; return
        }
        vuMeterLeftBar.progress = 40 + Random.nextInt(25)
        vuMeterRightBar.progress = 42 + Random.nextInt(25)
        tvVuPeakText.text = String.format("%.1f dB", audioSessionManager.limiterThresholdDb)
    }
}
