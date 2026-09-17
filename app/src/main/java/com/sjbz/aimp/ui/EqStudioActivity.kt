package com.sjbz.aimp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sjbz.aimp.R
import com.sjbz.aimp.databinding.ActivityEqBinding

class EqStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEqBinding

    // Frecuencias ISO estándar para ecualizador de alta precisión (32 bandas)
    private val isoFrequencies = arrayOf(
        "20", "25", "31.5", "40", "50", "63", "80", "100",
        "125", "160", "200", "250", "315", "400", "500", "630",
        "800", "1k", "1.25k", "1.6k", "2k", "2.5k", "3.15k", "4k",
        "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k", "22k"
    )

    // Listas para almacenar las referencias de los SeekBars y Textos de las 32 bandas
    private val faderSeekBars = ArrayList<SeekBar>()
    private val faderGainTexts = ArrayList<TextView>()

    // Array para almacenar los valores actuales en dB de cada banda (-12.0 a +12.0 dB)
    val bandGains = FloatArray(32) { 0.0f }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.eqToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupCollapsibleSections()
        setupMasterControls()
        setupPreGainsControls()
        setup32BandFaders()
        setupDynamicsControls()
        setupPresetControls()
    }

    // =========================================================================
    // 1. SECCIONES COLAPSIBLES (Acordeón)
    // =========================================================================
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

    // =========================================================================
    // 2. CONTROLES MASTER Y PREAMP
    // =========================================================================
    private fun setupMasterControls() {
        binding.seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dbValue = (progress - 120) / 10.0
                binding.tvPreampValue.text = String.format("%.1f dB", dbValue)
                binding.tvEffectivePreamp.text = "Auto-Headroom: Preamp Efectivo = ${String.format("%.1f", dbValue)} dB"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
            }
        })

        binding.btnResetPreamp.setOnClickListener {
            binding.seekPreamp.progress = 120
        }
    }

    // =========================================================================
    // 3. PRE-GANANCIAS (Graves, Medios, Agudos)
    // =========================================================================
    private fun setupPreGainsControls() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dbValue = (progress - 120) / 10.0
                val text = String.format("%.1f dB", dbValue)
                when (sb?.id) {
                    R.id.seekPreGainBass -> binding.tvPreGainBassValue.text = text
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

        binding.btnResetPreGains.setOnClickListener {
            binding.seekPreGainBass.progress = 120
            binding.seekPreGainMid.progress = 120
            binding.seekPreGainTreble.progress = 120
        }
    }

    // =========================================================================
    // 4. GENERACIÓN DINÁMICA DE LOS 32 FADERS ISO
    // =========================================================================
    private fun setup32BandFaders() {
        val container = binding.llFadersContainer
        container.removeAllViews()
        faderSeekBars.clear()
        faderGainTexts.clear()

        for (i in isoFrequencies.indices) {
            val faderView = LayoutInflater.from(this).inflate(R.layout.item_eq_fader_vertical, container, false)
            
            val tvFreq = faderView.findViewById<TextView>(R.id.tvFaderFreq)
            val tvGain = faderView.findViewById<TextView>(R.id.tvFaderGain)
            val seekBar = faderView.findViewById<SeekBar>(R.id.verticalSeekBar)

            tvFreq.text = isoFrequencies[i]
            tvGain.text = "0.0"
            seekBar.max = 240
            seekBar.progress = 120 // 0 dB central

            faderSeekBars.add(seekBar)
            faderGainTexts.add(tvGain)

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val dbValue = (progress - 120) / 10.0f
                    bandGains[i] = dbValue
                    tvGain.text = String.format("%.1f", dbValue)
                    
                    if (fromUser) {
                        // TODO: Enviar cambio al motor DSP de la banda [i]
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
            applyPreset(FloatArray(32) { 0.0f })
        }
    }

    // =========================================================================
    // 5. DINÁMICA (BassBoost, EMU ATS2835P y MDRC)
    // =========================================================================
    private fun setupDynamicsControls() {
        binding.seekBassGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = progress / 10.0
                binding.tvBassGainValue.text = String.format("+%.1f dB", gain)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        binding.seekEmulationAmount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvEmulationAmount.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        binding.seekMdrcThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val thresh = -progress
                binding.tvMdrcThresholdValue.text = "Umbral: $thresh dB"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(false) }
        })

        binding.seekMdrcRatio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = 1.0 + (progress / 10.0)
                binding.tvMdrcRatioValue.text = String.format("Ratio: %.1f:1", ratio)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(false) }
        })
    }

    // =========================================================================
    // 6. PRESETS PROFESIONALES Y GESTIÓN RÁPIDA
    // =========================================================================
    private fun setupPresetControls() {
        binding.btnPresetFlat.setOnClickListener {
            applyPreset(FloatArray(32) { 0.0f })
        }

        binding.btnPresetBass.setOnClickListener {
            val bassPreset = floatArrayOf(
                6.5f, 7.0f, 6.0f, 5.0f, 4.0f, 3.0f, 2.0f, 1.0f,
                0.5f, 0.0f, -0.5f, -1.0f, -1.0f, -0.5f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f,
                2.5f, 3.0f, 3.5f, 4.0f, 4.0f, 3.5f, 2.5f, 1.0f
            )
            applyPreset(bassPreset)
        }

        binding.btnPresetRock.setOnClickListener {
            val rockPreset = floatArrayOf(
                4.0f, 3.5f, 3.0f, 2.5f, 1.5f, 0.5f, -1.0f, -2.0f,
                -1.5f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f,
                3.0f, 3.5f, 4.0f, 4.5f, 4.5f, 4.0f, 3.5f, 3.0f,
                3.5f, 4.0f, 4.5f, 5.0f, 5.0f, 4.5f, 3.5f, 2.0f
            )
            applyPreset(rockPreset)
        }

        binding.btnPresetVocal.setOnClickListener {
            val vocalPreset = floatArrayOf(
                -2.0f, -2.0f, -1.5f, -1.0f, -0.5f, 0.0f, 0.0f, 0.5f,
                1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 4.5f, 5.0f,
                5.5f, 5.5f, 5.0f, 4.5f, 3.5f, 2.5f, 1.5f, 1.0f,
                0.5f, 0.0f, -0.5f, -1.0f, -1.5f, -2.0f, -2.5f, -3.0f
            )
            applyPreset(vocalPreset)
        }
    }

    private fun applyPreset(gains: FloatArray) {
        for (i in 0 until 32) {
            if (i < faderSeekBars.size && i < gains.size) {
                bandGains[i] = gains[i]
                val progress = ((gains[i] * 10.0f) + 120.0f).toInt().coerceIn(0, 240)
                faderSeekBars[i].progress = progress
                faderGainTexts[i].text = String.format("%.1f", gains[i])
            }
        }
    }
}
