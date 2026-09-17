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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {
                try { context.startService(intent) } catch (_: Exception) {}
            }
        }
        fun toggle(context: Context) {
            val intent = Intent(context, GlobalAudioService::class.java).apply { action = ACTION_TOGGLE }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    private lateinit var audioSessionManager: GlobalAudioSessionManager
    private var prevProfileListener: ((com.sjbz.aimp.model.AppProfile) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try { SjbzAudioEngine.ensureInitialized() } catch (_: Exception) {}
        audioSessionManager = GlobalAudioSessionManager.getInstance(this)
        createNotificationChannel()
        prevProfileListener = audioSessionManager.onProfileChangedListener
        audioSessionManager.onProfileChangedListener = { profile ->
            try { prevProfileListener?.invoke(profile) } catch (_: Exception) {}
            try {
                val appName = try { profile.appName } catch (_: Exception) { "Global" }
                val presetName = try { profile.presetName } catch (_: Exception) { "Studio" }
                updateNotification(appName, presetName)
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { SjbzAudioEngine.ensureInitialized() } catch (_: Exception) {}
        if (!::audioSessionManager.isInitialized) {
            audioSessionManager = GlobalAudioSessionManager.getInstance(this)
        }
        when (intent?.action) {
            ACTION_STOP -> {
                try {
                    audioSessionManager.setGlobalAudioEnabled(false)
                    SjbzAudioEngine.setMasterEnabled(false)
                } catch (_: Exception) {}
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                    else @Suppress("DEPRECATION") stopForeground(true)
                } catch (_: Exception) {}
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                val newState = try { !audioSessionManager.isGlobalAudioEnabled } catch (_: Exception) { false }
                try {
                    audioSessionManager.setGlobalAudioEnabled(newState)
                    SjbzAudioEngine.setMasterEnabled(newState)
                } catch (_: Exception) {}
                if (newState) {
                    startForegroundServiceWithNotification()
                } else {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                        else @Suppress("DEPRECATION") stopForeground(true)
                    } catch (_: Exception) {}
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_START, null -> {
                try {
                    audioSessionManager.setGlobalAudioEnabled(true)
                    SjbzAudioEngine.setMasterEnabled(true)
                    val p = audioSessionManager.currentProfile
                    val gains = try { p.mdrcGains.toFloatArray() } catch (_: Exception) { FloatArray(5) }
                    val mdrcEn = try { p.mdrcEnabled } catch (_: Exception) { true }
                    SjbzAudioEngine.syncMdrcFromGlobal(mdrcEn, gains, -14f, 3f)
                } catch (_: Exception) {}
                startForegroundServiceWithNotification()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            audioSessionManager.onProfileChangedListener = prevProfileListener
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundServiceWithNotification() {
        val cur = try { audioSessionManager.currentProfile } catch (_: Exception) { null }
        val appName = try { cur?.appName ?: "Global" } catch (_: Exception) { "Global" }
        val presetName = try { cur?.presetName ?: "Studio" } catch (_: Exception) { "Studio" }
        val n = buildNotification(appName, presetName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (_: Exception) {
            try { startForeground(NOTIFICATION_ID, n) } catch (_: Exception) {}
        }
    }

    private fun updateNotification(appName: String, presetName: String) {
        try {
            if (audioSessionManager.isGlobalAudioEnabled) {
                val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                m.notify(NOTIFICATION_ID, buildNotification(appName, presetName))
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
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
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(appName: String, presetName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpen = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val eqIntent = Intent(this, EqActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pEq = PendingIntent.getActivity(
            this, 3, eqIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, GlobalAudioService::class.java).apply { action = ACTION_STOP }
        val pStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val flagsStr = buildString {
            try {
                if (audioSessionManager.isLimiterEnabled) append(" • Limiter")
                if (audioSessionManager.isAutoGainEnabled) append(" • AutoGain")
                if (audioSessionManager.currentProfile.mdrcEnabled) append(" • MDRC")
            } catch (_: Exception) {}
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_equalizer)
            .setContentTitle("SB-Z Global DSP Activo")
            .setContentText("$appName ($presetName)$flagsStr")
            .setSubText("Session 0 — mezcla global")
            .setContentIntent(pOpen)
            .addAction(R.drawable.ic_equalizer, "Ecualizador", pEq)
            .addAction(R.drawable.ic_stop, "Detener", pStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
