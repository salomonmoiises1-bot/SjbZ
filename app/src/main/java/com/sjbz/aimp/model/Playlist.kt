package com.sjbz.aimp.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a user or system playlist in SjbZ.
 */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isSystem: Boolean = false, // e.g. "Main Queue", "Favorites", "History"
    val trackCount: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis()
)
