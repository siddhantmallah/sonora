package com.sonora.player.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * Minimal client for YouTube Music's private "InnerTube" API (the one music.youtube.com itself uses).
 * Works signed-out; with the user's cookies it returns their personal home feed and library.
 */
class InnerTube(private val http: OkHttpClient, private val auth: YouTubeAuth) {

    @Volatile private var visitorData: String? = null
    private val json = "application/json".toMediaType()

    private fun context(): JSONObject {
        val locale = Locale.getDefault()
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", locale.language.ifBlank { "en" })
            .put("gl", locale.country.ifBlank { "US" })
        visitorData?.let { client.put("visitorData", it) }
        return JSONObject().put("client", client).put("user", JSONObject().put("lockedSafetyMode", false))
    }

    suspend fun call(endpoint: String, body: JSONObject = JSONObject(), query: String = ""): JSONObject = withContext(Dispatchers.IO) {
        body.put("context", context())
        val req = Request.Builder()
            .url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false$query")
            .post(body.toString().toRequestBody(json))
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Origin", ORIGIN)
            .header("X-Youtube-Client-Name", "67")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
        auth.cookie?.let { cookie ->
            req.header("Cookie", cookie)
            auth.authorization()?.let { req.header("Authorization", it) }
            req.header("X-Goog-AuthUser", "0")
        }
        visitorData?.let { req.header("X-Goog-Visitor-Id", it) }
        val res = http.newCall(req.build()).execute()
        res.use {
            if (!it.isSuccessful) throw IOException("YouTube Music returned ${it.code}")
            val o = JSONObject(it.body.string())
            o.optJSONObject("responseContext")?.optString("visitorData")?.takeIf(String::isNotBlank)?.let { v -> visitorData = v }
            o
        }
    }

    suspend fun browse(browseId: String, params: String? = null, country: String? = null) =
        call("browse", JSONObject().put("browseId", browseId).apply {
            params?.let { put("params", it) }
            country?.let { put("formData", JSONObject().put("selectedValues", org.json.JSONArray().put(it))) }
        })

    suspend fun browseContinuation(token: String): JSONObject {
        val t = URLEncoder.encode(token, "UTF-8")
        return call("browse", JSONObject(), "&ctoken=$t&continuation=$t&type=next")
    }

    suspend fun search(query: String, params: String? = null) =
        call("search", JSONObject().put("query", query).apply { params?.let { put("params", it) } })

    suspend fun searchContinuation(token: String, params: String?): JSONObject {
        val t = URLEncoder.encode(token, "UTF-8")
        return call("search", JSONObject().apply { params?.let { put("params", it) } }, "&ctoken=$t&continuation=$t&type=next")
    }

    suspend fun next(videoId: String, playlistId: String? = null) = call(
        "next",
        JSONObject()
            .put("videoId", videoId)
            .put("playlistId", playlistId ?: "RDAMVM$videoId")
            .put("isAudioOnly", true)
            .put("enablePersistentPlaylistPanel", true)
            .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL"),
    )

    suspend fun nextContinuation(token: String) = call(
        "next",
        JSONObject().put("continuation", token).put("isAudioOnly", true).put("enablePersistentPlaylistPanel", true),
    )

    suspend fun suggestions(input: String) = call("music/get_search_suggestions", JSONObject().put("input", input))

    suspend fun like(videoId: String, like: Boolean) =
        call(if (like) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))

    suspend fun accountMenu() = call("account/account_menu")

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        const val CLIENT_VERSION = "1.20251110.01.00"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

/** Turns InnerTube's deeply nested renderer JSON into [YtPage]s. Tolerant of layout changes by walking the tree. */
object YtParser {

    fun page(root: JSONObject): YtPage {
        // Your own playlists wrap the real header (title, cover) inside an "editable" header; look inside it.
        val header = findFirst(root, HEADER_KEYS)?.let { h -> if (h.has("title")) h else findFirst(h, HEADER_KEYS) ?: h }
        val shelves = shelves(root)
        return YtPage(
            title = header?.o("title").text().ifBlank { null },
            subtitle = header?.let {
                listOf(it.o("straplineTextOne").text(), it.o("subtitle").text(), it.o("secondSubtitle").text())
                    .filter(String::isNotBlank).joinToString(" • ")
            }?.ifBlank { null },
            thumbnail = header?.let { thumb(it.optJSONObject("thumbnail") ?: it.optJSONObject("foregroundThumbnail")) },
            description = header?.let {
                it.o("description", "musicDescriptionShelfRenderer", "description").text().ifBlank { it.o("description").text() }
            }?.ifBlank { null },
            shelves = shelves,
            continuation = continuation(root),
            playlistId = header?.let { findString(it, "playlistId") } ?: findString(root.optJSONObject("microformat"), "playlistId"),
        )
    }

