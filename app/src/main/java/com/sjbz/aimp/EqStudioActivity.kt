package com.sjbz.aimp

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sjbz.aimp.audio.GlobalAudioSessionManager
import com.sjbz.aimp.audio.SjbzDspProcessor
import com.sjbz.aimp.databinding.ActivityEqBinding
import com.sjbz.aimp.service.GlobalAudioService
import java.util.Locale

open class EqStudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEqBinding
    private lateinit var sessionManager: GlobalAudioSessionManager
    private lateinit var dspProcessor: SjbzDspProcessor
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eqSeekBars = arrayOfNulls<SeekBar>(SjbzDspProcessor.BAND_COUNT)
    private val eqValueTexts = arrayOfNulls<TextView>(SjbzDspProcessor.BAND_COUNT)
    private var isUpdatingProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sessionManager = GlobalAudioSessionManager.getInstance(this)
        dspProcessor = sessionManager.dspProcessor
        setupToolbar()
        setupFftVisualizer()
        setupPreampControls()
        setupToneControls()
        setup32BandFaders()
        setupDynamicsAndBassControls()
        setupMdrcControls()
        setupPresetControls()
        setupCollapsibleSections()
        syncUiFromDspState()
    }

    override fun onResume() {
        super.onResume()
        updateGlobalBypassBanner()
        syncUiFromDspState()
    }

    private fun setupToolbar() {
        binding.eqToolbar.setNavigationOnClickListener { finish() }
        binding.switchMasterDsp.isChecked = dspProcessor.isMasterEnabled
        binding.switchMasterDsp.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            dspProcessor.setMasterEnabled(isChecked)
            binding.switchMasterDspSection.isChecked = isChecked
            updateGlobalBypassBanner()
        }
        binding.switchMasterDspSection.isChecked = dspProcessor.isMasterEnabled
        binding.switchMasterDspSection.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            dspProcessor.setMasterEnabled(isChecked)
            binding.switchMasterDsp.isChecked = isChecked
            updateGlobalBypassBanner()
        }
        binding.switchGlobalAudioMode.isChecked = sessionManager.isGlobalAudioEnabled
        binding.switchGlobalAudioMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            sessionManager.setGlobalAudioEnabled(isChecked)
            if (isChecked) GlobalAudioService.start(this) else GlobalAudioService.stop(this)
            updateGlobalBypassBanner()
        }
    }

    private fun updateGlobalBypassBanner() {
        val isGlobalRunning = GlobalAudioService.isServiceRunning || sessionManager.isGlobalAudioEnabled
        binding.bannerGlobalBypass.visibility = if (isGlobalRunning) View.VISIBLE else View.GONE
        binding.tvCaptureSessionCount.text = if (isGlobalRunning) "Activo (Session 0 + Apps)" else "Inactivo (Modo Local)"
    }

    private fun setupFftVisualizer() {
        dspProcessor.setFftListener { samples ->
            mainHandler.post {
                try { binding.visualizerView.invalidate() } catch (_: Exception) {}
                // Si tu VisualizerView tiene setFftData, descomenta esto:
                // try { binding.visualizerView.javaClass.getMethod("setFftData", FloatArray::class.java).invoke(binding.visualizerView, samples) } catch (_: Exception) {}
            }
        }
        dspProcessor.setClippingListener { isClipping ->
            mainHandler.post {
                if (isClipping) {
                    binding.tvClippingIndicator.setTextColor(Color.parseColor("#EF4444"))
                    binding.tvClippingIndicator.text = "LIMITER: CLIP PREVENTED"
                } else {
                    binding.tvClippingIndicator.setTextColor(Color.parseColor("#10B981"))
                    binding.tvClippingIndicator.text = "LIMITER: -1.0 dBFS SAFE"
                }
            }
        }
    }

    private fun setupPreampControls() {
        binding.seekPreamp.max = 240
        binding.seekPreamp.progress = (dspProcessor.getPreamp() * 10f + 120f).toInt().coerceIn(0, 240)
        updatePreampLabels(dspProcessor.getPreamp())
        binding.seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gainDb = (progress - 120) / 10.0f
                dspProcessor.setPreamp(gainDb)
                sessionManager.setPreampGain(gainDb)
                updatePreampLabels(gainDb)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(binding.seekPreamp)
        binding.btnResetPreamp.setOnClickListener {
            binding.seekPreamp.progress = 120
            dspProcessor.setPreamp(0.0f)
            sessionManager.setPreampGain(0.0f)
            updatePreampLabels(0.0f)
        }
    }

    private fun updatePreampLabels(gainDb: Float) {
        binding.tvPreampValue.text = String.format(Locale.US, "%+.1f dB", gainDb)
        binding.tvEffectivePreamp.text = String.format(Locale.US, "Margen Dinámico: %+.1f dB", dspProcessor.effectivePreampDb)
    }

    private fun setupToneControls() {
        binding.seekPreGainBass.max = 240
        binding.seekPreGainBass.progress = (dspProcessor.getToneBass() * 10f + 120f).toInt().coerceIn(0, 240)
        binding.tvPreGainBassValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.getToneBass())
        binding.seekPreGainBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gain = (progress - 120) / 10.0f
                binding.tvPreGainBassValue.text = String.format(Locale.US, "%+.1f dB", gain)
                dspProcessor.setToneBass(gain); sessionManager.setToneBass(gain); updatePreampLabels(dspProcessor.getPreamp())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(binding.seekPreGainBass)

        binding.seekPreGainMid.max = 240
        binding.seekPreGainMid.progress = (dspProcessor.getToneMid() * 10f + 120f).toInt().coerceIn(0, 240)
        binding.tvPreGainMidValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.getToneMid())
        binding.seekPreGainMid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gain = (progress - 120) / 10.0f
                binding.tvPreGainMidValue.text = String.format(Locale.US, "%+.1f dB", gain)
                dspProcessor.setToneMid(gain); sessionManager.setToneMid(gain); updatePreampLabels(dspProcessor.getPreamp())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(binding.seekPreGainMid)

        binding.seekPreGainTreble.max = 240
        binding.seekPreGainTreble.progress = (dspProcessor.getToneTreble() * 10f + 120f).toInt().coerceIn(0, 240)
        binding.tvPreGainTrebleValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.getToneTreble())
        binding.seekPreGainTreble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gain = (progress - 120) / 10.0f
                binding.tvPreGainTrebleValue.text = String.format(Locale.US, "%+.1f dB", gain)
                dspProcessor.setToneTreble(gain); sessionManager.setToneTreble(gain); updatePreampLabels(dspProcessor.getPreamp())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(binding.seekPreGainTreble)

        binding.btnResetPreGains.setOnClickListener {
            binding.seekPreGainBass.progress = 120; binding.seekPreGainMid.progress = 120; binding.seekPreGainTreble.progress = 120
            dspProcessor.setToneBass(0.0f); dspProcessor.setToneMid(0.0f); dspProcessor.setToneTreble(0.0f)
            sessionManager.setToneBass(0.0f); sessionManager.setToneMid(0.0f); sessionManager.setToneTreble(0.0f)
            binding.tvPreGainBassValue.text = "+0.0 dB"; binding.tvPreGainMidValue.text = "+0.0 dB"; binding.tvPreGainTrebleValue.text = "+0.0 dB"
            updatePreampLabels(dspProcessor.getPreamp())
        }
    }

    private fun setup32BandFaders() {
        binding.llFadersContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val cyanColor = Color.parseColor("#00E5FF")
        val trackBgColor = Color.parseColor("#334155")
        for (i in 0 until SjbzDspProcessor.BAND_COUNT) {
            val bandIndex = i
            val label = SjbzDspProcessor.BAND_LABELS[i]
            val currentBandGain = dspProcessor.getBandGain(bandIndex)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding((density * 4).toInt(), 0, (density * 4).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams((density * 48).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val tvGain = TextView(this).apply {
                textSize = 9.5f; setTextColor(Color.parseColor("#38BDF8"))
                text = String.format(Locale.US, "%+.1f", currentBandGain); gravity = Gravity.CENTER
                setPadding(0, 0, 0, (density * 4).toInt())
            }
            eqValueTexts[bandIndex] = tvGain; col.addView(tvGain)
            val sliderFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams((density * 48).toInt(), (density * 190).toInt()) }
            val seekBar = SeekBar(this).apply {
                layoutParams = FrameLayout.LayoutParams((density * 190).toInt(), (density * 48).toInt()).apply { gravity = Gravity.CENTER }
                rotation = 270f; max = 240; progress = (currentBandGain * 10f + 120f).toInt().coerceIn(0, 240)
                progressTintList = ColorStateList.valueOf(cyanColor); thumbTintList = ColorStateList.valueOf(cyanColor)
                progressBackgroundTintList = ColorStateList.valueOf(trackBgColor)
            }
            seekBar.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        binding.scrollFaders.requestDisallowInterceptTouchEvent(true)
                        binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        binding.scrollFaders.requestDisallowInterceptTouchEvent(false)
                        binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || isUpdatingProgrammatically) return
                    val gainDb = (progress - 120) / 10.0f
                    tvGain.text = String.format(Locale.US, "%+.1f", gainDb)
                    dspProcessor.setBandLevel(bandIndex, gainDb)
                    sessionManager.setBandGain(bandIndex, gainDb)
                    updatePreampLabels(dspProcessor.getPreamp())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollFaders.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
                override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollFaders.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
            })
            sliderFrame.addView(seekBar); col.addView(sliderFrame)
            val tvFreq = TextView(this).apply {
                textSize = 9.5f; setTextColor(Color.parseColor("#94A3B8")); text = label; gravity = Gravity.CENTER
                setPadding(0, (density * 6).toInt(), 0, 0)
            }
            col.addView(tvFreq); binding.llFadersContainer.addView(col); eqSeekBars[bandIndex] = seekBar
        }
        binding.btnResetEq.setOnClickListener {
            for (i in 0 until SjbzDspProcessor.BAND_COUNT) {
                eqSeekBars[i]?.progress = 120; eqValueTexts[i]?.text = "+0.0"
                dspProcessor.setBandLevel(i, 0.0f); sessionManager.setBandGain(i, 0.0f)
            }
            updatePreampLabels(dspProcessor.getPreamp())
            Toast.makeText(this, "Ecualizador de 32 Bandas reseteado a Plano (0 dB)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDynamicsAndBassControls() {
        binding.switchBassBoost.isChecked = dspProcessor.isBassBoostEnabled
        val bassFreqOptions = arrayOf("60 Hz (Sub)", "85 Hz (Punch)", "120 Hz (Mid-Bass)")
        binding.spinnerBassFreq.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bassFreqOptions)
        val initialBassPos = when (dspProcessor.getBassBoostFreq().toInt()) { 60 -> 0; 120 -> 2; else -> 1 }
        binding.spinnerBassFreq.setSelection(initialBassPos)
        binding.spinnerBassFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingProgrammatically) return
                val freq = when (position) { 0 -> 60.0f; 2 -> 120.0f; else -> 85.0f }
                dspProcessor.setBassBoost(binding.switchBassBoost.isChecked, freq, (binding.seekBassGain.progress / 10.0f))
                sessionManager.setBassBoost(binding.switchBassBoost.isChecked, (binding.seekBassGain.progress / 10.0f), freq)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            val freq = if (binding.spinnerBassFreq.selectedItemPosition == 0) 60f else if (binding.spinnerBassFreq.selectedItemPosition == 2) 120f else 85f
            val gain = binding.seekBassGain.progress / 10.0f
            dspProcessor.setBassBoost(isChecked, freq, gain); sessionManager.setBassBoost(isChecked, gain, freq)
            updatePreampLabels(dspProcessor.getPreamp())
        }
        binding.seekBassGain.max = 120
        binding.seekBassGain.progress = (dspProcessor.getBassBoostGain() * 10f).toInt().coerceIn(0, 120)
        binding.tvBassGainValue.text = String.format(Locale.US, "+%.1f dB", dspProcessor.getBassBoostGain())
        binding.seekBassGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gain = progress / 10.0f
                binding.tvBassGainValue.text = String.format(Locale.US, "+%.1f dB", gain)
                val freq = if (binding.spinnerBassFreq.selectedItemPosition == 0) 60f else if (binding.spinnerBassFreq.selectedItemPosition == 2) 120f else 85f
                dspProcessor.setBassBoost(binding.switchBassBoost.isChecked, freq, gain)
                sessionManager.setBassBoost(binding.switchBassBoost.isChecked, gain, freq)
                updatePreampLabels(dspProcessor.getPreamp())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(binding.seekBassGain)

        binding.switchHardwareEmulation.isChecked = dspProcessor.isEmulationEnabled
        binding.seekEmulationAmount.max = 100
        binding.seekEmulationAmount.progress = (dspProcessor.getEmulationAmount() * 100f).toInt().coerceIn(0, 100)
        binding.tvEmulationAmount.text = "${binding.seekEmulationAmount.progress}%"
        binding.switchHardwareEmulation.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor.setEmulationEnabled(isChecked)
            sessionManager.setAts2835pEmulation(isChecked, (binding.seekEmulationAmount.progress / 100.0f), binding.switchBluetoothBypass.isChecked)
            updatePreampLabels(dspProcessor.getPreamp())
        }
        binding.seekEmulationAmount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                binding.tvEmulationAmount.text = "$progress%"
                val amount = progress / 100.0f
                dspProcessor.setEmulationAmount(amount)
                sessionManager.setAts2835pEmulation(binding.switchHardwareEmulation.isChecked, amount, binding.switchBluetoothBypass.isChecked)
                updatePreampLabels(dspProcessor.getPreamp())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(binding.seekEmulationAmount)
        binding.switchBluetoothBypass.isChecked = dspProcessor.isBluetoothAutoBypass
        binding.switchBluetoothBypass.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor.setBluetoothAutoBypass(isChecked)
            sessionManager.setAts2835pEmulation(binding.switchHardwareEmulation.isChecked, (binding.seekEmulationAmount.progress / 100.0f), isChecked)
        }
    }

    private fun setupMdrcControls() {
        binding.switchMdrc.isChecked = dspProcessor.isMdrcEnabled
        binding.switchMdrc.setOnCheckedChangeListener { _, isChecked -> dspProcessor.setMdrcEnabled(isChecked); sessionManager.setMdrcEnabled(isChecked) }
        binding.seekMdrcThreshold.max = 360
        binding.seekMdrcThreshold.progress = (dspProcessor.getMdrcThreshold() * 10f + 360f).toInt().coerceIn(0, 360)
        binding.tvMdrcThresholdValue.text = String.format(Locale.US, "%.1f dB", dspProcessor.getMdrcThreshold())
        binding.seekMdrcThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val db = (progress - 360) / 10.0f
                binding.tvMdrcThresholdValue.text = String.format(Locale.US, "%.1f dB", db)
                val ratio = (binding.seekMdrcRatio.progress / 10.0f).coerceAtLeast(1.0f)
                dspProcessor.setMdrcDynamics(db, ratio); sessionManager.setMdrcDynamics(db, ratio)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(binding.seekMdrcThreshold)
        binding.seekMdrcRatio.max = 100
        binding.seekMdrcRatio.progress = (dspProcessor.getMdrcRatio() * 10f).toInt().coerceIn(10, 100)
        binding.tvMdrcRatioValue.text = String.format(Locale.US, "%.1f:1", dspProcessor.getMdrcRatio())
        binding.seekMdrcRatio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val ratio = (progress / 10.0f).coerceAtLeast(1.0f)
                binding.tvMdrcRatioValue.text = String.format(Locale.US, "%.1f:1", ratio)
                val thresh = (binding.seekMdrcThreshold.progress - 360) / 10.0f
                dspProcessor.setMdrcDynamics(thresh, ratio); sessionManager.setMdrcDynamics(thresh, ratio)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(sb: SeekBar?) { sb?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(binding.seekMdrcRatio)
        setupMdrcBandSlider(binding.seekMdrcSub, binding.tvMdrcSub, 0)
        setupMdrcBandSlider(binding.seekMdrcLow, binding.tvMdrcLow, 1)
        setupMdrcBandSlider(binding.seekMdrcMid, binding.tvMdrcMid, 2)
        setupMdrcBandSlider(binding.seekMdrcHigh, binding.tvMdrcHigh, 3)
        setupMdrcBandSlider(binding.seekMdrcAir, binding.tvMdrcAir, 4)
    }

    private fun setupMdrcBandSlider(sb: SeekBar, tv: TextView, bandIndex: Int) {
        sb.max = 240
        val currentGain = dspProcessor.getMdrcBandGain(bandIndex)
        sb.progress = (currentGain * 10f + 120f).toInt().coerceIn(0, 240)
        tv.text = String.format(Locale.US, "%+.1f dB", currentGain)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gainDb = (progress - 120) / 10.0f
                tv.text = String.format(Locale.US, "%+.1f dB", gainDb)
                dspProcessor.setMdrcBandGain(bandIndex, gainDb); sessionManager.setMdrcGain(bandIndex, gainDb)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { seekBar?.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { seekBar?.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
        })
        attachScrollLock(sb)
    }

    private fun setupPresetControls() {
        binding.btnPresetFlat.setOnClickListener { applyPresetGains(FloatArray(32) { 0.0f }, "Flat") }
        binding.btnPresetBass.setOnClickListener {
            val gains = FloatArray(32) { 0.0f }; gains[0]=6.0f; gains[1]=5.5f; gains[2]=5.0f; gains[3]=4.2f; gains[4]=3.5f; gains[5]=2.5f; gains[6]=1.2f
            applyPresetGains(gains, "Bass Boost")
        }
        binding.btnPresetRock.setOnClickListener {
            val gains = FloatArray(32) { 0.0f }; gains[0]=4.5f; gains[1]=4.0f; gains[2]=3.2f; gains[3]=2.0f; gains[14]=-1.5f; gains[15]=-2.0f; gains[16]=-1.5f; gains[27]=3.2f; gains[28]=3.8f; gains[29]=4.5f; gains[30]=5.0f
            applyPresetGains(gains, "Rock / Metal")
        }
        binding.btnPresetVocal.setOnClickListener {
            val gains = FloatArray(32) { 0.0f }; gains[0]=-2.5f; gains[1]=-2.0f; gains[2]=-1.0f; for (i in 12..22) gains[i]=3.2f
            applyPresetGains(gains, "Vocal / Podcast")
        }
        binding.btnSaveCustomPreset.setOnClickListener { showSavePresetDialog() }
        binding.btnApplyGenreEq.setOnClickListener { binding.btnPresetRock.performClick() }
    }

    private fun applyPresetGains(gains: FloatArray, name: String) {
        for (i in 0 until minOf(32, gains.size)) {
            dspProcessor.setBandLevel(i, gains[i]); sessionManager.setBandGain(i, gains[i])
            eqSeekBars[i]?.progress = (gains[i] * 10f + 120f).toInt().coerceIn(0, 240)
            eqValueTexts[i]?.text = String.format(Locale.US, "%+.1f", gains[i])
        }
        updatePreampLabels(dspProcessor.getPreamp())
        Toast.makeText(this, "Preset aplicado: $name", Toast.LENGTH_SHORT).show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply { hint = "Nombre del Preset (ej. Mi Ecualización)" }
        AlertDialog.Builder(this).setTitle("Guardar Preset de Estudio").setMessage("Almacena la curva actual de 32 bandas, Preamp y Controles de Tono.").setView(input)
           .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { sessionManager.saveCurrentAsProfile(name); Toast.makeText(this, "Preset '$name' guardado exitosamente", Toast.LENGTH_SHORT).show() }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun setupCollapsibleSections() {
        setupAccordion(binding.headerMaster, binding.chevronMaster, binding.contentMaster)
        setupAccordion(binding.headerPreGains, binding.chevronPreGains, binding.contentPreGains)
        setupAccordion(binding.headerEq32, binding.chevronEq32, binding.contentEq32)
        setupAccordion(binding.headerDynamics, binding.chevronDynamics, binding.contentDynamics)
        setupAccordion(binding.headerPresets, binding.chevronPresets, binding.contentPresets)
    }

    private fun setupAccordion(header: View, chevron: View, content: View) {
        header.setOnClickListener {
            val willBeVisible = content.visibility!= View.VISIBLE
            content.visibility = if (willBeVisible) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (willBeVisible) 180f else 0f).setDuration(200).start()
        }
    }

    private fun attachScrollLock(view: View?) {
        if (view == null) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { v.parent?.requestDisallowInterceptTouchEvent(true); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.parent?.requestDisallowInterceptTouchEvent(false); binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false) }
            }
            false
        }
    }

    private fun syncUiFromDspState() {
        isUpdatingProgrammatically = true
        try {
            binding.switchMasterDsp.isChecked = dspProcessor.isMasterEnabled
            binding.switchMasterDspSection.isChecked = dspProcessor.isMasterEnabled
            binding.seekPreamp.progress = (dspProcessor.getPreamp() * 10f + 120f).toInt().coerceIn(0, 240)
            binding.seekPreGainBass.progress = (dspProcessor.getToneBass() * 10f + 120f).toInt().coerceIn(0, 240)
            binding.seekPreGainMid.progress = (dspProcessor.getToneMid() * 10f + 120f).toInt().coerceIn(0, 240)
            binding.seekPreGainTreble.progress = (dspProcessor.getToneTreble() * 10f + 120f).toInt().coerceIn(0, 240)
            binding.tvPreGainBassValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.getToneBass())
            binding.tvPreGainMidValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.getToneMid())
            binding.tvPreGainTrebleValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.getToneTreble())
            updatePreampLabels(dspProcessor.getPreamp())
            for (i in 0 until SjbzDspProcessor.BAND_COUNT) {
                val gain = dspProcessor.getBandGain(i)
                eqSeekBars[i]?.progress = (gain * 10f + 120f).toInt().coerceIn(0, 240)
                eqValueTexts[i]?.text = String.format(Locale.US, "%+.1f", gain)
            }
        } finally { isUpdatingProgrammatically = false }
    }
}
