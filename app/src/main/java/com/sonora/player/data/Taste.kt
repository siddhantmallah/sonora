package com.sonora.player.data

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import com.sonora.player.data.db.PlayEventEntity
import com.sonora.player.playback.toMediaItem
import com.sonora.player.youtube.YtSong
import com.sonora.player.youtube.hiResThumb
import com.sonora.player.youtube.toMediaItem
import kotlin.math.exp
import kotlin.math.ln

/** A playable track from either source, so mixes can blend the phone's library with YouTube Music. */
@Immutable
sealed interface Track {
    val key: String
    val title: String
    val artist: String
    val art: Uri?
    val durationMs: Long
    fun mediaItem(): MediaItem

    data class Local(val song: Song) : Track {
        override val key get() = song.id.toString()
        override val title get() = song.title
        override val artist get() = song.artist
        override val art: Uri? get() = song.artUri
        override val durationMs get() = song.durationMs
        override fun mediaItem() = song.toMediaItem()
    }

    data class Online(val song: YtSong) : Track {
        override val key get() = song.mediaId
        override val title get() = song.title
        override val artist get() = song.artistNames
        override val art: Uri? get() = hiResThumb(song.thumbnail)?.let(Uri::parse)
        override val durationMs get() = song.durationSec * 1000L
        override fun mediaItem() = song.toMediaItem()
    }
}

@Immutable
data class WeightedName(val name: String, val weight: Double)

/** What the listener is into, derived from listens, skips, favourites and play counts. */
@Immutable
data class TasteProfile(
    val topArtists: List<WeightedName> = emptyList(),
    /** Best track (mediaId → event) for each top artist, used as a radio seed. */
    val artistSeeds: Map<String, PlayEventEntity> = emptyMap(),
    val languages: List<WeightedName> = emptyList(),
    val vibes: List<WeightedName> = emptyList(),
    val recent: List<PlayEventEntity> = emptyList(),
    val topTracks: List<PlayEventEntity> = emptyList(),
    val dislikedArtists: Set<String> = emptySet(),
    val heardMediaIds: Set<String> = emptySet(),
    /** Skip memory: songs skipped 3+ times and never played through. Left out of mixes and radio. */
    val skippedIds: Set<String> = emptySet(),
    val listens: Int = 0,
) {
    val isEmpty get() = listens == 0
}

/**
 * Scores artists, languages and "vibes" with recency decay (a listen from 3 weeks ago counts ~half as much
 * as one today) and penalises skips, so the feed follows what you're into *now*.
 */
object TasteAnalyzer {
    private const val HALF_LIFE_DAYS = 21.0

    private val vibeWords = linkedMapOf(
        "Lo-fi" to listOf("lofi", "lo-fi", "lo fi", "chill"),
        "Slowed + reverb" to listOf("slowed", "reverb"),
        "Remixes" to listOf("remix", "mashup", "bootleg"),
        "Acoustic" to listOf("unplugged", "acoustic", "unplg"),
        "Live" to listOf("(live", "live at", "live session", "concert"),
        "Phonk" to listOf("phonk"),
        "Romantic" to listOf("love", "ishq", "pyaar", "pyar", "dil", "mohabbat", "romantic", "amor"),
        "Sad songs" to listOf("sad", "broken", "dard", "alone", "tanha", "judaai"),
        "Party" to listOf("party", "dance", "club", "bhangra", "dj "),
        "Devotional" to listOf("bhajan", "aarti", "mantra", "shabad", "gurbani", "qawwali"),
        "Rap" to listOf("rap", "cypher", "freestyle", "drill"),
    )

    fun languageOf(text: String): String? {
        var dev = 0; var guru = 0; var arabic = 0; var tamil = 0; var telugu = 0; var hangul = 0; var kana = 0
        for (ch in text) when (ch.code) {
            in 0x0900..0x097F -> dev++
            in 0x0A00..0x0A7F -> guru++
            in 0x0600..0x06FF -> arabic++
            in 0x0B80..0x0BFF -> tamil++
            in 0x0C00..0x0C7F -> telugu++
            in 0xAC00..0xD7AF -> hangul++
            in 0x3040..0x30FF -> kana++
        }
        val best = listOf("Hindi" to dev, "Punjabi" to guru, "Urdu" to arabic, "Tamil" to tamil, "Telugu" to telugu, "K-pop" to hangul, "J-pop" to kana)
            .maxBy { it.second }
        if (best.second > 0) return best.first
        val lower = " ${text.lowercase()} "
        if (listOf(" de ", " la ", " que ", " mi ", " te ", " amor", "corazón", "ñ").count { it in lower } >= 2) return "Latin"
        return null
    }

    fun analyze(events: List<PlayEventEntity>, now: Long = System.currentTimeMillis()): TasteProfile {
        if (events.isEmpty()) return TasteProfile()
        val artist = HashMap<String, Double>()
        val artistSkips = HashMap<String, Int>()
        val artistPlays = HashMap<String, Int>()
        val track = HashMap<String, Double>()
        val trackEvent = HashMap<String, PlayEventEntity>()
        val lang = HashMap<String, Double>()
        val vibe = HashMap<String, Double>()

        for (e in events) {
            val ageDays = (now - e.playedAt).coerceAtLeast(0) / 86_400_000.0
            val decay = exp(-ln(2.0) * ageDays / HALF_LIFE_DAYS)
            val w = if (e.skipped) -0.6 * decay else decay * (1.0 + (e.listenedMs / 180_000.0).coerceAtMost(1.0))
            val names = splitArtists(e.artist.ifBlank { "Unknown artist" })
            for (a in names) {
                artist[a] = (artist[a] ?: 0.0) + w / names.size
                if (e.skipped) artistSkips[a] = (artistSkips[a] ?: 0) + 1 else artistPlays[a] = (artistPlays[a] ?: 0) + 1
            }
            track[e.mediaId] = (track[e.mediaId] ?: 0.0) + w
            if (!e.skipped) trackEvent.putIfAbsent(e.mediaId, e)
            if (!e.skipped) {
                languageOf("${e.title} ${e.artist}")?.let { lang[it] = (lang[it] ?: 0.0) + decay }
                val hay = " ${e.title.lowercase()} ${e.album.orEmpty().lowercase()} "
                vibeWords.forEach { (name, words) -> if (words.any { it in hay }) vibe[name] = (vibe[name] ?: 0.0) + decay }
            }
        }

        val topArtists = artist.filter { it.value > 0.05 && it.key != "Unknown artist" }
            .map { WeightedName(it.key, it.value) }.sortedByDescending { it.weight }.take(12)
        val bestTracks = track.filter { it.value > 0 }.entries.sortedByDescending { it.value }
            .mapNotNull { trackEvent[it.key] }
        val seeds = LinkedHashMap<String, PlayEventEntity>()
        for (a in topArtists) bestTracks.firstOrNull { a.name in splitArtists(it.artist) }?.let { seeds[a.name] = it }

        return TasteProfile(
            topArtists = topArtists,
            artistSeeds = seeds,
            languages = lang.map { WeightedName(it.key, it.value) }.sortedByDescending { it.weight },
            vibes = vibe.map { WeightedName(it.key, it.value) }.sortedByDescending { it.weight },
            recent = events.filter { !it.skipped }.distinctBy { it.mediaId }.take(30),
            topTracks = bestTracks.take(50),
            dislikedArtists = artistSkips.filter { (a, s) -> s >= 3 && s > (artistPlays[a] ?: 0) * 2 }.keys,
            heardMediaIds = events.map { it.mediaId }.toSet(),
            listens = events.count { !it.skipped },
        )
    }
}
