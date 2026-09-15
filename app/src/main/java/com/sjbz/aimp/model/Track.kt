package com.sjbz.aimp.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Locale

/**
 * Entity representing an audio track in the SjbZ Studio player.
 * Supports FLAC, MP3, WAV, APE, OPUS, OGG, M4A up to 24-bit/192kHz with automatic Genre Recognition.
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val genre: String = "General",
    val duration: Long = 0L, // in milliseconds
    val uri: String,
    val path: String = "",
    val format: String = "MP3", // FLAC, MP3, WAV, APE, OPUS, OGG, M4A
    val bitrate: Int = 320, // in kbps
    val sampleRate: Int = 44100, // in Hz (supports up to 192000)
    val bitDepth: Int = 16, // 16-bit, 24-bit
    val isFavorite: Boolean = false,
    val isHistory: Boolean = false,
    val playlistId: Long = 1L,
    val orderIndex: Int = 0,
    val albumArtUri: String? = null,
    val dateAdded: Long = System.currentTimeMillis()
) {
    fun getFormattedDuration(): String {
        val totalSeconds = duration / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    fun getTechInfo(): String {
        return "$format • ${bitrate}kbps • ${sampleRate / 1000}kHz • ${bitDepth}bit"
    }

    companion object {
        /**
         * Recognizes musical genre based on tags, artist, title, album, or file path.
         */
        fun inferGenre(rawGenre: String?, title: String, artist: String, album: String, path: String): String {
            if (!rawGenre.isNullOrBlank() && rawGenre != "Unknown" && rawGenre != "Other" && rawGenre != "General") {
                val clean = rawGenre.trim()
                if (clean.isNotEmpty()) return clean
            }

            val text = "$title $artist $album $path".lowercase(Locale.ROOT)
            return when {
                text.contains("rock") || text.contains("metal") || text.contains("punk") || text.contains("grunge") || text.contains("slash") || text.contains("metallica") || text.contains("acdc") || text.contains("nirvana") -> "Rock"
                text.contains("electronic") || text.contains("techno") || text.contains("house") || text.contains("trance") || text.contains("dance") || text.contains("edm") || text.contains("dubstep") || text.contains("ambient") || text.contains("synth") -> "Electronic"
                text.contains("bass") || text.contains("trap") || text.contains("sub") || text.contains("808") || text.contains("dnb") || text.contains("drum and bass") -> "Bass"
                text.contains("hip hop") || text.contains("hip-hop") || text.contains("rap") || text.contains("trap") || text.contains("r&b") || text.contains("urban") -> "Hip-Hop"
                text.contains("pop") || text.contains("hits") || text.contains("radio") || text.contains("chart") -> "Pop"
                text.contains("jazz") || text.contains("blues") || text.contains("swing") || text.contains("bossa") || text.contains("soul") -> "Jazz"
                text.contains("classic") || text.contains("symphon") || text.contains("orchestr") || text.contains("piano") || text.contains("mozart") || text.contains("beethoven") || text.contains("bach") -> "Classical"
                text.contains("acoustic") || text.contains("folk") || text.contains("guitar") || text.contains("unplugged") || text.contains("indie") -> "Acoustic"
                text.contains("vocal") || text.contains("acapella") || text.contains("ballad") || text.contains("choir") -> "Vocal"
                text.contains("latin") || text.contains("salsa") || text.contains("bachata") || text.contains("reggaeton") || text.contains("cumbia") -> "Latin"
                else -> "Studio"
            }
        }
    }
}
