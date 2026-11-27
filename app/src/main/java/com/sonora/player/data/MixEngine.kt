package com.sonora.player.data

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.sonora.player.data.db.HistoryDao
import com.sonora.player.data.db.PlayEventEntity
import com.sonora.player.youtube.YouTubeRepository
import com.sonora.player.youtube.YtCollection
import com.sonora.player.youtube.YtIds
import com.sonora.player.youtube.YtSearchFilter
import com.sonora.player.youtube.YtSong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

@Immutable
data class SmartMix(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>,
    val reason: String? = null,
) {
    val covers: List<Uri?> get() = tracks.mapNotNull { it.art }.distinct().take(4)
}

@Immutable
data class TasteShelf(val title: String, val subtitle: String, val playlists: List<YtCollection>)

/**
 * Builds personal mixes from a [TasteProfile]:
 *  - Daily Mixes: one per top artist, YouTube Music radio seeded by your favourite track of theirs, blended with
 *    their songs from your phone.
 *  - "Because you listened to…": radio from what you played most recently.
 *  - Discover: radio from your top tracks with everything you've already heard filtered out.
 *  - Taste shelves: playlists matching your top language / vibe / time of day.
 */
class MixEngine(
    private val yt: YouTubeRepository,
    private val library: LibraryRepository,
    private val history: HistoryDao,
    private val stats: StatsRepository,
) {
    private val localToVideo = ConcurrentHashMap<String, String>()
    private val radioCache = ConcurrentHashMap<String, List<YtSong>>()

    /** [extra]: listens from outside Sonora (e.g. the user's YouTube Music account history). */
    suspend fun profile(extra: List<PlayEventEntity> = emptyList()): TasteProfile {
        val now = System.currentTimeMillis()
        val events = history.since(now - 120L * 86_400_000) + extra
        // Older play counts and favourites count too, so personalisation works from day one.
        val statsMap = stats.all.value
        val legacy = statsMap.values.filter { it.songId > 0 && it.playCount > 0 }.flatMap { st ->
            val s = library.song(st.songId) ?: return@flatMap emptyList()
            List(min(st.playCount, 5)) { s.asEvent(st.lastPlayed.takeIf { t -> t > 0 } ?: (now - 30L * 86_400_000)) }
        }
        val favLocal = statsMap.values.filter { it.favorite && it.songId > 0 }
            .mapNotNull { library.song(it.songId) }.flatMap { s -> List(3) { s.asEvent(now - 7L * 86_400_000) } }
        val favIds = statsMap.values.filter { it.favorite }.map { it.songId }.toSet()
        val favOnline = yt.knownSongs().filter { YtIds.statsId(it.mediaId) in favIds }.flatMap { s ->
            List(3) {
                PlayEventEntity(
                    mediaId = s.mediaId, title = s.title, artist = s.artistNames, album = s.album?.name, artUri = s.thumbnail,
                    durationMs = s.durationSec * 1000L, playedAt = now - 7L * 86_400_000, listenedMs = s.durationSec * 1000L, skipped = false,
                )
            }
        }
        val skipMemory = events.groupBy { it.mediaId }
            .filter { (_, es) -> es.count { it.skipped } >= 3 && es.none { !it.skipped && it.listenedMs >= it.durationMs * 0.7 } }
            .keys
        return TasteAnalyzer.analyze(events + legacy + favLocal + favOnline, now).copy(skippedIds = skipMemory)
    }

    private fun Song.asEvent(at: Long) = PlayEventEntity(
        mediaId = id.toString(), title = title, artist = artist, album = album, artUri = artUri.toString(),
        durationMs = durationMs, playedAt = at, listenedMs = durationMs, skipped = false,
    )

    /** YouTube video id for any history entry (searches YouTube Music for local songs, cached). */
    private suspend fun videoIdFor(e: PlayEventEntity): String? {
        YtIds.videoId(e.mediaId)?.let { return it }
        localToVideo[e.mediaId]?.let { return it }
        val q = "${LyricsCleaner.title(e.title)} ${splitArtists(e.artist).first()}"
        val hit = runCatching { yt.search(q, YtSearchFilter.SONGS).allSongs.firstOrNull() }.getOrNull() ?: return null
        localToVideo[e.mediaId] = hit.videoId
        return hit.videoId
    }

    private suspend fun radio(videoId: String): List<YtSong> =
        radioCache[videoId] ?: runCatching { yt.radio(videoId) }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) radioCache[videoId] = it }

    private fun localByArtist(name: String): List<Song> =
        library.artists.value.firstOrNull { it.name.equals(name, ignoreCase = true) }?.songs.orEmpty()

    private fun List<YtSong>.clean(p: TasteProfile) =
        filter { s -> !s.isVideo || s.durationSec in 60..600 }
            .filter { s -> s.artists.none { it.name in p.dislikedArtists } }
            .filter { s -> s.mediaId !in p.skippedIds }

    suspend fun dailyMixes(p: TasteProfile, count: Int = 4): List<SmartMix> = coroutineScope {
        val seeds = p.artistSeeds.entries.take(count)
        seeds.mapIndexed { i, (artist, seed) ->
            async {
                val online = videoIdFor(seed)?.let { radio(it) }.orEmpty().clean(p).take(28).map { Track.Online(it) as Track }
                val local = localByArtist(artist).shuffled(kotlin.random.Random(dayOfYear() + i)).take(8).map { Track.Local(it) as Track }
                val blended = interleave(online, local, every = 3).distinctBy { it.key }
                if (blended.size < 6) null else {
                    val others = blended.map { it.artist }.flatMap { splitArtists(it) }.filter { it != artist }.distinct().take(2)
                    SmartMix("daily$i", "Daily Mix ${i + 1}", (listOf(artist) + others).joinToString(", "), blended, "Because you love $artist")
                }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun becauseYouListened(p: TasteProfile): SmartMix? {
        val seed = p.recent.firstOrNull() ?: return null
        val vid = videoIdFor(seed) ?: return null
        val songs = radio(vid).clean(p).filter { it.videoId != vid }.take(30)
        if (songs.size < 5) return null
        val short = LyricsCleaner.title(seed.title)
            .replace(Regex("""\s+(feat\.?|ft\.?|featuring|with|x)\s.*$""", RegexOption.IGNORE_CASE), "")
            .substringBefore(" | ").substringBefore(" (").substringBefore(" - ").trim()
            .ifBlank { seed.title }
        return SmartMix("because", short, "Because you listened to $short", songs.map { Track.Online(it) }, "Similar to ${splitArtists(seed.artist).first()}")
    }

    suspend fun discover(p: TasteProfile): SmartMix? = coroutineScope {
        val seeds = p.topTracks.shuffled(kotlin.random.Random(dayOfYear())).take(3)
        if (seeds.isEmpty()) return@coroutineScope null
        val top = p.topArtists.take(5).map { it.name }.toSet()
        val pool = seeds.map { async { videoIdFor(it)?.let { v -> radio(v) }.orEmpty() } }.awaitAll().flatten()
            .clean(p)
            .filter { it.mediaId !in p.heardMediaIds && it.artists.none { a -> a.name in top } }
            .distinctBy { it.videoId }
            .shuffled(kotlin.random.Random(dayOfYear()))
            .take(30)
        if (pool.size < 6) null
        else SmartMix("discover", "Discover Mix", "New artists picked from your taste", pool.map { Track.Online(it) }, "Fresh finds")
    }

    /**
     * "Across your apps": artists you play in two or more places (Sonora, YouTube Music, Spotify, Apple Music…),
     * the strongest signal of what you really love, turned into a radio blend.
     */
    suspend fun crossApp(bySource: Map<String, List<PlayEventEntity>>, p: TasteProfile): SmartMix? = coroutineScope {
        if (bySource.size < 2) return@coroutineScope null
        val artistSources = HashMap<String, MutableSet<String>>()
        val artistPlays = HashMap<String, Int>()
        val seedFor = HashMap<String, PlayEventEntity>()
        for ((src, events) in bySource) for (e in events) for (a in splitArtists(e.artist)) {
            val k = a.lowercase()
            artistSources.getOrPut(k) { HashSet() } += src
            artistPlays[k] = (artistPlays[k] ?: 0) + 1
            seedFor.putIfAbsent(k, e)
        }
        val shared = artistSources.filter { it.value.size >= 2 }.keys.sortedByDescending { artistPlays[it] ?: 0 }.take(4)
        if (shared.isEmpty()) return@coroutineScope null
        val pool = shared.map { k -> async { videoIdFor(seedFor.getValue(k))?.let { radio(it) }.orEmpty().take(12) } }.awaitAll()
        val mixed = ArrayList<YtSong>()
        for (i in 0 until 12) for (list in pool) list.getOrNull(i)?.let { mixed += it }
        val songs = mixed.clean(p).distinctBy { it.videoId }.take(40)
        if (songs.size < 6) return@coroutineScope null
        val names = shared.mapNotNull { k -> seedFor[k]?.artist?.let { splitArtists(it).firstOrNull { a -> a.equals(k, true) } } }.take(3)
        SmartMix(
            "cross", "Across your apps", names.joinToString(", "),
            songs.map { Track.Online(it) }, "Loved on ${bySource.keys.take(3).joinToString(" & ")}",
        )
    }

    /** Playlists that match the listener's top language, vibe and the time of day. */
    suspend fun tasteShelves(p: TasteProfile): List<TasteShelf> = coroutineScope {
        val language = p.languages.firstOrNull()?.name
        val vibe = p.vibes.firstOrNull()?.name
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val moment = when (hour) {
            in 5..10 -> "Morning"
            in 11..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Late night"
        }
        val lang = language.orEmpty()
        val queries = buildList {
            if (vibe != null) add(Triple("$vibe${if (language != null) " • $language" else ""}", "Because you play a lot of ${vibe.lowercase()}", "$vibe ${language.orEmpty()} songs"))
            add(Triple("$moment vibes", "For this time of day", "${moment.lowercase()} ${lang.ifBlank { "" }} ${if (moment == "Late night") "chill" else "hits"} songs"))
            p.topArtists.firstOrNull()?.let { add(Triple("More like ${it.name}", "Playlists for fans of ${it.name}", "${it.name} radio")) }
            if (language != null) add(Triple("Top $language hits", "Your most-played language", "top $language songs ${Calendar.getInstance().get(Calendar.YEAR)}"))
        }.take(3)
        queries.map { (title, subtitle, q) ->
            async {
                val lists = runCatching { yt.search(q.replace(Regex("\\s+"), " ").trim(), YtSearchFilter.PLAYLISTS) }.getOrNull()
                    ?.shelves?.flatMap { it.items }?.filterIsInstance<YtCollection>().orEmpty().take(10)
                if (lists.isEmpty()) null else TasteShelf(title, subtitle, lists)
            }
        }.awaitAll().filterNotNull()
    }

    private fun interleave(a: List<Track>, b: List<Track>, every: Int): List<Track> {
        if (b.isEmpty()) return a
        val out = ArrayList<Track>(a.size + b.size)
        var bi = 0
        a.forEachIndexed { i, t ->
            out += t
            if ((i + 1) % every == 0 && bi < b.size) out += b[bi++]
        }
        while (bi < b.size) out += b[bi++]
        return out
    }

    private fun dayOfYear() = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
}

/** Title cleanup shared with lyrics matching (drops "(feat…)", "[Remastered]", etc.). */
object LyricsCleaner {
    fun title(t: String) = com.sonora.player.lyrics.LyricsRepository.cleanTitle(t)
}
