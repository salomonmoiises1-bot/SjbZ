package com.sb.dsp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sb.dsp.DspConfig
import com.sb.dsp.PcmAudioPipeline
import com.sb.dsp.R

class SbDspForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "sb_dsp_audio_pipeline_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.sb.dsp.ACTION_START"
        const val ACTION_STOP = "com.sb.dsp.ACTION_STOP"
        const val ACTION_TOGGLE_MASTER = "com.sb.dsp.ACTION_TOGGLE_MASTER"

        @Volatile
        var activePipeline: PcmAudioPipeline? = null
            private set
    }

    private var pipeline = PcmAudioPipeline(48000, 2)

    override fun onCreate() {
        super.onCreate()
        activePipeline = pipeline
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SB Audio DSP Engine")
            .setContentText("Motor DSP Activo • 10/20/32 Bandas Constant-Q")
            .setSmallIcon(R.drawable.ic_dsp_notification)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SB DSP Engine", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}