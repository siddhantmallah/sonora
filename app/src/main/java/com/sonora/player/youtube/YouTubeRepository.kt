package com.sonora.player.youtube

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class YtSearchFilter(val label: String, val params: String?) {
    ALL("All", null),
    SONGS("Songs", "EgWKAQIIAWoMEA4QChADEAQQCRAF"),
    VIDEOS("Videos", "EgWKAQIQAWoMEA4QChADEAQQCRAF"),
    ALBUMS("Albums", "EgWKAQIYAWoMEA4QChADEAQQCRAF"),
    ARTISTS("Artists", "EgWKAQIgAWoMEA4QChADEAQQCRAF"),
    PLAYLISTS("Playlists", "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU="),
}

object YtBrowse {
    const val HOME = "FEmusic_home"
    const val LIKED_SONGS = "VLLM"
    const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
    const val LIBRARY_ALBUMS = "FEmusic_liked_albums"
    const val LIBRARY_ARTISTS = "FEmusic_library_corpus_artists"
    const val HISTORY = "FEmusic_history"
    const val EXPLORE = "FEmusic_explore"
    const val CHARTS = "FEmusic_charts"
}

/** Everything YouTube Music: browsing, search, radio, likes, account and stream resolution. */
class YouTubeRepository(private val context: Context, http: OkHttpClient, private val scope: CoroutineScope) {
    val auth = YouTubeAuth(context)
    private val api = InnerTube(http, auth)
    val resolver: StreamResolver by lazy { StreamResolver(http) }

