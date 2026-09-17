package com.sjbz.aimp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sjbz.aimp.R
import com.sjbz.aimp.audio.EqualizerProcessor
import com.sjbz.aimp.audio.GlobalAudioSessionManager
import com.sjbz.aimp.databinding.ActivityEqBinding

class EqStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEqBinding
    private lateinit var audioManager: GlobalAudioSessionManager
    private val equalizerProcessor = EqualizerProcessor()

    private val faderSeekBars = ArrayList<SeekBar>()
    private val faderGainTexts = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Instancia del Singleton centralizador de audio
        audioManager = GlobalAudioSessionManager.getInstance(this)

        setSupportActionBar(binding.eqToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupCollapsibleSections()
        setupMasterControls()
        setupPreGainsControls()
        setup32BandFaders()
        setupPresetControls()

        // Sincronizar la UI con los valores reales almacenados en el Manager
        syncUIFromManager()
    }

    private fun setupCollapsibleSections() {
        bindCollapsible(binding.headerMaster, binding.contentMaster, binding.chevronMaster)
        bindCollapsible(binding.headerPreGains, binding.contentPreGains, binding.chevronPreGains)
        bindCollapsible(binding.headerEq32, binding.contentEq32, binding.chevronEq32)
        bindCollapsible(binding.headerDynamics, binding.contentDynamics, binding.chevronDynamics)
        bindCollapsible(binding.headerPresets, binding.contentPresets, binding.chevronPresets)
    }

    private fun bindCollapsible(header: View, content: View, chevron: View) {
        header.setOnClickListener {
            val isVisible = content.visibility == View.VISIBLE
            content.visibility = if (isVisible) View.GONE else View.VISIBLE
            chevron.animate().rotation(if (isVisible) 0f else 180f).setDuration(200).start()
        }
    }

    private fun setupMasterControls() {
        binding.seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dbValue = (progress - 120) / 10.0f
                binding.tvPreampValue.text = String.format("%.1f dB", dbValue)
                if (fromUser) {
                    audioManager.setPreampGain(dbValue)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        })
    }

    private fun setupPreGainsControls() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dbValue = (progress - 120) / 10.0f
                val text = String.format("%.1f dB", dbValue)
                when (sb?.id) {
                    R.id.seekPreGainBass -> {
                        binding.tvPreGainBassValue.text = text
                        if (fromUser) audioManager.setBassBoost(dbValue, audioManager.bassFreqHz)
                    }
                    R.id.seekPreGainMid -> binding.tvPreGainMidValue.text = text
                    R.id.seekPreGainTreble -> binding.tvPreGainTrebleValue.text = text
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false) }
        }

        binding.seekPreGainBass.setOnSeekBarChangeListener(listener)
        binding.seekPreGainMid.setOnSeekBarChangeListener(listener)
        binding.seekPreGainTreble.setOnSeekBarChangeListener(listener)
    }

    private fun setup32BandFaders() {
        val container = binding.llFadersContainer
        container.removeAllViews()
        faderSeekBars.clear()
        faderGainTexts.clear()

        for (i in EqualizerProcessor.BAND_LABELS.indices) {
            val faderView = LayoutInflater.from(this).inflate(R.layout.item_eq_fader_vertical, container, false)
            
            val tvFreq = faderView.findViewById<TextView>(R.id.tvFaderFreq)
            val tvGain = faderView.findViewById<TextView>(R.id.tvFaderGain)
            val seekBar = faderView.findViewById<SeekBar>(R.id.verticalSeekBar)

            tvFreq.text = EqualizerProcessor.BAND_LABELS[i]
            seekBar.max = 240 // Rango de -12dB a +12dB (escalado por 10)
            
            faderSeekBars.add(seekBar)
            faderGainTexts.add(tvGain)

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val dbValue = (progress - 120) / 10.0f
                    tvGain.text = String.format("%.1f", dbValue)
                    
                    if (fromUser) {
                        audioManager.setBandGain(i, dbValue)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    sb?.parent?.parent?.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.parent?.parent?.requestDisallowInterceptTouchEvent(false)
                }
            })

            container.addView(faderView)
        }

        binding.btnResetEq.setOnClickListener {
            equalizerProcessor.applyPreset("Flat")
            applyPresetToManager()
            syncUIFromManager()
        }
    }

    private fun setupPresetControls() {
        binding.btnPresetFlat.setOnClickListener {
            equalizerProcessor.applyPreset("Flat")
            applyPresetToManager()
            syncUIFromManager()
        }

        binding.btnPresetBass.setOnClickListener {
            equalizerProcessor.applyPreset("Bass")
            applyPresetToManager()
            syncUIFromManager()
        }

        binding.btnPresetRock.setOnClickListener {
            equalizerProcessor.applyPreset("Rock")
            applyPresetToManager()
            syncUIFromManager()
        }

        binding.btnPresetVocal.setOnClickListener {
            equalizerProcessor.applyPreset("Vocal")
            applyPresetToManager()
            syncUIFromManager()
        }
    }

    private fun applyPresetToManager() {
        audioManager.setPreampGain(equalizerProcessor.preampDb)
        val gains = equalizerProcessor.getBandGains()
        for (i in gains.indices) {
            audioManager.setBandGain(i, gains[i])
        }
    }

    private fun syncUIFromManager() {
        val preampVal = audioManager.preampDb
        binding.seekPreamp.progress = ((preampVal * 10.0f) + 120.0f).toInt().coerceIn(0, 240)
        binding.tvPreampValue.text = String.format("%.1f dB", preampVal)

        val gains = audioManager.bandGains
        for (i in gains.indices) {
            if (i < faderSeekBars.size) {
                val progress = ((gains[i] * 10.0f) + 120.0f).toInt().coerceIn(0, 240)
                faderSeekBars[i].progress = progress
                faderGainTexts[i].text = String.format("%.1f", gains[i])
            }
        }
    }
}
