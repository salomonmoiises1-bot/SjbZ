package com.sjbz.aimp

import com.sjbz.aimp.ui.VerticalSeekBar
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
import com.sjbz.aimp.audio.BassBoostProcessor
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

    private lateinit var sbMdrcGainSub: SeekBar; private lateinit var tvMdrcGainSub: TextView
    private lateinit var sbMdrcGainLow: SeekBar; private lateinit var tvMdrcGainLow: TextView
    private lateinit var sbMdrcGainMid: SeekBar; private lateinit var tvMdrcGainMid: TextView
    private lateinit var sbMdrcGainHigh: SeekBar; private lateinit var tvMdrcGainHigh: TextView
    private lateinit var sbMdrcGainAir: SeekBar; private lateinit var tvMdrcGainAir: TextView
    private lateinit var btnSavePreset: Button; private lateinit var btnDeletePreset: Button; private lateinit var btnExportPreset: Button

    // BASS BOOST PRO
    private lateinit var seekBassBoost: SeekBar
    private lateinit var tvBassBoostValue: TextView
    private lateinit var tvBassGainValue: TextView
    private lateinit var btnBassOff: Button; private lateinit var btnBass3: Button; private lateinit var btnBass6: Button;
    private lateinit var btnFreq60: Button; private lateinit var btnFreq85: Button; private lateinit var btnFreq120: Button

    private val bandSeekBars = ArrayList<SeekBar>(); private val bandValueLabels = ArrayList<TextView>()
    private val mdrcSeekBars = ArrayList<SeekBar>(); private val mdrcValueLabels = ArrayList<TextView>()
    private var isUpdatingUiFromPreset = false
    private var currentThemeColor: Int = 0xFF00E5FF.toInt()

    private val audioHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val exportSjbzLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri!= null) {
            val currentPresetName = spinnerPresets.selectedItem?.toString()?: "SjbZ Preset"
            val preset = equalizerProcessor.toEqPreset(name = currentPresetName, isCustom = true, mdrcSettings = mdrcProcessor.getSettings())
            val success = presetManager.exportPresetToSjbz(preset, uri)
            Toast.makeText(this, if (success) "Exportado a.sjbz con éxito" else "Error al exportar", Toast.LENGTH_SHORT).show()
        }
    }

    private val importSjbzLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri!= null) {
            val imported = presetManager.importPresetFromSjbz(uri)
            if (imported!= null) { Toast.makeText(this, "Preset '${imported.name}' importado", Toast.LENGTH_SHORT).show(); }
            else { Toast.makeText(this, "Error al importar archivo.sjbz", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)
        presetManager = PresetManager(this)
        val liveEngine = PlaybackService.instance?.atsEngine
        equalizerProcessor = liveEngine?.equalizer?: EqualizerProcessor()
        mdrcProcessor = liveEngine?.mdrc2?: MDRCProcessor()
        initViews(); setupToolbar(); setupGlobalSystemEq(); setup32BandSliders(); setupPreampAndControls(); setupMdrcGainControls()
        val activeName = presetManager.getActivePresetName()
        val activePreset = presetManager.getAllPresets().find { it.name.equals(activeName, ignoreCase = true) }?: presetManager.getAllPresets().firstOrNull()
        loadPresetToUi(activePreset)
    }

    //... tus demás funciones initViews, setupToolbar, etc las dejás igual...

    private fun setup32BandSliders() {
        container32Bands.removeAllViews()
        bandSeekBars.clear(); bandValueLabels.clear()
        val freqs = equalizerProcessor.getBandFrequencies()
        for (i in 0 until 32) {
            val bandLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            val freqLabel = TextView(this).apply { text = if (freqs[i] >= 1000) "${freqs[i]/1000}k" else "${freqs[i]}"; textSize = 9f; gravity = Gravity.CENTER }
            val seekBar = VerticalSeekBar(this).apply {
                max = 30
                progress = 15
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 350)
            }
            val valueLabel = TextView(this).apply { text = "0dB"; textSize = 9f; gravity = Gravity.CENTER }

            val bandIndex = i
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val gainDb = progress - 15
                    valueLabel.text = "${gainDb}dB"
                    if (fromUser &&!isUpdatingUiFromPreset) {
                        equalizerProcessor.setBandGain(bandIndex, gainDb.toFloat())
                        audioHandler.post {
                            try { PlaybackService.instance?.atsEngine?.updateEqualizer() } catch (_:Exception) {}
                            try { syncAllEffects() } catch (_:Exception) {}
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isUpdatingUiFromPreset = false }
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            bandLayout.addView(freqLabel); bandLayout.addView(seekBar); bandLayout.addView(valueLabel)
            container32Bands.addView(bandLayout)
            bandSeekBars.add(seekBar); bandValueLabels.add(valueLabel)
        }
    }

    private fun setupBassBoost() {
        seekBassBoost.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if(fromUser){
                    tvBassBoostValue.text = "$progress%"
                    audioHandler.post {
                        try{ PlaybackService.instance?.bassBoostProcessor?.setPercent(progress) }catch(_:Exception){}
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun syncAllEffects() {
        // tu función original
    }

    private fun loadPresetToUi(preset: EqPreset?) {
        // tu función original
    }
}
