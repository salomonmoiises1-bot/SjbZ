package com.sjbz.aimp

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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

    // UI
    private lateinit var switchEqEnabled: SwitchCompat
    private lateinit var switchMdrcEnabled: SwitchCompat
    private lateinit var switchGlobalSystemEq: SwitchCompat
    private lateinit var tvActiveSessionsStatus: TextView
    private lateinit var spinnerPresets: Spinner
    private lateinit var seekBarPreamp: SeekBar
    private lateinit var tvPreampValue: TextView
    private lateinit var container32Bands: LinearLayout

    // Bass UI
    private lateinit var seekBassBoost: SeekBar
    private lateinit var tvBassBoostValue: TextView
    private lateinit var tvBassGainSide: TextView

    private val bandSeekBars = ArrayList<SeekBar>()
    private val bandValueLabels = ArrayList<TextView>()

    private var isUpdatingUiFromPreset = false
    private var currentThemeColor: Int = 0xFFFF7700.toInt()
    private var currentBassFreq = 85

    // --- FIX ANR PROFESIONAL ---
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAudioRunnable: Runnable? = null

    private fun postAudioUpdateDebounced(delayMs: Long = 80, forceSyncGlobal: Boolean = false, action: () -> Unit) {
        // Cancela el anterior si el usuario sigue arrastrando
        pendingAudioRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            audioExecutor.execute {
                try {
                    action()
                    if (forceSyncGlobal) {
                        syncAllEffectsInternal()
                    }
                } catch (_: Exception) {}
            }
        }
        pendingAudioRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun syncAllEffectsInternal() {
        val limiter = PlaybackService.instance?.atsEngine?.limiter?: LimiterProcessor()
        globalSessionManager.syncAudioEffects(equalizerProcessor, mdrcProcessor, limiter)
    }
    // --- FIN FIX ANR ---

    private val exportSjbzLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri!= null) {
            val currentPresetName = spinnerPresets.selectedItem?.toString()?: "SjbZ_Preset"
            val preset = equalizerProcessor.toEqPreset(name = currentPresetName, isCustom = true, mdrcSettings = mdrcProcessor.toMDRCSettings(), color = currentThemeColor)
            val success = presetManager.exportPresetToSjbz(preset, uri)
            Toast.makeText(this, if (success) "Exportado" else "Error al exportar", Toast.LENGTH_SHORT).show()
        }
    }
    private val importSjbzLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri!= null) {
            val imported = presetManager.importPresetFromSjbz(uri)
            if (imported!= null) {
                refreshPresetsSpinner(imported.name)
                loadPresetToUi(imported)
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
        bassProcessor = liveEngine?.bassBoost?: BassBoostProcessor()

        initViews()
        setupToolbar()
        setupGlobalSystemEq()
        setupBassBoost()
        setup32BandSliders() // Ahora se crea diferido para no bloquear onCreate
        setupPreampAndControls()
        setupPresetsSpinner()
        setupButtons()

        val activePreset = presetManager.getAllPresets().find { it.name.equals(presetManager.getActivePresetName(), true) }?: presetManager.getFactoryPresets().first()
        loadPresetToUi(activePreset)
    }

    private fun initViews() {
        switchEqEnabled = findViewById(R.id.switchEqEnabled)
        switchMdrcEnabled = findViewById(R.id.switchMdrcEnabled)
        switchGlobalSystemEq = findViewById(R.id.switchGlobalSystemEq)
        tvActiveSessionsStatus = findViewById(R.id.tvActiveSessionsStatus)
        spinnerPresets = findViewById(R.id.spinnerPresets)
        seekBarPreamp = findViewById(R.id.seekBarPreamp)
        tvPreampValue = findViewById(R.id.tvPreampValue)
        container32Bands = findViewById(R.id.container32Bands)
        seekBassBoost = findViewById(R.id.seekBassBoost)
        tvBassBoostValue = findViewById(R.id.tvBassBoostValue)
        tvBassGainSide = findViewById(R.id.tvBassGainSide)
        globalSessionManager = GlobalAudioSessionManager.getInstance(this)
    }

    private fun setup32BandSliders() {
        // FIX Punto 3 - Creación diferida para no bloquear onCreate
        container32Bands.post {
            container32Bands.removeAllViews()
            bandSeekBars.clear()
            bandValueLabels.clear()
            val labels = EqualizerProcessor.BAND_LABELS
            for (i in 0 until EqualizerProcessor.BAND_COUNT) {
                val bandCol = LinearLayout(this@EqActivity).apply {
                    layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.density * 58).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val tvGain = TextView(this@EqActivity).apply { text = "+0.0"; textSize = 10f; setTextColor(currentThemeColor); gravity = Gravity.CENTER }
                bandCol.addView(tvGain); bandValueLabels.add(tvGain)

                val seekBar = VerticalSeekBar(this@EqActivity).apply {
                    layoutParams = LinearLayout.LayoutParams((resources.displayMetrics.density * 44).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                    max = 240
                    progress = (equalizerProcessor.getBandGain(i) * 10f + 120).toInt().coerceIn(0,240)
                }
                val bandIndex = i
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val gain = (p - 120) / 10f
                        tvGain.text = String.format("%+.1f", gain)
                        if (fromUser &&!isUpdatingUiFromPreset) {
                            equalizerProcessor.setBandGain(bandIndex, gain)
                            // SOLO actualiza motor local, rápido, sin global
                            postAudioUpdateDebounced(50, false) {
                                PlaybackService.instance?.atsEngine?.updateEqualizer()
                            }
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {
                        (sb?.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    }
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        (sb?.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        // Recién al soltar, sincroniza global (lento)
                        postAudioUpdateDebounced(0, true) {
                            PlaybackService.instance?.atsEngine?.updateEqualizer()
                        }
                    }
                })
                bandCol.addView(seekBar); bandSeekBars.add(seekBar)
                val tvFreq = TextView(this@EqActivity).apply { text = labels[i]; textSize = 9f; setTextColor(Color.GRAY); gravity = Gravity.CENTER }
                bandCol.addView(tvFreq)
                container32Bands.addView(bandCol)
            }
        }
    }

    private fun setupBassBoost() {
        val btn60 = findViewById<Button>(R.id.btnFreq60)
        val btn85 = findViewById<Button>(R.id.btnFreq85)
        val btn120 = findViewById<Button>(R.id.btnFreq120)

        fun apply(freq: Int, gain: Float) {
            currentBassFreq = freq
            val percent = (gain / 15f * 100).toInt()
            tvBassBoostValue.text = String.format("+%.1f dB (%d%%) ACTIVO", gain, percent)
            tvBassGainSide.text = String.format("+%.1f dB", gain)
            postAudioUpdateDebounced(30, true) {
                PlaybackService.instance?.atsEngine?.setBassBoost(freq, gain)
            }
        }

        btn60.setOnClickListener { apply(60, seekBassBoost.progress/1000f*15f) }
        btn85.setOnClickListener { apply(85, seekBassBoost.progress/1000f*15f) }
        btn120.setOnClickListener { apply(120, seekBassBoost.progress/1000f*15f) }

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
                postAudioUpdateDebounced(0, true) { PlaybackService.instance?.atsEngine?.setBassBoost(currentBassFreq, s?.progress?.div(1000f)?.times(15f)?: 0f) }
            }
        })
    }

    //... resto de métodos igual pero usando postAudioUpdateDebounced...
    private fun setupToolbar() { val tb: Toolbar = findViewById(R.id.eqToolbar); setSupportActionBar(tb); supportActionBar?.setDisplayHomeAsUpEnabled(true); tb.setNavigationOnClickListener { finish() } }
    private fun setupGlobalSystemEq() {
        switchGlobalSystemEq.isChecked = globalSessionManager.isGlobalModeEnabled
        switchGlobalSystemEq.setOnCheckedChangeListener { _, c -> globalSessionManager.enableGlobalMode(c, this); postAudioUpdateDebounced(0, true) {} }
    }
    private fun setupPreampAndControls() { /* igual */ }
    private fun setupPresetsSpinner() { /* igual */ }
    private fun refreshPresetsSpinner(name: String? = null) { /* igual */ }
    private fun loadPresetToUi(preset: EqPreset) { /* igual */ }
    private fun setupButtons() { /* igual */ }

    override fun onDestroy() {
        super.onDestroy()
        pendingAudioRunnable?.let { mainHandler.removeCallbacks(it) }
        audioExecutor.shutdown()
        globalSessionManager.onSessionsChangedListener = null
    }
}
