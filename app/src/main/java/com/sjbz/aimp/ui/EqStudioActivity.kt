package com.sjbz.aimp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sjbz.aimp.R
import com.sjbz.aimp.audio.GlobalAudioSessionManager
import com.sjbz.aimp.audio.SjbzDspProcessor
import com.sjbz.aimp.databinding.ActivityEqBinding

class EqStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEqBinding
    private lateinit var audioManager: GlobalAudioSessionManager

    private val faderSeekBars = ArrayList<SeekBar>()
    private val faderGainTexts = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = GlobalAudioSessionManager.getInstance(this)

        setSupportActionBar(binding.eqToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupCollapsibleSections()
        setupMasterControls()
        setupPreGainsControls()
        setup32BandFaders()
        setupPresetControls()

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
                        if (fromUser) GlobalAudioSessionManager.getDspProcessor().setToneBass(dbValue)
                    }
                    R.id.seekPreGainMid -> {
                        binding.tvPreGainMidValue.text = text
                        if (fromUser) GlobalAudioSessionManager.getDspProcessor().setToneMid(dbValue)
                    }
                    R.id.seekPreGainTreble -> {
                        binding.tvPreGainTrebleValue.text = text
                        if (fromUser) GlobalAudioSessionManager.getDspProcessor().setToneTreble(dbValue)
                    }
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

        for (i in SjbzDspProcessor.BAND_LABELS.indices) {
            val faderView = LayoutInflater.from(this).inflate(R.layout.item_eq_fader_vertical, container, false)
            val tvFreq = faderView.findViewById<TextView>(R.id.tvFaderFreq)
            val tvGain = faderView.findViewById<TextView>(R.id.tvFaderGain)
            val seekBar = faderView.findViewById<SeekBar>(R.id.verticalSeekBar)

            tvFreq.text = SjbzDspProcessor.BAND_LABELS[i]
            seekBar.max = 240

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
                override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(true) }
                override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.parent?.requestDisallowInterceptTouchEvent(false) }
            })
            container.addView(faderView)
        }

        binding.btnResetEq.setOnClickListener {
            applyPresetValues(FloatArray(32) { 0.0f }, 0.0f)
        }
    }

    private fun setupPresetControls() {
        binding.btnPresetFlat.setOnClickListener {
            applyPresetValues(FloatArray(32) { 0.0f }, 0.0f)
        }
        binding.btnPresetBass.setOnClickListener {
            val bassBands = FloatArray(32) { 0.0f }.apply {
                this[0] = 6.0f; this[1] = 5.5f; this[2] = 5.0f; this[3] = 4.0f
                this[4] = 3.0f; this[5] = 2.0f; this[6] = 1.0f
            }
            applyPresetValues(bassBands, 2.0f)
        }
        binding.btnPresetRock.setOnClickListener {
            val rockBands = FloatArray(32) { 0.0f }.apply {
                this[0] = 4.5f; this[1] = 4.0f; this[2] = 3.5f; this[3] = 2.5f
                this[14] = -1.5f; this[15] = -2.0f; this[16] = -1.5f
                this[27] = 3.5f; this[28] = 4.0f; this[29] = 4.5f; this[30] = 5.0f
            }
            applyPresetValues(rockBands, 1.5f)
        }
        binding.btnPresetVocal.setOnClickListener {
            val vocalBands = FloatArray(32) { 0.0f }.apply {
                this[0] = -2.0f; this[1] = -1.5f; this[2] = -1.0f
                for (i in 12..22) this[i] = 3.5f
            }
            applyPresetValues(vocalBands, 1.0f)
        }
    }

    private fun applyPresetValues(gains: FloatArray, preamp: Float) {
        audioManager.setPreampGain(preamp)
        for (i in gains.indices) {
            audioManager.setBandGain(i, gains[i])
        }
        syncUIFromManager()
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
