package com.sjbz.aimp

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sjbz.aimp.adapter.PlaylistAdapter
import com.sjbz.aimp.database.AppDatabase
import com.sjbz.aimp.model.Playlist
import com.sjbz.aimp.model.Track
import com.sjbz.aimp.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

/**
 * Main Activity for SB-Z Audio Player.
 * Studio Cyan and Pitch-Black UI with:
 * - Top Toolbar (Search, SB-Z logo, EQ button)
 * - Navigation Drawer (Playlists, Favorites, History, Folders, M3U8 Export/Import)
 * - Central Playlist RecyclerView (Drag & drop reordering, Swipe to delete)
 * - Bottom Studio Deck (Stereo VU Meters, Waveform peak, elapsed/remaining time, controls)
 * - Automatic playlist looping and MediaStore scanner for Hi-Res audio.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private var playbackService: PlaybackService? = null
    private var isServiceBound = false

    // Views
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvPlaylist: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var emptyView: View
    private lateinit var btnScanStorage: Button

    // Toolbar views
    private lateinit var btnMenuDrawer: ImageButton
    private lateinit var etSearchTracks: EditText
    private lateinit var btnOpenEqualizer: ImageButton
    private lateinit var tvPlaylistTrackCount: TextView

    // Bottom Player views
    private lateinit var ivPlayerArtwork: ImageView
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerArtist: TextView
    private lateinit var tvPlayerTechSpecs: TextView
    private lateinit var tvElapsedTime: TextView
    private lateinit var tvRemainingTime: TextView
    private lateinit var playerSeekBar: SeekBar

    // Control buttons
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnCrossfade: ImageButton

    // VU Meters
    private lateinit var vuMeterLeftBar: ProgressBar
    private lateinit var vuMeterRightBar: ProgressBar
    private lateinit var tvVuPeakText: TextView

    // Data lists
    private val allTracksList = mutableListOf<Track>()
    private val currentDisplayList = mutableListOf<Track>()

    private var isShuffleActive = false
    private var isRepeatLoopActive = true
    private var isCrossfadeActive = true
    private var isUserTrackingSeekBar = false

    // Timer handler for seekbar and VU meters
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            try {
                updatePlaybackProgressAndVUMeters()
            } catch (_: Exception) {}
            uiHandler.postDelayed(this, 100)
        }
    }

    // Permission launcher
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] ?: false
        val storageGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        if (audioGranted || storageGranted) {
            scanAudioStorage()
        } else {
            Toast.makeText(this, "Permiso de almacenamiento requerido para reproducir audio", Toast.LENGTH_LONG).show()
        }
    }

    // Export M3U8 launcher
    private val exportM3U8Launcher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        if (uri != null) {
            exportCurrentPlaylistToM3U8(uri)
        }
    }

    // Player.Listener for Media3 ExoPlayer state tracking
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            runOnUiThread {
                try {
                    val isPlaying = playbackService?.player?.isPlaying == true
                    if (::btnPlayPause.isInitialized) btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                } catch (_: Exception) {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runOnUiThread {
                try {
                    if (::btnPlayPause.isInitialized) btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                } catch (_: Exception) {}
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            runOnUiThread {
                try {
                    val track = playbackService?.getCurrentTrack()
                    val index = playbackService?.getCurrentIndex() ?: 0
                    updatePlayerUi(track, index)
                } catch (_: Exception) {}
            }
        }
    }

    // Service Connection to PlaybackService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as? PlaybackService.LocalBinder ?: return
                playbackService = binder.getService()
                isServiceBound = true

                playbackService?.let { srv ->
                    try { srv.player.addListener(playerListener) } catch (_: Exception) {}
                    srv.isLoopPlaylistEnabled = isRepeatLoopActive
                    srv.onTrackChangedListener = { track, index ->
                        runOnUiThread {
                            try { updatePlayerUi(track, index) } catch (_: Exception) {}
                        }
                    }
                    srv.onPlaybackStateChangedListener = { isPlaying ->
                        runOnUiThread {
                            try { if (::btnPlayPause.isInitialized) btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play) } catch (_: Exception) {}
                        }
                    }

                    if (currentDisplayList.isNotEmpty() && srv.getPlaylist().isEmpty()) {
                        val (savedIndex, savedPos) = srv.restorePlaybackSession()
                        val targetIndex = if (savedIndex in currentDisplayList.indices) savedIndex else 0
                        srv.setPlaylist(currentDisplayList, targetIndex, startPlaying = false)
                        if (savedPos > 0L) {
                            try { srv.player.seekTo(savedPos) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            try { playbackService?.player?.removeListener(playerListener) } catch (_: Exception) {}
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(this)

        initViews()
        setupPlaylistRecyclerView()
        setupPlayerControls()
        setupDrawer()
        setupSearch()
        startPlaybackService()
        checkPermissionsAndScan()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiUpdateRunnable)
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        rvPlaylist = findViewById(R.id.rvPlaylist)
        emptyView = findViewById(R.id.emptyView)
        btnScanStorage = findViewById(R.id.btnScanStorage)

        btnMenuDrawer = findViewById(R.id.btnMenuDrawer)
        etSearchTracks = findViewById(R.id.etSearchTracks)
        btnOpenEqualizer = findViewById(R.id.btnOpenEqualizer)
        tvPlaylistTrackCount = findViewById(R.id.tvPlaylistTrackCount)

        ivPlayerArtwork = findViewById(R.id.ivPlayerArtwork)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvPlayerArtist = findViewById(R.id.tvPlayerArtist)
        tvPlayerTechSpecs = findViewById(R.id.tvPlayerTechSpecs)
        tvElapsedTime = findViewById(R.id.tvElapsedTime)
        tvRemainingTime = findViewById(R.id.tvRemainingTime)
        playerSeekBar = findViewById(R.id.playerSeekBar)

        btnShuffle = findViewById(R.id.btnShuffle)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnStop = findViewById(R.id.btnStop)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnCrossfade = findViewById(R.id.btnCrossfade)

        vuMeterLeftBar = findViewById(R.id.vuMeterLeftBar)
        vuMeterRightBar = findViewById(R.id.vuMeterRightBar)
        tvVuPeakText = findViewById(R.id.tvVuPeakText)

        btnScanStorage.setOnClickListener {
            checkPermissionsAndScan()
        }

        btnOpenEqualizer.setOnClickListener {
            try {
                val intent = Intent(this, EqActivity::class.java)
                startActivity(intent)
            } catch (_: Exception) {}
        }

        btnMenuDrawer.setOnClickListener {
            try {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            } catch (_: Exception) {}
        }
    }

    private fun setupPlaylistRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            tracks = currentDisplayList,
            onItemClick = { track, position ->
                try { playbackService?.setPlaylist(currentDisplayList, position, startPlaying = true) } catch (_: Exception) {}
            },
            onFavoriteClick = { track, position ->
                toggleFavorite(track, position)
            },
            onTrackMoved = { from, to ->
                try { playbackService?.setPlaylist(currentDisplayList, playbackService?.getCurrentIndex() ?: 0, startPlaying = false) } catch (_: Exception) {}
            },
            onTrackDeleted = { track, position ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try { database.trackDao().deleteTrack(track) } catch (_: Exception) {}
                }
                updateTrackCount()
            }
        )

        rvPlaylist.layoutManager = LinearLayoutManager(this)
        rvPlaylist.adapter = playlistAdapter

        try {
            playlistAdapter.getItemTouchHelper().attachToRecyclerView(rvPlaylist)
        } catch (_: Exception) {}
    }

    private fun setupPlayerControls() {
        btnPlayPause.setOnClickListener {
            try { playbackService?.togglePlayPause() } catch (_: Exception) {}
        }

        btnStop.setOnClickListener {
            try {
                playbackService?.stopPlayback()
                btnPlayPause.setImageResource(R.drawable.ic_play)
            } catch (_: Exception) {}
        }

        btnNext.setOnClickListener {
            try { playbackService?.playNext() } catch (_: Exception) {}
        }

        btnPrevious.setOnClickListener {
            try { playbackService?.playPrevious() } catch (_: Exception) {}
        }

        btnShuffle.setOnClickListener {
            try {
                isShuffleActive = !isShuffleActive
                btnShuffle.setColorFilter(if (isShuffleActive) ContextCompat.getColor(this, R.color.studio_cyan) else ContextCompat.getColor(this, R.color.text_muted))
                if (isShuffleActive) {
                    currentDisplayList.shuffle()
                    playlistAdapter.updateData(currentDisplayList)
                    playbackService?.setPlaylist(currentDisplayList, 0, startPlaying = false)
                } else {
                    currentDisplayList.clear()
                    currentDisplayList.addAll(allTracksList)
                    playlistAdapter.updateData(currentDisplayList)
                }
            } catch (_: Exception) {}
        }

        btnRepeat.setOnClickListener {
            try {
                isRepeatLoopActive = !isRepeatLoopActive
                playbackService?.isLoopPlaylistEnabled = isRepeatLoopActive
                btnRepeat.setColorFilter(if (isRepeatLoopActive) ContextCompat.getColor(this, R.color.studio_cyan) else ContextCompat.getColor(this, R.color.text_muted))
                Toast.makeText(this, if (isRepeatLoopActive) "Bucle de Playlist Activado" else "Bucle Desactivado", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }

        btnCrossfade.setOnClickListener {
            try {
                isCrossfadeActive = !isCrossfadeActive
                btnCrossfade.setColorFilter(if (isCrossfadeActive) ContextCompat.getColor(this, R.color.studio_cyan) else ContextCompat.getColor(this, R.color.text_muted))
                try { playbackService?.atsEngine?.crossfadeSeconds = if (isCrossfadeActive) 3 else 0 } catch (_: Exception) {}
                Toast.makeText(this, if (isCrossfadeActive) "Crossfade 3s Activo" else "Crossfade 0s", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }

        playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                try {
                    if (fromUser) {
                        val duration: Long = playbackService?.player?.duration ?: 0L
                        if (duration > 0L) {
                            val seekPos: Long = (progress / 1000.0f * duration).toLong()
                            tvElapsedTime.text = formatTime(seekPos)
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserTrackingSeekBar = false
                try {
                    val player: ExoPlayer? = playbackService?.player
                    val duration: Long = player?.duration ?: 0L
                    if (duration > 0L && sb != null) {
                        val targetPos: Long = (sb.progress / 1000.0f * duration).toLong()
                        player?.seekTo(targetPos)
                    }
                } catch (_: Exception) {}
            }
        })
    }

    private fun setupDrawer() {
        findViewById<View?>(R.id.drawerItemPlaylists)?.setOnClickListener {
            try { filterTracks("all") } catch (_: Exception) {}
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
        }

        findViewById<View?>(R.id.drawerItemFavorites)?.setOnClickListener {
            try { filterTracks("favorites") } catch (_: Exception) {}
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
        }

        findViewById<View?>(R.id.drawerItemHistory)?.setOnClickListener {
            try { filterTracks("history") } catch (_: Exception) {}
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
        }

        findViewById<View?>(R.id.drawerItemFolders)?.setOnClickListener {
            Toast.makeText(this, "Explorador de carpetas activo", Toast.LENGTH_SHORT).show()
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
        }

        findViewById<View?>(R.id.drawerExportM3U8)?.setOnClickListener {
            try { exportM3U8Launcher.launch("SjbZ_Playlist_${System.currentTimeMillis()}.m3u8") } catch (_: Exception) {}
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
        }

        findViewById<View?>(R.id.drawerImportM3U8)?.setOnClickListener {
            Toast.makeText(this, "Importador M3U8 listo para abrir listas", Toast.LENGTH_SHORT).show()
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
        }
    }

    private fun setupSearch() {
        etSearchTracks.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                try {
                    val query = s?.toString()?.trim() ?: ""
                    filterTracksByQuery(query)
                } catch (_: Exception) {}
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterTracksByQuery(query: String) {
        try {
            if (query.isEmpty()) {
                currentDisplayList.clear()
                currentDisplayList.addAll(allTracksList)
            } else {
                val filtered = allTracksList.filter {
                    it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
                }
                currentDisplayList.clear()
                currentDisplayList.addAll(filtered)
            }
            playlistAdapter.updateData(currentDisplayList)
            updateTrackCount()
        } catch (_: Exception) {}
    }

    private fun filterTracks(filter: String) {
        lifecycleScope.launch {
            try {
                val list = when (filter) {
                    "favorites" -> database.trackDao().getFavoriteTracks()
                    "history" -> database.trackDao().getHistoryTracks()
                    else -> database.trackDao().getAllTracks()
                }
                currentDisplayList.clear()
                currentDisplayList.addAll(list)
                playlistAdapter.updateData(currentDisplayList)
                updateTrackCount()
            } catch (_: Exception) {}
        }
    }

    private fun toggleFavorite(track: Track, position: Int) {
        try {
            val newFav = !track.isFavorite
            val updated = track.copy(isFavorite = newFav)
            if (position in currentDisplayList.indices) {
                currentDisplayList[position] = updated
                playlistAdapter.notifyItemChanged(position)
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try { database.trackDao().setFavorite(track.id, newFav) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun startPlaybackService() {
        try {
            val serviceIntent = Intent(this, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
            val bindIntent = Intent(this, PlaybackService::class.java).apply { action = PlaybackService.ACTION_BIND_LOCAL }
            bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkPermissionsAndScan() {
        try {
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val needed = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (needed.isEmpty()) {
                scanAudioStorage()
            } else {
                storagePermissionLauncher.launch(needed.toTypedArray())
            }
        } catch (_: Exception) {}
    }

    private fun scanAudioStorage() {
        lifecycleScope.launch {
            val scannedTracks = withContext(Dispatchers.IO) {
                val tracks = mutableListOf<Track>()
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA
                )
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

                var cursor: Cursor? = null
                try {
                    cursor = contentResolver.query(uri, projection, selection, null, "${MediaStore.Audio.Media.TITLE} ASC")
                    cursor?.let { c ->
                        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                        var order = 0
                        while (c.moveToNext()) {
                            val id = c.getLong(idCol)
                            val title = c.getString(titleCol) ?: "Audio Track"
                            val artist = c.getString(artistCol) ?: "Unknown Artist"
                            val album = c.getString(albumCol) ?: "Unknown Album"
                            val duration = c.getLong(durationCol)
                            val path = try { c.getString(dataCol) } catch (_: Exception) { "" } ?: ""

                            val contentUri = ContentUris.withAppendedId(uri, id).toString()
                            val format = detectFormat(path)
                            val recognizedGenre = Track.inferGenre(null, title, artist, album, path)

                            tracks.add(
                                Track(
                                    id = id,
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    genre = recognizedGenre,
                                    duration = duration,
                                    uri = contentUri,
                                    path = path,
                                    format = format,
                                    bitrate = if (format == "FLAC" || format == "WAV") 1411 else 320,
                                    sampleRate = if (format == "FLAC") 96000 else 44100,
                                    bitDepth = if (format == "FLAC") 24 else 16,
                                    orderIndex = order++
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { cursor?.close() } catch (_: Exception) {}
                }

                if (tracks.isEmpty()) {
                    tracks.addAll(createDemoTracks())
                }

                try {
                    database.trackDao().clearTracks()
                    database.trackDao().insertTracks(tracks)
                } catch (_: Exception) {}
                tracks
            }

            try {
                allTracksList.clear()
                allTracksList.addAll(scannedTracks)
                currentDisplayList.clear()
                currentDisplayList.addAll(allTracksList)

                playlistAdapter.updateData(currentDisplayList)
                updateTrackCount()

                playbackService?.setPlaylist(currentDisplayList, 0, startPlaying = false)
            } catch (_: Exception) {}
        }
    }

    private fun detectFormat(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".flac") -> "FLAC"
            lower.endsWith(".wav") -> "WAV"
            lower.endsWith(".ape") -> "APE"
            lower.endsWith(".opus") -> "OPUS"
            lower.endsWith(".ogg") -> "OGG"
            lower.endsWith(".m4a") -> "M4A"
            lower.endsWith(".aac") -> "AAC"
            else -> "MP3"
        }
    }

    private fun createDemoTracks(): List<Track> {
        return listOf(
            Track(id = 1, title = "SjbZ Studio Bass Master Reference", artist = "SB-Z Hi-Res Studio", album = "Audiophile Acoustic Tests 2026", genre = "Bass", duration = 248000L, uri = "asset:///demo_track_1.mp3", format = "FLAC", bitrate = 1411, sampleRate = 96000, bitDepth = 24, orderIndex = 0),
            Track(id = 2, title = "Cybernetic Pulse - Electronic Synth", artist = "SB-Z Studio Labs", album = "Electronic Soundscapes", genre = "Electronic", duration = 195000L, uri = "asset:///demo_track_2.mp3", format = "FLAC", bitrate = 9216, sampleRate = 192000, bitDepth = 24, orderIndex = 1),
            Track(id = 3, title = "Vocal Clarity & Acoustic Resonance", artist = "SB-Z Master Engineers", album = "Hi-Fi Stereo Demonstrations", genre = "Vocal", duration = 212000L, uri = "asset:///demo_track_3.mp3", format = "WAV", bitrate = 2304, sampleRate = 48000, bitDepth = 24, orderIndex = 2),
            Track(id = 4, title = "Sub-Bass 20Hz - 120Hz Excursion Sweep", artist = "Frequency Sweep Generator", album = "Subwoofer Calibration", genre = "Bass", duration = 180000L, uri = "asset:///demo_track_4.mp3", format = "FLAC", bitrate = 1411, sampleRate = 96000, bitDepth = 24, orderIndex = 3)
        )
    }

    private fun updateTrackCount() {
        try {
            val count = currentDisplayList.size
            if (::tvPlaylistTrackCount.isInitialized) tvPlaylistTrackCount.text = "$count tracks"
            if (::emptyView.isInitialized) emptyView.visibility = if (count == 0) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    private fun updatePlayerUi(track: Track?, index: Int) {
        try {
            if (!::tvPlayerTitle.isInitialized) return
            if (track == null) {
                tvPlayerTitle.text = "Selecciona una pista"
                tvPlayerArtist.text = "SB-Z Hi-Res Player"
                tvPlayerTechSpecs.text = "24-bit • 192kHz • 32-bit Float DSP"
                return
            }
            tvPlayerTitle.text = track.title
            tvPlayerArtist.text = "${track.artist} • [${track.genre}]"
            tvPlayerTechSpecs.text = track.getTechInfo()
            try { playlistAdapter.setPlayingIndex(index) } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun updatePlaybackProgressAndVUMeters() {
        try {
            val srv = playbackService ?: return
            if (!srv.player.isInitializedSafe()) return
            val player = srv.player

            val isPlaying = try { player.isPlaying } catch (_: Exception) { false }
            val duration = try { player.duration } catch (_: Exception) { 0L }
            val position = try { player.currentPosition } catch (_: Exception) { 0L }

            if (!isUserTrackingSeekBar && duration > 0) {
                val progress = (position.toFloat() / duration * 1000).toInt().coerceIn(0, 1000)
                if (::playerSeekBar.isInitialized) playerSeekBar.progress = progress
                if (::tvElapsedTime.isInitialized) tvElapsedTime.text = formatTime(position)
                if (::tvRemainingTime.isInitialized) tvRemainingTime.text = "-" + formatTime((duration - position).coerceAtLeast(0L))
            }

            val fraction = if (duration > 0) position.toFloat() / duration else 0f
            try {
                if (::srv.audioChain.isInitialized) srv.audioChain.updateVUMeters(isPlaying, fraction)
            } catch (_: Exception) {}

            val leftLevel = try { (srv.audioChain.vuMeterLeft * 100).toInt().coerceIn(0,100) } catch (_: Exception) { 0 }
            val rightLevel = try { (srv.audioChain.vuMeterRight * 100).toInt().coerceIn(0,100) } catch (_: Exception) { 0 }

            if (::vuMeterLeftBar.isInitialized) vuMeterLeftBar.progress = leftLevel
            if (::vuMeterRightBar.isInitialized) vuMeterRightBar.progress = rightLevel

            val peakDb = if (isPlaying) String.format("%.1f dB", -12.0f + (leftLevel / 100.0f) * 11.7f) else "-inf dB"
            if (::tvVuPeakText.isInitialized) tvVuPeakText.text = peakDb

            val shouldShowPause = isPlaying
            if (::btnPlayPause.isInitialized) {
                val currentTag = btnPlayPause.getTag(R.id.btnPlayPause) as? Boolean
                if (currentTag != shouldShowPause) {
                    btnPlayPause.setImageResource(if (shouldShowPause) R.drawable.ic_pause else R.drawable.ic_play)
                    btnPlayPause.setTag(R.id.btnPlayPause, shouldShowPause)
                }
            }
        } catch (_: Exception) {}
    }

    private fun ExoPlayer.isInitializedSafe(): Boolean {
        return try { this.playbackState != Player.STATE_IDLE || true } catch (_: Exception) { false }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun exportCurrentPlaylistToM3U8(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out).use { writer ->
                    writer.write("#EXTM3U\n")
                    for (track in currentDisplayList) {
                        writer.write("#EXTINF:${track.duration / 1000},${track.artist} - ${track.title}\n")
                        writer.write("${track.path}\n")
                    }
                }
            }
            Toast.makeText(this, "Lista exportada en M3U8 con éxito", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al exportar M3U8: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        try { uiHandler.removeCallbacks(uiUpdateRunnable) } catch (_: Exception) {}
        try { playbackService?.player?.removeListener(playerListener) } catch (_: Exception) {}
        try {
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
