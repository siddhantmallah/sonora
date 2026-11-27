package com.sonora.player.data

import androidx.compose.runtime.Immutable
import com.sonora.player.data.db.ExternalPlayEntity
import com.sonora.player.data.db.PlayEventEntity
import java.util.Calendar

/** One listen from any source (Sonora itself or an imported service). */
data class Listen(
    val key: String,
    val title: String,
    val artist: String,
    val art: String?,
    val at: Long,
    val ms: Long,
    val skipped: Boolean,
    val source: String,
)

enum class Metric(val label: String) { TIME("Listening time"), PLAYS("Plays"), SONGS("Songs"), ARTISTS("Artists") }

@Immutable
data class TopSong(val key: String, val title: String, val artist: String, val art: String?, val plays: Int, val minutes: Int)

@Immutable
data class TopArtist(val name: String, val art: String?, val minutes: Int, val share: Float)

/** YouTube-Studio-style analytics for a period, compared with the period before it. */
@Immutable
data class Insights(
    val days: Int?,
    val totals: Map<Metric, Long>,
    val previous: Map<Metric, Long>,
    /** One value per bucket (day, or week for long periods), oldest first. */
    val series: Map<Metric, List<Float>>,
    val seriesStart: Long,
    val bucketMs: Long,
    /** Minutes per hour over the last 48 hours, oldest first. */
    val last48: List<Float>,
    val last48Minutes: Long,
    val topSongs: List<TopSong>,
    val topArtists: List<TopArtist>,
    /** [dayOfWeek Mon..Sun][hour] minutes. */
    val heat: List<List<Float>>,
    /** Source → minutes. */
    val sources: List<Pair<String, Long>>,
    val streakDays: Int,
    val skipRate: Int,
    val newArtists: Int,
    val repeatShare: Int,
    val empty: Boolean,
) {
    fun change(m: Metric): Int? {
        val prev = previous[m] ?: return null
        if (days == null || prev == 0L) return null
        return (((totals[m] ?: 0) - prev) * 100 / prev).toInt()
    }
}

object InsightsEngine {
    private const val DAY = 86_400_000L

    fun listens(own: List<PlayEventEntity>, external: List<ExternalPlayEntity>): List<Listen> =
        own.map {
            Listen(it.mediaId, it.title, it.artist, it.artUri, it.playedAt, it.listenedMs, it.skipped,
                if (it.mediaId.startsWith("yt:")) "YouTube Music" else "On this phone")
        } + external.map {
            Listen("ext:${it.title.lowercase()}|${it.artist.lowercase()}", it.title, it.artist, null, it.playedAt, it.msPlayed, false, it.source)
        }

