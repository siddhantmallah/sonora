package com.sonora.player.lyrics

import androidx.compose.runtime.Immutable
import com.sonora.player.data.SettingsRepository
import com.sonora.player.data.Song
import com.sonora.player.data.db.LyricsDao
import com.sonora.player.data.db.LyricsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Immutable
data class LyricWord(val startMs: Long, val text: String)

/** A lyric line; [words] carry per-word timings (from enhanced LRC, or estimated) for karaoke highlighting. */
@Immutable
data class LyricLine(val timeMs: Long, val text: String, val endMs: Long = timeMs + 4000, val words: List<LyricWord> = emptyList())

@Immutable
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String,
    val offsetMs: Long = 0,
    /** Timings were estimated (lyrics came without them), so they are approximate. */
    val estimated: Boolean = false,
) {
    /** Index of the line that should be highlighted at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        val t = positionMs + offsetMs
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

@Immutable
data class LyricsCandidate(
    val id: Long,
    val track: String,
    val artist: String,
    val album: String,
    val durationSec: Double,
    val synced: String?,
    val plain: String?,
) {
    val best: String? get() = synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() }
}

object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[[a-zA-Z]+:.*]$""")

    fun isSynced(text: String) = timeTag.containsMatchIn(text)

    fun parse(text: String, source: String, extraOffset: Long = 0): Lyrics {
        if (!isSynced(text)) {
            val lines = text.lines().map { LyricLine(0, it.trim()) }
            return Lyrics(lines, synced = false, source = source)
        }
        val fileOffset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        // Sonora's own tag for slowed / sped-up versions: original timings × factor.
        val tempo = tempoTag.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.4..2.5 } ?: 1.0
        data class Raw(val time: Long, val body: String)
        val raws = ArrayList<Raw>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) continue
            val body = line.substring(tags.last().range.last + 1)
            for (t in tags) raws += Raw((stamp(t.groupValues[1], t.groupValues[2], t.groupValues[3]) * tempo).toLong(), body)
        }
        raws.sortBy { it.time }
        val out = raws.mapIndexed { i, r ->
            val end = raws.getOrNull(i + 1)?.time ?: (r.time + 5000)
            val words = wordsOf(r.body, r.time, end)
            LyricLine(r.time, r.body.replace(wordTag, "").replace(Regex("\\s+"), " ").trim(), end, words)
        }
        // LRC "offset" is positive when lyrics should appear earlier.
        return Lyrics(out, synced = true, source = source, offsetMs = fileOffset + extraOffset)
    }

    private val tempoTag = Regex("""\[sonora-tempo:\s*([0-9.]+)\s*]""", RegexOption.IGNORE_CASE)

    /** Marks synced lyrics to be stretched by [factor] (song length / original length). */
    fun withTempo(text: String, factor: Double) = "[sonora-tempo:%.4f]\n%s".format(java.util.Locale.US, factor, text)

    /**
     * Gives unsynced lyrics estimated timings so the current line can still be followed: lines share the song
     * between a short intro and outro in proportion to their length (blank lines count as short pauses).
     */
    fun estimate(lyrics: Lyrics, durationMs: Long): Lyrics {
        val lines = lyrics.lines.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
        if (durationMs < 30_000 || lines.size < 3) return lyrics
        val intro = (durationMs * 0.07).toLong().coerceIn(4_000, 14_000)
        val outro = (durationMs * 0.06).toLong().coerceIn(3_000, 12_000)
        val span = (durationMs - intro - outro).coerceAtLeast(10_000)
        val weights = lines.map { if (it.text.isBlank()) 6.0 else 10.0 + it.text.length }
        val total = weights.sum()
        var t = intro.toDouble()
        val out = lines.mapIndexed { i, l ->
            val start = t.toLong()
            t += span * weights[i] / total
            LyricLine(start, l.text, t.toLong())
        }
        return lyrics.copy(lines = out, synced = true, estimated = true)
    }

    private val wordTagCapture = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    private fun stamp(min: String, sec: String, frac: String): Long {
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + ms
    }

    /**
     * Word timings: real ones from enhanced LRC (`<mm:ss.xx>word`), otherwise spread across the line by word length
     * (capped so a long instrumental gap doesn't stretch the last line).
     */
    private fun wordsOf(body: String, start: Long, end: Long): List<LyricWord> {
        val tagged = wordTagCapture.findAll(body).toList()
        if (tagged.isNotEmpty()) {
            return tagged.mapIndexedNotNull { i, m ->
                val textEnd = tagged.getOrNull(i + 1)?.range?.first ?: body.length
                val w = body.substring(m.range.last + 1, textEnd)
                if (w.isBlank()) null else LyricWord(stamp(m.groupValues[1], m.groupValues[2], m.groupValues[3]), w)
            }
        }
        val plain = body.replace(wordTag, "").trim()
        if (plain.isEmpty()) return emptyList()
        val tokens = plain.split(Regex("(?<=\\s)"))
        val span = (end - start).coerceIn(600, 7000) * 0.92
        val total = tokens.sumOf { it.trim().length.coerceAtLeast(1) }.toDouble()
        var t = start.toDouble()
        return tokens.map { tok ->
            val w = LyricWord(t.toLong(), tok)
            t += span * tok.trim().length.coerceAtLeast(1) / total
            w
        }
    }

    /** Synced LRC → plain text (for when the timing can't be trusted). */
    fun stripTimes(text: String) = text.lines().map { it.replace(timeTag, "").replace(wordTag, "").trim() }
        .filterNot { metaTag.matches(it) }.joinToString("\n")

    fun stripMeta(text: String) = text.lines().filterNot { metaTag.matches(it.trim()) }.joinToString("\n")
}

