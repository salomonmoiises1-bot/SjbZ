package com.sjbz.aimp.service

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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sjbz.aimp.MainActivity
import com.sjbz.aimp.audio.ATS2835PEngine
import com.sjbz.aimp.audio.AudioChain
import com.sjbz.aimp.audio.GlobalAudioSessionManager
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
    lateinit var player: ExoPlayer
    lateinit var atsEngine: ATS2835PEngine
    lateinit var audioChain: AudioChain
    private var mediaSession: MediaSession? = null
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
        } catch (e: Exception) {}
        try {
            atsEngine = ATS2835PEngine(this)
            audioChain = AudioChain(this, atsEngine)
            GlobalAudioSessionManager.getInstance(this).syncAudioEffects(atsEngine.equalizer, atsEngine.mdrc, atsEngine.limiter)
            bluetoothDetector = BluetoothDetector(this) { atsEngine.onBluetoothStatusChanged(it) }
            bluetoothDetector.start()
        } catch (e: Exception) { e.printStackTrace() }
        val audioAttributes = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
        player = ExoPlayer.Builder(this).setAudioAttributes(audioAttributes, true).setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        try { audioChain.bindPlayer(player) } catch (e: Exception) {}
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onSongEnded(); handleWakeLockState(player.isPlaying); updateNotification(player.isPlaying); onPlaybackStateChangedListener?.invoke(player.isPlaying) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { handleWakeLockState(isPlaying); updateNotification(isPlaying); onPlaybackStateChangedListener?.invoke(isPlaying) }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { updateNotification(player.isPlaying); onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex) }
        })
        try {
            val sid = player.audioSessionId
            if (sid!= C.AUDIO_SESSION_ID_UNSET) audioChain.attachAudioSession(sid)
        } catch (e: Exception) {}
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(pending).build()
        createNotificationChannel(); startForegroundWithNotification(player.isPlaying)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) { ACTION_TOGGLE -> togglePlayPause(); ACTION_PREV -> playPrevious(); ACTION_NEXT -> playNext(); ACTION_STOP -> stop() }
        return START_STICKY
    }
    private fun handleWakeLockState(p: Boolean) { try { if (p) { if (wakeLock?.isHeld == false) wakeLock?.acquire(2*60*60*1000L) } else { if (wakeLock?.isHeld == true) wakeLock?.release() } } catch (e: Exception) {} }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val ch = NotificationChannel(CHANNEL_ID, "SjbZ Reproducción", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC }; getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch) } }
    private fun buildNotification(isPlaying: Boolean): Notification {
        val cur = getCurrentTrack(); val title = cur?.title?: "SjbZ Hi-Res"; val artist = cur?.artist?: "ATS-2835P DSP"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val prev = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).apply { action = ACTION_PREV }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val tog = PendingIntent.getService(this, 2, Intent(this, PlaybackService::class.java).apply { action = ACTION_TOGGLE }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val next = PendingIntent.getService(this, 3, Intent(this, PlaybackService::class.java).apply { action = ACTION_NEXT }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 4, Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(title).setContentText(artist).setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(open).setOngoing(isPlaying).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_TRANSPORT).addAction(android.R.drawable.ic_media_previous, "Ant", prev).addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "Pausa" else "Play", tog).addAction(android.R.drawable.ic_media_next, "Sig", next).addAction(android.R.drawable.ic_delete, "Stop", stop).build()
    }
    private fun startForegroundWithNotification(p: Boolean) { val n = buildNotification(p); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTIFICATION_ID, n) }
    private fun updateNotification(p: Boolean) { try { (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIFICATION_ID, buildNotification(p)) } catch (e: Exception) {} }
    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onBind(intent: Intent?): IBinder { super.onBind(intent); return binder }
    fun setPlaylist(tracks: List<Track>, startIndex: Int = 0, startPlaying: Boolean = true) {
        playlist.clear(); playlist.addAll(tracks); player.clearMediaItems()
        for (t in playlist) player.addMediaItem(MediaItem.Builder().setUri(t.uri).setMediaId(t.id.toString()).build())
        player.prepare(); if (startIndex in playlist.indices) playTrackAtIndex(startIndex, startPlaying)
    }
    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean) = setPlaylist(tracks, 0, startPlaying)
    fun setPlaybackSpeed(s: Float) { player.playbackParameters = PlaybackParameters(s) }
    fun playTrackAtIndex(i: Int, play: Boolean = true) {
        if (i!in playlist.indices) return; currentTrackIndex = i; player.seekToDefaultPosition(i); if (play) { player.play(); try { audioChain.startFadeIn() } catch (e: Exception) {} }
        try { val sid = player.audioSessionId; if (sid!= C.AUDIO_SESSION_ID_UNSET) audioChain.attachAudioSession(sid) } catch (e: Exception) {}; updateNotification(play); onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex)
    }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun play() { player.play() }
    fun pause() { player.pause() }
    fun stopPlayback() { player.stop(); player.seekTo(0); handleWakeLockState(false); updateNotification(false) }
    fun stop() = stopPlayback()
    fun playNext() { if (playlist.isEmpty()) return; val n = currentTrackIndex + 1; if (n < playlist.size) playTrackAtIndex(n, true) else if (isLoopPlaylistEnabled) playTrackAtIndex(0, true) }
    fun playPrevious() { if (playlist.isEmpty()) return; if (player.currentPosition > 3000) { player.seekTo(0); return }; val p = currentTrackIndex - 1; if (p >= 0) playTrackAtIndex(p, true) else if (isLoopPlaylistEnabled) playTrackAtIndex(playlist.size - 1, true) }
    private fun onSongEnded() { if (playlist.isEmpty()) return; val n = currentTrackIndex + 1; if (n < playlist.size) playTrackAtIndex(n, true) else if (isLoopPlaylistEnabled) playTrackAtIndex(0, true) }
    fun getCurrentTrack(): Track? = if (currentTrackIndex in playlist.indices) playlist[currentTrackIndex] else null
    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex
    override fun onTaskRemoved(root: Intent?) { updateNotification(player.isPlaying) }
    override fun onDestroy() { if (instance == this) instance = null; try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}; try { bluetoothDetector.stop() } catch (_: Exception) {}; try { audioChain.release() } catch (_: Exception) {}; mediaSession?.let { val p = player; p.release(); it.release() }; mediaSession = null; super.onDestroy() }
}
