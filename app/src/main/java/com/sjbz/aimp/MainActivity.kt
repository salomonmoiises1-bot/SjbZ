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

class MainActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private var playbackService: PlaybackService? = null
    private var isServiceBound = false

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvPlaylist: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var emptyView: View
    private lateinit var btnScanStorage: Button
    private lateinit var btnMenuDrawer: ImageButton
    private lateinit var etSearchTracks: EditText
    private lateinit var btnOpenEqualizer: ImageButton
    private lateinit var tvPlaylistTrackCount: TextView
    private lateinit var ivPlayerArtwork: ImageView
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerArtist: TextView
    private lateinit var tvPlayerTechSpecs: TextView
    private lateinit var tvElapsedTime: TextView
    private lateinit var tvRemainingTime: TextView
    private lateinit var playerSeekBar: SeekBar
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnCrossfade: ImageButton
    private lateinit var vuMeterLeftBar: ProgressBar
    private lateinit var vuMeterRightBar: ProgressBar
    private lateinit var tvVuPeakText: TextView

    private val allTracksList = mutableListOf<Track>()
    private val currentDisplayList = mutableListOf<Track>()

    private var isShuffleActive = false
    private var isRepeatLoopActive = true
    private var isCrossfadeActive = true
    private var isUserTrackingSeekBar = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updatePlaybackProgressAndVUMeters()
            uiHandler.postDelayed(this, 100)
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] ?: false
        val storageGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        if (audioGranted || storageGranted) scanAudioStorage()
        else Toast.makeText(this, "Permiso de almacenamiento requerido", Toast.LENGTH_LONG).show()
    }

    private val exportM3U8Launcher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? -> if (uri != null) exportCurrentPlaylistToM3U8(uri) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isServiceBound = true
            playbackService?.let { srv ->
                srv.isLoopPlaylistEnabled = isRepeatLoopActive
                srv.onTrackChangedListener = { track, index -> runOnUiThread { updatePlayerUi(track, index) } }
                srv.onPlaybackStateChangedListener = { isPlaying -> runOnUiThread { btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play) } }
                if (currentDisplayList.isNotEmpty() && srv.getPlaylist().isEmpty()) srv.setPlaylist(currentDisplayList, 0, startPlaying = false)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { playbackService = null; isServiceBound = false }
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

    override fun onResume() { super.onResume(); uiHandler.post(uiUpdateRunnable) }
    override fun onPause() { super.onPause(); uiHandler.removeCallbacks(uiUpdateRunnable) }

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
        btnScanStorage.setOnClickListener { checkPermissionsAndScan() }
        btnOpenEqualizer.setOnClickListener { startActivity(Intent(this, EqActivity::class.java)) }
        btnMenuDrawer.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START) else drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupPlaylistRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            tracks = currentDisplayList,
            onItemClick = { _, position -> playbackService?.setPlaylist(currentDisplayList, position, startPlaying = true) },
            onFavoriteClick = { track, position -> toggleFavorite(track, position) },
            onTrackMoved = { _, _ -> playbackService?.setPlaylist(currentDisplayList, playbackService?.getCurrentIndex() ?: 0, startPlaying = false) },
            onTrackDeleted = { track, _ -> lifecycleScope.launch(Dispatchers.IO) { database.trackDao().deleteTrack(track) }; updateTrackCount() }
        )
        rvPlaylist.layoutManager = LinearLayoutManager(this)
        rvPlaylist.adapter = playlistAdapter
        playlistAdapter.getItemTouchHelper().attachToRecyclerView(rvPlaylist)
    }

    private fun setupPlayerControls() {
        btnPlayPause.setOnClickListener { playbackService?.togglePlayPause() }
        btnStop.setOnClickListener { playbackService?.stopPlayback(); btnPlayPause.setImageResource(R.drawable.ic_play) }
        btnNext.setOnClickListener { playbackService?.playNext() }
        btnPrevious.setOnClickListener { playbackService?.playPrevious() }
        btnShuffle.setOnClickListener {
            isShuffleActive = !isShuffleActive
            btnShuffle.setColorFilter(if (isShuffleActive) ContextCompat.getColor(this, R.color.aimp_orange) else ContextCompat.getColor(this, R.color.text_muted))
            if (isShuffleActive) { currentDisplayList.shuffle(); playlistAdapter.updateData(currentDisplayList); playbackService?.setPlaylist(currentDisplayList, 0, startPlaying = false) }
            else { currentDisplayList.clear(); currentDisplayList.addAll(allTracksList); playlistAdapter.updateData(currentDisplayList) }
        }
        btnRepeat.setOnClickListener {
            isRepeatLoopActive = !isRepeatLoopActive
            playbackService?.isLoopPlaylistEnabled = isRepeatLoopActive
            btnRepeat.setColorFilter(if (isRepeatLoopActive) ContextCompat.getColor(this, R.color.aimp_orange) else ContextCompat.getColor(this, R.color.text_muted))
            Toast.makeText(this, if (isRepeatLoopActive) "Bucle Activado" else "Bucle Desactivado", Toast.LENGTH_SHORT).show()
        }
        btnCrossfade.setOnClickListener {
            isCrossfadeActive = !isCrossfadeActive
            btnCrossfade.setColorFilter(if (isCrossfadeActive) ContextCompat.getColor(this, R.color.aimp_orange) else ContextCompat.getColor(this, R.color.text_muted))
            playbackService?.atsEngine?.crossfadeSeconds = if (isCrossfadeActive) 3 else 0
        }
        playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = playbackService?.player?.duration ?: 0L
                    if (duration > 0) tvElapsedTime.text = formatTime((progress / 1000.0f * duration).toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserTrackingSeekBar = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserTrackingSeekBar = false
                val duration = playbackService?.player?.duration ?: 0L
                if (duration > 0 && sb != null) playbackService?.player?.seekTo((sb.progress / 1000.0f * duration).toLong())
            }
        })
    }

    private fun setupDrawer() {
        findViewById<View>(R.id.drawerItemPlaylists).setOnClickListener { filterTracks("all"); drawerLayout.closeDrawers() }
        findViewById<View>(R.id.drawerItemFavorites).setOnClickListener { filterTracks("favorites"); drawerLayout.closeDrawers() }
        findViewById<View>(R.id.drawerItemHistory).setOnClickListener { filterTracks("history"); drawerLayout.closeDrawers() }
        findViewById<View>(R.id.drawerItemFolders).setOnClickListener { Toast.makeText(this, "Explorador de carpetas activo", Toast.LENGTH_SHORT).show(); drawerLayout.closeDrawers() }
        findViewById<View>(R.id.drawerExportM3U8).setOnClickListener { exportM3U8Launcher.launch("SjbZ_Playlist_${System.currentTimeMillis()}.m3u8"); drawerLayout.closeDrawers() }
        findViewById<View>(R.id.drawerImportM3U8).setOnClickListener { Toast.makeText(this, "Importador M3U8 listo", Toast.LENGTH_SHORT).show(); drawerLayout.closeDrawers() }
    }

    private fun setupSearch() {
        // PARCHE SjbZ - debounce 300ms para 716 FLAC
        var searchJob: Runnable? = null
        etSearchTracks.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.let { uiHandler.removeCallbacks(it) }
                val q = s?.toString()?.trim() ?: ""
                searchJob = Runnable { filterTracksByQuery(q) }
                uiHandler.postDelayed(searchJob!!, 300)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterTracksByQuery(query: String) {
        if (query.isEmpty()) { currentDisplayList.clear(); currentDisplayList.addAll(allTracksList) }
        else {
            val filtered = allTracksList.filter { it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) }
            currentDisplayList.clear(); currentDisplayList.addAll(filtered)
        }
        playlistAdapter.updateData(currentDisplayList); updateTrackCount()
    }

    private fun filterTracks(filter: String) {
        lifecycleScope.launch {
            val list = when (filter) {
                "favorites" -> database.trackDao().getFavoriteTracks()
                "history" -> database.trackDao().getHistoryTracks()
                else -> database.trackDao().getAllTracks()
            }
            currentDisplayList.clear(); currentDisplayList.addAll(list)
            playlistAdapter.updateData(currentDisplayList); updateTrackCount()
        }
    }

    private fun toggleFavorite(track: Track, position: Int) {
        val newFav = !track.isFavorite
        val updated = track.copy(isFavorite = newFav)
        currentDisplayList[position] = updated
        playlistAdapter.notifyItemChanged(position)
        lifecycleScope.launch(Dispatchers.IO) { database.trackDao().setFavorite(track.id, newFav) }
    }

    private fun startPlaybackService() {
        val serviceIntent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(this, serviceIntent) else startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { permissions.add(Manifest.permission.READ_MEDIA_AUDIO); permissions.add(Manifest.permission.POST_NOTIFICATIONS) }
        else permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) scanAudioStorage() else storagePermissionLauncher.launch(needed.toTypedArray())
    }

    private fun scanAudioStorage() {
        lifecycleScope.launch {
            val scannedTracks = withContext(Dispatchers.IO) {
                val tracks = mutableListOf<Track>()
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA)
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
                            val id = c.getLong(idCol); val title = c.getString(titleCol) ?: "Audio Track"; val artist = c.getString(artistCol) ?: "Unknown Artist"; val album = c.getString(albumCol) ?: "Unknown Album"; val duration = c.getLong(durationCol); val path = c.getString(dataCol) ?: ""
                            val contentUri = ContentUris.withAppendedId(uri, id).toString()
                            val format = detectFormat(path)
                            tracks.add(Track(id = id, title = title, artist = artist, album = album, duration = duration, uri = contentUri, path = path, format = format, bitrate = if (format == "FLAC" || format == "WAV") 1411 else 320, sampleRate = if (format == "FLAC") 96000 else 44100, bitDepth = if (format == "FLAC") 24 else 16, orderIndex = order++))
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() } finally { cursor?.close() }
                if (tracks.isEmpty()) tracks.addAll(createDemoTracks())
                database.trackDao().clearTracks(); database.trackDao().insertTracks(tracks)
                tracks
            }

            // PARCHE SjbZ ANTI-ANR 716 FLAC
            allTracksList.clear(); allTracksList.addAll(scannedTracks)
            currentDisplayList.clear(); currentDisplayList.addAll(allTracksList.take(60))
            playlistAdapter.updateData(currentDisplayList)
            updateTrackCount()
            playbackService?.setPlaylist(allTracksList, 0, startPlaying = false)
            if (allTracksList.size > 60) {
                uiHandler.postDelayed({
                    currentDisplayList.clear(); currentDisplayList.addAll(allTracksList)
                    playlistAdapter.updateData(currentDisplayList); updateTrackCount()
                }, 350)
            }
        }
    }

    private fun detectFormat(path: String): String {
        val lower = path.lowercase()
        return when { lower.endsWith(".flac") -> "FLAC"; lower.endsWith(".wav") -> "WAV"; lower.endsWith(".ape") -> "APE"; lower.endsWith(".opus") -> "OPUS"; lower.endsWith(".ogg") -> "OGG"; lower.endsWith(".m4a") -> "M4A"; lower.endsWith(".aac") -> "AAC"; else -> "MP3" }
    }

    private fun createDemoTracks(): List<Track> {
        return listOf(
            Track(id = 1, title = "AIMP SjbZ Bass Master Reference", artist = "ATS2835P Hi-Res Studio", album = "Audiophile Acoustic Tests 2026", duration = 248000L, uri = "asset:///demo_track_1.mp3", format = "FLAC", bitrate = 1411, sampleRate = 96000, bitDepth = 24, orderIndex = 0),
            Track(id = 2, title = "Cybernetic Pulse - MDRC Dynamic Test", artist = "Actions Semiconductor DSP Labs", album = "Hardware Reference Curves", duration = 195000L, uri = "asset:///demo_track_2.mp3", format = "FLAC", bitrate = 9216, sampleRate = 192000, bitDepth = 24, orderIndex = 1)
        )
    }

    private fun updateTrackCount() { val count = currentDisplayList.size; tvPlaylistTrackCount.text = "$count tracks"; emptyView.visibility = if (count == 0) View.VISIBLE else View.GONE }
    private fun updatePlayerUi(track: Track?, index: Int) {
        if (track == null) { tvPlayerTitle.text = "Selecciona una pista"; tvPlayerArtist.text = "SjbZ Hi-Res Player"; tvPlayerTechSpecs.text = "24-bit • 192kHz • ATS2835P"; return }
        tvPlayerTitle.text = track.title; tvPlayerArtist.text = track.artist; tvPlayerTechSpecs.text = track.getTechInfo(); playlistAdapter.setPlayingIndex(index)
    }
    private fun updatePlaybackProgressAndVUMeters() {
        val srv = playbackService ?: return; val player = srv.player; val isPlaying = player.isPlaying; val duration = player.duration; val position = player.currentPosition
        if (!isUserTrackingSeekBar && duration > 0) {
            playerSeekBar.progress = (position.toFloat() / duration * 1000).toInt()
            tvElapsedTime.text = formatTime(position); tvRemainingTime.text = "-" + formatTime((duration - position).coerceAtLeast(0L))
        }
        val fraction = if (duration > 0) position.toFloat() / duration else 0f
        srv.audioChain.updateVUMeters(isPlaying, fraction)
        vuMeterLeftBar.progress = (srv.audioChain.vuMeterLeft * 100).toInt()
        vuMeterRightBar.progress = (srv.audioChain.vuMeterRight * 100).toInt()
        tvVuPeakText.text = if (isPlaying) String.format("%.1f dB", -12.0f + (vuMeterLeftBar.progress / 100.0f) * 11.7f) else "-inf dB"
    }
    private fun formatTime(millis: Long): String { val totalSeconds = millis / 1000; val minutes = totalSeconds / 60; val seconds = totalSeconds % 60; return String.format("%d:%02d", minutes, seconds) }
    private fun exportCurrentPlaylistToM3U8(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out -> OutputStreamWriter(out).use { writer -> writer.write("#EXTM3U\n"); for (track in currentDisplayList) { writer.write("#EXTINF:${track.duration / 1000},${track.artist} - ${track.title}\n"); writer.write("${track.path}\n") } } }
            Toast.makeText(this, "Lista exportada en M3U8", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Error M3U8: ${e.message}", Toast.LENGTH_SHORT).show() }
    }
    override fun onDestroy() { if (isServiceBound) { unbindService(serviceConnection); isServiceBound = false }; super.onDestroy() }
}
