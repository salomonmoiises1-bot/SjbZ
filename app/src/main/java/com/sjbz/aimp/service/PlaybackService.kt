package com.sjbz.aimp.service
import androidx.media3.session.MediaSession
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSessionService
import com.sjbz.aimp.MainActivity
import com.sjbz.aimp.R
import com.sjbz.aimp.audio.ATS2835PEngine
import com.sjbz.aimp.audio.AudioChain
import com.sjbz.aimp.model.Track
import com.sjbz.aimp.utils.BluetoothDetector

class PlaybackService : MediaSessionService() {
    companion object {
        const val CHANNEL_ID = "sjbz_playback_channel"
        const val NOTIFICATION_ID = 2835
        const val ACTION_TOGGLE = "com.sjbz.aimp.ACTION_TOGGLE"
        const val ACTION_PREV = "com.sjbz.aimp.ACTION_PREV"
        const val ACTION_NEXT = "com.sjbz.aimp.ACTION_NEXT"
        const val ACTION_STOP = "com.sjbz.aimp.ACTION_STOP"
        var instance: PlaybackService? = null; private set
    }
    inner class LocalBinder : Binder() { fun getService(): PlaybackService = this@PlaybackService }
    private val binder = LocalBinder()
    lateinit var player: ExoPlayer; private set
    private var mediaSession: MediaSession? = null
    lateinit var atsEngine: ATS2835PEngine; private set
    lateinit var audioChain: AudioChain; private set
    private lateinit var bluetoothDetector: BluetoothDetector
    private val playlist = mutableListOf<Track>()
    private var currentTrackIndex = -1
    var isLoopPlaylistEnabled: Boolean = true
    private var wakeLock: PowerManager.WakeLock? = null
    var onTrackChangedListener: ((Track?, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SjbZ:PlaybackWakeLock").apply { setReferenceCounted(false) }
        } catch (_: Exception) {}
        atsEngine = ATS2835PEngine(this)
        audioChain = AudioChain(this, atsEngine)
        com.sjbz.aimp.audio.GlobalAudioSessionManager.getInstance(this).syncAudioEffects(atsEngine.equalizer, atsEngine.mdrc, atsEngine.limiter)
        bluetoothDetector = BluetoothDetector(this) { atsEngine.onBluetoothStatusChanged(it) }
        bluetoothDetector.start()
        val audioAttributes = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
        player = ExoPlayer.Builder(this).setAudioAttributes(audioAttributes, true).setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        audioChain.bindPlayer(player)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_ENDED) onSongEnded(); handleWakeLockState(player.isPlaying); updateNotification(player.isPlaying); onPlaybackStateChangedListener?.invoke(player.isPlaying) }
            override fun onIsPlayingChanged(p: Boolean) { handleWakeLockState(p); updateNotification(p); onPlaybackStateChangedListener?.invoke(p) }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { updateNotification(player.isPlaying); onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex) }
        })
        val sid = player.audioSessionId; if (sid!= C.AUDIO_SESSION_ID_UNSET) audioChain.attachAudioSession(sid)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(pi).build()
        createNotificationChannel()
        startForegroundWithNotification(player.isPlaying)
    }
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int { super.onStartCommand(i,f,s); when(i?.action){ ACTION_TOGGLE->togglePlayPause(); ACTION_PREV->playPrevious(); ACTION_NEXT->playNext(); ACTION_STOP->stop() }; return START_STICKY }
    private fun handleWakeLockState(p: Boolean){ try{ if(p){ if(wakeLock?.isHeld==false) wakeLock?.acquire(7200000L) } else { if(wakeLock?.isHeld==true) wakeLock?.release() } }catch(_: Exception){} }
    private fun createNotificationChannel(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            val ch = NotificationChannel(CHANNEL_ID,"SjbZ Reproducción de Audio",NotificationManager.IMPORTANCE_LOW).apply{
                description="Reproducción continua de música en segundo plano y pantalla bloqueada con DSP ATS2835P"; setShowBadge(false); lockscreenVisibility=Notification.VISIBILITY_PUBLIC; enableLights(false); enableVibration(false); setSound(null,null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
    private fun buildNotification(isPlaying: Boolean): Notification {
        val cur = getCurrentTrack()
        val title = cur?.title?.ifBlank{ cur.uri.toString().substringAfterLast("/").substringBeforeLast(".") }?:"SjbZ Reproductor Hi-Res"
        val artist = cur?.artist?.ifBlank{ "ATS-2835P DSP Audio Engine" }?:"ATS-2835P DSP Audio Engine"
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val prev = PendingIntent.getService(this,1,Intent(this,PlaybackService::class.java).apply{action=ACTION_PREV},PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val tog = PendingIntent.getService(this,2,Intent(this,PlaybackService::class.java).apply{action=ACTION_TOGGLE},PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val next = PendingIntent.getService(this,3,Intent(this,PlaybackService::class.java).apply{action=ACTION_NEXT},PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this,4,Intent(this,PlaybackService::class.java).apply{action=ACTION_STOP},PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val icon = if(isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        return NotificationCompat.Builder(this,CHANNEL_ID)
          .setContentTitle(title).setContentText(artist)
          .setSubText(if(atsEngine.isBluetoothConnected)"ATS-2835P • Bluetooth A2DP • 3 m" else "ATS-2835P • Hi-Res Direct • 3 m")
          .setSmallIcon(R.drawable.ic_music_note).setContentIntent(open)
          .setOngoing(isPlaying).setOnlyAlertOnce(true)
          .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
          .addAction(R.drawable.ic_skip_previous,"Anterior",prev)
          .addAction(icon,if(isPlaying)"Pausar" else "Reproducir",tog)
          .addAction(R.drawable.ic_skip_next,"Siguiente",next)
          .addAction(R.drawable.ic_stop,"Detener",stop)
          .build()
    }
    private fun startForegroundWithNotification(p:Boolean){ val n=buildNotification(p); if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTIFICATION_ID,n) }
    private fun updateNotification(p:Boolean){ try{ (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIFICATION_ID,buildNotification(p)) }catch(_:Exception){} }
    override fun onGetSession(c: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onBind(i: Intent?): IBinder { super.onBind(i); return binder }
    fun setPlaylist(tracks: List<Track>, startIndex: Int = 0, startPlaying: Boolean = true){
        playlist.clear(); playlist.addAll(tracks)
        val items = tracks.map{ t->
            val ct = if(t.title.equals("<unknown>",true)||t.title.isBlank()) t.uri.toString().substringAfterLast("/").substringBeforeLast(".") else t.title
            val ca = if(t.artist.equals("<unknown>",true)||t.artist.isBlank()) "SjbZ • ATS-2835P" else t.artist
            MediaItem.Builder().setUri(t.uri).setMediaId(t.id.toString()).setMediaMetadata(MediaMetadata.Builder().setTitle(ct).setArtist(ca).setAlbumTitle(t.album?:"SjbZ Player").build()).build()
        }
        player.setMediaItems(items); player.prepare()
        if(startIndex in playlist.indices) playTrackAtIndex(startIndex,startPlaying)
    }
    fun setPlaylist(tracks: List<Track>){ setPlaylist(tracks,0,true) }
    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean){ setPlaylist(tracks,0,startPlaying) }
    fun setPlaylist(tracks: List<Track>, startIndex: Int){ setPlaylist(tracks,startIndex,true) }
    fun setPlaybackSpeed(s: Float){ player.playbackParameters = PlaybackParameters(s) }
    fun playTrackAtIndex(index: Int, startPlaying: Boolean = true){
        if(index!in playlist.indices) return; currentTrackIndex=index; player.seekToDefaultPosition(index)
        if(startPlaying){ player.play(); audioChain.startFadeIn() }
        val sid=player.audioSessionId; if(sid!=C.AUDIO_SESSION_ID_UNSET) audioChain.attachAudioSession(sid)
        updateNotification(startPlaying); onTrackChangedListener?.invoke(getCurrentTrack(),currentTrackIndex)
    }
    fun togglePlayPause(){ if(player.isPlaying) player.pause() else player.play() }
    fun play(){ player.play() }
    fun pause(){ player.pause() }
    fun stopPlayback(){ player.stop(); player.seekTo(0); handleWakeLockState(false); updateNotification(false) }
    fun stop(){ stopPlayback() }
    fun playNext(){ if(playlist.isEmpty()) return; val n=currentTrackIndex+1; if(n<playlist.size) playTrackAtIndex(n,true) else if(isLoopPlaylistEnabled) playTrackAtIndex(0,true) }
    fun playPrevious(){ if(playlist.isEmpty()) return; if(player.currentPosition>3000){ player.seekTo(0); return }; val p=currentTrackIndex-1; if(p>=0) playTrackAtIndex(p,true) else if(isLoopPlaylistEnabled) playTrackAtIndex(playlist.size-1,true) }
    private fun onSongEnded(){ if(playlist.isEmpty()) return; val n=currentTrackIndex+1; if(n<playlist.size) playTrackAtIndex(n,true) else if(isLoopPlaylistEnabled) playTrackAtIndex(0,true) }
    fun getCurrentTrack(): Track? = if(currentTrackIndex in playlist.indices) playlist[currentTrackIndex] else null
    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex
    override fun onTaskRemoved(rootIntent: Intent?) { updateNotification(player.isPlaying) }
    override fun onDestroy(){
        if(instance==this) instance=null
        try{ if(wakeLock?.isHeld==true) wakeLock?.release() }catch(_:Exception){}
        bluetoothDetector.stop(); audioChain.release()
        mediaSession?.run{ player.release(); release(); mediaSession=null }
        super.onDestroy()
    }
}