/**
 * Finds lyrics for a song, in order: saved/edited lyrics, lyrics embedded in the file's tags,
 * a sidecar .lrc next to the audio file, then LRCLIB (free, synced, no API key).
 */
class LyricsRepository(private val dao: LyricsDao, private val settings: SettingsRepository) {

    private val embedded = ConcurrentHashMap<Long, String>()
    private val _version = MutableStateFlow(0)
    /** Bumps whenever stored lyrics change so screens can reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun putEmbedded(songId: Long, text: String) {
        if (text.isBlank() || embedded[songId] == text) return
        embedded[songId] = text
        _version.update { it + 1 }
    }

    suspend fun load(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        dao.get(song.id)?.let { return@withContext LrcParser.parse(it.content, it.source, it.offsetMs) }
        embedded[song.id]?.let { return@withContext LrcParser.parse(it, "Embedded") }
        readSidecar(song)?.let { return@withContext LrcParser.parse(it, ".lrc file") }
        if (!settings.current().onlineLyrics) return@withContext null
        val found = runCatching { fetchBestMatch(song) }.getOrNull() ?: return@withContext null
        dao.upsert(LyricsEntity(song.id, found, "LRCLIB"))
        LrcParser.parse(found, "LRCLIB")
    }

    suspend fun save(songId: Long, content: String, source: String) = withContext(Dispatchers.IO) {
        val offset = dao.get(songId)?.offsetMs ?: 0
        dao.upsert(LyricsEntity(songId, content, source, offset))
        _version.update { it + 1 }
    }

    suspend fun adjustOffset(songId: Long, deltaMs: Long, current: Lyrics) = withContext(Dispatchers.IO) {
        val existing = dao.get(songId)
        if (existing != null) {
            dao.upsert(existing.copy(offsetMs = existing.offsetMs + deltaMs))
        } else {
            // Lyrics came from tags or a sidecar file: persist them so the offset sticks.
            val text = embedded[songId] ?: current.lines.joinToString("\n") { l ->
                val t = l.timeMs
                "[%02d:%02d.%02d]%s".format(t / 60000, (t / 1000) % 60, (t % 1000) / 10, l.text)
            }
            dao.upsert(LyricsEntity(songId, text, current.source, deltaMs))
        }
        _version.update { it + 1 }
    }

    suspend fun clear(songId: Long) = withContext(Dispatchers.IO) {
        dao.delete(songId)
        embedded.remove(songId)
        _version.update { it + 1 }
    }

    /** Manual search for the "match lyrics" dialog. */
    suspend fun search(title: String, artist: String): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val url = "$BASE/api/search?track_name=${enc(title)}" + if (artist.isNotBlank()) "&artist_name=${enc(artist)}" else ""
        val body = httpGet(url) ?: return@withContext emptyList()
        parseArray(JSONArray(body))
    }

    private fun readSidecar(song: Song): String? {
        if (song.path.isBlank()) return null
        val base = song.path.substringBeforeLast('.')
        for (ext in listOf(".lrc", ".LRC", ".txt")) {
            val f = File(base + ext)
            val text = runCatching { if (f.canRead()) f.readText() else null }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /**
     * Finds the best LRCLIB match. Works for messy streaming metadata too ("Song (From \"Film\")", "Artist - Song
     * (Official Video)", several artists, unknown length):
     *  1. exact signature lookup when the length is known,
     *  2. searches with a few cleaned-up title / artist variants (then a free-text query),
     *  3. picks the best candidate by title + artist similarity, preferring synced lyrics whose length is close.
     *     When the length is unknown or clearly different, plain (unsynced) lyrics are used rather than mistimed ones.
     */
    private fun fetchBestMatch(song: Song): String? {
        val durationSec = song.durationMs / 1000.0
        val titles = titleVariants(song.title, song.artist)
        val artists = artistVariants(song.artist)
        val mainArtist = artists.firstOrNull().orEmpty()

        if (durationSec > 0 && mainArtist.isNotBlank()) {
            for (t in titles.take(2)) {
                val url = "$BASE/api/get?track_name=${enc(t)}&artist_name=${enc(mainArtist)}" +
                    (if (song.album.isNotBlank()) "&album_name=${enc(song.album)}" else "") + "&duration=${durationSec.toInt()}"
                httpGet(url)?.let { body -> candidate(JSONObject(body)).best?.let { return it } }
            }
        }

        val pool = LinkedHashMap<Long, LyricsCandidate>()
        fun add(url: String) = httpGet(url)?.let { runCatching { parseArray(JSONArray(it)) }.getOrNull() }.orEmpty().forEach { pool.putIfAbsent(it.id, it) }
        for (t in titles) {
            if (mainArtist.isNotBlank()) add("$BASE/api/search?track_name=${enc(t)}&artist_name=${enc(mainArtist)}")
            if (pool.size >= 3) break
        }
        if (pool.isEmpty()) add("$BASE/api/search?q=${enc("${titles.first()} $mainArtist".trim())}")
        if (pool.isEmpty()) add("$BASE/api/search?track_name=${enc(titles.first())}")
        pick(pool.values.toList(), titles, artists, durationSec, remix = false)?.let { return it }

        // Remixes / edits / slowed versions are usually listed under the original artist: use the original's words,
        // unsynced (the timing of a remix differs).
        if (remixMarker.containsMatchIn(song.title)) {
            val t = titles.last()
            add("$BASE/api/search?track_name=${enc(t)}")
            return pick(pool.values.toList(), listOf(t), artists, durationSec, remix = true, rawTitle = song.title)
        }
        return null
    }

    private val tempoMarker = Regex("""\b(slowed|sped|speed|nightcore|reverb|daycore)\b""", RegexOption.IGNORE_CASE)

    private val remixMarker = Regex("""\b(remix|mix|edit|slowed|sped|reverb|bootleg|flip|cover|version|lofi|lo-fi|8d|nightcore|mashup)\b""", RegexOption.IGNORE_CASE)

    private fun pick(all: List<LyricsCandidate>, titles: List<String>, artists: List<String>, durationSec: Double, remix: Boolean, rawTitle: String = ""): String? {
        val wantTitles = titles.map(::norm).filter { it.isNotBlank() }
        val wantArtists = artists.map(::norm).filter { it.isNotBlank() }
        data class Scored(val c: LyricsCandidate, val score: Double)
        val scored = all.filter { it.best != null }.mapNotNull { c ->
            val t = norm(c.track)
            val a = norm(c.artist)
            val titleOk = wantTitles.any { w -> t == w || (w.length >= 3 && (t.startsWith(w) || w.startsWith(t))) }
            if (!titleOk) return@mapNotNull null
            val artistOk = wantArtists.isEmpty() || wantArtists.any { w -> a.contains(w) || w.contains(a) }
            val diff = if (durationSec > 0 && c.durationSec > 0) abs(c.durationSec - durationSec) else Double.NaN
            var score = 0.0
            if (remix) {
                // Only an exact title match is trusted when the artist can't be.
                if (wantTitles.none { it == t } || t.length < 4) return@mapNotNull null
            } else if (artistOk) score += 4 else score -= 3
            if (wantTitles.any { it == t }) score += 2
            if (!c.synced.isNullOrBlank()) score += 1.5
            if (!diff.isNaN()) score -= (diff / 3.0).coerceAtMost(6.0)
            Scored(c, score)
        }.sortedByDescending { it.score }
        val best = scored.firstOrNull()?.takeIf { remix || it.score > 0 }?.c ?: return null
        if (remix) {
            val ratio = if (durationSec > 0 && best.durationSec > 0) durationSec / best.durationSec else 0.0
            if (!best.synced.isNullOrBlank() && tempoMarker.containsMatchIn(rawTitle) && ratio in 0.55..1.8) {
                return LrcParser.withTempo(best.synced, ratio)
            }
            return best.plain?.takeIf { it.isNotBlank() } ?: best.synced?.let { LrcParser.stripTimes(it) }
        }
        val diff = if (durationSec > 0 && best.durationSec > 0) abs(best.durationSec - durationSec) else Double.NaN
        // Synced lyrics from a different cut of the song would drift; fall back to the plain text then.
        val syncedOk = !best.synced.isNullOrBlank() && (diff.isNaN() || diff <= 8)
        return if (syncedOk) best.synced else best.plain?.takeIf { it.isNotBlank() } ?: best.synced
    }

    private fun parseArray(arr: JSONArray): List<LyricsCandidate> =
        (0 until arr.length()).map { candidate(arr.getJSONObject(it)) }

    private fun candidate(o: JSONObject) = LyricsCandidate(
        id = o.optLong("id"),
        track = o.optString("trackName"),
        artist = o.optString("artistName"),
        album = o.optString("albumName"),
        durationSec = o.optDouble("duration", 0.0),
        synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") },
        plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") },
    )

    private fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Sonora Music Player/0.1 (Android)")
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val BASE = "https://lrclib.net"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private val noise = listOf(
            Regex("""\s*[(\[](feat|ft|with)\.?\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*[(\[][^)\]]*(remaster|version|edit|mix|live|mono|stereo|explicit)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s+-\s+.*(remaster|version|edit|live|mono|stereo).*$""", RegexOption.IGNORE_CASE),
        )

        fun cleanTitle(t: String): String = noise.fold(t) { acc, r -> acc.replace(r, "") }.trim()

        private val streamNoise = listOf(
            Regex("""\s*[(\[][^)\]]*\b(from|official|video|audio|lyric|lyrics|visuali[sz]er|full song|hd|4k|explicit|clean|prod\.?)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s*[|｜].*$"""),
            Regex("""\s+-\s+(from|official|lyric|lyrics|audio|video)\b.*$""", RegexOption.IGNORE_CASE),
            Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE),
        )

        /** Title variants from most to least specific, e.g. "Sitaare (From "Ikkis")" -> ["Sitaare (From…)", "Sitaare"]. */
        fun titleVariants(raw: String, artist: String): List<String> {
            val out = LinkedHashSet<String>()
            var t = raw.trim()
            // "Artist - Title" style video titles.
            val dash = t.split(" - ", limit = 2)
            if (dash.size == 2 && artistVariants(artist).any { norm(dash[0]).contains(norm(it)) || norm(it).contains(norm(dash[0])) }) t = dash[1]
            out += cleanTitle(t)
            val stripped = streamNoise.fold(cleanTitle(t)) { acc, r -> acc.replace(r, "") }.trim()
            out += stripped
            out += stripped.substringBefore(" (").substringBefore(" [").substringBefore(" - ").trim()
            return out.filter { it.isNotBlank() }
        }

        fun artistVariants(raw: String): List<String> {
            val parts = raw.split(Regex("""\s*(,|&| x | and |\bfeat\.?|\bft\.?|•)\s*""", RegexOption.IGNORE_CASE))
                .map { it.replace(Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE), "").trim() }
                .filter { it.isNotBlank() && !it.equals("YouTube Music", true) }
            return parts.distinct().take(3)
        }

        /** Lowercase letters/digits only (any script), for loose comparisons. */
        fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