    fun shelves(root: Any): List<YtShelf> {
        val out = ArrayList<YtShelf>()
        // Newer search layout: every result is its own itemSectionRenderer holding a single list item.
        val loose = ArrayList<YtItem>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val keys = node.keys().asSequence().toList()
                    for (k in keys) {
                        val v = node.opt(k)
                        val o = v as? JSONObject
                        when (k) {
                            "itemSectionRenderer" -> {
                                val contents = o?.optJSONArray("contents")
                                val direct = (0 until (contents?.length() ?: 0)).all { i ->
                                    contents?.optJSONObject(i)?.let { it.has("musicResponsiveListItemRenderer") || it.has("musicTwoRowItemRenderer") } == true
                                }
                                if (contents != null && contents.length() > 0 && direct) loose += items(contents) else walk(v)
                            }
                            "musicCarouselShelfRenderer" -> {
                                val h = o?.o("header", "musicCarouselShelfBasicHeaderRenderer")
                                out += YtShelf(
                                    h?.o("title").text(),
                                    items(o?.optJSONArray("contents")),
                                    h?.o("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint")?.optString("browseId"),
                                    h?.o("strapline").text()?.ifBlank { null },
                                )
                            }
                            "musicShelfRenderer" -> out += YtShelf(
                                o?.o("title").text(), items(o?.optJSONArray("contents")),
                                o?.o("bottomEndpoint", "browseEndpoint")?.optString("browseId"),
                            )
                            "musicPlaylistShelfRenderer", "musicShelfContinuation", "musicPlaylistShelfContinuation" ->
                                out += YtShelf("", items(o?.optJSONArray("contents")))
                            "gridRenderer" -> out += YtShelf(o?.o("header", "gridHeaderRenderer", "title").text().orEmpty(), items(o?.optJSONArray("items")))
                            "gridContinuation" -> out += YtShelf("", items(o?.optJSONArray("items")))
                            "musicCardShelfRenderer" -> {
                                val top = o?.let(::card)
                                var inner = items(o?.optJSONArray("contents"))
                                // Under an artist's top-result card, YouTube leaves the artist implicit ("Song • 3:59"):
                                // fill it in so rows don't fall back to a placeholder.
                                if (top is YtCollection && top.kind == YtKind.ARTIST) {
                                    val ref = YtRef(top.title, top.browseId)
                                    inner = inner.map { item ->
                                        when {
                                            item is YtSong && item.artists.isEmpty() -> item.copy(artists = listOf(ref))
                                            item is YtCollection && item.kind != YtKind.ARTIST && !item.subtitle.contains(top.title) ->
                                                item.copy(subtitle = listOf(item.subtitle.substringBefore(" • "), top.title, item.subtitle.substringAfter(" • ", ""))
                                                    .filter { it.isNotBlank() }.joinToString(" • "))
                                            else -> item
                                        }
                                    }
                                }
                                out += YtShelf("Top result", listOfNotNull(top) + inner)
                            }
                            "playlistPanelRenderer" -> out += YtShelf("Up next", items(o?.optJSONArray("contents")))
                            "continuationItems" -> out += YtShelf("", items(v as? JSONArray))
                            else -> walk(v)
                        }
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        if (loose.isNotEmpty()) {
            out += YtShelf("Songs", loose.filterIsInstance<YtSong>().filter { !it.isVideo })
            out += YtShelf("Videos", loose.filterIsInstance<YtSong>().filter { it.isVideo })
            out += YtShelf("Playlists & albums", loose.filterIsInstance<YtCollection>().filter { it.kind != YtKind.ARTIST })
            out += YtShelf("Artists", loose.filterIsInstance<YtCollection>().filter { it.kind == YtKind.ARTIST })
        }
        return out.filter { it.items.isNotEmpty() }
    }

    fun continuation(root: JSONObject): String? {
        var found: String? = null
        fun walk(node: Any?) {
            if (found != null) return
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf(String::isNotBlank)?.let { found = it; return }
                    node.optJSONObject("nextRadioContinuationData")?.optString("continuation")?.takeIf(String::isNotBlank)?.let { found = it; return }
                    node.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank)?.let { found = it; return }
                    for (k in node.keys()) walk(node.opt(k))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return found
    }

    fun account(root: JSONObject): YtAccount? {
        val h = findFirst(root, setOf("activeAccountHeaderRenderer")) ?: return null
        return YtAccount(
            name = h.o("accountName").text(),
            handle = h.o("channelHandle").text().ifBlank { null },
            photo = thumb(h.optJSONObject("accountPhoto")),
        )
    }

    fun suggestions(root: JSONObject): List<String> {
        val out = ArrayList<String>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("searchSuggestionRenderer")?.let { out += it.o("suggestion").text(); return }
                    for (k in node.keys()) walk(node.opt(k))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return out.filter(String::isNotBlank).distinct()
    }

    // ---------------------------------------------------------------- items

    fun items(arr: JSONArray?): List<YtItem> {
        arr ?: return emptyList()
        val out = ArrayList<YtItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val item = o.optJSONObject("musicResponsiveListItemRenderer")?.let(::responsive)
                ?: o.optJSONObject("musicTwoRowItemRenderer")?.let(::twoRow)
                ?: o.optJSONObject("playlistPanelVideoRenderer")?.let(::panel)
                ?: o.o("playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer")?.let(::panel)
            if (item != null) out += item
        }
        return out
    }

