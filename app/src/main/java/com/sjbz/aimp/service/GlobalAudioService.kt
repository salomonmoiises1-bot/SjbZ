package com.sjbz.aimp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sjbz.aimp.MainActivity
import com.sjbz.aimp.R
import com.sjbz.aimp.audio.GlobalAudioSessionManager

class GlobalAudioService : Service() {
    companion object {
        const val CHANNEL_ID = "sjbz_global_audio_channel"
        const val NOTIFICATION_ID = 2836
        const val ACTION_START = "com.sjbz.aimp.ACTION_START_GLOBAL_AUDIO"
        const val ACTION_STOP = "com.sjbz.aimp.ACTION_STOP_GLOBAL_AUDIO"
        @Volatile var isServiceRunning = false
        @JvmStatic var isGlobalAudioEnabled get()=isServiceRunning; set(v){ isServiceRunning=v }
        fun start(c: Context){ val i=Intent(c, GlobalAudioService::class.java).apply{ action=ACTION_START }; if(Build.VERSION.SDK_INT>=26) c.startForegroundService(i) else c.startService(i) }
        fun stop(c: Context){ c.startService(Intent(c, GlobalAudioService::class.java).apply{ action=ACTION_STOP }) }
    }
    private lateinit var mgr: GlobalAudioSessionManager
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        mgr = GlobalAudioSessionManager.getInstance(this)
        createChannel()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action){
            ACTION_STOP -> { releaseAll(); isServiceRunning=false; mgr.setGlobalAudioEnabled(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            else -> { isServiceRunning=true; mgr.setGlobalAudioEnabled(true); startFg(); attachAll() }
        }
        return START_STICKY
    }
    private fun attachAll(){
        try{
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)!= android.content.pm.PackageManager.PERMISSION_GRANTED){ Log.e("Service","FALTA RECORD_AUDIO"); return }
            releaseAll()
            eq = Equalizer(0,0).apply{ enabled=true }
            bass = BassBoost(0,0).apply{ enabled=mgr.isBassBoostEnabled }
            virt = Virtualizer(0,0).apply{ enabled=mgr.isVirtualizerEnabled }
            loud = LoudnessEnhancer(0).apply{ enabled=mgr.isLimiterEnabled || mgr.isAutoGainEnabled }
            mgr.attachSystemEqualizer(eq!!); mgr.attachSystemBassBoost(bass!!); mgr.attachSystemVirtualizer(virt!!); mgr.attachSystemLoudness(loud!!)
            mgr.reapplyAllParams()
            Log.d("Service","TODO enganchado a 0,0: EQ+Bass+Virt+Loud")
        }catch(e: Exception){ Log.e("Service","attach fail: ${e.message}") }
    }
    private fun releaseAll(){
        try{ eq?.enabled=false; eq?.release() }catch(_:Exception){}; eq=null
        try{ bass?.enabled=false; bass?.release() }catch(_:Exception){}; bass=null
        try{ virt?.enabled=false; virt?.release() }catch(_:Exception){}; virt=null
        try{ loud?.enabled=false; loud?.release() }catch(_:Exception){}; loud=null
    }
    override fun onDestroy(){ releaseAll(); isServiceRunning=false; super.onDestroy() }
    private fun startFg(){
        val n=buildNotification()
        if (Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTIFICATION_ID, n)
    }
    private fun createChannel(){
        if (Build.VERSION.SDK_INT>=26){
            val ch=NotificationChannel(CHANNEL_ID,"SB-Z Global", NotificationManager.IMPORTANCE_LOW).apply{ setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
    private fun buildNotification()=NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_equalizer).setContentTitle("SB-Z Global Activo").setContentText("EQ 32 bandas + Bass + Virtualizer + Limiter en Session 0").setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
}
