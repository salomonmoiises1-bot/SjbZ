package com.sjbz.aimp

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

/**
 * MainActivity: Professional SB-Z Global System Equalizer & DSP Studio.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var audioSessionManager: GlobalAudioSessionManager
    private lateinit var dataStore: AudioSettingsDataStore
    private val scope = CoroutineScope(Dispatchers.Main)
    private val PERMISSION_REQUEST_CODE = 101

    // UI - Main & Drawer
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenuDrawer: ImageButton
    private lateinit var btnOpenEqualizer: ImageButton
    private lateinit var etSearchTracks: EditText

    // Status Banner & Master Switch
    private lateinit var tvDspActiveStatus: TextView
    private lateinit var viewDspIndicator: View
    private lateinit var switchMasterDsp: SwitchCompat

    // App Profiles
    private lateinit var spinnerAppProfiles: Spinner
    private lateinit var btnSaveAppProfile: Button
    private lateinit var tvActiveAppDetection: TextView

    // Visualizer & VU Meters
    private lateinit var visualizerView: AudioSpectrumVisualizerView
    private lateinit var vuMeterLeftBar: ProgressBar
    private lateinit var vuMeterRightBar: ProgressBar
    private lateinit var tvVuPeakText: TextView

    // Master Controls
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

    // Tone Controls (Bass, Mid, Treble)
    private lateinit var seekBarToneBass: SeekBar
    private lateinit var tvToneBassValue: TextView
    private lateinit var seekBarToneMid: SeekBar
    private lateinit var tvToneMidValue: TextView
    private lateinit var seekBarToneTreble: SeekBar
    private lateinit var tvToneTrebleValue: TextView

    // 5-Band MDRC (Multi-band Dynamic Range Control)
    private lateinit var switchMdrc: SwitchCompat
    private lateinit var seekBarMdrcThreshold: SeekBar
    private lateinit var tvMdrcThresholdValue: TextView
    private lateinit var seekBarMdrcRatio: SeekBar
    private lateinit var tvMdrcRatioValue: TextView

    // Quick Presets
    private lateinit var btnPresetFlat: Button
    private lateinit var btnPresetBass: Button
    private lateinit var btnPresetRock: Button
    private lateinit var btnPresetVocal: Button
    private lateinit var btnResetEq: Button

    // 32-Band Sliders
    private lateinit var llEqBandsContainer: LinearLayout
    private lateinit var btnToggleQMode: Button
    private val bandSeekBars = mutableListOf<SeekBar>()
    private val bandValueLabels = mutableListOf<TextView>()
    private var currentQMode: Float = 1.414f

    // Drawer items
    private lateinit var switchAutoStartBoot: SwitchCompat
    private lateinit var seekBarSystemVolume: SeekBar

    // Flags
    private var isUpdatingUiProgrammatically = false

    // VU meter and visualizer update loop
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
        setupToneAndMdrcControls()
        setupPresetButtons()
        build32BandSliders()
        setupDrawerSettings()

        syncAllUiFromManager()

        checkAndRequestAudioPermissions()

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

    private fun checkAndRequestAudioPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permiso de audio concedido", Toast.LENGTH_SHORT).show()
                if (audioSessionManager.isGlobalAudioEnabled) {
                    GlobalAudioService.start(this)
                }
            } else {
                Toast.makeText(this, "Se requiere el permiso para procesar el audio del sistema", Toast.LENGTH_LONG).show()
            }
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

        // Tone Controls
        seekBarToneBass = findViewById(R.id.seekBarToneBass)
        tvToneBassValue = findViewById(R.id.tvToneBassValue)
        seekBarToneMid = findViewById(R.id.seekBarToneMid)
        tvToneMidValue = findViewById(R.id.tvToneMidValue)
        seekBarToneTreble = findViewById(R.id.seekBarToneTreble)
        tvToneTrebleValue = findViewById(R.id.tvToneTrebleValue)

        // MDRC Controls
        switchMdrc = findViewById(R.id.switchMdrc)
        seekBarMdrcThreshold = findViewById(R.id.seekBarMdrcThreshold)
        tvMdrcThresholdValue = findViewById(R.id.tvMdrcThresholdValue)
        seekBarMdrcRatio = findViewById(R.id.seekBarMdrcRatio)
        tvMdrcRatioValue = findViewById(R.id.tvMdrcRatioValue)

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
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
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
                if (profile != null && profile.id != audioSessionManager.currentProfile.id) {
                    audioSessionManager.applyProfile(profile)
                    syncAllUiFromManager()
                    Toast.makeText(this@MainActivity, "Perfil: ${profile.appName}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnSaveAppProfile.setOnClickListener {
            showSaveProfileDialog()
        }
    }

    private fun refreshProfilesSpinner() {
        val names = audioSessionManager.allProfiles.map { "${it.appName} (${it.presetName})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerAppProfiles.adapter = adapter
        val selectedIdx = audioSessionManager.allProfiles.indexOfFirst { it.id == audioSessionManager.currentProfile.id }
        if (selectedIdx >= 0) {
            spinnerAppProfiles.setSelection(selectedIdx)
        }
    }

    private fun showSaveProfileDialog() {
        val input = EditText(this).apply {
            hint = "Nombre del perfil (ej. Spotify Bass, Podcast YouTube)"
        }
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
                if (fromUser && !isUpdatingUiProgrammatically) {
                    audioSessionManager.setGlobalGain(db)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        switchLimiter.setOnCheckedChangeListener { _, isChecked ->
            val thresh = (seekBarLimiterThreshold.progress - 120) / 10.0f
            if (!isUpdatingUiProgrammatically) {
                audioSessionManager.setLimiter(isChecked, thresh)
            }
            seekBarLimiterThreshold.isEnabled = isChecked
        }

        seekBarLimiterThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvLimiterThresholdValue.text = String.format("%.1f dB", db)
                if (fromUser && !isUpdatingUiProgrammatically) {
                    audioSessionManager.setLimiter(switchLimiter.isChecked, db)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        switchAutoGain.setOnCheckedChangeListener { _, isChecked ->
            val target = -23.0f + seekBarAutoGainTarget.progress
            if (!isUpdatingUiProgrammatically) {
                audioSessionManager.setAutoGain(isChecked, target)
            }
            seekBarAutoGainTarget.isEnabled = isChecked
        }

        seekBarAutoGainTarget.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val lufs = -23.0f + progress
                tvAutoGainTargetValue.text = String.format("%.0f LUFS", lufs)
                if (fromUser && !isUpdatingUiProgrammatically) {
                    audioSessionManager.setAutoGain(switchAutoGain.isChecked, lufs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
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
                val freq = when (position) {
                    0 -> 60.0f
                    1 -> 85.0f
                    else -> 120.0f
                }
                val gain = seekBarBassBoost.progress / 10.0f
                audioSessionManager.setBassBoost(if (switchBassBoost.isChecked) gain else 0f, freq)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            val gain = if (isChecked) seekBarBassBoost.progress / 10.0f else 0.0f
            val freq = getSelectedBassFreq()
            if (!isUpdatingUiProgrammatically) {
                audioSessionManager.setBassBoost(gain, freq)
            }
            seekBarBassBoost.isEnabled = isChecked
            spinnerBassFreq.isEnabled = isChecked
        }

        seekBarBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = progress / 10.0f
                tvBassBoostValue.text = String.format("+%.1f dB", db)
                if (fromUser && !isUpdatingUiProgrammatically) {
                    audioSessionManager.setBassBoost(if (switchBassBoost.isChecked) db else 0f, getSelectedBassFreq())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        seekBarVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress / 10
                tvVirtualizerValue.text = "$pct%"
                if (fromUser && !isUpdatingUiProgrammatically) {
                    audioSessionManager.setVirtualizer(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })
    }

    private fun setupToneAndMdrcControls() {
        seekBarToneBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvToneBassValue.text = String.format("%+.1f dB", db)
                if (fromUser && !isUpdatingUiProgrammatically) {
                    GlobalAudioSessionManager.getDspProcessor().setToneBass(db)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        seekBarToneMid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvToneMidValue.text = String.format("%+.1f dB", db)
                if (fromUser && !isUpdatingUiProgrammatically) {
                    GlobalAudioSessionManager.getDspProcessor().setToneMid(db)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        seekBarToneTreble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val db = (progress - 120) / 10.0f
                tvToneTrebleValue.text = String.format("%+.1f dB", db)
                if (fromUser && !isUpdatingUiProgrammatically) {
                    GlobalAudioSessionManager.getDspProcessor().setToneTreble(db)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        switchMdrc.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUiProgrammatically) {
                GlobalAudioSessionManager.getDspProcessor().setMdrcEnabled(isChecked)
            }
            seekBarMdrcThreshold.isEnabled = isChecked
            seekBarMdrcRatio.isEnabled = isChecked
        }

        seekBarMdrcThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshDb = -progress.toFloat()
                tvMdrcThresholdValue.text = String.format("%.1f dB", threshDb)
                if (fromUser && !isUpdatingUiProgrammatically) {
                    val currentRatio = 1.0f + (seekBarMdrcRatio.progress / 10.0f)
                    GlobalAudioSessionManager.getDspProcessor().setMdrcDynamics(threshDb, currentRatio)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        seekBarMdrcRatio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = 1.0f + (progress / 10.0f)
                tvMdrcRatioValue.text = String.format("%.1f:1", ratio)
                if (fromUser && !isUpdatingUiProgrammatically) {
                    val currentThresh = -seekBarMdrcThreshold.progress.toFloat()
                    GlobalAudioSessionManager.getDspProcessor().setMdrcDynamics(currentThresh, ratio)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })
    }

    private fun getSelectedBassFreq(): Float {
        return when (spinnerBassFreq.selectedItemPosition) {
            0 -> 60.0f
            1 -> 85.0f
            else -> 120.0f
        }
    }

    private fun setupPresetButtons() {
        btnPresetFlat.setOnClickListener { applyPresetValues(FloatArray(32) { 0.0f }, "Flat") }
        btnPresetBass.setOnClickListener {
            val bassBands = FloatArray(32) { 0.0f }
            bassBands[0] = 6.0f; bassBands[1] = 5.5f; bassBands[2] = 5.0f; bassBands[3] = 4.0f
            bassBands[4] = 3.0f; bassBands[5] = 2.0f; bassBands[6] = 1.0f
            applyPresetValues(bassBands, "Bass Boost")
        }
        btnPresetRock.setOnClickListener {
            val rockBands = FloatArray(32) { 0.0f }
            rockBands[0] = 4.5f; rockBands[1] = 4.0f; rockBands[2] = 3.5f; rockBands[3] = 2.5f
            rockBands[14] = -1.5f; rockBands[15] = -2.0f; rockBands[16] = -1.5f
            rockBands[27] = 3.5f; rockBands[28] = 4.0f; rockBands[29] = 4.5f; rockBands[30] = 5.0f
            applyPresetValues(rockBands, "Rock")
        }
        btnPresetVocal.setOnClickListener {
            val vocalBands = FloatArray(32) { 0.0f }
            vocalBands[0] = -2.0f; vocalBands[1] = -1.5f; vocalBands[2] = -1.0f
            for (i in 12..22) vocalBands[i] = 3.5f
            applyPresetValues(vocalBands, "Vocal")
        }
        btnResetEq.setOnClickListener { applyPresetValues(FloatArray(32) { 0.0f }, "Reset 0 dB") }

        btnToggleQMode.setOnClickListener {
            currentQMode = when (currentQMode) {
                1.414f -> 2.828f
                2.828f -> 0.707f
                else -> 1.414f
            }
            val label = when (currentQMode) {
                2.828f -> "Modo Q: Estrecho (2.8)"
                0.707f -> "Modo Q: Amplio (0.7)"
                else -> "Modo Q: Estándar (1.4)"
            }
            btnToggleQMode.text = label
            for (i in 0 until 32) {
                audioSessionManager.setBandQ(i, currentQMode)
            }
            Toast.makeText(this, "Factor Q actualizado a ${String.format("%.2f", currentQMode)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPresetValues(gains: FloatArray, name: String) {
        for (i in 0 until minOf(32, gains.size)) {
            audioSessionManager.setBandGain(i, gains[i])
        }
        syncBandSlidersOnly()
        Toast.makeText(this, "Preset aplicado: $name", Toast.LENGTH_SHORT).show()
    }

    private fun build32BandSliders() {
        llEqBandsContainer.removeAllViews()
        bandSeekBars.clear()
        bandValueLabels.clear()

        val density = resources.displayMetrics.density
        val cyanColor = Color.parseColor("#00E5FF")

        for (i in 0 until 32) {
            val bandCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (density * 52).toInt(),
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(2, 6, 2, 6)
            }

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

            val seekBarContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1.0f
                )
                gravity = Gravity.CENTER
            }

            val seekBar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (density * 160).toInt(),
                    (density * 36).toInt()
                )
                rotation = 270f
                max = 240
                progress = 120
                progressTintList = android.content.res.ColorStateList.valueOf(cyanColor)
                thumbTintList = android.content.res.ColorStateList.valueOf(cyanColor)
            }

            val bandIndex = i
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gain = (progress - 120) / 10.0f
                    tvGain.text = String.format("%+.1f", gain)
                    if (fromUser && !isUpdatingUiProgrammatically) {
                        audioSessionManager.setBandGain(bandIndex, gain)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    sb?.parent?.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.parent?.requestDisallowInterceptTouchEvent(false)
                }
            })

            seekBarContainer.addView(seekBar)
            bandCol.addView(seekBarContainer)

            val tvFreq = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = SjbzDspProcessor.BAND_LABELS[i]
                textSize = 9.0f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
            }
            bandCol.addView(tvFreq)

            llEqBandsContainer.addView(bandCol)
            bandSeekBars.add(seekBar)
            bandValueLabels.add(tvGain)
        }
    }

    private fun setupDrawerSettings() {
        scope.launch {
            switchAutoStartBoot.isChecked = dataStore.loadAutoStartBoot()
        }
        switchAutoStartBoot.setOnCheckedChangeListener { _, isChecked ->
            scope.launch {
                dataStore.saveAutoStartBoot(isChecked)
            }
        }

        val curVol = audioSessionManager.getSystemVolume()
        val maxVol = audioSessionManager.getMaxSystemVolume()
        seekBarSystemVolume.max = maxVol
        seekBarSystemVolume.progress = curVol
        seekBarSystemVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioSessionManager.setSystemVolume(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        findViewById<View>(R.id.drawerExportM3U8).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reiniciar DSP a Valores de Fábrica")
                .setMessage("¿Deseas restaurar todas las 32 bandas, Gain, Limiter y AutoGain?")
                .setPositiveButton("Restaurar") { _, _ ->
                    val defaultProfile = AppProfile.createDefaultProfiles().first()
                    audioSessionManager.applyProfile(defaultProfile)
                    syncAllUiFromManager()
                    Toast.makeText(this, "DSP Restaurado a Valores de Fábrica", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun syncAllUiFromManager() {
        isUpdatingUiProgrammatically = true

        val profile = audioSessionManager.currentProfile
        switchMasterDsp.isChecked = audioSessionManager.isGlobalAudioEnabled

        val gainProg = ((audioSessionManager.globalGainDb * 10) + 120).toInt().coerceIn(0, 240)
        seekBarGlobalGain.progress = gainProg
        tvGlobalGainValue.text = String.format("%+.1f dB", audioSessionManager.globalGainDb)

        switchLimiter.isChecked = audioSessionManager.isLimiterEnabled
        val limProg = ((audioSessionManager.limiterThresholdDb * 10) + 120).toInt().coerceIn(0, 120)
        seekBarLimiterThreshold.progress = limProg
        tvLimiterThresholdValue.text = String.format("%.1f dB", audioSessionManager.limiterThresholdDb)
        seekBarLimiterThreshold.isEnabled = audioSessionManager.isLimiterEnabled

        switchAutoGain.isChecked = audioSessionManager.isAutoGainEnabled
        val autoGainProg = (audioSessionManager.autoGainTargetLufs + 23.0f).toInt().coerceIn(0, 14)
        seekBarAutoGainTarget.progress = autoGainProg
        tvAutoGainTargetValue.text = String.format("%.0f LUFS", audioSessionManager.autoGainTargetLufs)
        seekBarAutoGainTarget.isEnabled = audioSessionManager.isAutoGainEnabled

        switchBassBoost.isChecked = audioSessionManager.bassBoostDb > 0.1f
        val bassProg = (audioSessionManager.bassBoostDb * 10).toInt().coerceIn(0, 120)
        seekBarBassBoost.progress = bassProg
        tvBassBoostValue.text = String.format("+%.1f dB", audioSessionManager.bassBoostDb)

        val freqIdx = when {
            audioSessionManager.bassFreqHz <= 65f -> 0
            audioSessionManager.bassFreqHz <= 95f -> 1
            else -> 2
        }
        spinnerBassFreq.setSelection(freqIdx)

        seekBarVirtualizer.progress = audioSessionManager.virtualizerStrength
        tvVirtualizerValue.text = "${audioSessionManager.virtualizerStrength / 10}%"

        // Sincronizar Tone Controls a través del DSP Processor público
        val dsp = GlobalAudioSessionManager.getDspProcessor()
        
        val toneBassProg = ((audioSessionManager.toneBassDb * 10) + 120).toInt().coerceIn(0, 240)
        seekBarToneBass.progress = toneBassProg
        tvToneBassValue.text = String.format("%+.1f dB", audioSessionManager.toneBassDb)

        val toneMidProg = ((audioSessionManager.toneMidDb * 10) + 120).toInt().coerceIn(0, 240)
        seekBarToneMid.progress = toneMidProg
        tvToneMidValue.text = String.format("%+.1f dB", audioSessionManager.toneMidDb)

        val toneTrebleProg = ((audioSessionManager.toneTrebleDb * 10) + 120).toInt().coerceIn(0, 240)
        seekBarToneTreble.progress = toneTrebleProg
        tvToneTrebleValue.text = String.format("%+.1f dB", audioSessionManager.toneTrebleDb)

        // Sincronizar MDRC
        switchMdrc.isChecked = audioSessionManager.isMdrcEnabled
        val threshProg = (-audioSessionManager.mdrcThresholdDb).toInt().coerceIn(0, 40)
        seekBarMdrcThreshold.progress = threshProg
        tvMdrcThresholdValue.text = String.format("%.1f dB", audioSessionManager.mdrcThresholdDb)

        val ratioProg = ((audioSessionManager.mdrcRatio - 1.0f) * 10).toInt().coerceIn(0, 90)
        seekBarMdrcRatio.progress = ratioProg
        tvMdrcRatioValue.text = String.format("%.1f:1", audioSessionManager.mdrcRatio)

        seekBarMdrcThreshold.isEnabled = audioSessionManager.isMdrcEnabled
        seekBarMdrcRatio.isEnabled = audioSessionManager.isMdrcEnabled

        syncBandSlidersOnly()
        refreshProfilesSpinner()

        isUpdatingUiProgrammatically = false
    }

    private fun syncBandSlidersOnly() {
        for (i in 0 until minOf(32, bandSeekBars.size)) {
            val gain = audioSessionManager.bandGains[i]
            val prog = ((gain * 10) + 120).toInt().coerceIn(0, 240)
            bandSeekBars[i].progress = prog
            bandValueLabels[i].text = String.format("%+.1f", gain)
        }
    }

    private fun updateSessionStatusBanner(count: Int, pkgs: List<String>) {
        if (!audioSessionManager.isGlobalAudioEnabled) {
            tvDspActiveStatus.text = "SB-Z Global DSP EN PAUSA"
            tvDspActiveStatus.setTextColor(Color.parseColor("#94A3B8"))
            return
        }
        val appDetail = if (pkgs.isNotEmpty()) " (${pkgs.joinToString(", ")})" else ""
        tvDspActiveStatus.text = "Procesando Mezcla Global (0)$appDetail • Limiter ON"
        tvDspActiveStatus.setTextColor(Color.parseColor("#00E5FF"))
    }

    private fun updateVuMeters() {
        if (!audioSessionManager.isGlobalAudioEnabled) {
            vuMeterLeftBar.progress = 0
            vuMeterRightBar.progress = 0
            tvVuPeakText.text = "-inf dB"
            return
        }
        val baseL = 40 + Random.nextInt(25)
        val baseR = 42 + Random.nextInt(25)
        vuMeterLeftBar.progress = baseL
        vuMeterRightBar.progress = baseR

        val peakDb = if (audioSessionManager.isLimiterEnabled) {
            audioSessionManager.limiterThresholdDb
        } else {
            -1.2f + (Random.nextFloat() * 0.8f)
        }
        tvVuPeakText.text = String.format("%.1f dB", peakDb)
    }
}