    private fun responsive(r: JSONObject): YtItem? {
        val flex = r.optJSONArray("flexColumns") ?: return null
        val col0 = flex.optJSONObject(0)?.o("musicResponsiveListItemFlexColumnRenderer", "text")
        val title = col0.text().ifBlank { return null }
        val subRuns = ArrayList<JSONObject>()
        for (i in 1 until flex.length()) {
            val runs = flex.optJSONObject(i)?.o("musicResponsiveListItemFlexColumnRenderer", "text")?.optJSONArray("runs") ?: continue
            if (subRuns.isNotEmpty()) subRuns += JSONObject().put("text", " • ")
            for (j in 0 until runs.length()) runs.optJSONObject(j)?.let(subRuns::add)
        }
        val thumbnail = thumb(r.optJSONObject("thumbnail"))
        val watch = col0?.optJSONArray("runs")?.optJSONObject(0)?.o("navigationEndpoint", "watchEndpoint")
        // A row that opens a page (album / playlist / artist) is that collection, even though its play overlay
        // carries the first track's videoId.
        val opensPage = r.o("navigationEndpoint", "browseEndpoint") != null
        val videoId = r.o("playlistItemData")?.optString("videoId")?.ifBlank { null }
            ?: watch?.optString("videoId")?.ifBlank { null }
            ?: if (opensPage) null else findString(r.optJSONObject("overlay"), "videoId")

        if (videoId != null) {
            val fixed = r.optJSONArray("fixedColumns")?.optJSONObject(0)?.o("musicResponsiveListItemFixedColumnRenderer", "text").text()
            val duration = parseDuration(fixed) ?: subRuns.asReversed().firstNotNullOfOrNull { parseDuration(it.optString("text")) } ?: 0
            val videoType = findString(r, "musicVideoType")
            return YtSong(
                videoId = videoId,
                title = title,
                artists = artistsFrom(subRuns),
                album = albumFrom(subRuns),
                durationSec = duration,
                thumbnail = thumbnail,
                isVideo = videoType != null && videoType != "MUSIC_VIDEO_TYPE_ATV",
            )
        }
        val browse = r.o("navigationEndpoint", "browseEndpoint") ?: return null
        return YtCollection(
            browseId = browse.optString("browseId").ifBlank { return null },
            title = title,
            subtitle = subRuns.joinToString("") { it.optString("text") },
            thumbnail = thumbnail,
            kind = kindOf(browse),
        )
    }

    private fun twoRow(t: JSONObject): YtItem? {
        val title = t.o("title").text().ifBlank { return null }
        val subRuns = runsList(t.o("subtitle"))
        val subtitle = subRuns.joinToString("") { it.optString("text") }
        val thumbnail = thumb(t.optJSONObject("thumbnailRenderer"))
        val nav = t.optJSONObject("navigationEndpoint") ?: return null
        nav.optJSONObject("watchEndpoint")?.let { w ->
            val id = w.optString("videoId").ifBlank { return null }
            return YtSong(id, title, artistsFrom(subRuns), null, 0, thumbnail, isVideo = true)
        }
        val browse = nav.optJSONObject("browseEndpoint") ?: return null
        return YtCollection(
            browseId = browse.optString("browseId").ifBlank { return null },
            title = title,
            subtitle = subtitle,
            thumbnail = thumbnail,
            kind = kindOf(browse),
            playlistId = findString(t.optJSONObject("thumbnailOverlay"), "playlistId"),
        )
    }

    private fun panel(p: JSONObject): YtSong? {
        val id = p.optString("videoId").ifBlank { return null }
        val runs = runsList(p.o("longBylineText")).ifEmpty { runsList(p.o("shortBylineText")) }
        return YtSong(
            videoId = id,
            title = p.o("title").text(),
            artists = artistsFrom(runs),
            album = albumFrom(runs),
            durationSec = parseDuration(p.o("lengthText").text()) ?: 0,
            thumbnail = thumb(p.optJSONObject("thumbnail")),
        )
    }

