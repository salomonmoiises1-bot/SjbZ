package com.sjbz.aimp.service

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

/**
 * Foreground MediaSessionService for SjbZ player.
 * Powered by Media3 ExoPlayer 1.4.0 with ATS2835P DSP Audio Chain,
 * background playback notification, and gapless auto-loop playlist support.
 */
class PlaybackService : MediaSessionService() {

    companion object {
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

    // Listener callbacks for UI
    var onTrackChangedListener: ((Track?, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize ATS2835P Audio Engine
        atsEngine = ATS2835PEngine(this)
        audioChain = AudioChain(this, atsEngine)

        // 2. Initialize Bluetooth Detector
        bluetoothDetector = BluetoothDetector(this) { connected ->
            atsEngine.onBluetoothStatusChanged(connected)
        }
        bluetoothDetector.start()

        // 3. Configure ExoPlayer with Hi-Res Music AudioAttributes
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // handles audio focus
            .setHandleAudioBecomingNoisy(true)
            .build()

        audioChain.bindPlayer(player)

        // 4. Attach audio effects when audio session ID is created
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onSongEnded()
                }
                onPlaybackStateChangedListener?.invoke(player.isPlaying)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentTrack = getCurrentTrack()
                onTrackChangedListener?.invoke(currentTrack, currentTrackIndex)
            }
        })

        // Attach DSP to ExoPlayer audio session
        val sessionId = player.audioSessionId
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
            audioChain.attachAudioSession(sessionId)
        }

        // 5. Build MediaSession for background & lockscreen playback
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onBind(intent: Intent?): IBinder {
        super.onBind(intent)
        return binder
    }

    /**
     * Updates the active playlist queue.
     */
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
        if (index !in playlist.indices) return
        currentTrackIndex = index

        player.seekToDefaultPosition(index)
        if (startPlaying) {
            player.play()
            audioChain.startFadeIn()
        }

        // Ensure DSP is attached to active session
        val sessionId = player.audioSessionId
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
            audioChain.attachAudioSession(sessionId)
        }

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
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        val nextIndex = currentTrackIndex + 1
        if (nextIndex < playlist.size) {
            playTrackAtIndex(nextIndex, true)
        } else if (isLoopPlaylistEnabled) {
            // Auto-loop playlist without pause
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
            // Seamless auto-loop to track 0
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
