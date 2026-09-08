package com.sjbz.aimp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
        var instance: PlaybackService? = null
            private set
        var audioSessionId: Int = 0
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
    var onTrackChangedListener: ((Track?, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // FIX 1: Crear canal - ESTO ES LO QUE EVITA QUE SE CORTE
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("music_channel", "Música", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
           .build()

        audioChain.bindPlayer(player)
        audioSessionId = player.audioSessionId
        if (audioSessionId!= C.AUDIO_SESSION_ID_UNSET) {
            audioChain.attachAudioSession(audioSessionId)
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onSongEnded()
                onPlaybackStateChangedListener?.invoke(player.isPlaying)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex)
                // FIX 2: Actualizar sessionId cuando cambia de tema
                audioSessionId = player.audioSessionId
                if (audioSessionId!= 0) audioChain.attachAudioSession(audioSessionId)
            }
        })

        // FIX 3: Crear MediaSession para que el servicio se mantenga vivo
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
           .setSessionActivity(sessionActivityPendingIntent)
           .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // FIX 4: ESTO ES CLAVE PARA QUE NO SE CORTE AL SALIR
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        bluetoothDetector.stop()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun onSongEnded() {
        if (isLoopPlaylistEnabled && playlist.isNotEmpty()) {
            val nextIndex = (currentTrackIndex + 1) % playlist.size
            playTrackAt(nextIndex)
        }
    }

    fun getCurrentTrack(): Track? = if (currentTrackIndex in playlist.indices) playlist[currentTrackIndex] else null
    fun playTrackAt(index: Int) {
        if (index in playlist.indices) {
            currentTrackIndex = index
            val track = playlist[index]
            player.setMediaItem(MediaItem.fromUri(track.uri))
            player.prepare()
            player.play()
        }
    }

    fun setPlaylist(tracks: List<Track>) {
        playlist.clear()
        playlist.addAll(tracks)
    }
}
