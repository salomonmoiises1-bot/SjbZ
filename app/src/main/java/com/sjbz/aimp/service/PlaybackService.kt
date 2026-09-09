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

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SjbZ:PlaybackWakeLock").apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        atsEngine = ATS2835PEngine(this)
        audioChain = AudioChain(this, atsEngine)

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
                if (playbackState == Player.STATE_ENDED) {
                    onSongEnded()
                }
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
                val currentTrack = getCurrentTrack()
                updateNotification(player.isPlaying)
                onTrackChangedListener?.invoke(currentTrack, currentTrackIndex)
            }
        })

        val sessionId = player.audioSessionId
        if (sessionId!= C.AUDIO_SESSION_ID_UNSET) {
            audioChain.attachAudioSession(sessionId)
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
           .setSessionActivity(sessionActivityPendingIntent)
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

    // FIX: metodo que llama EqActivity para el speed
    fun setPlaybackSpeed(factor: Float) {
        try {
            player.playbackParameters = PlaybackParameters(factor)
        } catch (_: Exception) {}
    }

    private fun handleWakeLockState(isPlaying: Boolean) {
        try {
            if (isPlaying) {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(2 * 60 * 60 * 1000L)
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SjbZ Reproducción de Audio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Reproducción continua de música en segundo plano"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val currentTrack = getCurrentTrack()
        val title = currentTrack?.title?: "SjbZ Reproductor Hi-Res"
        val artist = currentTrack?.artist?: "ATS-2835P DSP Audio Engine"

        val openActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // FIX: usar iconos de sistema, no R.drawable inexistentes
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
           .setContentTitle(title)
           .setContentText(artist)
           .setSubText(if (atsEngine.isBluetoothConnected) "ATS-2835P • Bluetooth A2DP" else "ATS-2835P • Hi-Res Direct")
           .setSmallIcon(android.R.drawable.sym_def_app_icon)
           .setContentIntent(openActivityIntent)
           .setOngoing(isPlaying)
           .setOnlyAlertOnce(true)
           .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
           .setPriority(NotificationCompat.PRIORITY_LOW)
           .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
           .addAction(android.R.drawable.ic_media_previous, "Anterior", prevIntent)
           .addAction(playPauseIcon, if (isPlaying) "Pausar" else "Reproducir", toggleIntent)
           .addAction(android.R.drawable.ic_media_next, "Siguiente", nextIntent)
           .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopIntent)
           .build()
    }

    private fun startForegroundWithNotification(isPlaying: Boolean) {
        val notification = buildNotification(isPlaying)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(isPlaying: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildNotification(isPlaying))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onBind(intent: Intent?): IBinder {
        super.onBind(intent)
        return binder
    }

    fun setPlaylist(tracks: List<Track>, startIndex: Int = 0, startPlaying: Boolean = true) {
        playlist.clear()
        playlist.addAll(tracks)

        player.clearMediaItems()
        for (track in playlist) {
            val mediaItem = MediaItem.Builder()
               .setUri(track.uri)
               .setMediaId(track.id.toString())
               .build()
            player.addMediaItem(mediaItem)
        }
        player.prepare()

        if (startIndex in playlist.indices) {
            playTrackAtIndex(startIndex, startPlaying)
        }
    }

    fun playTrackAtIndex(index: Int, startPlaying: Boolean = true) {
        if (index!in playlist.indices) return
        currentTrackIndex = index

        player.seekToDefaultPosition(index)
        if (startPlaying) {
            player.play()
            audioChain.startFadeIn()
        }

        val sessionId = player.audioSessionId
        if (sessionId!= C.AUDIO_SESSION_ID_UNSET) {
            audioChain.attachAudioSession(sessionId)
        }

        updateNotification(startPlaying)
        onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun stop() {
        player.stop()
        player.seekTo(0)
        handleWakeLockState(false)
        updateNotification(false)
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        val nextIndex = currentTrackIndex + 1
        if (nextIndex < playlist.size) {
            playTrackAtIndex(nextIndex, true)
        } else if (isLoopPlaylistEnabled) {
            playTrackAtIndex(0, true)
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        if (player.currentPosition > 3000) {
            player.seekTo(0)
            return
        }
        val prevIndex = currentTrackIndex - 1
        if (prevIndex >= 0) {
            playTrackAtIndex(prevIndex, true)
        } else if (isLoopPlaylistEnabled) {
            playTrackAtIndex(playlist.size - 1, true)
        }
    }

    private fun onSongEnded() {
        if (playlist.isEmpty()) return
        val nextIndex = currentTrackIndex + 1
        if (nextIndex < playlist.size) {
            playTrackAtIndex(nextIndex, true)
        } else if (isLoopPlaylistEnabled) {
            playTrackAtIndex(0, true)
        }
    }

    fun getCurrentTrack(): Track? {
        return if (currentTrackIndex in playlist.indices) playlist[currentTrackIndex] else null
    }

    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        bluetoothDetector.stop()
        audioChain.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
