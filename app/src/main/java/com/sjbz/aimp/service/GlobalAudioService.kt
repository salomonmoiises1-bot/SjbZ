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
 * GlobalAudioService — Foreground service para procesado system-wide.
 *
 * Session 0 (mezcla global) + sesiones dinámicas de terceros.
 * Usa [SjbzAudioEngine] como única fuente de DSP para evitar motores fantasmas.
 */
class GlobalAudioService : Service() {

    companion object {
        const val CHANNEL_ID = "sjbz_global_audio_channel"
        const val NOTIFICATION_ID = 2836

        const val ACTION_START = "com.sjbz.aimp.ACTION_START_GLOBAL_AUDIO"
        const val ACTION_STOP = "com.sjbz.aimp.ACTION_STOP_GLOBAL_AUDIO"
        const val ACTION_TOGGLE = "com.sjbz.aimp.ACTION_TOGGLE_GLOBAL_AUDIO"

        fun start(context: Context) {
            val intent = Intent(context, GlobalAudioService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GlobalAudioService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun toggle(context: Context) {
            val intent = Intent(context, GlobalAudioService::class.java).apply { action = ACTION_TOGGLE }
            context.startService(intent)
        }
    }

    private lateinit var audioSessionManager: GlobalAudioSessionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SjbzAudioEngine.ensureInitialized()
        audioSessionManager = GlobalAudioSessionManager.getInstance(this)
        createNotificationChannel()

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
                SjbzAudioEngine.setMasterEnabled(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                val newState =!audioSessionManager.isGlobalAudioEnabled
                audioSessionManager.setGlobalAudioEnabled(newState)
                SjbzAudioEngine.setMasterEnabled(newState)
                if (newState) startForegroundServiceWithNotification()
                else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_START, null -> {
                audioSessionManager.setGlobalAudioEnabled(true)
                SjbzAudioEngine.setMasterEnabled(true)
                // Re-sincroniza MDRC por si EqActivity lo cambió mientras el servicio estaba muerto
                val p = audioSessionManager.currentProfile
                SjbzAudioEngine.syncMdrcFromGlobal(
                    p.mdrcEnabled, p.mdrcGains, p.mdrcThresholdDb, p.mdrcRatio
                )
                startForegroundServiceWithNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // No apagamos el motor acá: EqActivity puede seguir usándolo en foreground
        super.onDestroy()
    }

    private fun startForegroundServiceWithNotification() {
        val cur = audioSessionManager.currentProfile
        val n = buildNotification(cur.appName, cur.presetName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(appName: String, presetName: String) {
        if (audioSessionManager.isGlobalAudioEnabled) {
            val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            m.notify(NOTIFICATION_ID, buildNotification(appName, presetName))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "SB-Z Ecualizador Global",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "32 bandas, Limiter, AutoGain, MDRC — Session 0"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
               .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(appName: String, presetName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpen = PendingIntent.getActivity(this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val eqIntent = Intent(this, EqActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pEq = PendingIntent.getActivity(this, 3, eqIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, GlobalAudioService::class.java).apply { action = ACTION_STOP }
        val pStop = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val flags = buildString {
            if (audioSessionManager.isLimiterEnabled) append(" • Limiter")
            if (audioSessionManager.isAutoGainEnabled) append(" • AutoGain")
            if (audioSessionManager.currentProfile.mdrcEnabled) append(" • MDRC")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
           .setSmallIcon(R.drawable.ic_equalizer)
           .setContentTitle("SB-Z Global DSP Activo")
           .setContentText("$appName ($presetName)$flags")
           .setSubText("Session 0 — mezcla global")
           .setContentIntent(pOpen)
           .addAction(R.drawable.ic_equalizer, "Ecualizador", pEq)
           .addAction(R.drawable.ic_stop, "Detener", pStop)
           .setOngoing(true)
           .setPriority(NotificationCompat.PRIORITY_LOW)
           .build()
    }
}
