package com.sjbz.aimp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sjbz.aimp.EqActivity
import com.sjbz.aimp.MainActivity
import com.sjbz.aimp.R
import com.sjbz.aimp.audio.GlobalAudioSessionManager
import com.sjbz.aimp.audio.SjbzAudioEngine

/**
 * GlobalAudioService: Foreground Service that processes audio system-wide.
 * Captures audio mix on audioSessionId = 0 (and dynamic third-party app sessions),
 * applying the 32-Band Equalizer, Anti-Clipping Limiter, and AutoGain leveler.
 *
 * Runs as a foreground service with low battery overhead and persistent notification.
 */
class GlobalAudioService : Service() {

    companion object {
        const val CHANNEL_ID = "sjbz_global_audio_channel"
        const val NOTIFICATION_ID = 2836

        const val ACTION_START = "com.sjbz.aimp.ACTION_START_GLOBAL_AUDIO"
        const val ACTION_STOP = "com.sjbz.aimp.ACTION_STOP_GLOBAL_AUDIO"
        const val ACTION_TOGGLE = "com.sjbz.aimp.ACTION_TOGGLE_GLOBAL_AUDIO"

        fun start(context: Context) {
            val intent = Intent(context, GlobalAudioService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GlobalAudioService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun toggle(context: Context) {
            val intent = Intent(context, GlobalAudioService::class.java).apply {
                action = ACTION_TOGGLE
            }
            context.startService(intent)
        }
    }

    private lateinit var audioSessionManager: GlobalAudioSessionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioSessionManager = GlobalAudioSessionManager.getInstance(this)
        createNotificationChannel()

        // No pisar el listener de las Activities: encadenarlo
        val prev = audioSessionManager.onProfileChangedListener
        audioSessionManager.onProfileChangedListener = { profile ->
            prev?.invoke(profile)
            updateNotification(profile.appName, profile.presetName)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                audioSessionManager.setGlobalAudioEnabled(false)
                SjbzAudioEngine.processor.masterEnabled = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                val newState = !audioSessionManager.isGlobalAudioEnabled
                audioSessionManager.setGlobalAudioEnabled(newState)
                SjbzAudioEngine.processor.masterEnabled = newState
                if (newState) {
                    startForegroundServiceWithNotification()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_START, null -> {
                audioSessionManager.setGlobalAudioEnabled(true)
                SjbzAudioEngine.processor.masterEnabled = true
                startForegroundServiceWithNotification()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val curProfile = audioSessionManager.currentProfile
        val notification = buildNotification(curProfile.appName, curProfile.presetName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(appName: String, presetName: String) {
        if (audioSessionManager.isGlobalAudioEnabled) {
            val notification = buildNotification(appName, presetName)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SB-Z Ecualizador Global del Sistema",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Procesamiento de audio global (32 Bandas, Limiter, AutoGain) para Spotify, YouTube y el sistema"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(appName: String, presetName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpen = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tap en la notificación abre el estudio completo si querés afinar
        val eqIntent = Intent(this, EqActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pEq = PendingIntent.getActivity(
            this,
            3,
            eqIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GlobalAudioService::class.java).apply {
            action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val limiterStatus = if (audioSessionManager.isLimiterEnabled) " • Limiter ON" else ""
        val autoGainStatus = if (audioSessionManager.isAutoGainEnabled) " • AutoGain ON" else ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_equalizer)
            .setContentTitle("SB-Z Ecualizador Global Activo")
            .setContentText("Perfil: $appName ($presetName)$limiterStatus$autoGainStatus")
            .setSubText("Procesando mezcla global del sistema (Session 0)")
            .setContentIntent(pOpen)
            .addAction(R.drawable.ic_equalizer, "Ecualizador", pEq)
            .addAction(R.drawable.ic_stop, "Desactivar", pStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
