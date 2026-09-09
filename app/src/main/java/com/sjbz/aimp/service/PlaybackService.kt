package com.sjbz.aimp.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
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

    fun initPlayer(
        exoPlayer: ExoPlayer,
        chain: AudioChain,
        ats: ATS2835PEngine,
        btDetector: BluetoothDetector
    ) {
        player = exoPlayer
        audioChain = chain
        atsEngine = ats
        bluetoothDetector = btDetector

        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = false

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                getCurrentTrack()?.let { track ->
                    onTrackChangedListener?.invoke(track, currentTrackIndex)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }
        })
    }

    fun setPlaylist(tracks: List<Track>) {
        setPlaylist(tracks, true)
    }

    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean) {
        playlist = tracks
        val mediaItems = tracks.map { MediaItem.fromUri(it.path) }
        player.setMediaItems(mediaItems)
        player.prepare()
        if (startPlaying) player.play()
    }

    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex
    fun getCurrentTrack(): Track? = playlist.getOrNull(currentTrackIndex)

    fun playTrackAtIndex(index: Int, startPlaying: Boolean = true) {
        if (index !in playlist.indices) return
        currentTrackIndex = index
        player.seekTo(index, 0)
        if (startPlaying) player.play()
        getCurrentTrack()?.let { track ->
            onTrackChangedListener?.invoke(track, index)
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        val next = (currentTrackIndex + 1) % playlist.size
        playTrackAtIndex(next, true)
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        val prev = if (currentTrackIndex - 1 < 0) playlist.size - 1 else currentTrackIndex - 1
        playTrackAtIndex(prev, true)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
