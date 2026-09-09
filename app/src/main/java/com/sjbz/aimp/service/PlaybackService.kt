package com.sjbz.aimp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sjbz.aimp.model.Track

class PlaybackService : Service() {

    companion object {
        var instance: PlaybackService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()

    lateinit var player: ExoPlayer
    lateinit var audioChain: AudioChain
    lateinit var atsEngine: ATS2835PEngine
    lateinit var bluetoothDetector: BluetoothDetector

    var isLoopPlaylistEnabled: Boolean = true
    private var playlist: List<Track> = emptyList()
    private var currentTrackIndex = 0

    var onTrackChangedListener: ((Track, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun startAsForeground() {
        val channelId = "sjbz_playback"
        val channel = NotificationChannel(channelId, "Reproducción", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SjbZ")
            .setContentText(getCurrentTrack()?.title ?: "Reproduciendo...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    fun initPlayer(exoPlayer: ExoPlayer, chain: AudioChain, ats: ATS2835PEngine, btDetector: BluetoothDetector) {
        player = exoPlayer
        audioChain = chain
        atsEngine = ats
        bluetoothDetector = btDetector
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = false
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                getCurrentTrack()?.let { onTrackChangedListener?.invoke(it, currentTrackIndex) }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }
        })
    }

    // FIX PARA MainActivity:160 y 249
    fun setPlaylist(tracks: List<Track>) = setPlaylist(tracks, true)
    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean) {
        playlist = tracks
        player.setMediaItems(tracks.map { MediaItem.fromUri(it.path) })
        player.prepare()
        if (startPlaying) player.play()
    }
    fun setPlaylist(tracks: List<Track>, startIndex: Int) {
        playlist = tracks
        player.setMediaItems(tracks.map { MediaItem.fromUri(it.path) })
        player.prepare()
        player.play()
        if (tracks.isNotEmpty()) playTrackAtIndex(startIndex.coerceIn(tracks.indices), true)
    }
    fun setPlaylist(tracks: List<Track>, startIndex: Int, startPlaying: Boolean) {
        playlist = tracks
        player.setMediaItems(tracks.map { MediaItem.fromUri(it.path) })
        player.prepare()
        if (startPlaying) player.play()
        if (tracks.isNotEmpty()) playTrackAtIndex(startIndex.coerceIn(tracks.indices), startPlaying)
    }

    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex
    fun getCurrentTrack(): Track? = playlist.getOrNull(currentTrackIndex)

    // FIX PARA EqActivity:26
    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    fun playTrackAtIndex(index: Int, startPlaying: Boolean = true) {
        if (index !in playlist.indices) return
        currentTrackIndex = index
        player.seekTo(index, 0)
        if (startPlaying) player.play()
        getCurrentTrack()?.let { onTrackChangedListener?.invoke(it, index) }
    }

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun playNext() { if (playlist.isNotEmpty()) playTrackAtIndex((currentTrackIndex + 1) % playlist.size, true) }
    fun playPrevious() { if (playlist.isNotEmpty()) playTrackAtIndex(if (currentTrackIndex - 1 < 0) playlist.size - 1 else currentTrackIndex - 1, true) }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { super.onDestroy(); instance = null }
}
