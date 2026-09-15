package com.sjbz.aimp.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a user or system playlist in SjbZ.
 */
@Entity(
    tableName = "playlists",
    // PATCH: evita playlists duplicadas con el mismo nombre (insensible a mayúsculas
    // se controla en DAO/repo). Antes se podían crear "Rock" x3 desde distintos flujos.
    indices = [Index(value = ["name"], unique = true)]
)
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isSystem: Boolean = false, // e.g. "Main Queue", "Favorites", "History"
    // PATCH: campo desnormalizado. Se mantiene por compat con UI que lee
    // trackCount sin hacer COUNT(*) cada vez, pero ahora se documenta que
    // la fuente de verdad es Track.playlistId y debe sincronizarse vía
    // PlaylistRepository.refreshTrackCount() tras insert/delete.
    // Antes se leía como si fuera automático y quedaba stale.
    val trackCount: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis(),
    // PATCH: faltaba updatedTimestamp para ordenar por "recientes" y para
    // resolver conflictos de nombre en import M3U8. Default = createdTimestamp
    // se asigna en el repositorio si viene en 0 (legacy).
    val updatedTimestamp: Long = System.currentTimeMillis()
) {
    init {
        // PATCH: valida nombre no vacío. Antes se podía insertar Playlist("")
        // y el drawer mostraba un item en blanco imposible de borrar.
        require(name.isNotBlank()) { "Playlist name must not be blank" }
    }

    // PATCH: helper para refrescar el contador sin mutar el resto.
    // Uso: playlist.withTrackCount(dao.countByPlaylist(id))
    fun withTrackCount(count: Int): Playlist =
        copy(trackCount = count.coerceAtLeast(0), updatedTimestamp = System.currentTimeMillis())

    companion object {
        // PATCH: nombres reservados del sistema. Antes estaban solo en un comentario
        // y MainActivity los hardcodeaba ("favorites"/"history") por su lado.
        const val SYSTEM_MAIN_QUEUE = "Main Queue"
        const val SYSTEM_FAVORITES = "Favorites"
        const val SYSTEM_HISTORY = "History"

        val SYSTEM_NAMES = setOf(SYSTEM_MAIN_QUEUE, SYSTEM_FAVORITES, SYSTEM_HISTORY)

        fun isSystemName(name: String): Boolean =
            SYSTEM_NAMES.any { it.equals(name, ignoreCase = true) }

        fun systemPlaylist(name: String): Playlist = Playlist(
            name = name,
            isSystem = true,
            trackCount = 0
        )
    }
}
