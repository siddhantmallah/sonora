package com.sonora.player.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Immutable
import com.sonora.player.data.db.ExternalDao
import com.sonora.player.data.db.ExternalPlayEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/** A music service the user can connect. */
@Immutable
data class Service(
    val id: String,
    val name: String,
    val color: Long,
    /** How to get a listening-history file out of the service. */
    val howTo: String,
    val signIn: Boolean = false,
)

val Services = listOf(
    Service("Spotify", "Spotify", 0xFF1DB954,
        "Spotify app or website → Account → Privacy settings → Download your data. Choose \"Extended streaming history\" (or \"Account data\"). Spotify emails a .zip in a few days. Import the .zip here.",
        signIn = true),
    Service("Apple Music", "Apple Music", 0xFFFA2D48,
        "privacy.apple.com → Request a copy of your data → Apple Media Services. Unzip it and import \"Apple Music - Play History Daily Tracks.csv\" (or \"Play Activity.csv\")."),
    Service("Last.fm", "Last.fm", 0xFFD51007,
        "Sign in with your username and an API key, or import a CSV of your scrobbles.", signIn = true),
    Service("Amazon Music", "Amazon Music", 0xFF25D1DA,
        "amazon.com → Request your data → Digital music. Import the listening-history CSV from the download."),
    Service("YouTube", "YouTube (Takeout)", 0xFFFF0000,
        "takeout.google.com → YouTube and YouTube Music → History (JSON). Import watch-history.json. Your YouTube Music account history is already used when you're signed in."),
    Service("Deezer", "Deezer", 0xFFA238FF,
        "Deezer → Settings → Privacy → My personal data. Open the .xlsx, save the listening-history sheet as CSV, and import it."),
    Service("SoundCloud", "SoundCloud", 0xFFFF5500,
        "SoundCloud → Settings → Privacy → Request your data. Import the listening-history CSV."),
    Service("Tidal", "TIDAL", 0xFF33FFEE,
        "TIDAL support → request your personal data. Import the streaming-history CSV."),
    Service("JioSaavn", "JioSaavn", 0xFF2BC5B4,
        "JioSaavn has no history export. Export your playlists or liked songs to CSV with a playlist-transfer tool (e.g. TuneMyMusic) and import that file."),
    Service("Gaana", "Gaana", 0xFFE72C30,
        "Gaana has no history export. Export your playlists to CSV with a playlist-transfer tool and import that file."),
    Service("Wynk", "Wynk Music", 0xFFE41E26,
        "Wynk has no history export. Export your playlists to CSV with a playlist-transfer tool and import that file."),
)

/**
 * Connects other music services: imports their listening-history exports into `external_plays`, signs in to
 * Spotify (OAuth PKCE with the user's own Client ID) and Last.fm (username + API key) for live sync, and keeps
 * their "top artists / tracks" as extra taste signals. Tokens stay in app-private storage.
 */
