package com.sjbz.aimp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.sjbz.aimp.service.PlaybackService

class EqActivity : AppCompatActivity() {

    private val presets = arrayOf("Flat", "Rock", "Pop", "Dance", "Hip-Hop", "Jazz", "Harman", "Bass Boost")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_eq)

        val service = PlaybackService.instance
        val audioChain = service?.audioChain

        val spinner = findViewById<Spinner>(R.id.spinnerPresets)
        val switchEq = findViewById<SwitchCompat>(R.id.switchEqEnabled)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (audioChain == null) return
                when (presets[position]) {
                    "Flat" -> audioChain.setFlat()
                    "Harman" -> {
                        try {
                            audioChain.applyHarmanTargetIfAvailable()
                        } catch (e: Exception) {
                            try { audioChain.applyPreset("harman") } catch (_: Exception) {}
                        }
                    }
                    else -> {
                        try { audioChain.applyPreset(presets[position].lowercase()) } catch (_: Exception) {}
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchEq?.setOnCheckedChangeListener { _, isChecked ->
            audioChain?.setEnabled(isChecked)
        }

        // Botón atrás de la toolbar
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.eqToolbar)?.setNavigationOnClickListener {
            finish()
        }
    }
}
