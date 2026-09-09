package com.jbz.aimp.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.jbz.aimp.model.Track

class PlaybackService(
    private val player: ExoPlayer,
    private val audioChain: AudioChain,
    private val atsEngine: ATS2835PEngine,
    private val bluetoothDetector: BluetoothDetector
) {
    private var playlist: List<Track> = emptyList()
    private var currentTrackIndex = 0
    
    var onTrackChangedListener: ((Track, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null

    init {
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = false

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackIndex = player.currentMediaItemIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
                getCurrentTrack()?.let { 
                    onTrackChangedListener?.invoke(it, currentTrackIndex)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }
        })
    }

    fun setPlaylist(tracks: List<Track>) = run {
        playlist = tracks
        player.setMediaItems(tracks.map { MediaItem.fromUri(it.path) })
        player.prepare()
    }
    fun getPlaylist() = playlist
    fun getCurrentIndex() = currentTrackIndex
    fun getCurrentTrack(): Track? = playlist.getOrNull(currentTrackIndex)

    fun playTrackAtIndex(index: Int, startPlaying: Boolean = true) {
        if (index !in playlist.indices) return
        currentTrackIndex = index
        player.seekTo(index, 0)
        if (startPlaying) player.play()
        getCurrentTrack()?.let { onTrackChangedListener?.invoke(it, index) }
    }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun playNext() {
        if (playlist.isEmpty()) return
        playTrackAtIndex((currentTrackIndex + 1) % playlist.size, true)
    }
    fun playPrevious() {
        if (playlist.isEmpty()) return
        playTrackAtIndex(if (currentTrackIndex - 1 < 0) playlist.size - 1 else currentTrackIndex - 1, true)
    }
}
