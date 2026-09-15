package com.sjbz.aimp.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an audio track in the SjbZ player.
 * Supports FLAC, MP3, WAV, APE, OPUS, OGG, M4A up to 24-bit/192kHz.
 */
@Entity(
    tableName = "tracks",
    // PATCH: índice único por mediaStoreId para no duplicar al re-escanear.
    // Antes el re-scan hacía clearTracks()+insert, pero si se cambia a upsert
    // sin este índice se duplicaban tracks.
    indices = [Index(value = ["mediaStoreId"], unique = false)]
)
data class Track(
    // PATCH: id autogenerado por Room, NO el _ID de MediaStore.
    // Antes MainActivity metía el _ID de MediaStore directo en este campo
    // con autoGenerate=true, lo que colisionaba con la secuencia de Room y
    // podía lanzar SQLiteConstraintException al insertar demos con id 1..4.
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // PATCH: ID estable de MediaStore para mapear favoritismo entre scans.
    // 0 = track demo / asset sin MediaStore.
    val mediaStoreId: Long = 0L,

    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val duration: Long = 0L, // in milliseconds
    val uri: String,
    // PATCH: path conservado por compat con M3U8 legacy, pero el campo
    // reproducible es uri (content:// o asset:///). Antes scan llenaba path
    // con DATA (deprecated) y export lo usaba, rompiendo en Android 10+.
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
        // PATCH: protege duración negativa o cero (MediaStore a veces da 0)
        val totalSeconds = (duration.coerceAtLeast(0L)) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    fun getTechInfo(): String {
        // PATCH: antes sampleRate/1000 era división entera: 44100 -> "44kHz"
        // en vez de "44.1kHz". Ahora formatea con un decimal cuando aplica.
        val srText = when (sampleRate) {
            in 43900..44300 -> "44.1kHz"
            in 47900..48300 -> "48kHz"
            in 95500..96500 -> "96kHz"
            in 191000..193000 -> "192kHz"
            else -> if (sampleRate > 0) String.format("%.1fkHz", sampleRate / 1000f) else "Unknown"
        }
        val brText = if (bitrate > 0) "${bitrate}kbps" else "VBR"
        return "$format • $brText • $srText • ${bitDepth}bit"
    }

    companion object {
        // PATCH: helper para comparar tracks entre listas (UI vs servicio)
        // sin depender del id autogenerado que cambia entre DBs.
        fun stableKey(t: Track): String = if (t.mediaStoreId!= 0L) "ms:${t.mediaStoreId}" else "uri:${t.uri}"
    }
}