    // Metadata of every YouTube song we've shown, so queues survive restarts and notifications have titles.
    private val metaFile = File(context.filesDir, "yt_songs.json")
    private val meta = object : LinkedHashMap<String, YtSong>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, YtSong>?) = size > 2000
    }
    private var saveJob: Job? = null

    // Songs liked on YouTube Music (newest first), stored on the phone so the list shows instantly and offline.
    private val likedFile = File(context.filesDir, "yt_liked.json")
    private val _liked = kotlinx.coroutines.flow.MutableStateFlow<List<YtSong>>(emptyList())
    val liked: kotlinx.coroutines.flow.StateFlow<List<YtSong>> = _liked
    /** Becomes true once stored song details are loaded, so lists that depend on them can recompute. */
    private val _metaLoaded = kotlinx.coroutines.flow.MutableStateFlow(false)
    val metaLoaded: kotlinx.coroutines.flow.StateFlow<Boolean> = _metaLoaded

    init {
        scope.launch(Dispatchers.IO) {
            loadMeta()
            loadLiked()
            _metaLoaded.value = true
        }
    }

    fun remember(songs: Collection<YtSong>) {
        if (songs.isEmpty()) return
        synchronized(meta) { songs.forEach { meta[it.videoId] = it } }
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) { delay(2000); saveMeta() }
    }

    fun song(videoId: String): YtSong? = synchronized(meta) { meta[videoId] }

    fun knownSongs(): List<YtSong> = synchronized(meta) { meta.values.toList() }

    fun mediaItem(videoId: String): MediaItem =
        song(videoId)?.toMediaItem()
            ?: MediaItem.Builder().setMediaId(YtIds.mediaId(videoId)).setUri(Uri.parse("ytm:$videoId")).build()

    // ------------------------------------------------------------ browsing

    suspend fun home(): YtPage {
        var page = YtParser.page(api.browse(YtBrowse.HOME))
        // The home feed arrives in pages; pull a couple more for a richer screen.
        repeat(2) {
            val token = page.continuation ?: return@repeat
            val more = runCatching { YtParser.page(api.browseContinuation(token)) }.getOrNull() ?: return@repeat
            page = page.copy(shelves = page.shelves + more.shelves, continuation = more.continuation)
        }
        remember(page.allSongs)
        return page
    }

    private val charts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<YtSong>>>()

    /**
     * Today's top songs for a country ("IN") or the world ("ZZ"), from YouTube Music charts: the "Trending" chart
     * when there is one, otherwise the daily top chart. Cached for 3 hours.
     */
    suspend fun topSongs(country: String, count: Int = 10): List<YtSong> {
        charts[country]?.let { (at, list) -> if (System.currentTimeMillis() - at < 3 * 3_600_000L && list.isNotEmpty()) return list }
        val page = YtParser.page(api.browse(YtBrowse.CHARTS, country = country))
        val lists = page.shelves.flatMap { it.items }.filterIsInstance<YtCollection>()
        val chart = lists.firstOrNull { it.title.startsWith("Trending", true) }
            ?: lists.firstOrNull { it.title.contains("Daily Top", true) }
            ?: lists.firstOrNull { it.title.contains("Top", true) && it.kind != YtKind.ARTIST }
            ?: return emptyList()
        val songs = browse(chart.browseId).allSongs.take(count)
        if (songs.isNotEmpty()) charts[country] = System.currentTimeMillis() to songs
        return songs
    }

    suspend fun browse(browseId: String): YtPage = YtParser.page(api.browse(browseId)).also { remember(it.allSongs) }

    suspend fun more(token: String): YtPage = YtParser.page(api.browseContinuation(token)).also { remember(it.allSongs) }

    suspend fun search(query: String, filter: YtSearchFilter): YtPage =
        YtParser.page(api.search(query, filter.params)).also { remember(it.allSongs) }

    suspend fun searchMore(token: String, filter: YtSearchFilter): YtPage =
        YtParser.page(api.searchContinuation(token, filter.params)).also { remember(it.allSongs) }

    suspend fun suggestions(query: String): List<String> =
        runCatching { YtParser.suggestions(api.suggestions(query)) }.getOrDefault(emptyList())

    /** "Song radio": YouTube Music's endless queue of similar tracks. */
    suspend fun radio(videoId: String, playlistId: String? = null): List<YtSong> {
        val root = api.next(videoId, playlistId)
        val songs = YtParser.shelves(root).flatMap { it.songs }.distinctBy { it.videoId }
        remember(songs)
        return songs
    }

    // ------------------------------------------------------------ account

    fun onSignedIn(cookie: String) {
        auth.saveCookie(cookie)
        scope.launch { refreshAccount() }
    }

    suspend fun refreshAccount() {
        if (!auth.signedIn.value) return
        runCatching { YtParser.account(api.accountMenu()) }
            .onSuccess { it?.let(auth::saveAccount) }
            .onFailure { Log.w("SonoraYT", "account menu failed", it) }
    }

    fun signOut() = auth.signOut()

    suspend fun setLiked(videoId: String, liked: Boolean) {
        if (!auth.signedIn.value) return
        // Keep the stored list in step right away; YouTube confirms on the next sync.
        val cur = _liked.value
        _liked.value = if (liked) listOfNotNull(song(videoId)) + cur.filter { it.videoId != videoId } else cur.filter { it.videoId != videoId }
        withContext(Dispatchers.IO) { saveLiked(_liked.value) }
        runCatching { api.like(videoId, liked) }.onFailure { Log.w("SonoraYT", "like failed", it) }
    }

    /**
     * Pulls every song in the account's "Liked music" (newest first). Returns null when signed out or when the
     * page couldn't be read, so a hiccup never wipes the stored list.
     */
    suspend fun syncLiked(): List<YtSong>? {
        if (!auth.signedIn.value) return null
        var page = runCatching { YtParser.page(api.browse(YtBrowse.LIKED_SONGS)) }
            .onFailure { Log.w("SonoraYT", "liked songs failed", it) }.getOrNull() ?: return null
        val all = page.allSongs.toMutableList()
        var guard = 0
        while (guard++ < 60) {
            val token = page.continuation ?: break
            page = runCatching { YtParser.page(api.browseContinuation(token)) }.getOrNull() ?: break
            if (page.allSongs.isEmpty()) break
            all += page.allSongs
        }
        val list = all.filter { !it.isVideo || it.title.isNotBlank() }.distinctBy { it.videoId }
        if (list.isEmpty() && _liked.value.isNotEmpty()) return null
        remember(list)
        _liked.value = list
        withContext(Dispatchers.IO) { saveLiked(list) }
        return list
    }

    // ------------------------------------------------------------ persistence

    private fun loadLiked() {
        runCatching {
            if (!likedFile.exists()) return
            val arr = JSONArray(likedFile.readText())
            _liked.value = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }
    }

    private fun saveLiked(list: List<YtSong>) {
        runCatching { likedFile.writeText(JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()) }
    }

    private fun loadMeta() {
        runCatching {
            if (!metaFile.exists()) return
            val arr = JSONArray(metaFile.readText())
            val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
            synchronized(meta) { list.forEach { meta.putIfAbsent(it.videoId, it) } }
        }
    }

    private suspend fun saveMeta() = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        synchronized(meta) { meta.values.forEach { arr.put(toJson(it)) } }
        runCatching { metaFile.writeText(arr.toString()) }
    }

    private fun toJson(s: YtSong) = JSONObject()
        .put("v", s.videoId).put("t", s.title).put("d", s.durationSec).put("th", s.thumbnail).put("iv", s.isVideo)
        .put("a", JSONArray(s.artists.map { JSONObject().put("n", it.name).put("b", it.browseId) }))
        .put("al", s.album?.let { JSONObject().put("n", it.name).put("b", it.browseId) })

    private fun fromJson(o: JSONObject): YtSong? {
        val v = o.optString("v").ifBlank { return null }
        val a = o.optJSONArray("a")
        return YtSong(
            videoId = v,
            title = o.optString("t"),
            artists = (0 until (a?.length() ?: 0)).mapNotNull { i ->
                a!!.optJSONObject(i)?.let { YtRef(it.optString("n"), it.optString("b").ifBlank { null }) }
            },
            album = o.optJSONObject("al")?.let { YtRef(it.optString("n"), it.optString("b").ifBlank { null }) },
            durationSec = o.optInt("d"),
            thumbnail = o.optString("th").ifBlank { null },
            isVideo = o.optBoolean("iv"),
        )
    }
}
