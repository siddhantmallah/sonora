package com.sonora.player.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_songs", indices = [Index("playlistId")])
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val sortOrder: Long,
)

data class PlaylistSummary(val id: Long, val name: String, val createdAt: Long, val songCount: Int)

@Entity(tableName = "song_stats")
data class SongStatsEntity(
    @PrimaryKey val songId: Long,
    val playCount: Int = 0,
    val lastPlayed: Long = 0,
    val favorite: Boolean = false,
    val skipCount: Int = 0,
)

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val content: String,
    val source: String,
    val offsetMs: Long = 0,
)

/** One listen (or skip) of any track, local or YouTube. Feeds the taste analyser. */
@Entity(tableName = "play_history", indices = [Index("playedAt"), Index("mediaId")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artUri: String?,
    val durationMs: Long,
    val playedAt: Long,
    val listenedMs: Long,
    val skipped: Boolean,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(e: PlayEventEntity): Long

    @Query("UPDATE play_history SET listenedMs = :ms, skipped = :skipped WHERE id = :id")
    suspend fun updateListen(id: Long, ms: Long, skipped: Boolean)

    @Query("SELECT * FROM play_history WHERE playedAt > :since ORDER BY playedAt DESC")
    suspend fun since(since: Long): List<PlayEventEntity>

    @Query("SELECT * FROM play_history WHERE skipped = 0 ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlayEventEntity>>

    @Query("SELECT COUNT(*) FROM play_history")
    fun observeCount(): Flow<Int>
}

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id, p.name, p.createdAt, (SELECT COUNT(*) FROM playlist_songs s WHERE s.playlistId = p.id) AS songCount " +
            "FROM playlists p ORDER BY p.name COLLATE NOCASE",
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id ORDER BY sortOrder")
    fun observeEntries(id: Long): Flow<List<PlaylistSongEntity>>

    @Insert
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM playlist_songs WHERE playlistId = :id")
    suspend fun maxOrder(id: Long): Long

    @Insert
    suspend fun insertEntries(entries: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE entryId = :entryId")
    suspend fun removeEntry(entryId: Long)

    @Query("UPDATE playlist_songs SET sortOrder = :order WHERE entryId = :entryId")
    suspend fun setOrder(entryId: Long, order: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clearEntries(id: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistRow(id: Long)

    @Transaction
    suspend fun deletePlaylist(id: Long) {
        clearEntries(id)
        deletePlaylistRow(id)
    }
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM song_stats")
    fun observeAll(): Flow<List<SongStatsEntity>>

    @Query("SELECT * FROM song_stats WHERE songId = :id")
    suspend fun get(id: Long): SongStatsEntity?

    @Upsert
    suspend fun upsert(e: SongStatsEntity)
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :id")
    suspend fun get(id: Long): LyricsEntity?

    @Upsert
    suspend fun upsert(e: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE songId = :id")
    suspend fun delete(id: Long)
}

/** A listen imported from another music service (Spotify, Apple Music, Last.fm…), kept apart from Sonora's own history. */
@Entity(tableName = "external_plays", indices = [Index("source"), Index("playedAt")])
data class ExternalPlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val title: String,
    val artist: String,
    val album: String?,
    val playedAt: Long,
    val msPlayed: Long,
)

data class SourceSummary(val source: String, val plays: Int, val lastPlayed: Long, val ms: Long)

@Dao
interface ExternalDao {
    @Insert
    suspend fun insertAll(list: List<ExternalPlayEntity>)

    @Query("DELETE FROM external_plays WHERE source = :source")
    suspend fun clear(source: String)

    @Query("SELECT * FROM external_plays WHERE playedAt > :since")
    suspend fun since(since: Long): List<ExternalPlayEntity>

    @Query("SELECT MAX(playedAt) FROM external_plays WHERE source = :source")
    suspend fun latest(source: String): Long?

    @Query("SELECT source, COUNT(*) AS plays, MAX(playedAt) AS lastPlayed, SUM(msPlayed) AS ms FROM external_plays GROUP BY source")
    fun observeSummary(): Flow<List<SourceSummary>>
}

@Database(
    entities = [PlaylistEntity::class, PlaylistSongEntity::class, SongStatsEntity::class, LyricsEntity::class, PlayEventEntity::class, ExternalPlayEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class SonoraDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun statsDao(): StatsDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun historyDao(): HistoryDao
    abstract fun externalDao(): ExternalDao

    companion object {
        /** v1 → v2 adds listening history; keeps playlists, favourites and lyrics. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`mediaId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, `artUri` TEXT, " +
                        "`durationMs` INTEGER NOT NULL, `playedAt` INTEGER NOT NULL, `listenedMs` INTEGER NOT NULL, `skipped` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_playedAt` ON `play_history` (`playedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_mediaId` ON `play_history` (`mediaId`)")
            }
        }

        /** v2 → v3 adds listens imported from other music services. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `external_plays` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`source` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT, " +
                        "`playedAt` INTEGER NOT NULL, `msPlayed` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_plays_source` ON `external_plays` (`source`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_plays_playedAt` ON `external_plays` (`playedAt`)")
            }
        }

        fun build(context: Context): SonoraDatabase =
            Room.databaseBuilder(context, SonoraDatabase::class.java, "sonora.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
