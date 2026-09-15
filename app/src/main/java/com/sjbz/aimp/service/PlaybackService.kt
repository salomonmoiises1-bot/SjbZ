package com.sjbz.aimp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sjbz.aimp.MainActivity
import com.sjbz.aimp.R
import com.sjbz.aimp.audio.ATS2835PEngine
import com.sjbz.aimp.audio.AudioChain
import com.sjbz.aimp.audio.SjbzDspProcessor
import com.sjbz.aimp.model.Track
import com.sjbz.aimp.utils.BluetoothDetector

/**
 * Robust Foreground MediaSessionService for SjbZ player.
 * Powered by Media3 ExoPlayer with direct SjbzDspProcessor floating-point DSP pipeline.
 *
 * Responsibilities:
 * - Hosts ExoPlayer with SjbzDspProcessor injected into DefaultAudioSink
 * - Bridges DSP fftListener to UI spectrum visualizer via spectrumListener
 * - Exposes DSP control surface (EQ, BassBoost, ATS2835P emulation) to activities
 * - Manages MediaSessionCompat, foreground notification, wake lock, BT auto-bypass
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val CHANNEL_ID = "sjbz_playback_channel"
        const val NOTIFICATION_ID = 2835

        const val ACTION_TOGGLE = "com.sjbz.aimp.ACTION_TOGGLE"
        const val ACTION_PREV = "com.sjbz.aimp.ACTION_PREV"
        const val ACTION_NEXT = "com.sjbz.aimp.ACTION_NEXT"
        const val ACTION_STOP = "com.sjbz.aimp.ACTION_STOP"

        private const val PREFS_DSP = "sjbz_dsp_pro"
        private const val KEY_EMU_ENABLED = "emu_enabled"
        private const val KEY_EMU_AMOUNT = "emu_amount"

        var instance: PlaybackService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
        fun getDsp(): SjbzDspProcessor = this@PlaybackService.getDsp()
    }

    private val binder = LocalBinder()

    lateinit var player: ExoPlayer
        private set
    private var mediaSession: MediaSession? = null
    private lateinit var mediaSessionCompat: MediaSessionCompat

    // Studio DSP Processor
    private val dspProcessor = SjbzDspProcessor()
    fun getDsp(): SjbzDspProcessor = dspProcessor

    lateinit var atsEngine: ATS2835PEngine
        private set
    lateinit var audioChain: AudioChain
        private set
    private lateinit var bluetoothDetector: BluetoothDetector
    private lateinit var prefs: SharedPreferences

    // Spectrum bridge: DSP -> UI visualizer. Set from EqActivity/MainActivity.
    var spectrumListener: SjbzDspProcessor.FftListener? = null

    // Artwork caching
    private var cachedArtworkTrackId: Long = -999L
    private var cachedArtworkBitmap: Bitmap? = null

    private val playlist = mutableListOf<Track>()
    private var currentTrackIndex = -1
    var isLoopPlaylistEnabled: Boolean = true

    // CPU partial wake lock for zero audio dropouts when phone screen turns off
    private var wakeLock: PowerManager.WakeLock? = null

    // Listener callbacks for UI
    var onTrackChangedListener: ((Track?, Int) -> Unit)? = null
    var onPlaybackStateChangedListener: ((Boolean) -> Unit)? = null

    // -------------------------------------------------------------------------
    // DSP Control Surface - Emulation ATS2835P
    // -------------------------------------------------------------------------

    fun setEmulationEnabled(enabled: Boolean) {
        dspProcessor.setEmulationEnabled(enabled)
        prefs.edit().putBoolean(KEY_EMU_ENABLED, enabled).apply()
        updateNotification(player.isPlaying)
    }

    fun setEmulationAmount(amount: Float) {
        dspProcessor.setEmulationAmount(amount.coerceIn(0f, 1f))
        prefs.edit().putFloat(KEY_EMU_AMOUNT, amount.coerceIn(0f, 1f)).apply()
    }

    fun isEmulationEnabled(): Boolean = dspProcessor.isEmulationEnabled()
    fun getEmulationAmount(): Float = dspProcessor.getEmulationAmount()

    // -------------------------------------------------------------------------
    // DSP Control Surface - Bass Boost
    // -------------------------------------------------------------------------

    fun setBassBoost(freqHz: Float, gainDb: Float) {
        dspProcessor.setBassBoost(freqHz, gainDb)
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        dspProcessor.setBassBoostEnabled(enabled)
    }

    fun setSpectrumListener(listener: SjbzDspProcessor.FftListener?) {
        spectrumListener = listener
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_DSP, Context.MODE_PRIVATE)

        // 1. Setup Partial WakeLock for zero-dropout lockscreen playback
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SjbZ:PlaybackWakeLock").apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Initialize Audio Engine & Chain
        atsEngine = ATS2835PEngine(dspProcessor)
        audioChain = AudioChain(this, atsEngine)

        // 2b. Bridge DSP fftListener -> UI spectrum visualizer (thread-safe hop)
        dspProcessor.fftListener = object : SjbzDspProcessor.FftListener {
            override fun onAudioData(samples: FloatArray) {
                spectrumListener?.onAudioData(samples)
            }
        }

        // 2c. Restore persisted emulation state before playback starts
        dspProcessor.setEmulationEnabled(prefs.getBoolean(KEY_EMU_ENABLED, false))
        dspProcessor.setEmulationAmount(prefs.getFloat(KEY_EMU_AMOUNT, 0.8f))

        // 3. Initialize Bluetooth Detector with Auto-Bypass sync
        bluetoothDetector = BluetoothDetector(this) { connected ->
            dspProcessor.setBluetoothConnected(connected)
            updateNotification(player.isPlaying)
            updateMediaSessionMetadataAndState()
        }
        bluetoothDetector.start()
        dspProcessor.setBluetoothConnected(bluetoothDetector.isBluetoothA2dpConnected())

        // 4. Configure ExoPlayer with SjbzDspProcessor in DefaultAudioSink
        val audioAttributes = AudioAttributes.Builder()
           .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
           .setUsage(C.USAGE_MEDIA)
           .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                   .setEnableFloatOutput(true)
                   .setAudioProcessors(arrayOf(dspProcessor))
                   .build()
            }
        }

        player = ExoPlayer.Builder(this, renderersFactory)
           .setAudioAttributes(audioAttributes, true)
           .setHandleAudioBecomingNoisy(true)
           .setWakeMode(C.WAKE_MODE_LOCAL)
           .build()

        audioChain.bindPlayer(player)

        // 5. Attach audio listeners
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onSongEnded()
                }
                val isPlaying = player.isPlaying
                handleWakeLockState(isPlaying)
                updateNotification(isPlaying)
                updateMediaSessionMetadataAndState()
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handleWakeLockState(isPlaying)
                updateNotification(isPlaying)
                updateMediaSessionMetadataAndState()
                onPlaybackStateChangedListener?.invoke(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val currentTrack = getCurrentTrack()
                updateNotification(player.isPlaying)
                updateMediaSessionMetadataAndState()
                onTrackChangedListener?.invoke(currentTrack, currentTrackIndex)
            }
        })

        // 6. Build MediaSession for background & lockscreen playback
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
           .setSessionActivity(sessionActivityPendingIntent)
           .build()

        // 7. MediaSessionCompat for lockscreen & hardware button transport controls
        mediaSessionCompat = MediaSessionCompat(this, "SjbZMediaSession").apply {
            setSessionActivity(sessionActivityPendingIntent)
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrevious() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { player.seekTo(pos) }
            })
        }
        updateMediaSessionMetadataAndState()

        // 8. Notification Channel & Initial Foreground Notification
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
                description = "Reproducción continua de música en segundo plano y pantalla bloqueada con Pro DSP"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun updateMediaSessionMetadataAndState() {
        if (!::mediaSessionCompat.isInitialized) return
        val isPlaying = player.isPlaying
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO

        val playbackState = PlaybackStateCompat.Builder()
           .setActions(actions)
           .setState(state, player.currentPosition, player.playbackParameters.speed)
           .build()
        mediaSessionCompat.setPlaybackState(playbackState)

        val currentTrack = getCurrentTrack()
        val title = currentTrack?.title?: "SB-Z Hi-Res Player"
        val artist = currentTrack?.artist?: "SB-Z Studio Pro DSP"
        val album = currentTrack?.album?: "Studio Master 32-bit"
        val duration = if (player.duration > 0) player.duration else (currentTrack?.duration?: 0L)
        val artworkBitmap = getArtworkBitmap(currentTrack)

        val metadata = MediaMetadataCompat.Builder()
           .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
           .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
           .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
           .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, artist)
           .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
           .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap)
           .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artworkBitmap)
           .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    private fun getArtworkBitmap(track: Track?): Bitmap {
        if (track!= null && track.id == cachedArtworkTrackId && cachedArtworkBitmap!= null) {
            return cachedArtworkBitmap!!
        }

        var bitmap: Bitmap? = null

        if (track!= null && (track.uri.startsWith("content://") || track.uri.startsWith("file://"))) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, Uri.parse(track.uri))
                val artBytes = retriever.embeddedPicture
                retriever.release()
                if (artBytes!= null) {
                    bitmap = android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                }
            } catch (_: Exception) {}
        }

        if (bitmap == null) {
            val size = 512
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            paint.shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                Color.parseColor("#080D14"), Color.parseColor("#121F33"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.parseColor("#1A2942")
            val cx = size / 2f
            val cy = size / 2f
            for (r in 60..220 step 20) {
                canvas.drawCircle(cx, cy, r.toFloat(), paint)
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#00E5FF")
            paint.alpha = 40
            canvas.drawCircle(cx, cy, 70f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.parseColor("#00E5FF")
            paint.alpha = 255
            canvas.drawCircle(cx, cy, 70f, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#05080E")
            canvas.drawCircle(cx, cy, 18f, paint)

            paint.color = Color.parseColor("#00E5FF")
            paint.textSize = 28f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText("SB-Z STUDIO", cx, cy - 105f, paint)

            paint.color = Color.parseColor("#E2E8F0")
            paint.textSize = 22f
            val displayTitle = (track?.title?: "Hi-Res Audio").take(22)
            canvas.drawText(displayTitle, cx, cy + 130f, paint)

            paint.color = Color.parseColor("#94A3B8")
            paint.textSize = 16f
            val displayArtist = (track?.artist?: "Pro 32-bit DSP").take(26)
            canvas.drawText(displayArtist, cx, cy + 160f, paint)

            bitmap = bmp
        }

        if (track!= null) {
            cachedArtworkTrackId = track.id
        }
        cachedArtworkBitmap = bitmap
        return bitmap
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val currentTrack = getCurrentTrack()
        val title = currentTrack?.title?: "SB-Z Hi-Res Player"
        val artist = currentTrack?.artist?: "SB-Z Studio Pro DSP"

        val openActivityIntent = PendingIntent.getActivity(
            this, 0,
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

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val artworkBitmap = getArtworkBitmap(currentTrack)

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
           .setMediaSession(mediaSessionCompat.sessionToken)
           .setShowActionsInCompactView(0, 1, 2)
           .setShowCancelButton(true)
           .setCancelButtonIntent(stopIntent)

        val emuTag = when {
            dspProcessor.isBluetoothAutoBypass() && dspProcessor.isBluetoothConnected() -> " [BT Bypass]"
            dspProcessor.isEmulationEnabled() -> " [ATS2835P]"
            else -> ""
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
           .setContentTitle(title)
           .setContentText(artist)
           .setSubText("SB-Z • 32-Band Pro DSP$emuTag")
           .setSmallIcon(R.drawable.ic_launcher_foreground)
           .setLargeIcon(artworkBitmap)
           .setStyle(mediaStyle)
           .setContentIntent(openActivityIntent)
           .setOngoing(isPlaying)
           .setOnlyAlertOnce(true)
           .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
           .setPriority(NotificationCompat.PRIORITY_MAX)
           .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
           .addAction(R.drawable.ic_skip_previous, "Anterior", prevIntent)
           .addAction(playPauseIcon, if (isPlaying) "Pausar" else "Reproducir", toggleIntent)
           .addAction(R.drawable.ic_skip_next, "Siguiente", nextIntent)
           .addAction(R.drawable.ic_stop, "Detener", stopIntent)
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
            updateMediaSessionMetadataAndState()
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
        if (tracks.isEmpty()) return
        val isSameList = playlist.size == tracks.size && playlist.indices.all { playlist[it].id == tracks[it].id }
        if (isSameList) {
            if (startIndex in playlist.indices) playTrackAtIndex(startIndex, startPlaying)
            return
        }
        playlist.clear()
        playlist.addAll(tracks)
        val mediaItems = ArrayList<MediaItem>(playlist.size)
        for (track in playlist) {
            val mediaItem = MediaItem.Builder()
               .setUri(track.uri)
               .setMediaId(track.uri)
               .build()
            mediaItems.add(mediaItem)
        }
        val targetIndex = if (startIndex in playlist.indices) startIndex else 0
        player.setMediaItems(mediaItems, targetIndex, 0L)
        player.prepare()
        if (targetIndex in playlist.indices) {
            playTrackAtIndex(targetIndex, startPlaying)
        }
    }

    fun setPlaylist(tracks: List<Track>) { setPlaylist(tracks, 0, true) }
    fun setPlaylist(tracks: List<Track>, startPlaying: Boolean) { setPlaylist(tracks, 0, startPlaying) }
    fun setPlaylist(tracks: List<Track>, startIndex: Int) { setPlaylist(tracks, startIndex, true) }
    fun setPlaybackSpeed(speed: Float) { player.playbackParameters = PlaybackParameters(speed) }

    fun playTrackAtIndex(index: Int, startPlaying: Boolean = true) {
        if (index!in playlist.indices) return
        currentTrackIndex = index
        if (player.currentMediaItemIndex!= index) {
            player.seekToDefaultPosition(index)
        }
        if (startPlaying) {
            player.play()
            audioChain.startFadeIn()
        }
        savePlaybackSession()
        updateNotification(startPlaying)
        onTrackChangedListener?.invoke(getCurrentTrack(), currentTrackIndex)
    }

    fun savePlaybackSession() {
        try {
            val prefs = getSharedPreferences("sjbz_dsp_pro", Context.MODE_PRIVATE)
            val currentTrack = getCurrentTrack()
            prefs.edit().apply {
                putLong("last_track_id", currentTrack?.id?: -1L)
                putInt("last_track_index", currentTrackIndex)
                putLong("last_position_ms", player.currentPosition)
                putFloat("last_speed", player.playbackParameters.speed)
                apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun restorePlaybackSession(): Pair<Int, Long> {
        val prefs = getSharedPreferences("sjbz_dsp_pro", Context.MODE_PRIVATE)
        val index = prefs.getInt("last_track_index", 0)
        val pos = prefs.getLong("last_position_ms", 0L)
        return Pair(index, pos)
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun play() { player.play() }
    fun pause() { player.pause() }

    fun stopPlayback() {
        player.stop()
        player.seekTo(0)
        handleWakeLockState(false)
        updateNotification(false)
    }

    fun stop() { stopPlayback() }

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
        } else {
            val prevIndex = currentTrackIndex - 1
            if (prevIndex >= 0) {
                playTrackAtIndex(prevIndex, true)
            } else if (isLoopPlaylistEnabled) {
                playTrackAtIndex(playlist.size - 1, true)
            }
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

    fun getCurrentTrack(): Track? =
        if (currentTrackIndex in playlist.indices) playlist[currentTrackIndex] else null

    fun getPlaylist(): List<Track> = playlist
    fun getCurrentIndex(): Int = currentTrackIndex

    override fun onTaskRemoved(rootIntent: Intent?) {
        updateNotification(player.isPlaying)
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Detach spectrum bridge to avoid leaking activity references
        dspProcessor.fftListener = null
        spectrumListener = null
        bluetoothDetector.stop()
        audioChain.release()
        try {
            if (::mediaSessionCompat.isInitialized) mediaSessionCompat.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
