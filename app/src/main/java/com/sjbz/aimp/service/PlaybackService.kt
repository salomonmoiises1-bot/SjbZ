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
    inner class LocalBinder : Binder() { fun getService(): PlaybackService = this@PlaybackService }
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
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("music_channel", "Música", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        atsEngine = ATS2835PEngine(this)
        audioChain = AudioChain(this, atsEngine)
        bluetoothDetector = BluetoothDetector(this) { atsEngine.onBluetoothStatusChanged(it) }
        bluetoothDetector.start()
        val audioAttributes = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
        player = ExoPlayer.Builder(this).setAudioAttributes(audioAttributes, true).setHandleAudioBecomingNoisy(true).build()
        audioChain.bindPlayer(player)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { if(s==Player.STATE_ENDED) playNext(); onPlaybackStateChangedListener?.invoke(player.isPlaying) }
            override fun onIsPlayingChanged(isPlaying: Boolean) { onPlaybackStateChangedListener?.invoke(isPlaying) }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { audioSessionId=player.audioSessionId; if(audioSessionId!=0) audioChain.attachAudioSession(audioSessionId); onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex) }
        })
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(pi).build()
    }
    override fun onGetSession(c: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int { super.onStartCommand(i,f,s); return START_STICKY }
    override fun onDestroy() { mediaSession?.run { player.release(); release() }; mediaSession=null; bluetoothDetector.stop(); instance=null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder { super.onBind(intent); return binder }

    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex
    fun getCurrentTrack(): Track? = if (currentTrackIndex in playlist.indices) playlist[currentTrackIndex] else null

    // Los 2 metodos para que MainActivity no falle
    fun setPlaylist(tracks: List<Track>) { playlist.clear(); playlist.addAll(tracks) }
    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean) { playlist.clear(); playlist.addAll(tracks); if(startPlaying && tracks.isNotEmpty()) playTrackAt(0) }

    fun playTrackAt(index: Int) { if(index in playlist.indices){ currentTrackIndex=index; player.setMediaItem(MediaItem.fromUri(playlist[index].uri)); player.prepare(); player.play(); onTrackChangedListener?.invoke(playlist[index], index) } }
    fun togglePlayPause() { if(player.isPlaying) player.pause() else player.play() }
    fun stop() { player.stop() }
    fun playNext() { if(playlist.isNotEmpty()) playTrackAt((currentTrackIndex+1)%playlist.size) }
    fun playPrevious() { if(playlist.isNotEmpty()) playTrackAt(if(currentTrackIndex-1<0) playlist.size-1 else currentTrackIndex-1) }
}
