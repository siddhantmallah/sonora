package com.sonora.player.data

import com.sonora.player.data.db.PlaylistDao
import com.sonora.player.data.db.PlaylistEntity
import com.sonora.player.data.db.PlaylistSongEntity
import com.sonora.player.data.db.PlaylistSummary
import com.sonora.player.data.db.SongStatsEntity
import com.sonora.player.data.db.StatsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class StatsRepository(private val dao: StatsDao, scope: CoroutineScope) {
    val all: StateFlow<Map<Long, SongStatsEntity>> = dao.observeAll()
        .map { list -> list.associateBy { it.songId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val favorites: StateFlow<Set<Long>> = all
        .map { m -> m.values.filter { it.favorite }.map { it.songId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun recordPlay(songId: Long) {
        val cur = dao.get(songId) ?: SongStatsEntity(songId)
        dao.upsert(cur.copy(playCount = cur.playCount + 1, lastPlayed = System.currentTimeMillis()))
    }

    suspend fun recordSkip(songId: Long) {
        val cur = dao.get(songId) ?: SongStatsEntity(songId)
        dao.upsert(cur.copy(skipCount = cur.skipCount + 1))
    }

    suspend fun toggleFavorite(songId: Long): Boolean {
        val cur = dao.get(songId) ?: SongStatsEntity(songId)
        val next = !cur.favorite
        dao.upsert(cur.copy(favorite = next))
        return next
    }

    fun isFavorite(songId: Long): Boolean = favorites.value.contains(songId)

    /** Sets favourites in bulk (used to mirror likes from YouTube Music); skips ones already in that state. */
    suspend fun setFavorites(ids: Collection<Long>, favorite: Boolean) {
        ids.forEach { id ->
            val cur = dao.get(id) ?: SongStatsEntity(id)
            if (cur.favorite != favorite) dao.upsert(cur.copy(favorite = favorite))
        }
    }
}

class PlaylistRepository(private val dao: PlaylistDao) {
    val summaries: Flow<List<PlaylistSummary>> = dao.observeSummaries()

    fun playlist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)
    fun entries(id: Long): Flow<List<PlaylistSongEntity>> = dao.observeEntries(id)

    suspend fun create(name: String, songs: List<Long> = emptyList()): Long {
        val id = dao.insertPlaylist(PlaylistEntity(name = name.trim().ifBlank { "New playlist" }))
        if (songs.isNotEmpty()) add(id, songs)
        return id
    }

    suspend fun add(playlistId: Long, songIds: List<Long>) {
        var order = dao.maxOrder(playlistId)
        dao.insertEntries(songIds.map { PlaylistSongEntity(playlistId = playlistId, songId = it, sortOrder = ++order) })
    }

    suspend fun remove(entryId: Long) = dao.removeEntry(entryId)
    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())
    suspend fun delete(id: Long) = dao.deletePlaylist(id)

    /** Persists a new order after a drag-reorder. */
    suspend fun reorder(entries: List<PlaylistSongEntity>) {
        entries.forEachIndexed { i, e -> dao.setOrder(e.entryId, i.toLong() + 1) }
    }
}
