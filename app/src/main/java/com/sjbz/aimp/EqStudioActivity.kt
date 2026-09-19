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

/**
 * EqStudioActivity: Professional 32-Band ISO Equalizer & Hardware DSP Controller.
 *
 * Full-featured controller for the 100% Software Audio Processing Engine (ATS2835P Emulation):
 * - Input Preamp (-12 dB to +12 dB) with real-time automatic headroom calculation.
 * - 3-Band Tone Controls: Bass (200Hz Low-Shelf), Mid (1kHz Peaking), Treble (6kHz High-Shelf).
 * - 32-Band ISO Equalizer Matrix (20 Hz to 20 kHz) with dedicated vertical faders.
 * - Strict touch interception lock on all faders to completely eliminate vertical page scrolling.
 * - 5-Band Multiband Dynamic Range Compressor (MDRC: Sub, Low, Mid, High, Air).
 * - Selectable Bass Boost (60Hz, 85Hz, 120Hz) and Virtualizer 3D surround sound.
 * - ATS2835P Embedded Hardware Emulation with analog saturation modeling.
 * - Real-time FFT visualizer rendering at 60 FPS.
 * - Bidirectional synchronization with GlobalAudioSessionManager and GlobalAudioService.
 */
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

        // Enforce Singleton DSP Processor from GlobalAudioSessionManager
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
        binding.eqToolbar.setNavigationOnClickListener {
            finish()
        }

        binding.switchMasterDsp.isChecked = dspProcessor.isMasterEnabled
        binding.switchMasterDsp.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            dspProcessor.isMasterEnabled = isChecked
            binding.switchMasterDspSection.isChecked = isChecked
            updateGlobalBypassBanner()
        }

        binding.switchMasterDspSection.isChecked = dspProcessor.isMasterEnabled
        binding.switchMasterDspSection.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            dspProcessor.isMasterEnabled = isChecked
            binding.switchMasterDsp.isChecked = isChecked
            updateGlobalBypassBanner()
        }

        binding.switchGlobalAudioMode.isChecked = sessionManager.isGlobalAudioEnabled
        binding.switchGlobalAudioMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
            sessionManager.setGlobalAudioEnabled(isChecked)
            if (isChecked) {
                GlobalAudioService.start(this)
            } else {
                GlobalAudioService.stop(this)
            }
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
                binding.visualizerView.updateFft(samples)
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

    // =========================================================================
    // SECTION 1: PREAMP & AUTO-HEADROOM
    // =========================================================================

    private fun setupPreampControls() {
        // Preamp SeekBar: -12.0 dB to +12.0 dB (range 0 to 240, center 120 = 0.0 dB)
        binding.seekPreamp.max = 240
        binding.seekPreamp.progress = (dspProcessor.preamp * 10f + 120f).toInt().coerceIn(0, 240)
        updatePreampLabels(dspProcessor.preamp)

        binding.seekPreamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gainDb = (progress - 120) / 10.0f
                dspProcessor.preamp = gainDb
                sessionManager.setPreampGain(gainDb)
                updatePreampLabels(gainDb)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })

        attachScrollLock(binding.seekPreamp)

        binding.btnResetPreamp.setOnClickListener {
            binding.seekPreamp.progress = 120
            dspProcessor.preamp = 0.0f
            sessionManager.setPreampGain(0.0f)
            updatePreampLabels(0.0f)
        }
    }

    private fun updatePreampLabels(gainDb: Float) {
        binding.tvPreampValue.text = String.format(Locale.US, "%+.1f dB", gainDb)
        binding.tvEffectivePreamp.text = String.format(Locale.US, "Margen Dinámico: %+.1f dB", dspProcessor.effectivePreampDb)
    }

    // =========================================================================
    // SECTION 2: 3-BAND TONE CONTROLS (Bass, Mid, Treble)
    // =========================================================================

    private fun setupToneControls() {
        // Bass (Graves): 200 Hz Low-Shelf (-12 dB to +12 dB)
        binding.seekPreGainBass.max = 240
        binding.seekPreGainBass.progress = (dspProcessor.toneBass * 10f + 120f).toInt().coerceIn(0, 240)
        binding.tvPreGainBassValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.toneBass)

        binding.seekPreGainBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gain = (progress - 120) / 10.0f
                binding.tvPreGainBassValue.text = String.format(Locale.US, "%+.1f dB", gain)
                dspProcessor.toneBass = gain
                sessionManager.setToneBass(gain)
                updatePreampLabels(dspProcessor.preamp)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })
        attachScrollLock(binding.seekPreGainBass)

        // Mid (Medios): 1000 Hz Peaking Q=1.0 (-12 dB to +12 dB)
        binding.seekPreGainMid.max = 240
        binding.seekPreGainMid.progress = (dspProcessor.toneMid * 10f + 120f).toInt().coerceIn(0, 240)
        binding.tvPreGainMidValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.toneMid)

        binding.seekPreGainMid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gain = (progress - 120) / 10.0f
                binding.tvPreGainMidValue.text = String.format(Locale.US, "%+.1f dB", gain)
                dspProcessor.toneMid = gain
                sessionManager.setToneMid(gain)
                updatePreampLabels(dspProcessor.preamp)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })
        attachScrollLock(binding.seekPreGainMid)

        // Treble (Agudos): 6000 Hz High-Shelf (-12 dB to +12 dB)
        binding.seekPreGainTreble.max = 240
        binding.seekPreGainTreble.progress = (dspProcessor.toneTreble * 10f + 120f).toInt().coerceIn(0, 240)
        binding.tvPreGainTrebleValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.toneTreble)

        binding.seekPreGainTreble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gain = (progress - 120) / 10.0f
                binding.tvPreGainTrebleValue.text = String.format(Locale.US, "%+.1f dB", gain)
                dspProcessor.toneTreble = gain
                sessionManager.setToneTreble(gain)
                updatePreampLabels(dspProcessor.preamp)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })
        attachScrollLock(binding.seekPreGainTreble)

        // Reset Tone Controls button
        binding.btnResetPreGains.setOnClickListener {
            binding.seekPreGainBass.progress = 120
            binding.seekPreGainMid.progress = 120
            binding.seekPreGainTreble.progress = 120
            dspProcessor.toneBass = 0.0f
            dspProcessor.toneMid = 0.0f
            dspProcessor.toneTreble = 0.0f
            sessionManager.setToneBass(0.0f)
            sessionManager.setToneMid(0.0f)
            sessionManager.setToneTreble(0.0f)
            binding.tvPreGainBassValue.text = "+0.0 dB"
            binding.tvPreGainMidValue.text = "+0.0 dB"
            binding.tvPreGainTrebleValue.text = "+0.0 dB"
            updatePreampLabels(dspProcessor.preamp)
        }
    }

    // =========================================================================
    // SECTION 3: 32-BAND ISO PARAMETRIC EQUALIZER MATRIX
    // =========================================================================

    private fun setup32BandFaders() {
        binding.llFadersContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val cyanColor = Color.parseColor("#00E5FF")
        val trackBgColor = Color.parseColor("#334155")

        // Strict iteration over all 32 ISO frequencies (indices 0 to 31)
        for (i in 0 until SjbzDspProcessor.BAND_COUNT) {
            val bandIndex = i
            val label = SjbzDspProcessor.BAND_LABELS[i]
            val currentBandGain = dspProcessor.getBandLevel(bandIndex)

            // Column container for vertical fader
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((density * 4).toInt(), 0, (density * 4).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    (density * 48).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Real-time gain value text on top
            val tvGain = TextView(this).apply {
                textSize = 9.5f
                setTextColor(Color.parseColor("#38BDF8"))
                text = String.format(Locale.US, "%+.1f", currentBandGain)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, (density * 4).toInt())
            }
            eqValueTexts[bandIndex] = tvGain
            col.addView(tvGain)

            // Frame container for rotated vertical SeekBar (190dp height, 48dp width)
            val sliderFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (density * 48).toInt(),
                    (density * 190).toInt()
                )
            }

            // Rotated SeekBar (-12.0 dB to +12.0 dB, 0 to 240 steps)
            val seekBar = SeekBar(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    (density * 190).toInt(),
                    (density * 48).toInt()
                ).apply {
                    gravity = Gravity.CENTER
                }
                rotation = 270f
                max = 240
                progress = (currentBandGain * 10f + 120f).toInt().coerceIn(0, 240)
                progressTintList = ColorStateList.valueOf(cyanColor)
                thumbTintList = ColorStateList.valueOf(cyanColor)
                progressBackgroundTintList = ColorStateList.valueOf(trackBgColor)
            }

            // CRITICAL GESTURE INTERCEPTION SOLUTION:
            // Prevents parent ScrollView and HorizontalScrollView from scrolling when dragging vertical sliders
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

            // Mandatory SeekBarChangeListener with strict disallow touch interception calls
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || isUpdatingProgrammatically) return
                    val gainDb = (progress - 120) / 10.0f
                    tvGain.text = String.format(Locale.US, "%+.1f", gainDb)
                    dspProcessor.setBandLevel(bandIndex, gainDb)
                    sessionManager.setBandGain(bandIndex, gainDb)
                    updatePreampLabels(dspProcessor.preamp)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    sb?.parent?.requestDisallowInterceptTouchEvent(true)
                    binding.scrollFaders.requestDisallowInterceptTouchEvent(true)
                    binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.parent?.requestDisallowInterceptTouchEvent(false)
                    binding.scrollFaders.requestDisallowInterceptTouchEvent(false)
                    binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
                }
            })

            sliderFrame.addView(seekBar)
            col.addView(sliderFrame)

            // Frequency label below slider
            val tvFreq = TextView(this).apply {
                textSize = 9.5f
                setTextColor(Color.parseColor("#94A3B8"))
                text = label
                gravity = Gravity.CENTER
                setPadding(0, (density * 6).toInt(), 0, 0)
            }
            col.addView(tvFreq)

            binding.llFadersContainer.addView(col)
            eqSeekBars[bandIndex] = seekBar
        }

        // Reset EQ button
        binding.btnResetEq.setOnClickListener {
            for (i in 0 until SjbzDspProcessor.BAND_COUNT) {
                eqSeekBars[i]?.progress = 120
                eqValueTexts[i]?.text = "+0.0"
                dspProcessor.setBandLevel(i, 0.0f)
                sessionManager.setBandGain(i, 0.0f)
            }
            updatePreampLabels(dspProcessor.preamp)
            Toast.makeText(this, "Ecualizador de 32 Bandas reseteado a Plano (0 dB)", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // SECTION 4: DYNAMICS, BASS BOOST & ATS2835P EMULATION
    // =========================================================================

    private fun setupDynamicsAndBassControls() {
        // Bass Boost Switch & Freq Spinner
        binding.switchBassBoost.isChecked = dspProcessor.isBassBoostEnabled
        val bassFreqOptions = arrayOf("60 Hz (Sub)", "85 Hz (Punch)", "120 Hz (Mid-Bass)")
        binding.spinnerBassFreq.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bassFreqOptions)
        val initialBassPos = when (dspProcessor.bassBoostFreq.toInt()) {
            60 -> 0
            120 -> 2
            else -> 1
        }
        binding.spinnerBassFreq.setSelection(initialBassPos)

        binding.spinnerBassFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingProgrammatically) return
                val freq = when (position) {
                    0 -> 60.0f
                    2 -> 120.0f
                    else -> 85.0f
                }
                dspProcessor.setBassBoost(binding.switchBassBoost.isChecked, freq, (binding.seekBassGain.progress / 10.0f))
                sessionManager.setBassBoost(binding.switchBassBoost.isChecked, (binding.seekBassGain.progress / 10.0f), freq)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.switchBassBoost.setOnCheckedChangeListener { _, isChecked ->
            val freq = if (binding.spinnerBassFreq.selectedItemPosition == 0) 60f else if (binding.spinnerBassFreq.selectedItemPosition == 2) 120f else 85f
            val gain = binding.seekBassGain.progress / 10.0f
            dspProcessor.setBassBoost(isChecked, freq, gain)
            sessionManager.setBassBoost(isChecked, gain, freq)
            updatePreampLabels(dspProcessor.preamp)
        }

        binding.seekBassGain.max = 120
        binding.seekBassGain.progress = (dspProcessor.bassBoostGain * 10f).toInt().coerceIn(0, 120)
        binding.tvBassGainValue.text = String.format(Locale.US, "+%.1f dB", dspProcessor.bassBoostGain)

        binding.seekBassGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val gain = progress / 10.0f
                binding.tvBassGainValue.text = String.format(Locale.US, "+%.1f dB", gain)
                val freq = if (binding.spinnerBassFreq.selectedItemPosition == 0) 60f else if (binding.spinnerBassFreq.selectedItemPosition == 2) 120f else 85f
                dspProcessor.setBassBoost(binding.switchBassBoost.isChecked, freq, gain)
                sessionManager.setBassBoost(binding.switchBassBoost.isChecked, gain, freq)
                updatePreampLabels(dspProcessor.preamp)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })
        attachScrollLock(binding.seekBassGain)

        // Hardware Emulation ATS2835P
        binding.switchHardwareEmulation.isChecked = dspProcessor.isEmulationEnabled
        binding.seekEmulationAmount.max = 100
        binding.seekEmulationAmount.progress = (dspProcessor.emulationAmount * 100f).toInt().coerceIn(0, 100)
        binding.tvEmulationAmount.text = "${binding.seekEmulationAmount.progress}%"

        binding.switchHardwareEmulation.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor.isEmulationEnabled = isChecked
            sessionManager.setAts2835pEmulation(isChecked, (binding.seekEmulationAmount.progress / 100.0f), binding.switchBluetoothBypass.isChecked)
            updatePreampLabels(dspProcessor.preamp)
        }

        binding.seekEmulationAmount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                binding.tvEmulationAmount.text = "$progress%"
                val amount = progress / 100.0f
                dspProcessor.emulationAmount = amount
                sessionManager.setAts2835pEmulation(binding.switchHardwareEmulation.isChecked, amount, binding.switchBluetoothBypass.isChecked)
                updatePreampLabels(dspProcessor.preamp)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })
        attachScrollLock(binding.seekEmulationAmount)

        binding.switchBluetoothBypass.isChecked = dspProcessor.isBluetoothAutoBypass
        binding.switchBluetoothBypass.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor.isBluetoothAutoBypass = isChecked
            sessionManager.setAts2835pEmulation(binding.switchHardwareEmulation.isChecked, (binding.seekEmulationAmount.progress / 100.0f), isChecked)
        }
    }

    // =========================================================================
    // SECTION 5: 5-BAND MDRC (Multi-band Dynamic Range Control)
    // =========================================================================

    private fun setupMdrcControls() {
        binding.switchMdrc.isChecked = dspProcessor.isMdrcEnabled
        binding.switchMdrc.setOnCheckedChangeListener { _, isChecked ->
            dspProcessor.isMdrcEnabled = isChecked
            sessionManager.setMdrcEnabled(isChecked)
        }

        // MDRC Threshold: -36.0 dB to 0.0 dB (progress 0 to 360, db = progress - 360 / 10)
        binding.seekMdrcThreshold.max = 360
        binding.seekMdrcThreshold.progress = (dspProcessor.mdrcThresholdDb * 10f + 360f).toInt().coerceIn(0, 360)
        binding.tvMdrcThresholdValue.text = String.format(Locale.US, "%.1f dB", dspProcessor.mdrcThresholdDb)

        binding.seekMdrcThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val db = (progress - 360) / 10.0f
                binding.tvMdrcThresholdValue.text = String.format(Locale.US, "%.1f dB", db)
                val ratio = (binding.seekMdrcRatio.progress / 10.0f).coerceAtLeast(1.0f)
                dspProcessor.setMdrcDynamics(db, ratio)
                sessionManager.setMdrcDynamics(db, ratio)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })
        attachScrollLock(binding.seekMdrcThreshold)

        // MDRC Ratio: 1.0:1 to 10.0:1 (progress 10 to 100)
        binding.seekMdrcRatio.max = 100
        binding.seekMdrcRatio.progress = (dspProcessor.mdrcRatio * 10f).toInt().coerceIn(10, 100)
        binding.tvMdrcRatioValue.text = String.format(Locale.US, "%.1f:1", dspProcessor.mdrcRatio)

        binding.seekMdrcRatio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingProgrammatically) return
                val ratio = (progress / 10.0f).coerceAtLeast(1.0f)
                binding.tvMdrcRatioValue.text = String.format(Locale.US, "%.1f:1", ratio)
                val thresh = (binding.seekMdrcThreshold.progress - 360) / 10.0f
                dspProcessor.setMdrcDynamics(thresh, ratio)
                sessionManager.setMdrcDynamics(thresh, ratio)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })
        attachScrollLock(binding.seekMdrcRatio)

        // 5 MDRC Bands: Sub (0), Low (1), Mid (2), High (3), Air (4)
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
                dspProcessor.setMdrcBandGain(bandIndex, gainDb)
                sessionManager.setMdrcGain(bandIndex, gainDb)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBar?.parent?.requestDisallowInterceptTouchEvent(true)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
            }
        })
        attachScrollLock(sb)
    }

    // =========================================================================
    // SECTION 6: PRESETS & PERSISTENCE
    // =========================================================================

    private fun setupPresetControls() {
        binding.btnPresetFlat.setOnClickListener {
            applyPresetGains(FloatArray(32) { 0.0f }, "Flat")
        }

        binding.btnPresetBass.setOnClickListener {
            val gains = FloatArray(32) { 0.0f }
            gains[0] = 6.0f; gains[1] = 5.5f; gains[2] = 5.0f; gains[3] = 4.2f
            gains[4] = 3.5f; gains[5] = 2.5f; gains[6] = 1.2f
            applyPresetGains(gains, "Bass Boost")
        }

        binding.btnPresetRock.setOnClickListener {
            val gains = FloatArray(32) { 0.0f }
            gains[0] = 4.5f; gains[1] = 4.0f; gains[2] = 3.2f; gains[3] = 2.0f
            gains[14] = -1.5f; gains[15] = -2.0f; gains[16] = -1.5f
            gains[27] = 3.2f; gains[28] = 3.8f; gains[29] = 4.5f; gains[30] = 5.0f
            applyPresetGains(gains, "Rock / Metal")
        }

        binding.btnPresetVocal.setOnClickListener {
            val gains = FloatArray(32) { 0.0f }
            gains[0] = -2.5f; gains[1] = -2.0f; gains[2] = -1.0f
            for (i in 12..22) gains[i] = 3.2f
            applyPresetGains(gains, "Vocal / Podcast")
        }

        binding.btnSaveCustomPreset.setOnClickListener {
            showSavePresetDialog()
        }

        binding.btnApplyGenreEq.setOnClickListener {
            // Adaptive curve for Rock / Metal
            binding.btnPresetRock.performClick()
        }
    }

    private fun applyPresetGains(gains: FloatArray, name: String) {
        for (i in 0 until minOf(32, gains.size)) {
            dspProcessor.setBandLevel(i, gains[i])
            sessionManager.setBandGain(i, gains[i])
            eqSeekBars[i]?.progress = (gains[i] * 10f + 120f).toInt().coerceIn(0, 240)
            eqValueTexts[i]?.text = String.format(Locale.US, "%+.1f", gains[i])
        }
        updatePreampLabels(dspProcessor.preamp)
        Toast.makeText(this, "Preset aplicado: $name", Toast.LENGTH_SHORT).show()
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply {
            hint = "Nombre del Preset (ej. Mi Ecualización)"
        }
        AlertDialog.Builder(this)
            .setTitle("Guardar Preset de Estudio")
            .setMessage("Almacena la curva actual de 32 bandas, Preamp y Controles de Tono.")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    sessionManager.saveCurrentAsProfile(name)
                    Toast.makeText(this, "Preset '$name' guardado exitosamente", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =========================================================================
    // SECTION 7: COLLAPSIBLE ACCORDION HEADERS
    // =========================================================================

    private fun setupCollapsibleSections() {
        setupAccordion(binding.headerMaster, binding.chevronMaster, binding.contentMaster)
        setupAccordion(binding.headerPreGains, binding.chevronPreGains, binding.contentPreGains)
        setupAccordion(binding.headerEq32, binding.chevronEq32, binding.contentEq32)
        setupAccordion(binding.headerDynamics, binding.chevronDynamics, binding.contentDynamics)
        setupAccordion(binding.headerPresets, binding.chevronPresets, binding.contentPresets)
    }

    private fun setupAccordion(header: View, chevron: View, content: View) {
        header.setOnClickListener {
            val willBeVisible = content.visibility != View.VISIBLE
            content.visibility = if (willBeVisible) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (willBeVisible) 180f else 0f).setDuration(200).start()
        }
    }

    // =========================================================================
    // SECTION 8: GESTURE LOCK & UI STATE SYNCHRONIZATION
    // =========================================================================

    /**
     * Prevents parasitic vertical scrolling when interacting with sliders.
     */
    private fun attachScrollLock(view: View?) {
        if (view == null) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    binding.scrollEqRoot.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    binding.scrollEqRoot.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
    }

    private fun syncUiFromDspState() {
        isUpdatingProgrammatically = true
        try {
            binding.switchMasterDsp.isChecked = dspProcessor.isMasterEnabled
            binding.switchMasterDspSection.isChecked = dspProcessor.isMasterEnabled

            // Preamp & Tones
            binding.seekPreamp.progress = (dspProcessor.preamp * 10f + 120f).toInt().coerceIn(0, 240)
            binding.seekPreGainBass.progress = (dspProcessor.toneBass * 10f + 120f).toInt().coerceIn(0, 240)
            binding.seekPreGainMid.progress = (dspProcessor.toneMid * 10f + 120f).toInt().coerceIn(0, 240)
            binding.seekPreGainTreble.progress = (dspProcessor.toneTreble * 10f + 120f).toInt().coerceIn(0, 240)

            binding.tvPreGainBassValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.toneBass)
            binding.tvPreGainMidValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.toneMid)
            binding.tvPreGainTrebleValue.text = String.format(Locale.US, "%+.1f dB", dspProcessor.toneTreble)
            updatePreampLabels(dspProcessor.preamp)

            // 32 Bands
            for (i in 0 until SjbzDspProcessor.BAND_COUNT) {
                val gain = dspProcessor.getBandLevel(i)
                eqSeekBars[i]?.progress = (gain * 10f + 120f).toInt().coerceIn(0, 240)
                eqValueTexts[i]?.text = String.format(Locale.US, "%+.1f", gain)
            }
        } finally {
            isUpdatingProgrammatically = false
        }
    }
}
