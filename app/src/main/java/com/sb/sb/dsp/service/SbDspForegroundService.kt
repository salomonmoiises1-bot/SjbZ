package com.sb.dsp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.sb.dsp.DspConfig
import com.sb.dsp.DspConfigStore
import com.sb.dsp.DspEngine
import com.sb.dsp.R

class SbDspForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "sb_dsp_audio_pipeline_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.sb.dsp.ACTION_START"
        const val ACTION_STOP = "com.sb.dsp.ACTION_STOP"
        const val ACTION_UPDATE_CONFIG = "com.sb.dsp.ACTION_UPDATE_CONFIG"
        const val ACTION_TOGGLE_MASTER = "com.sb.dsp.ACTION_TOGGLE_MASTER"
        const val EXTRA_SESSION_ID = "session_id"

        fun startService(context: Context, sessionId: Int = DspEngine.GLOBAL_SESSION_ID) {
            val intent = Intent(context, SbDspForegroundService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateConfig(context: Context, config: DspConfig) {
            DspConfigStore.save(context, config)
            val intent = Intent(context, SbDspForegroundService::class.java).setAction(ACTION_UPDATE_CONFIG)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) { context.stopService(Intent(context, SbDspForegroundService::class.java)) }
    }

    private val engine = DspEngine(this)

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                engine.updateConfig(DspConfigStore.load(this))
                engine.start(DspEngine.GLOBAL_SESSION_ID)
            }
            ACTION_UPDATE_CONFIG -> engine.updateConfig(DspConfigStore.load(this))
            ACTION_TOGGLE_MASTER -> {
                val current = DspConfigStore.load(this)
                val next = current.copy(masterEnabled = !current.masterEnabled).validate()
                DspConfigStore.save(this, next)
                engine.updateConfig(next)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
            ACTION_STOP -> { engine.release(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() { engine.release(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val config = DspConfigStore.load(this)
        val toggleIntent = Intent(this, SbDspForegroundService::class.java).setAction(ACTION_TOGGLE_MASTER)
        val togglePending = PendingIntent.getService(
            this, 31416, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (config.masterEnabled) "DSP del sistema • ACTIVO" else "DSP del sistema • BYPASS")
            .setSmallIcon(R.drawable.ic_dsp_notification)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    if (config.masterEnabled) "Desactivar motor" else "Activar motor",
                    togglePending
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SB DSP", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