class ConnectionsRepository(private val context: Context, private val http: OkHttpClient, private val dao: ExternalDao) {
    private val prefs = context.getSharedPreferences("connections", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow<String?>(null)
    /** Last import / sync message, for the UI. */
    val status: StateFlow<String?> = _status

    // ---------------------------------------------------------------- imports

    /** Imports a file (zip / json / csv) for [source]. Returns how many listens were added. */
    suspend fun import(source: String, uri: Uri): Int = withContext(Dispatchers.IO) {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val rows = ArrayList<ExternalPlayEntity>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = input.buffered()
            head.mark(4)
            val sig = ByteArray(2)
            val n = head.read(sig)
            head.reset()
            if (n == 2 && sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte()) {
                ZipInputStream(head).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val en = e.name.lowercase()
                        val wanted = en.endsWith(".json") && ("streaming" in en || "history" in en) ||
                            en.endsWith(".csv") && ("play" in en || "history" in en || "listen" in en)
                        if (!e.isDirectory && wanted) rows += parse(source, en, zip.readBytes().toString(Charsets.UTF_8))
                        e = zip.nextEntry
                    }
                }
            } else {
                rows += parse(source, name, BufferedReader(InputStreamReader(head, Charsets.UTF_8)).readText())
            }
        }
        val clean = rows.filter { it.title.isNotBlank() && it.playedAt > 0 }
            .distinctBy { "${it.playedAt}|${it.title}" }
        if (clean.isEmpty()) {
            _status.value = "No listening history found in that file"
            return@withContext 0
        }
        dao.clear(source) // an export is a full snapshot: replace the previous one
        clean.chunked(2000).forEach { dao.insertAll(it) }
        _status.value = "Imported ${clean.size} listens from $source"
        clean.size
    }

    private fun parse(source: String, name: String, text: String): List<ExternalPlayEntity> {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return if (t.startsWith("[") || t.startsWith("{")) parseJson(source, t) else parseCsv(source, t)
    }

    private fun parseJson(source: String, text: String): List<ExternalPlayEntity> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ExternalPlayEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when {
                // Spotify extended streaming history.
                o.has("master_metadata_track_name") -> {
                    val title = o.optString("master_metadata_track_name").takeIf { it != "null" } ?: continue
                    out += ExternalPlayEntity(0, source, title, o.optString("master_metadata_album_artist_name"),
                        o.optString("master_metadata_album_album_name").ifBlank { null }, time(o.optString("ts")), o.optLong("ms_played"))
                }
                // Spotify account-data history.
                o.has("trackName") -> out += ExternalPlayEntity(0, source, o.optString("trackName"), o.optString("artistName"), null,
                    time(o.optString("endTime")), o.optLong("msPlayed"))
                // Google Takeout (YouTube / YouTube Music).
                o.has("header") && o.has("title") -> {
                    if (!o.optString("header").contains("Music", true)) continue // skip plain YouTube videos
                    val title = o.optString("title").removePrefix("Watched ").trim()
                    val artist = o.optJSONArray("subtitles")?.optJSONObject(0)?.optString("name")?.removeSuffix(" - Topic").orEmpty()
                    out += ExternalPlayEntity(0, source, title, artist, null, time(o.optString("time")), 180_000)
                }
            }
        }
        return out
    }

    /** CSV with a header (columns found by name) or headerless Last.fm style (artist, album, track, date). */
    private fun parseCsv(source: String, text: String): List<ExternalPlayEntity> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val header = splitCsv(lines.first()).map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { h -> names.any { h == it } }.takeIf { it >= 0 }
            ?: header.indexOfFirst { h -> names.any { n -> h.contains(n) } }.takeIf { it >= 0 }
        var title = col("song name", "track name", "song title", "track title", "title", "track", "song", "content name")
        var artist = col("artist name", "artist", "artists", "album artist")
        var album = col("album name", "album title", "album", "container description")
        var time = col("event start timestamp", "date played", "played at", "timestamp", "utc_time", "date", "time", "ts", "endtime")
        val dur = col("play duration milliseconds", "ms_played", "msplayed", "media duration in milliseconds", "listening time", "duration")
        val desc = col("track description")
        var start = 1
        if (title == null && desc == null) {
            // Headerless Last.fm export: artist, album, track, date
            title = 2; artist = 0; album = 1; time = 3; start = 0
        }
        val out = ArrayList<ExternalPlayEntity>()
        for (i in start until lines.size) {
            val c = splitCsv(lines[i])
            fun at(ix: Int?) = ix?.let { c.getOrNull(it) }?.trim().orEmpty()
            var t = at(title)
            var a = at(artist)
            if (t.isBlank() && desc != null) {
                // Apple Music "Track Description" = "Artist - Title"
                val d = at(desc)
                a = d.substringBefore(" - ", "")
                t = d.substringAfter(" - ")
            }
            if (t.isBlank()) continue
            val ms = at(dur).toLongOrNull()?.let { if (it < 10_000) it * 1000 else it } ?: 180_000
            out += ExternalPlayEntity(0, source, t, a, at(album).ifBlank { null }, time(at(time)).takeIf { it > 0 } ?: System.currentTimeMillis(), ms)
        }
        return out
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        val sep = if (line.count { it == '\t' } > line.count { it == ',' }) '\t' else ','
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** Parses the many timestamp styles exports use. */
    private fun time(s: String): Long {
        val v = s.trim()
        if (v.isEmpty()) return 0
        v.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        runCatching { return Instant.parse(v).toEpochMilli() }
        runCatching { return Instant.parse(v.replace(' ', 'T') + if (v.endsWith("Z")) "" else "Z").toEpochMilli() }
        val zone = ZoneId.systemDefault()
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "dd MMM yyyy HH:mm", "dd MMM yyyy, HH:mm", "MM/dd/yyyy HH:mm", "dd/MM/yyyy HH:mm")) {
            runCatching { return LocalDateTime.parse(v, DateTimeFormatter.ofPattern(p, java.util.Locale.ENGLISH)).atZone(zone).toInstant().toEpochMilli() }
        }
        for (p in listOf("yyyyMMdd", "yyyy-MM-dd", "MM/dd/yyyy", "dd/MM/yyyy")) {
            runCatching { return LocalDate.parse(v, DateTimeFormatter.ofPattern(p)).atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli() }
        }
        return 0
    }

    suspend fun remove(source: String) = withContext(Dispatchers.IO) {
        dao.clear(source)
        prefs.edit().remove("$source.token").remove("$source.refresh").remove("$source.user").remove("$source.top").remove("$source.synced").apply()
        _status.value = "Disconnected $source"
    }

    fun isSignedIn(source: String) = prefs.contains("$source.token") || (source == "Last.fm" && prefs.contains("Last.fm.user"))
    fun lastSync(source: String) = prefs.getLong("$source.synced", 0)

    // ---------------------------------------------------------------- taste signals from connected services

    /** Top artists / tracks reported by signed-in services, as (title, artist) pairs, strongest first. */
    fun topTracks(): List<Pair<String, String>> = Services.flatMap { s ->
        val arr = runCatching { JSONArray(prefs.getString("${s.id}.top", "[]")) }.getOrDefault(JSONArray())
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { it.optString("t") to it.optString("a") } }
    }

    private fun saveTop(source: String, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { (t, a) -> arr.put(JSONObject().put("t", t).put("a", a)) }
        prefs.edit().putString("$source.top", arr.toString()).apply()
    }

    // ---------------------------------------------------------------- Spotify (OAuth PKCE with the user's Client ID)

    var spotifyClientId: String
        get() = prefs.getString("Spotify.clientId", "").orEmpty()
        set(v) = prefs.edit().putString("Spotify.clientId", v.trim()).apply()

    /** Builds the Spotify authorize URL (opened in the browser); the redirect comes back to [SPOTIFY_REDIRECT]. */
    fun spotifyAuthUrl(): String {
        val verifier = ByteArray(64).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        prefs.edit().putString("Spotify.verifier", verifier).apply()
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", spotifyClientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", "user-read-recently-played user-top-read")
            .build().toString()
    }

    /** Handles sonora://spotify-auth?code=… */
    suspend fun finishSpotify(redirect: Uri): Boolean = withContext(Dispatchers.IO) {
        val code = redirect.getQueryParameter("code") ?: run { _status.value = "Spotify sign-in was cancelled"; return@withContext false }
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code).add("redirect_uri", SPOTIFY_REDIRECT)
            .add("client_id", spotifyClientId).add("code_verifier", prefs.getString("Spotify.verifier", "").orEmpty())
            .build()
        val ok = token(body)
        if (ok) syncSpotify() else _status.value = "Spotify sign-in failed. Check the Client ID and redirect URI."
        ok
    }

    private fun token(body: FormBody): Boolean = runCatching {
        http.newCall(Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) return false
            val o = JSONObject(r.body.string())
            prefs.edit().putString("Spotify.token", o.getString("access_token"))
                .putLong("Spotify.expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000)
                .apply { o.optString("refresh_token").takeIf { it.isNotBlank() }?.let { putString("Spotify.refresh", it) } }
                .apply()
            true
        }
    }.getOrDefault(false)

    private fun spotifyGet(path: String): JSONObject? {
        if (System.currentTimeMillis() > prefs.getLong("Spotify.expires", 0)) {
            val refresh = prefs.getString("Spotify.refresh", null) ?: return null
            if (!token(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", spotifyClientId).build())) return null
        }
        val t = prefs.getString("Spotify.token", null) ?: return null
        return runCatching {
            http.newCall(Request.Builder().url("https://api.spotify.com/v1$path").header("Authorization", "Bearer $t").build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body.string()) else null
            }
        }.getOrNull()
    }

    suspend fun syncSpotify() = withContext(Dispatchers.IO) {
        if (!prefs.contains("Spotify.token")) return@withContext
        val latest = dao.latest("Spotify") ?: 0L
        val recent = spotifyGet("/me/player/recently-played?limit=50")?.optJSONArray("items")
        val rows = ArrayList<ExternalPlayEntity>()
        for (i in 0 until (recent?.length() ?: 0)) {
            val it = recent!!.getJSONObject(i)
            val tr = it.getJSONObject("track")
            val at = time(it.optString("played_at"))
            if (at <= latest) continue
            rows += ExternalPlayEntity(0, "Spotify", tr.optString("name"), artists(tr), tr.optJSONObject("album")?.optString("name"), at, tr.optLong("duration_ms"))
        }
        if (rows.isNotEmpty()) dao.insertAll(rows)
        val top = ArrayList<Pair<String, String>>()
        for (range in listOf("short_term", "medium_term")) {
            val items = spotifyGet("/me/top/tracks?time_range=$range&limit=50")?.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) items.getJSONObject(i).let { top += it.optString("name") to artists(it) }
        }
        if (top.isNotEmpty()) saveTop("Spotify", top.distinct())
        prefs.edit().putLong("Spotify.synced", System.currentTimeMillis()).apply()
        _status.value = "Spotify synced: ${rows.size} new listens, ${top.distinct().size} top tracks"
    }

    private fun artists(track: JSONObject) = track.optJSONArray("artists")?.let { a ->
        (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") }
    }.orEmpty()

    // ---------------------------------------------------------------- Last.fm (username + API key)

    suspend fun connectLastFm(user: String, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        prefs.edit().putString("Last.fm.user", user.trim()).putString("Last.fm.key", apiKey.trim()).apply()
        val ok = syncLastFm()
        if (!ok) prefs.edit().remove("Last.fm.user").remove("Last.fm.key").apply()
        ok
    }

    suspend fun syncLastFm(): Boolean = withContext(Dispatchers.IO) {
        val user = prefs.getString("Last.fm.user", null) ?: return@withContext false
        val key = prefs.getString("Last.fm.key", null) ?: return@withContext false
        fun call(method: String, extra: String) = runCatching {
            http.newCall(Request.Builder().url("https://ws.audioscrobbler.com/2.0/?method=$method&user=${Uri.encode(user)}&api_key=$key&format=json$extra").build())
                .execute().use { r -> if (r.isSuccessful) JSONObject(r.body.string()) else null }
        }.getOrNull()
        val latest = dao.latest("Last.fm") ?: 0L
        val rows = ArrayList<ExternalPlayEntity>()
        for (page in 1..5) {
            val tracks = call("user.getrecenttracks", "&limit=200&page=$page")?.optJSONObject("recenttracks")?.optJSONArray("track") ?: break
            var older = false
            for (i in 0 until tracks.length()) {
                val t = tracks.getJSONObject(i)
                val at = t.optJSONObject("date")?.optString("uts")?.toLongOrNull()?.times(1000) ?: continue
                if (at <= latest) { older = true; break }
                rows += ExternalPlayEntity(0, "Last.fm", t.optString("name"), t.optJSONObject("artist")?.optString("#text").orEmpty(),
                    t.optJSONObject("album")?.optString("#text")?.ifBlank { null }, at, 200_000)
            }
            if (older || tracks.length() < 200) break
        }
        if (rows.isEmpty() && latest == 0L && call("user.getinfo", "") == null) {
            _status.value = "Last.fm: couldn't sign in. Check the username and API key."
            return@withContext false
        }
        if (rows.isNotEmpty()) dao.insertAll(rows)
        val top = call("user.gettoptracks", "&period=6month&limit=100")?.optJSONObject("toptracks")?.optJSONArray("track")
        if (top != null) saveTop("Last.fm", (0 until top.length()).map { i -> top.getJSONObject(i).let { it.optString("name") to (it.optJSONObject("artist")?.optString("name").orEmpty()) } })
        prefs.edit().putLong("Last.fm.synced", System.currentTimeMillis()).apply()
        _status.value = "Last.fm synced: ${rows.size} new scrobbles"
        true
    }

    /** Refreshes every signed-in service (called when the app opens). */
    suspend fun syncAll() {
        if (prefs.contains("Spotify.token")) runCatching { syncSpotify() }
        if (prefs.contains("Last.fm.user")) runCatching { syncLastFm() }
    }

    companion object {
        const val SPOTIFY_REDIRECT = "sonora://spotify-auth"
    }
}
