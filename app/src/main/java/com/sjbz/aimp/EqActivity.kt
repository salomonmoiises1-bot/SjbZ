package com.sjbz.aimp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sjbz.aimp.audio.PresetManager
import com.sjbz.aimp.service.PlaybackService

class EqActivity : AppCompatActivity() {
    private val builtIn = arrayOf("Flat", "Rock", "Pop", "Dance", "Hip-Hop", "Jazz", "Harman", "Bass Boost")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)
        val spinner = findViewById<Spinner>(R.id.spinnerPresets)
        val btnSave = findViewById<Button>(R.id.btnSavePreset)
        val service = PlaybackService.instance

        val allPresets = (builtIn.toList() + PresetManager.getAllNames(this)).distinct()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, allPresets)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val name = allPresets[pos]; val bands = PresetManager.getBuiltIn(name) // aqui aplicas a tu audioChain sin romper motor
                try { service?.audioChain?.setBands(bands)?: service?.audioChain?.applyBands(bands) } catch(_: Exception){}
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnSave?.setOnClickListener {
            val current = spinner.selectedItem as String
            val bands = PresetManager.getBuiltIn(current)
            PresetManager.savePreset(this, current, bands)
            Toast.makeText(this, "Preset $current guardado", Toast.LENGTH_SHORT).show()
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.eqToolbar)?.setNavigationOnClickListener { finish() }
    }
}
