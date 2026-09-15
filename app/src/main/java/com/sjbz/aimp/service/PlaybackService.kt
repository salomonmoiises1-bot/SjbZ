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
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
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
        var instance: PlaybackService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    lateinit var player: ExoPlayer
        private set
    private var mediaSession: MediaSession? = null
    lateinit var atsEngine: ATS2835PEngine
        private set
    lateinit var audioChain: AudioChain
        private set
    private lateinit var bluetoothDetector: BluetoothDetector
    private val playlist = mutableListOf<Track>()
    private var currentTrackIndex = -1
    var isLoopPlaylistEnabled: Boolean = true
    private var wakeLock: PowerManager.WakeLock? = null
    var onTrackChangedListener: ((Track?, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null

    private var lastAttachedAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    fun getAudioSessionId(): Int {
        return if (::player.isInitialized) player.audioSessionId else C.AUDIO_SESSION_ID_UNSET
    }

    private fun attachAudioSessionOnce(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
        if (sessionId == lastAttachedAudioSessionId) return
        lastAttachedAudioSessionId = sessionId
        audioChain.attachAudioSession(sessionId)
    }

    private fun buildMediaItem(track: Track): MediaItem {
        val metadata = MediaMetadata.Builder()
         .setTitle(track.title)
         .setArtist(track.artist)
         .setAlbumTitle(track.album)
         .build()
        return MediaItem.Builder()
         .setUri(track.uri)
         .setMediaId(track.id.toString())
         .setMediaMetadata(metadata)
         .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SjbZ:PlaybackWakeLock").apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) { e.printStackTrace() }

        atsEngine = ATS2835PEngine(this)
        audioChain = AudioChain(this, atsEngine)
        com.sjbz.aimp.audio.GlobalAudioSessionManager.getInstance(this).syncAudioEffects(atsEngine.equalizer, atsEngine.mdrc, atsEngine.limiter)

        bluetoothDetector = BluetoothDetector(this) { connected ->
            atsEngine.onBluetoothStatusChanged(connected)
        }
        bluetoothDetector.start()

        val audioAttributes = AudioAttributes.Builder()
      .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
      .setUsage(C.USAGE_MEDIA)
      .build()

        player = ExoPlayer.Builder(this)
      .setAudioAttributes(audioAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      .setWakeMode(C.WAKE_MODE_LOCAL)
      .build()

        audioChain.bindPlayer(player)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onSongEnded()
                val isPlaying = player.isPlaying
                handleWakeLockState(isPlaying)
                updateNotification(isPlaying)
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handleWakeLockState(isPlaying)
                updateNotification(isPlaying)
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newIndex = player.currentMediaItemIndex
                if (newIndex in playlist.indices) {
                    currentTrackIndex = newIndex
                    savePlaybackSession()
                }
                val currentTrack = getCurrentTrack()
                updateNotification(player.isPlaying)
                onTrackChangedListener?.invoke(currentTrack, currentTrackIndex)
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachAudioSessionOnce(audioSessionId)
            }
        })

        val sessionId = player.audioSessionId
        attachAudioSessionOnce(sessionId)

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // FIX CI: MediaSession.Callback de media3 no tiene onSkipToNext/onSkipToPrevious/onPlay/onPause
        // con esas firmas. Esos "overrides nothing" rompían el build. Se usa callback por defecto;
        // play/next/prev se manejan vía Player (ExoPlayer) y via intents ACTION_*.
        // Si necesitas interceptar comandos, hazlo con onConnect/onPlaybackResumption o Player.Listener.
        val callback = object : MediaSession.Callback {
        }
        mediaSession = MediaSession.Builder(this, player)
      .setSessionActivity(sessionActivityPendingIntent)
      .setCallback(callback)
      .build()
        createNotificationChannel()
        startForegroundWithNotification(player.isPlaying)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_PREV -> playPrevious()
            ACTION_NEXT -> playNext()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    private fun handleWakeLockState(isPlaying: Boolean) {
        try {
            if (isPlaying) {
                if (wakeLock?.isHeld == false) wakeLock?.acquire()
            } else {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SjbZ Reproducción de Audio", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Reproducción continua de música en segundo plano y pantalla bloqueada con DSP ATS2835P"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val currentTrack = getCurrentTrack()
        val title = currentTrack?.title?: "SB-Z Hi-Res Player"
        val artist = currentTrack?.artist?: "SB-Z Studio ATS2835P DSP"
        val openActivityIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val prevIntent = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).apply { action = ACTION_PREV }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val toggleIntent = PendingIntent.getService(this, 2, Intent(this, PlaybackService::class.java).apply { action = ACTION_TOGGLE }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nextIntent = PendingIntent.getService(this, 3, Intent(this, PlaybackService::class.java).apply { action = ACTION_NEXT }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = PendingIntent.getService(this, 4, Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title).setContentText(artist)
      .setSubText(if (atsEngine.isBluetoothConnected) "SB-Z • Bluetooth LDAC/A2DP" else "SB-Z • ATS2835P Hi-Res Direct")
      .setSmallIcon(R.drawable.ic_play).setContentIntent(openActivityIntent)
      .setOngoing(isPlaying).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
          .setMediaSession(mediaSession?.sessionCompatToken)
          .setShowActionsInCompactView(0, 1, 2))
      .addAction(R.drawable.ic_skip_previous, "Anterior", prevIntent)
      .addAction(playPauseIcon, if (isPlaying) "Pausar" else "Reproducir", toggleIntent)
      .addAction(R.drawable.ic_skip_next, "Siguiente", nextIntent)
      .addAction(R.drawable.ic_stop, "Detener", stopIntent).build()
    }

    private fun startForegroundWithNotification(isPlaying: Boolean) {
        val notification = buildNotification(isPlaying)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(isPlaying: Boolean) {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIFICATION_ID, buildNotification(isPlaying)) }
        catch (e: Exception) { e.printStackTrace() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)?: binder
    }

    fun setPlaylist(tracks: List<Track>, startIndex: Int = 0, startPlaying: Boolean = true) {
        if (tracks.isEmpty()) return
        val isSameList = playlist.size == tracks.size && playlist.indices.all { playlist[it].id == tracks[it].id }
        if (isSameList) {
            if (startIndex in playlist.indices) {
                if (startIndex == currentTrackIndex && player.isPlaying) return
                playTrackAtIndex(startIndex, startPlaying)
            }
            return
        }
        playlist.clear(); playlist.addAll(tracks)
        val mediaItems = ArrayList<MediaItem>(playlist.size)
        for (track in playlist) mediaItems.add(buildMediaItem(track))
        val targetIndex = if (startIndex in playlist.indices) startIndex else 0
        player.setMediaItems(mediaItems, targetIndex, 0L); player.prepare()
        val (savedIndex, savedPos) = restorePlaybackSession()
        if (savedIndex in playlist.indices && playlist[savedIndex].id == playlist[targetIndex].id && savedPos > 5000) {
            player.seekTo(targetIndex, savedPos)
        }
        if (targetIndex in playlist.indices) playTrackAtIndex(targetIndex, startPlaying)
    }
    fun setPlaylist(tracks: List<Track>) { setPlaylist(tracks, 0, true) }
    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean) { setPlaylist(tracks, 0, startPlaying) }
    fun setPlaylist(tracks: List<Track>, startIndex: Int) { setPlaylist(tracks, startIndex, true) }
    fun setPlaybackSpeed(speed: Float) { player.playbackParameters = PlaybackParameters(speed) }

    fun applyRestoredSession() {
        val (savedIndex, savedPos) = restorePlaybackSession()
        if (savedIndex in playlist.indices) {
            currentTrackIndex = savedIndex
            if (player.currentMediaItemIndex!= savedIndex) {
                player.seekTo(savedIndex, savedPos)
            } else if (savedPos > 0) {
                player.seekTo(savedPos)
            }
            updateNotification(player.isPlaying)
            onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex)
        }
    }

    fun playTrackAtIndex(index: Int, startPlaying: Boolean = true) {
        if (index!in playlist.indices) return
        currentTrackIndex = index
        if (player.currentMediaItemIndex!= index) player.seekToDefaultPosition(index)
        if (startPlaying) { player.play(); audioChain.startFadeIn() }
        attachAudioSessionOnce(player.audioSessionId)
        savePlaybackSession(); updateNotification(startPlaying)
        onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex)
    }

    fun savePlaybackSession() {
        try {
            val prefs = getSharedPreferences("sbz_playback_session", Context.MODE_PRIVATE)
            val currentTrack = getCurrentTrack()
            prefs.edit().apply {
                putLong("last_track_id", currentTrack?.id?: -1L)
                putInt("last_track_index", currentTrackIndex)
                putLong("last_position_ms", player.currentPosition)
                putFloat("last_speed", player.playbackParameters.speed)
                apply()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    fun restorePlaybackSession(): Pair<Int, Long> {
        val prefs = getSharedPreferences("sbz_playback_session", Context.MODE_PRIVATE)
        return Pair(prefs.getInt("last_track_index", 0), prefs.getLong("last_position_ms", 0L))
    }
    fun togglePlayPause() { if (player.isPlaying) { player.pause() } else { player.play(); audioChain.startFadeIn() } }
    fun play() { player.play() }
    fun pause() { player.pause() }
    fun stopPlayback() {
        player.stop(); player.seekTo(0); handleWakeLockState(false); updateNotification(false)
    }
    fun stop() {
        stopPlayback()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    fun playNext() {
        if (playlist.isEmpty()) return
        val nextIndex = currentTrackIndex + 1
        if (nextIndex < playlist.size) playTrackAtIndex(nextIndex, true)
        else if (isLoopPlaylistEnabled) playTrackAtIndex(0, true)
    }
    fun playPrevious() {
        if (playlist.isEmpty()) return
        if (player.currentPosition > 3000) { player.seekTo(0); return }
        val prevIndex = currentTrackIndex - 1
        if (prevIndex >= 0) playTrackAtIndex(prevIndex, true)
        else if (isLoopPlaylistEnabled) playTrackAtIndex(playlist.size - 1, true)
    }
    private fun onSongEnded() {
        if (playlist.isEmpty()) return
        val baseIndex = player.currentMediaItemIndex.takeIf { it in playlist.indices }?: currentTrackIndex
        val nextIndex = baseIndex + 1
        if (nextIndex < playlist.size) playTrackAtIndex(nextIndex, true)
        else if (isLoopPlaylistEnabled) playTrackAtIndex(0, true)
    }
    fun getCurrentTrack(): Track? {
        val idx = player.currentMediaItemIndex.takeIf { it in playlist.indices }?: currentTrackIndex
        return if (idx in playlist.indices) playlist[idx] else null
    }
    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            updateNotification(true)
        }
    }
    override fun onDestroy() {
        if (instance == this) instance = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { e.printStackTrace() }
        bluetoothDetector.stop(); audioChain.release()
        mediaSession?.run { player.release(); release(); mediaSession = null }
        super.onDestroy()
    }
}
