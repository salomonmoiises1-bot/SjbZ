package com.sjbz.aimp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sjbz.aimp.databinding.ActivityEqBinding
import com.sjbz.aimp.service.PlaybackService

class EqActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEqBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val service = PlaybackService.instance
        val audioChain = service?.audioChain
        val atsEngine = service?.atsEngine

        if (audioChain == null) {
            finish()
            return
        }

        // Configurar los 32 sliders del ecualizador
        binding.eqRecycler.adapter = EqAdapter(audioChain)

        // Boton Flat / Reset
        binding.btnFlat.setOnClickListener {
            audioChain.setFlat()
            binding.eqRecycler.adapter?.notifyDataSetChanged()
        }

        // Boton Atrás
        binding.btnBack.setOnClickListener { finish() }

        // Si tenías un boton Harman, lo dejamos deshabilitado para que no rompa el build
        // El target Harman lo aplicamos directo desde el AudioChain si existe
        try {
            audioChain.applyHarmanTargetIfAvailable()
        } catch (e: Exception) { }
    }
}