    fun compute(all: List<Listen>, days: Int?, now: Long = System.currentTimeMillis()): Insights {
        val first = all.minOfOrNull { it.at } ?: now
        val start = if (days == null) startOfDay(first) else startOfDay(now) - (days - 1) * DAY
        val span = now - start
        val inPeriod = all.filter { it.at >= start }
        val prevPeriod = if (days == null) emptyList() else all.filter { it.at >= start - span && it.at < start }

        fun totals(list: List<Listen>): Map<Metric, Long> {
            val heard = list.filter { !it.skipped }
            return mapOf(
                Metric.TIME to list.sumOf { it.ms } / 60_000,
                Metric.PLAYS to heard.size.toLong(),
                Metric.SONGS to heard.map { it.key }.distinct().size.toLong(),
                Metric.ARTISTS to heard.flatMap { splitArtists(it.artist) }.distinct().size.toLong(),
            )
        }

        // Daily buckets up to 90 days; weekly beyond that.
        val bucket = if (days != null && days <= 90) DAY else 7 * DAY
        val buckets = ((now - start) / bucket + 1).toInt().coerceIn(1, 120)
        val time = FloatArray(buckets)
        val plays = FloatArray(buckets)
        val songSets = Array(buckets) { HashSet<String>() }
        val artistSets = Array(buckets) { HashSet<String>() }
        for (l in inPeriod) {
            val b = ((l.at - start) / bucket).toInt().coerceIn(0, buckets - 1)
            time[b] += l.ms / 60_000f
            if (!l.skipped) {
                plays[b] += 1f
                songSets[b] += l.key
                artistSets[b].addAll(splitArtists(l.artist))
            }
        }

        val hourStart = now - 47 * 3_600_000L - now % 3_600_000L
        val last48 = FloatArray(48)
        for (l in all) if (l.at >= hourStart) last48[((l.at - hourStart) / 3_600_000L).toInt().coerceIn(0, 47)] += l.ms / 60_000f

        val heard = inPeriod.filter { !it.skipped }
        val bySong = heard.groupBy { it.key }
        val topSongs = bySong.map { (k, ls) ->
            val f = ls.maxBy { it.at }
            TopSong(k, f.title, f.artist, ls.firstNotNullOfOrNull { it.art }, ls.size, (ls.sumOf { it.ms } / 60_000).toInt())
        }.sortedWith(compareByDescending<TopSong> { it.plays }.thenByDescending { it.minutes }).take(10)

        val artistMs = HashMap<String, Long>()
        val artistArt = HashMap<String, String?>()
        for (l in heard) for (a in splitArtists(l.artist)) {
            artistMs[a] = (artistMs[a] ?: 0L) + l.ms
            if (artistArt[a] == null) artistArt[a] = l.art
        }
        val totalArtistMs = artistMs.values.sum().coerceAtLeast(1L)
        val topArtists = artistMs.entries.sortedByDescending { it.value }.take(8)
            .map { TopArtist(it.key, artistArt[it.key], (it.value / 60_000).toInt(), it.value.toFloat() / totalArtistMs) }

        val cal = Calendar.getInstance()
        val heat = List(7) { FloatArray(24) }
        for (l in inPeriod) {
            cal.timeInMillis = l.at
            val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon = 0
            heat[dow][cal.get(Calendar.HOUR_OF_DAY)] += l.ms / 60_000f
        }

        val sources = inPeriod.groupBy { it.source }.map { (k, v) -> k to v.sumOf { it.ms } / 60_000 }
            .filter { it.second > 0 }.sortedByDescending { it.second }

        // Streak: consecutive days (ending today or yesterday) with at least a minute of listening.
        val dayMinutes = HashMap<Long, Long>()
        for (l in all) dayMinutes[startOfDay(l.at)] = (dayMinutes[startOfDay(l.at)] ?: 0L) + l.ms
        var day = startOfDay(now)
        if ((dayMinutes[day] ?: 0L) < 60_000) day -= DAY
        var streak = 0
        while ((dayMinutes[day] ?: 0L) >= 60_000) { streak++; day -= DAY }

        val before = all.filter { it.at < start }.flatMap { splitArtists(it.artist) }.toHashSet()
        val periodArtists = heard.flatMap { splitArtists(it.artist) }.toSet()
        val newArtists = if (days == null) 0 else periodArtists.count { it !in before }
        val repeatPlays = bySong.values.filter { it.size >= 3 }.sumOf { it.size }

        return Insights(
            days = days,
            totals = totals(inPeriod),
            previous = totals(prevPeriod),
            series = mapOf(
                Metric.TIME to time.toList(),
                Metric.PLAYS to plays.toList(),
                Metric.SONGS to songSets.map { it.size.toFloat() },
                Metric.ARTISTS to artistSets.map { it.size.toFloat() },
            ),
            seriesStart = start,
            bucketMs = bucket,
            last48 = last48.toList(),
            last48Minutes = last48.sum().toLong(),
            topSongs = topSongs,
            topArtists = topArtists,
            heat = heat.map { it.toList() },
            sources = sources,
            streakDays = streak,
            skipRate = if (inPeriod.isEmpty()) 0 else inPeriod.count { it.skipped } * 100 / inPeriod.size,
            newArtists = newArtists,
            repeatShare = if (heard.isEmpty()) 0 else repeatPlays * 100 / heard.size,
            empty = inPeriod.isEmpty(),
        )
    }

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
