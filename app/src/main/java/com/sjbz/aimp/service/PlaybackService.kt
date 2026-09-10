package com.sjbz.aimp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlaybackService : Service() {

    private val binder = LocalBinder()
    var player: ExoPlayer? = null
        private set
    private var mediaSession: MediaSession? = null
    private var mediaSessionCompat: MediaSessionCompat? = null
    private var audioChain: AudioChain? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentPlaylist: List<Track> = emptyList()
    private var currentEqPreset: EqPreset? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.sjbz.aimp.ACTION_PLAY"
        const val ACTION_PAUSE = "com.sjbz.aimp.ACTION_PAUSE"
        const val ACTION_NEXT = "com.sjbz.aimp.ACTION_NEXT"
        const val ACTION_PREV = "com.sjbz.aimp.ACTION_PREV"
        const val ACTION_STOP = "com.sjbz.aimp.ACTION_STOP"
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SjbZ:PlaybackWakeLock")
        wakeLock?.setReferenceCounted(false)

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        serviceScope.launch {
                            audioChain?.let { chain ->
                                val sessionId = player?.audioSessionId ?: 0
                                if (sessionId != 0) {
                                    chain.attachAudioSession(sessionId)
                                    chain.applyEqPreset(currentEqPreset)
                                    try {
                                        GlobalAudioSessionManager.getInstance(this@PlaybackService)
                                            .syncAudioEffects(chain.atsEngine.equalizer, chain.atsEngine)
                                    } catch (e: Exception) {
                                    }
                                }
                            }
                        }
                    }
                    updateNotification()
                    updateMediaSession()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateNotification()
                    updateMediaSession()
                    if (isPlaying) {
                        wakeLock?.acquire(2*60*60*1000L)
                    } else {
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateNotification()
                    updateMediaSession()
                }
            })
        }

        audioChain = AudioChain(this, player!!)
        
        mediaSessionCompat = MediaSessionCompat(this, "SjbZMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { prev() }
                override fun onStop() { stopSelf() }
                override fun onSeekTo(pos: Long) { player?.seekTo(pos) }
            })
            isActive = true
        }

        mediaSession = MediaSession.Builder(this, player!!).build()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Reproducción SjbZ", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Controles de reproducción ATS-2835P"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateMediaSession() {
        val track = getCurrentTrack()
        mediaSessionCompat?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title ?: "SjbZ AIMP")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist ?: "ATS-2835P Master")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player?.duration ?: 0L)
                .build()
        )
        val state = if (player?.isPlaying == true) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSessionCompat?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, player?.currentPosition ?: 0L, 1.0f)
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val isPlaying = player?.isPlaying == true
        val track = getCurrentTrack()
        val title = track?.title ?: "SjbZ AIMP"
        val artist = track?.artist ?: "ATS-2835P Master Activo"

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(R.drawable.ic_pause, "Pausar", getPendingIntent(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, "Reproducir", getPendingIntent(ACTION_PLAY))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_foreground))
            .setContentIntent(pendingIntent)
            .addAction(NotificationCompat.Action(R.drawable.ic_prev, "Anterior", getPendingIntent(ACTION_PREV)))
            .addAction(playPauseAction)
            .addAction(NotificationCompat.Action(R.drawable.ic_next, "Siguiente", getPendingIntent(ACTION_NEXT)))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSessionCompat?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    fun setPlaylist(tracks: List<Track>, startIndex: Int, startPlaying: Boolean) {
        currentPlaylist = tracks
        val mediaItems = tracks.map { 
            MediaItem.Builder().setUri(it.path).setMediaId(it.path).build()
        }
        player?.setMediaItems(mediaItems, startIndex, 0L)
        player?.prepare()
        if (startPlaying) {
            player?.play()
        }
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    fun applyEqPreset(preset: EqPreset) {
        currentEqPreset = preset
        audioChain?.applyEqPreset(preset)
        try {
            GlobalAudioSessionManager.getInstance(this).syncAudioEffects(audioChain?.atsEngine?.equalizer, audioChain?.atsEngine)
        } catch (e: Exception) {}
    }

    fun applyMDRC(gains: FloatArray) {
        audioChain?.atsEngine?.mdrcProcessor?.setGains(gains)
    }

    fun setBassBoost(strength: Int) {
        audioChain?.atsEngine?.bassBoost?.setStrength(strength)
    }

    fun play() {
        player?.play()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    fun pause() {
        player?.pause()
        stopForeground(STOP_FOREGROUND_DETACH)
        updateNotification()
    }

    fun next() {
        player?.seekToNextMediaItem()
    }

    fun prev() {
        player?.seekToPreviousMediaItem()
    }

    fun seekTo(pos: Long) = player?.seekTo(pos)
    fun getCurrentTrack(): Track? = currentPlaylist.getOrNull(player?.currentMediaItemIndex ?: -1)
    fun getPlaylist(): List<Track> = currentPlaylist
    fun isPlaying(): Boolean = player?.isPlaying ?: false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> {
                pause()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (player?.isPlaying == true) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        mediaSession?.release()
        mediaSessionCompat?.release()
        player?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    // ===========================================
    // PARCHES DE COMPATIBILIDAD - RESPETA ORIGINAL 314 LÍNEAS
    // ===========================================
    fun setPlaylist(tracks: List<Track>) = setPlaylist(tracks, 0, true)
    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean) = setPlaylist(tracks, 0, startPlaying)
    fun setPlaybackSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
    }
}
