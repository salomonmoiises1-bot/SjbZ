package com.sjbz.aimp.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.sjbz.aimp.model.Playlist
import com.sjbz.aimp.model.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY orderIndex ASC")
    fun getAllTracksFlow(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY orderIndex ASC")
    suspend fun getAllTracks(): List<Track>

    @Query("SELECT * FROM tracks WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    suspend fun getTracksByPlaylist(playlistId: Long): List<Track>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY id DESC")
    suspend fun getFavoriteTracks(): List<Track>

    @Query("SELECT * FROM tracks WHERE isHistory = 1 ORDER BY dateAdded DESC")
    suspend fun getHistoryTracks(): List<Track>

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY orderIndex ASC")
    suspend fun searchTracks(query: String): List<Track>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: Track): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    @Update
    suspend fun updateTrack(track: Track)

    @Update
    suspend fun updateTracks(tracks: List<Track>)

    @Delete
    suspend fun deleteTrack(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackById(id: Long)

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("UPDATE tracks SET isFavorite = :isFav WHERE id = :trackId")
    suspend fun setFavorite(trackId: Long, isFav: Boolean)

    @Query("UPDATE tracks SET isHistory = 1, dateAdded = :timestamp WHERE id = :trackId")
    suspend fun markHistory(trackId: Long, timestamp: Long = System.currentTimeMillis())
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id ASC")
    fun getAllPlaylistsFlow(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY id ASC")
    suspend fun getAllPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): Playlist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Query("UPDATE playlists SET name = :newName WHERE id = :id")
    suspend fun renamePlaylist(id: Long, newName: String)

    @Query("DELETE FROM playlists WHERE id = :id AND isSystem = 0")
    suspend fun deletePlaylist(id: Long)
}

@Database(entities = [Track::class, Playlist::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sjbz_aimp_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
