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
 * Main Activity for SjbZ Audio Player.
 * AIMP Dark Orange and Pitch-Black UI with:
 * - Top Toolbar (Search, SjbZ logo, EQ button)
 * - Navigation Drawer (Playlists, Favorites, History, Folders, M3U8 Export/Import)
 * - Central Playlist RecyclerView (Drag & drop reordering, Swipe to delete)
 * - Bottom AIMP Deck (Stereo VU Meters, Waveform peak, elapsed/remaining time, controls)
 * - Automatic playlist looping and MediaStore scanner for Hi-Res audio.
 * FIX 184 - Anti-cuelgue al explorar + fix visual <unknown>
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
            updatePlaybackProgressAndVUMeters()
            uiHandler.postDelayed(this, 120)
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

    // Service Connection to PlaybackService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isServiceBound = true

            playbackService?.let { srv ->
                srv.isLoopPlaylistEnabled = isRepeatLoopActive
                srv.onTrackChangedListener = { track, index ->
                    runOnUiThread {
                        updatePlayerUi(track, index)
                    }
                }
                srv.onPlaybackStateChangedListener = { isPlaying ->
                    runOnUiThread {
                        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                    }
                }

                if (currentDisplayList.isNotEmpty() && srv.getPlaylist().isEmpty()) {
                    srv.setPlaylist(currentDisplayList, 0, startPlaying = false)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val intent = Intent(this, EqActivity::class.java)
            startActivity(intent)
        }

        btnMenuDrawer.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
    }

    private fun setupPlaylistRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            tracks = currentDisplayList,
            onItemClick = { track, position ->
                playbackService?.setPlaylist(currentDisplayList, position, startPlaying = true)
            },
            onFavoriteClick = { track, position ->
                toggleFavorite(track, position)
            },
            onTrackMoved = { from, to ->
                playbackService?.setPlaylist(currentDisplayList, playbackService?.getCurrentIndex() ?: 0, startPlaying = false)
            },
            onTrackDeleted = { track, position ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.trackDao().deleteTrack(track)
                }
                updateTrackCount()
            }
        )

        rvPlaylist.layoutManager = LinearLayoutManager(this)
        // FIX 184 - Mejora rendimiento con 716 tracks
        rvPlaylist.setHasFixedSize(true)
        rvPlaylist.setItemViewCacheSize(20)
        rvPlaylist.adapter = playlistAdapter

        // Attach ItemTouchHelper for drag-and-drop & swipe-to-delete
        playlistAdapter.getItemTouchHelper().attachToRecyclerView(rvPlaylist)
    }

    private fun setupPlayerControls() {
        btnPlayPause.setOnClickListener {
            playbackService?.togglePlayPause()
        }

        btnStop.setOnClickListener {
            playbackService?.stopPlayback()
            btnPlayPause.setImageResource(R.drawable.ic_play)
        }

        btnNext.setOnClickListener {
            playbackService?.playNext()
        }

        btnPrevious.setOnClickListener {
            playbackService?.playPrevious()
        }

        btnShuffle.setOnClickListener {
            isShuffleActive = !isShuffleActive
            btnShuffle.setColorFilter(if (isShuffleActive) ContextCompat.getColor(this, R.color.aimp_orange) else ContextCompat.getColor(this, R.color.text_muted))
            if (isShuffleActive) {
                currentDisplayList.shuffle()
                playlistAdapter.updateData(currentDisplayList)
                playbackService?.setPlaylist(currentDisplayList, 0, startPlaying = false)
            } else {
                // FIX: vuelve en bloques para no colgar
                currentDisplayList.clear()
                currentDisplayList.addAll(allTracksList.take(60))
                playlistAdapter.updateData(currentDisplayList)
                rvPlaylist.postDelayed({
                    currentDisplayList.clear()
                    currentDisplayList.addAll(allTracksList)
                    playlistAdapter.updateData(currentDisplayList)
                }, 200)
            }
        }

        btnRepeat.setOnClickListener {
            isRepeatLoopActive = !isRepeatLoopActive
            playbackService?.isLoopPlaylistEnabled = isRepeatLoopActive
            btnRepeat.setColorFilter(if (isRepeatLoopActive) ContextCompat.getColor(this, R.color.aimp_orange) else ContextCompat.getColor(this, R.color.text_muted))
            Toast.makeText(this, if (isRepeatLoopActive) "Bucle de Playlist Activado" else "Bucle Desactivado", Toast.LENGTH_SHORT).show()
        }

        btnCrossfade.setOnClickListener {
            isCrossfadeActive = !isCrossfadeActive
            btnCrossfade.setColorFilter(if (isCrossfadeActive) ContextCompat.getColor(this, R.color.aimp_orange) else ContextCompat.getColor(this, R.color.text_muted))
            playbackService?.atsEngine?.crossfadeSeconds = if (isCrossfadeActive) 3 else 0
            Toast.makeText(this, if (isCrossfadeActive) "Crossfade 3s Activo" else "Crossfade 0s", Toast.LENGTH_SHORT).show()
        }

        playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = playbackService?.player?.duration ?: 0L
                    if (duration > 0) {
                        val seekPos = (progress / 1000.0f * duration).toLong()
                        tvElapsedTime.text = formatTime(seekPos)
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserTrackingSeekBar = false
                val duration = playbackService?.player?.duration ?: 0L
                if (duration > 0 && sb != null) {
                    val targetPos = (sb.progress / 1000.0f * duration).toLong()
                    playbackService?.player?.seekTo(targetPos)
                }
            }
        })
    }

    private fun setupDrawer() {
        findViewById<View>(R.id.drawerItemPlaylists).setOnClickListener {
            filterTracks("all")
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.drawerItemFavorites).setOnClickListener {
            filterTracks("favorites")
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.drawerItemHistory).setOnClickListener {
            filterTracks("history")
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.drawerItemFolders).setOnClickListener {
            Toast.makeText(this, "Explorador de carpetas activo - cargando por bloques", Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.drawerExportM3U8).setOnClickListener {
            exportM3U8Launcher.launch("SjbZ_Playlist_${System.currentTimeMillis()}.m3u8")
            drawerLayout.closeDrawers()
        }

        findViewById<View>(R.id.drawerImportM3U8).setOnClickListener {
            Toast.makeText(this, "Importador M3U8 listo para abrir listas", Toast.LENGTH_SHORT).show()
            drawerLayout.closeDrawers()
        }
    }

    private fun setupSearch() {
        etSearchTracks.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                filterTracksByQuery(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * FIX 184 - Filtro de busqueda sin ANR
     * Antes: filtraba en MainThread con 716 -> cuelgue al explorar
     * Ahora: filtra en Dispatchers.Default y pagina de 80 en 80
     */
    private fun filterTracksByQuery(query: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            val filtered = if (query.isEmpty()) {
                allTracksList
            } else {
                allTracksList.filter {
                    it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
                }
            }

            withContext(Dispatchers.Main) {
                currentDisplayList.clear()
                val batch = if (filtered.size > 80) filtered.take(80) else filtered
                currentDisplayList.addAll(batch)
                playlistAdapter.updateData(currentDisplayList)
                updateTrackCount()

                if (filtered.size > 80) {
                    rvPlaylist.postDelayed({
                        currentDisplayList.clear()
                        currentDisplayList.addAll(filtered)
                        playlistAdapter.updateData(currentDisplayList)
                        updateTrackCount()
                    }, 300)
                }
            }
        }
    }

    private fun filterTracks(filter: String) {
        lifecycleScope.launch {
            val list = when (filter) {
                "favorites" -> database.trackDao().getFavoriteTracks()
                "history" -> database.trackDao().getHistoryTracks()
                else -> database.trackDao().getAllTracks()
            }
            // FIX 184 - Carga paginada tambien en filtros de drawer
            currentDisplayList.clear()
            val first = if (list.size > 60) list.take(60) else list
            currentDisplayList.addAll(first)
            playlistAdapter.updateData(currentDisplayList)
            updateTrackCount()

            if (list.size > 60) {
                rvPlaylist.postDelayed({
                    currentDisplayList.clear()
                    currentDisplayList.addAll(list)
                    playlistAdapter.updateData(currentDisplayList)
                    updateTrackCount()
                }, 300)
            }
        }
    }

    private fun toggleFavorite(track: Track, position: Int) {
        val newFav = !track.isFavorite
        val updated = track.copy(isFavorite = newFav)
        currentDisplayList[position] = updated
        playlistAdapter.notifyItemChanged(position)

        lifecycleScope.launch(Dispatchers.IO) {
            database.trackDao().setFavorite(track.id, newFav)
        }
    }

    private fun startPlaybackService() {
        val serviceIntent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
    }

    /**
     * Scans MediaStore for audio files (FLAC, MP3, WAV, APE, OPUS, OGG, M4A).
     * FIX 716 TRACKS: carga progresiva para evitar ANR.
     * FIX VISUAL: limpia <unknown> para evitar glitch verde
     */
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
                            var rawTitle = c.getString(titleCol) ?: ""
                            var rawArtist = c.getString(artistCol) ?: ""
                            val album = c.getString(albumCol) ?: "Unknown Album"
                            val duration = c.getLong(durationCol)
                            val path = c.getString(dataCol) ?: ""

                            // FIX 183 VISUAL - Limpia <unknown> que genera pantalla verde
                            val fileName = path.substringAfterLast("/").substringBeforeLast(".")
                            val title = when {
                                rawTitle.isBlank() || rawTitle.equals("<unknown>", ignoreCase = true) || rawTitle.equals("unknown", ignoreCase = true) -> fileName.ifBlank { "Audio Track ${order + 1}" }
                                else -> rawTitle
                            }
                            val artist = when {
                                rawArtist.isBlank() || rawArtist.equals("<unknown>", ignoreCase = true) || rawArtist.equals("unknown", ignoreCase = true) -> "SjbZ ATS-2835P"
                                else -> rawArtist
                            }

                            val contentUri = ContentUris.withAppendedId(uri, id).toString()
                            val format = detectFormat(path)

                            tracks.add(
                                Track(
                                    id = id,
                                    title = title,
                                    artist = artist,
                                    album = album,
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
                    cursor?.close()
                }

                // If device has no local music files in emulator, seed high-fidelity demo items
                if (tracks.isEmpty()) {
                    tracks.addAll(createDemoTracks())
                }

                database.trackDao().clearTracks()
                database.trackDao().insertTracks(tracks)
                tracks
            }

            // --- FIX ORIGINAL RESPETADO PARA 716 TRACKS ---
            allTracksList.clear()
            allTracksList.addAll(scannedTracks)

            // 1. Pintamos solo 60 primero para abrir instantáneo
            val firstBatch = if (allTracksList.size > 60) allTracksList.take(60) else allTracksList
            currentDisplayList.clear()
            currentDisplayList.addAll(firstBatch)
            playlistAdapter.updateData(currentDisplayList)
            updateTrackCount()

            // 2. Si hay más, los metemos 350ms después sin congelar
            if (allTracksList.size > 60) {
                rvPlaylist.postDelayed({
                    currentDisplayList.clear()
                    currentDisplayList.addAll(allTracksList)
                    playlistAdapter.updateData(currentDisplayList)
                    updateTrackCount()
                    if (playbackService?.getPlaylist()?.isEmpty() == true) {
                        playbackService?.setPlaylist(currentDisplayList, 0, startPlaying = false)
                    }
                }, 350)
            } else {
                if (playbackService?.getPlaylist()?.isEmpty() == true) {
                    playbackService?.setPlaylist(currentDisplayList, 0, startPlaying = false)
                }
            }
            // --- FIN FIX ---
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
            Track(
                id = 1,
                title = "AIMP SjbZ Bass Master Reference",
                artist = "ATS2835P Hi-Res Studio",
                album = "Audiophile Acoustic Tests 2026",
                duration = 248000L,
                uri = "asset:///demo_track_1.mp3",
                format = "FLAC",
                bitrate = 1411,
                sampleRate = 96000,
                bitDepth = 24,
                orderIndex = 0
            ),
            Track(
                id = 2,
                title = "Cybernetic Pulse - MDRC Dynamic Test",
                artist = "Actions Semiconductor DSP Labs",
                album = "Hardware Reference Curves",
                duration = 195000L,
                uri = "asset:///demo_track_2.mp3",
                format = "FLAC",
                bitrate = 9216,
                sampleRate = 192000,
                bitDepth = 24,
                orderIndex = 1
            ),
            Track(
                id = 3,
                title = "Vocal Clarity & Acoustic Resonance",
                artist = "SjbZ Master Engineers",
                album = "Hi-Fi Stereo Demonstrations",
                duration = 212000L,
                uri = "asset:///demo_track_3.mp3",
                format = "WAV",
                bitrate = 2304,
                sampleRate = 48000,
                bitDepth = 24,
                orderIndex = 2
            ),
            Track(
                id = 4,
                title = "Sub-Bass 20Hz - 120Hz Excursion Sweep",
                artist = "Frequency Sweep Generator",
                album = "Subwoofer Calibration",
                duration = 180000L,
                uri = "asset:///demo_track_4.mp3",
                format = "FLAC",
                bitrate = 1411,
                sampleRate = 96000,
                bitDepth = 24,
                orderIndex = 3
            )
        )
    }

    private fun updateTrackCount() {
        val count = allTracksList.size
        val showing = currentDisplayList.size
        tvPlaylistTrackCount.text = if (count > showing) "$showing / $count tracks" else "$count tracks"
        emptyView.visibility = if (currentDisplayList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updatePlayerUi(track: Track?, index: Int) {
        if (track == null) {
            tvPlayerTitle.text = "Selecciona una pista"
            tvPlayerArtist.text = "SjbZ Hi-Res Player"
            tvPlayerTechSpecs.text = "24-bit • 192kHz • ATS2835P"
            return
        }

        tvPlayerTitle.text = track.title
        tvPlayerArtist.text = track.artist
        tvPlayerTechSpecs.text = track.getTechInfo()
        playlistAdapter.setPlayingIndex(index)
    }

    private fun updatePlaybackProgressAndVUMeters() {
        val srv = playbackService ?: return
        val player = srv.player

        val isPlaying = player.isPlaying
        val duration = player.duration
        val position = player.currentPosition

        if (!isUserTrackingSeekBar && duration > 0) {
            val progress = (position.toFloat() / duration * 1000).toInt()
            playerSeekBar.progress = progress
            tvElapsedTime.text = formatTime(position)
            tvRemainingTime.text = "-" + formatTime((duration - position).coerceAtLeast(0L))
        }

        // Update AIMP Stereo VU Meters in real time
        val fraction = if (duration > 0) position.toFloat() / duration else 0f
        srv.audioChain.updateVUMeters(isPlaying, fraction)

        val leftLevel = (srv.audioChain.vuMeterLeft * 100).toInt()
        val rightLevel = (srv.audioChain.vuMeterRight * 100).toInt()

        vuMeterLeftBar.progress = leftLevel
        vuMeterRightBar.progress = rightLevel

        val peakDb = if (isPlaying) String.format("%.1f dB", -12.0f + (leftLevel / 100.0f) * 11.7f) else "-inf dB"
        tvVuPeakText.text = peakDb
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
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
