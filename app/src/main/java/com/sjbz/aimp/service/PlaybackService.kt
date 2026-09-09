package com.sjbz.aimp.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sjbz.aimp.model.Track

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
        // FIX INFINITO 500 TEMAS
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = false

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackIndex = player.currentMediaItemIndex
                // FIX 94:48 - era Track? pero esperaba Track
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
        playlist = tracks
        val mediaItems = tracks.map { track ->
            MediaItem.fromUri(track.path)
        }
        player.setMediaItems(mediaItems)
        player.prepare()
    }

    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex
    fun getCurrentTrack(): Track? = playlist.getOrNull(currentTrackIndex)

    fun playTrackAtIndex(index: Int, startPlaying: Boolean = true) {
        if (index !in playlist.indices) return
        currentTrackIndex = index
        player.seekToDefaultPosition(index)
        if (startPlaying) player.play()
        // FIX 175:74 - era Track? pero esperaba Track
        getCurrentTrack()?.let { track ->
            onTrackChangedListener?.invoke(track, index)
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        val nextIndex = (currentTrackIndex + 1) % playlist.size
        playTrackAtIndex(nextIndex, true)
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        val prevIndex = if (currentTrackIndex - 1 < 0) playlist.size - 1 else currentTrackIndex - 1
        playTrackAtIndex(prevIndex, true)
    }

    fun updateNotification(isPlaying: Boolean) {
        // tu codigo de notificacion existente dejalo igual
    }

    // FIX 162:5 onBind borrado - tu clase no es un Service, por eso daba 'overrides nothing'
}
