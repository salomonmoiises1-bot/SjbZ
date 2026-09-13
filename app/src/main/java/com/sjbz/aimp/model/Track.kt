package com.sjbz.aimp.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val duration: Long = 0L,
    val uri: String,
    val path: String = "",
    val format: String = "MP3",
    val bitrate: Int = 320,
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
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

    // --- FIX PARA PlaybackService.kt:208,220 ---
    fun toDisplay(): String {
        return if (artist.isNotBlank() && artist != "Unknown Artist") "$title - $artist"
        else title
    }

    fun toDisplayNotification(): String = toDisplay()
}
