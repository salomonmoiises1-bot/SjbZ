package com.sjbz.aimp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
    companion object { var instance: PlaybackService? = null; var audioSessionId: Int = 0; const val NOTIF_ID = 101; const val CHANNEL_ID = "music_channel" }
    inner class LocalBinder : Binder() { fun getService() = this@PlaybackService }
    private val binder = LocalBinder()
    lateinit var player: ExoPlayer; private var mediaSession: MediaSession? = null
    lateinit var atsEngine: ATS2835PEngine; lateinit var audioChain: AudioChain
    private lateinit var bluetoothDetector: BluetoothDetector
    private val playlist = mutableListOf<Track>(); private var currentTrackIndex = -1
    var isLoopPlaylistEnabled = true
    var onTrackChangedListener: ((Track?, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate(); instance = this
        createChannel()
        atsEngine = ATS2835PEngine(this); audioChain = AudioChain(this, atsEngine)
        bluetoothDetector = BluetoothDetector(this) { atsEngine.onBluetoothStatusChanged(it) }; bluetoothDetector.start()
        val aa = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
        player = ExoPlayer.Builder(this).setAudioAttributes(aa, true).setHandleAudioBecomingNoisy(true).build()
        audioChain.bindPlayer(player)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { if(s==Player.STATE_ENDED) playNext() }
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { audioSessionId=player.audioSessionId; if(audioSessionId!=0) audioChain.attachAudioSession(audioSessionId) }
        })
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(pi).build()
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun createChannel(){ if(android.os.Build.VERSION.SDK_INT>=26){ val ch=NotificationChannel(CHANNEL_ID,"Música",NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager::class.java).createNotificationChannel(ch) } }
    private fun buildNotification(): Notification { return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("SjbZ AIMP").setContentText("Reproduciendo en segundo plano").setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build() }
    override fun onGetSession(c: MediaSession.ControllerInfo) = mediaSession
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int { super.onStartCommand(i,f,s); return START_STICKY }
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent) } // NO parar al cerrar app
    override fun onDestroy() { mediaSession?.run { player.release(); release() }; mediaSession=null; bluetoothDetector.stop(); instance=null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder { super.onBind(intent); return binder }

    fun getPlaylist() = playlist.toList(); fun getCurrentIndex() = currentTrackIndex
    fun getCurrentTrack() = if (currentTrackIndex in playlist.indices) playlist[currentTrackIndex] else null
    fun setPlaylist(tracks: List<Track>) { playlist.clear(); playlist.addAll(tracks) }
    fun setPlaylist(tracks: List<Track>, startIndex: Int) { playlist.clear(); playlist.addAll(tracks); if(tracks.isNotEmpty()) playTrackAt(startIndex.coerceIn(tracks.indices)) }
    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean) { playlist.clear(); playlist.addAll(tracks); if(startPlaying && tracks.isNotEmpty()) playTrackAt(0) }
    fun setPlaylist(tracks: List<Track>, startIndex: Int, startPlaying: Boolean) { playlist.clear(); playlist.addAll(tracks); if(tracks.isNotEmpty() && startPlaying) playTrackAt(startIndex.coerceIn(tracks.indices)) }
    fun playTrackAt(index: Int) { if(index in playlist.indices){ currentTrackIndex=index; player.setMediaItem(MediaItem.fromUri(playlist[index].uri)); player.prepare(); player.play() } }
    fun togglePlayPause() { if(player.isPlaying) player.pause() else player.play() }
    fun stop() { player.stop() }
    fun playNext() { if(playlist.isNotEmpty()) playTrackAt((currentTrackIndex+1)%playlist.size) }
    fun playPrevious() { if(playlist.isNotEmpty()) playTrackAt(if(currentTrackIndex-1<0) playlist.size-1 else currentTrackIndex-1) }
}