    private fun card(c: JSONObject): YtItem? {
        val run = c.o("title")?.optJSONArray("runs")?.optJSONObject(0) ?: return null
        val title = run.optString("text")
        val subRuns = runsList(c.o("subtitle"))
        val thumbnail = thumb(c.optJSONObject("thumbnail"))
        val nav = run.optJSONObject("navigationEndpoint") ?: c.optJSONObject("onTap") ?: return null
        nav.optJSONObject("watchEndpoint")?.let { w ->
            return YtSong(w.optString("videoId"), title, artistsFrom(subRuns), albumFrom(subRuns),
                subRuns.asReversed().firstNotNullOfOrNull { parseDuration(it.optString("text")) } ?: 0, thumbnail)
        }
        val browse = nav.optJSONObject("browseEndpoint") ?: return null
        return YtCollection(browse.optString("browseId"), title, subRuns.joinToString("") { it.optString("text") }, thumbnail, kindOf(browse))
    }

    // ---------------------------------------------------------------- helpers

    private val HEADER_KEYS = setOf(
        "musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer",
        "musicVisualHeaderRenderer", "musicHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
    )

    private fun kindOf(browse: JSONObject): YtKind {
        val type = findString(browse, "pageType") ?: ""
        val id = browse.optString("browseId")
        return when {
            type.contains("ALBUM") || id.startsWith("MPRE") -> YtKind.ALBUM
            type.contains("ARTIST") || type.contains("USER_CHANNEL") || id.startsWith("UC") -> YtKind.ARTIST
            type.contains("PLAYLIST") || id.startsWith("VL") || id.startsWith("RD") -> YtKind.PLAYLIST
            else -> YtKind.OTHER
        }
    }

    private val TYPE_WORDS = setOf("Song", "Video", "Album", "Single", "EP", "Playlist", "Artist", "Episode", "Podcast")

    private fun artistsFrom(runs: List<JSONObject>): List<YtRef> {
        val linked = runs.mapNotNull { r ->
            val b = r.o("navigationEndpoint", "browseEndpoint") ?: return@mapNotNull null
            val id = b.optString("browseId")
            if (id.startsWith("UC") || (findString(b, "pageType") ?: "").contains("ARTIST")) YtRef(r.optString("text"), id) else null
        }
        if (linked.isNotEmpty()) return linked
        // Unlinked artist text: first run that isn't a type label, separator, view count or duration.
        val first = runs.map { it.optString("text").trim() }.firstOrNull {
            it.isNotBlank() && it != "•" && it !in TYPE_WORDS && parseDuration(it) == null && !it.contains(" views") && !it.contains(" plays")
        }
        return listOfNotNull(first?.let { YtRef(it, null) })
    }

    private fun albumFrom(runs: List<JSONObject>): YtRef? = runs.firstNotNullOfOrNull { r ->
        val b = r.o("navigationEndpoint", "browseEndpoint") ?: return@firstNotNullOfOrNull null
        val id = b.optString("browseId")
        if (id.startsWith("MPRE") || (findString(b, "pageType") ?: "").contains("ALBUM")) YtRef(r.optString("text"), id) else null
    }

    fun parseDuration(s: String?): Int? {
        s ?: return null
        val m = Regex("^(\\d+):(\\d{2})(?::(\\d{2}))?$").matchEntire(s.trim()) ?: return null
        val (a, b, c) = m.destructured
        return if (c.isEmpty()) a.toInt() * 60 + b.toInt() else a.toInt() * 3600 + b.toInt() * 60 + c.toInt()
    }

    private fun thumb(o: JSONObject?): String? {
        val arr = findArray(o, "thumbnails") ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(arr.length() - 1)?.optString("url")?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun runsList(o: JSONObject?): List<JSONObject> {
        val arr = o?.optJSONArray("runs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun findFirst(node: Any?, keys: Set<String>): JSONObject? {
        when (node) {
            is JSONObject -> {
                for (k in node.keys()) if (k in keys) return node.optJSONObject(k)
                for (k in node.keys()) findFirst(node.opt(k), keys)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findFirst(node.opt(i), keys)?.let { return it }
        }
        return null
    }

    private fun findString(node: Any?, key: String): String? {
        when (node) {
            is JSONObject -> {
                node.opt(key)?.let { if (it is String && it.isNotBlank()) return it }
                for (k in node.keys()) findString(node.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun findArray(node: Any?, key: String): JSONArray? {
        when (node) {
            is JSONObject -> {
                node.optJSONArray(key)?.let { return it }
                for (k in node.keys()) findArray(node.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findArray(node.opt(i), key)?.let { return it }
        }
        return null
    }
}

internal fun JSONObject.o(vararg keys: String): JSONObject? {
    var cur: JSONObject? = this
    for (k in keys) cur = cur?.optJSONObject(k) ?: return null
    return cur
}

internal fun JSONObject?.text(): String {
    this ?: return ""
    optJSONArray("runs")?.let { runs -> return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }
    return optString("simpleText")
}
