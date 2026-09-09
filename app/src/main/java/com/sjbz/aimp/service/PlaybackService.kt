package com.sjbz.aimp.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.sjbz.aimp.audio.EqualizerProcessor

class PlaybackService : Service() {

    private val binder = LocalBinder()
    val equalizerProcessor = EqualizerProcessor()

    // Propiedad agregada para resolver la referencia 'app'
    val app: Context
        get() = applicationContext

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
