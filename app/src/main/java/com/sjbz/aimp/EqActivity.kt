package com.sjbz.aimp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sjbz.aimp.audio.PresetManager
import com.sjbz.aimp.audio.EqualizerProcessor
import com.sjbz.aimp.service.PlaybackService

class EqActivity : AppCompatActivity() {

    private lateinit var presetManager: PresetManager
    private val eqProcessor = EqualizerProcessor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)

        presetManager = PresetManager(this)

        val spinner = findViewById<Spinner>(R.id.spinnerPresets)
        val btnSave = findViewById<Button>(R.id.btnSavePreset)
        val service = PlaybackService.instance

        // Usa tu PresetManager real, que ya incluye Harman
        val allPresets = presetManager.getAllPresets()
        val allNames = allPresets.map { it.name }.distinct()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, allNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Seleccionar el activo
        val activeName = presetManager.getActivePresetName()
        val activePos = allNames.indexOf(activeName).takeIf { it >= 0 }?: 0
        spinner.setSelection(activePos)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val preset = allPresets[pos]
                presetManager.setActivePresetName(preset.name)
                eqProcessor.loadFromPreset(preset)
                try {
                    // Aplica a tu cadena de audio sin romper ATS2835P
                    service?.audioChain?.setBands(preset.bandGains.toFloatArray())
                    // fallback por si tu AudioChain usa otro nombre
                    service?.atsEngine?.setEqualizer(preset.bandGains)
                } catch (_: Exception) {
                    try { service?.audioChain?.applyBands(preset.bandGains.toFloatArray()) } catch(_: Exception){}
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnSave?.setOnClickListener {
            val pos = spinner.selectedItemPosition
            if (pos in allPresets.indices) {
                val currentPreset = allPresets[pos]
                val success = presetManager.saveCustomPreset(currentPreset)
                if (success) {
                    Toast.makeText(this, "Preset ${currentPreset.name} guardado", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No se pudo guardar", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.eqToolbar)?.setNavigationOnClickListener { finish() }
    }
}
