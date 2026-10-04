package com.sb.dsp

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.sb.dsp.service.SbDspForegroundService
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private var config = DspConfig().validate()
    private lateinit var root: LinearLayout

    companion object {
        private val EQ_FREQUENCIES = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
            800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 18000f, 20000f
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = DspConfigStore.load(this)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 100)
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(40))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        scroll.addView(root)
        setContentView(scroll)

        title("SB Audio DSP")
        subtitle("Cadena completa: Pre-Gain → Bass → Tone → EQ32 → MDRC → AutoGain → Limiter → Spatial → Master → Balance")

        section("CONTROL GENERAL")
        switchControl("DSP maestro", config.masterEnabled) { v -> update { copy(masterEnabled = v) } }
        actionButtons()

        section("PRE-GAIN")
        switchControl("Pre-Gain", config.pregainEnabled) { v -> update { copy(pregainEnabled = v) } }
        dbSlider("Ganancia", -24f, 12f, config.pregainDb) { v -> update { copy(pregainDb = v) } }

        section("BASS BOOST")
        switchControl("Bass Boost", config.bassBoostEnabled) { v -> update { copy(bassBoostEnabled = v) } }
        percentSlider("Intensidad", config.bassBoostStrength) { v -> update { copy(bassBoostStrength = v) } }
        floatSlider("Frecuencia central", 30f, 160f, config.bassBoostCenterFreq, 1f, "%.0f Hz") { v -> update { copy(bassBoostCenterFreq = v) } }

        section("TONE")
        switchControl("Tone", config.toneEnabled) { v -> update { copy(toneEnabled = v) } }
        dbSlider("Bass", -12f, 12f, config.bassToneDb) { v -> update { copy(bassToneDb = v) } }
        dbSlider("Mid", -12f, 12f, config.midToneDb) { v -> update { copy(midToneDb = v) } }
        dbSlider("Treble", -12f, 12f, config.trebleToneDb) { v -> update { copy(trebleToneDb = v) } }

        section("EQ GRÁFICO — 32 BANDAS")
        switchControl("EQ 32 bandas", config.eqEnabled) { v -> update { copy(eqEnabled = v, eqMode = DspConfig.EqMode.BANDS_32) } }
        EQ_FREQUENCIES.forEachIndexed { i, f ->
            dbSlider(freqLabel(f), -24f, 24f, config.gains32BandDb[i]) { v ->
                val a = config.gains32BandDb.copyOf(); a[i] = v
                update { copy(eqMode = DspConfig.EqMode.BANDS_32, gains32BandDb = a) }
            }
        }

        section("MDRC — MULTIBANDA")
        switchControl("MDRC", config.mdrcEnabled) { v -> update { copy(mdrcEnabled = v) } }
        floatSlider("Crossover Low", 20f, 500f, config.mdrcLowCrossoverHz, 1f, "%.0f Hz") { v -> update { copy(mdrcLowCrossoverHz = v) } }
        floatSlider("Crossover Mid", 100f, 5000f, config.mdrcMidCrossoverHz, 1f, "%.0f Hz") { v -> update { copy(mdrcMidCrossoverHz = v) } }
        floatSlider("Crossover High", 500f, 20000f, config.mdrcHighCrossoverHz, 10f, "%.0f Hz") { v -> update { copy(mdrcHighCrossoverHz = v) } }
        mdrcBand("LOW", config.mdrcLowBand) { b -> update { copy(mdrcLowBand = b) } }
        mdrcBand("MID", config.mdrcMidBand) { b -> update { copy(mdrcMidBand = b) } }
        mdrcBand("HIGH", config.mdrcHighBand) { b -> update { copy(mdrcHighBand = b) } }

        section("AUTO GAIN")
        switchControl("AutoGain", config.autoGainEnabled) { v -> update { copy(autoGainEnabled = v) } }
        dbSlider("Objetivo RMS", -60f, 0f, config.autoGainTargetRmsDb) { v -> update { copy(autoGainTargetRmsDb = v) } }

        section("LIMITER / HEADROOM")
        switchControl("Limiter / Headroom", config.headroomEnabled) { v -> update { copy(headroomEnabled = v) } }
        dbSlider("Headroom", -12f, 0f, config.headroomDb) { v -> update { copy(headroomDb = v) } }

        section("SPATIAL / VIRTUALIZER")
        switchControl("Spatial / Virtualizer", config.virtualizerEnabled) { v -> update { copy(virtualizerEnabled = v) } }
        percentSlider("Intensidad", config.virtualizerStrength) { v -> update { copy(virtualizerStrength = v) } }

        section("MASTER")
        dbSlider("Master Gain", -60f, 12f, config.masterGainDb) { v -> update { copy(masterGainDb = v) } }
        floatSlider("Balance L / R", -1f, 1f, config.balance, 0.01f, "%.2f") { v -> update { copy(balance = v) } }

        val note = TextView(this).apply {
            text = "Los controles se guardan automáticamente. El botón ACTIVAR DSP inicia la ruta externa DynamicsProcessing; la ruta PCM Oboe permanece separada."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(note)
    }

    private fun mdrcBand(name: String, b: DspConfig.BandCompressorConfig, onChange: (DspConfig.BandCompressorConfig) -> Unit) {
        subsection(name)
        dbSlider("Threshold", -60f, 0f, b.thresholdDb) { v -> onChange(b.copy(thresholdDb = v)) }
        floatSlider("Ratio", 1f, 20f, b.ratio, 0.1f, "%.1f:1") { v -> onChange(b.copy(ratio = v)) }
        floatSlider("Attack", 0.1f, 200f, b.attackMs, 0.1f, "%.1f ms") { v -> onChange(b.copy(attackMs = v)) }
        floatSlider("Release", 1f, 1000f, b.releaseMs, 1f, "%.0f ms") { v -> onChange(b.copy(releaseMs = v)) }
        dbSlider("Makeup Gain", -12f, 12f, b.makeupGainDb) { v -> onChange(b.copy(makeupGainDb = v)) }
    }

    private fun actionButtons() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(14) })
        button("ACTIVAR DSP") { SbDspForegroundService.startService(this@MainActivity, 0) }.also { row.addView(it, weightParams()) }
        button("DETENER DSP") {
            stopService(Intent(this, SbDspForegroundService::class.java))
        }.also { row.addView(it, weightParams()) }
    }

    private fun update(change: DspConfig.() -> DspConfig) {
        config = config.change().validate()
        DspConfigStore.save(this, config)
        if (DspConfigStore.isServiceActive(this)) {
            startService(Intent(this, SbDspForegroundService::class.java).setAction(SbDspForegroundService.ACTION_UPDATE_CONFIG))
        }
    }

    private fun switchControl(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val s = Switch(this).apply {
            text = label; isChecked = checked; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(4)); setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        root.addView(s, fullParams())
    }

    private fun dbSlider(label: String, min: Float, max: Float, value: Float, onChange: (Float) -> Unit) =
        floatSlider(label, min, max, value, 0.1f, "%.1f dB", onChange)

    private fun percentSlider(label: String, value: Float, onChange: (Float) -> Unit) =
        floatSlider(label, 0f, 1f, value, 0.01f, "%.0f %%") { onChange(it) }

    private fun floatSlider(label: String, min: Float, max: Float, value: Float, step: Float, format: String, onChange: (Float) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
        val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        val seek = SeekBar(this)
        val steps = ((max - min) / step).roundToInt().coerceAtLeast(1)
        seek.max = steps
        seek.progress = ((value.coerceIn(min, max) - min) / step).roundToInt().coerceIn(0, steps)
        fun valueAt(p: Int) = (min + p * step).coerceIn(min, max)
        fun render(v: Float) { tv.text = String.format(Locale.US, "$label: $format", v) }
        render(valueAt(seek.progress))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { val v = valueAt(p); render(v); if (fromUser) onChange(v) }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        row.addView(tv, fullParams()); row.addView(seek, fullParams())
        root.addView(row, fullParams())
    }

    private fun title(text: String) { root.addView(TextView(this).apply { this.text=text; textSize=28f; setTextColor(Color.WHITE); setPadding(0,0,0,dp(6)) }, fullParams()) }
    private fun subtitle(text: String) { root.addView(TextView(this).apply { this.text=text; textSize=13f; setTextColor(Color.LTGRAY); setPadding(0,0,0,dp(14)) }, fullParams()) }
    private fun section(text: String) { root.addView(TextView(this).apply { this.text=text; textSize=18f; setTextColor(Color.WHITE); setPadding(0,dp(18),0,dp(8)) }, fullParams()) }
    private fun subsection(text: String) { root.addView(TextView(this).apply { this.text=text; textSize=16f; setTextColor(Color.LTGRAY); setPadding(dp(4),dp(10),0,dp(2)) }, fullParams()) }
    private fun button(text: String, click: () -> Unit) = Button(this).apply { this.text=text; textSize=12f; setOnClickListener { click() }; minHeight=0; minimumHeight=0 }
    private fun freqLabel(f: Float): String = if (f >= 1000f) { val k=f/1000f; if (k == k.toInt().toFloat()) "${k.toInt()} kHz" else "%.1f kHz".format(Locale.US,k) } else if (f == f.toInt().toFloat()) "${f.toInt()} Hz" else "%.1f Hz".format(Locale.US,f)
    private fun fullParams() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weightParams() = LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd=dp(6) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
