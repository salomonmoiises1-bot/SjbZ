package com.sjbz.aimp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import com.sjbz.aimp.audio.EqualizerProcessor
import com.sjbz.aimp.audio.PresetManager
import com.sjbz.aimp.service.PlaybackService

class EqActivity : AppCompatActivity() {

    private var playbackService: PlaybackService? = null
    private var isBound = false
    private lateinit var presetManager: PresetManager

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            setupEqualizerUI()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            playbackService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presetManager = PresetManager(this)

        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun setupEqualizerUI() {
        val service = playbackService ?: return
        val eq = service.equalizerProcessor

        // Ejemplo de lectura usando getBandGain
        val gainBandZero = eq.getBandGain(0)

        // Ejemplo de escritura usando setBandGain
        eq.setBandGain(0, gainBandZero)

        // Uso de la propiedad app desde el servicio
        val appContext = service.app
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
