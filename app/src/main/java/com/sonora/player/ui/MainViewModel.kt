package com.sonora.player.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.mapLatest
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonora.player.AppContainer
import com.sonora.player.data.Album
import com.sonora.player.data.AppSettings
import com.sonora.player.data.Artist
import com.sonora.player.data.Song
import com.sonora.player.data.SongSort
import com.sonora.player.data.db.PlaylistSongEntity
import com.sonora.player.data.db.PlaylistSummary
import com.sonora.player.data.db.SongStatsEntity
import kotlinx.coroutines.Dispatchers
import com.sonora.player.youtube.YtIds
import com.sonora.player.youtube.YtPage
import com.sonora.player.youtube.YtSong
import com.sonora.player.youtube.toMediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

@Immutable
data class Mix(val title: String, val subtitle: String, val artUri: Uri?, val songs: List<Song>)

@Immutable
data class HomeData(
    val recentlyPlayed: List<Song> = emptyList(),
    val mostPlayed: List<Song> = emptyList(),
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val topArtists: List<Artist> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    val rediscover: List<Album> = emptyList(),
    val totalSongs: Int = 0,
)

/** Built-in "smart" playlists use negative ids so they share the playlist screen. */
object SmartPlaylists {
    const val FAVORITES = -1L
    const val MOST_PLAYED = -2L
    const val RECENTLY_PLAYED = -3L
    const val RECENTLY_ADDED = -4L
    const val NEVER_PLAYED = -5L

    fun name(id: Long) = when (id) {
        FAVORITES -> "Favorites"
        MOST_PLAYED -> "Most played"
        RECENTLY_PLAYED -> "Recently played"
        RECENTLY_ADDED -> "Recently added"
        NEVER_PLAYED -> "Never played"
        else -> "Playlist"
    }
}

class MainViewModel(val c: AppContainer) : ViewModel() {
    val songs: StateFlow<List<Song>> = c.library.songs
    val albums = c.library.albums
    val artists = c.library.artists
    val genres = c.library.genres
    val folders = c.library.folders
    val loaded = c.library.loaded
    val hasPermission = c.library.hasPermission
    val settings: StateFlow<AppSettings> = c.settings.state
    val stats: StateFlow<Map<Long, SongStatsEntity>> = c.stats.all
    val favorites: StateFlow<Set<Long>> = c.stats.favorites
    val player = c.playerConnection.state
    val output = c.output.state
    val eqInfo = c.effects.info

    val playlists: StateFlow<List<PlaylistSummary>> =
        c.playlists.summaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sortedSongs: StateFlow<List<Song>> = combine(songs, settings.map { it.songSort }) { list, sort ->
        when (sort) {
            SongSort.TITLE -> list.sortedBy { it.title.lowercase() }
            SongSort.ARTIST -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNumber }))
            SongSort.ALBUM -> list.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber }))
            SongSort.DATE_ADDED -> list.sortedByDescending { it.dateAddedSec }
            SongSort.DURATION -> list.sortedByDescending { it.durationMs }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val home: StateFlow<HomeData> = combine(songs, albums, artists, stats) { songs, albums, artists, stats ->
        buildHome(songs, albums, artists, stats)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, HomeData())

    private fun buildHome(songs: List<Song>, albums: List<Album>, artists: List<Artist>, stats: Map<Long, SongStatsEntity>): HomeData {
        val byId = songs.associateBy { it.id }
        val played = stats.values.filter { it.playCount > 0 && it.songId in byId }
        val recentlyPlayed = played.sortedByDescending { it.lastPlayed }.take(20).mapNotNull { byId[it.songId] }
        val mostPlayed = played.sortedByDescending { it.playCount }.take(20).mapNotNull { byId[it.songId] }

        fun artistScore(a: Artist) = a.songs.sumOf { stats[it.id]?.playCount ?: 0 }
        val topArtists = artists.filter { artistScore(it) > 0 }.sortedByDescending(::artistScore).take(12)
            .ifEmpty { artists.sortedByDescending { it.songs.size }.take(12) }

        // "Daily mix"-style: each top artist seeds a mix with their songs plus songs from the same genres.
        val daySeed = Calendar.getInstance().get(Calendar.DAY_OF_YEAR).toLong()
        val mixes = topArtists.take(6).mapIndexed { i, artist ->
            val genres = artist.songs.mapNotNull { it.genre?.lowercase() }.toSet()
            val related = songs.filter { s -> artist.name !in s.artists && s.genre?.lowercase() in genres }
            val pool = (artist.songs.shuffled(kotlin.random.Random(daySeed + i)).take(15) +
                related.shuffled(kotlin.random.Random(daySeed * 31 + i)).take(25))
                .distinctBy { it.id }.shuffled(kotlin.random.Random(daySeed + 7 * i))
            val others = pool.map { it.primaryArtist }.filter { it != artist.name }.distinct().take(2)
            Mix(
                title = "Mix ${i + 1}",
                subtitle = (listOf(artist.name) + others).joinToString(", "),
                artUri = artist.artUri,
                songs = pool,
            )
        }.filter { it.songs.size >= 5 }

        val playedAlbumIds = played.mapNotNull { byId[it.songId]?.albumId }.toSet()
        val rediscover = albums.filter { it.id !in playedAlbumIds }.shuffled(kotlin.random.Random(daySeed)).take(12)

        return HomeData(
            recentlyPlayed = recentlyPlayed,
            mostPlayed = mostPlayed,
            recentlyAddedAlbums = albums.sortedByDescending { it.dateAddedSec }.take(16),
            topArtists = topArtists,
            mixes = mixes,
            rediscover = rediscover,
            totalSongs = songs.size,
        )
    }

    fun smartPlaylist(id: Long): Flow<List<Song>> = combine(songs, stats) { songs, stats ->
        val byId = songs.associateBy { it.id }
        when (id) {
            SmartPlaylists.FAVORITES -> stats.values.filter { it.favorite }.sortedByDescending { it.lastPlayed }.mapNotNull { byId[it.songId] }
            SmartPlaylists.MOST_PLAYED -> stats.values.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(100).mapNotNull { byId[it.songId] }
            SmartPlaylists.RECENTLY_PLAYED -> stats.values.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(100).mapNotNull { byId[it.songId] }
            SmartPlaylists.RECENTLY_ADDED -> songs.sortedByDescending { it.dateAddedSec }.take(100)
            SmartPlaylists.NEVER_PLAYED -> songs.filter { (stats[it.id]?.playCount ?: 0) == 0 }
            else -> emptyList()
        }
    }.flowOn(Dispatchers.Default)

    fun playlistName(id: Long) = c.playlists.playlist(id).map { it?.name }
    fun playlistEntries(id: Long): Flow<List<Pair<PlaylistSongEntity, Song>>> =
        combine(c.playlists.entries(id), songs) { entries, songs ->
            val byId = songs.associateBy { it.id }
            entries.mapNotNull { e -> byId[e.songId]?.let { e to it } }
        }

    // ------------------------------------------------------------ actions

    fun refreshLibrary() = viewModelScope.launch { c.library.refresh() }
    fun onPermissionsResult() {
        refreshLibrary()
        c.output.onPermissionsChanged()
    }

    fun play(list: List<Song>, index: Int = 0) = c.playerConnection.play(list, index)
    fun shuffle(list: List<Song>) = c.playerConnection.play(list, shuffle = true)
    fun shuffleAll() = shuffle(songs.value)
    fun playNext(song: Song) = c.playerConnection.playNext(listOf(song))
    fun playNext(list: List<Song>) = c.playerConnection.playNext(list)
    fun addToQueue(song: Song) = c.playerConnection.addToQueue(listOf(song))
    fun addToQueue(list: List<Song>) = c.playerConnection.addToQueue(list)

    fun toggleFavorite(songId: Long) = viewModelScope.launch { c.stats.toggleFavorite(songId) }

    /**
     * Likes/unlikes any track (local or YouTube) straight in the database, so every heart in the app updates
     * instantly; the service sees the same change and refreshes the notification button.
     */
    fun toggleFavoriteMedia(mediaId: String?) = viewModelScope.launch {
        val id = YtIds.statsId(mediaId) ?: return@launch
        val fav = c.stats.toggleFavorite(id)
        YtIds.videoId(mediaId)?.let { yt.setLiked(it, fav) }
    }

    fun createPlaylist(name: String, songs: List<Long> = emptyList()) = viewModelScope.launch { c.playlists.create(name, songs) }
    fun addToPlaylist(playlistId: Long, songs: List<Long>) = viewModelScope.launch { c.playlists.add(playlistId, songs) }
    fun removeFromPlaylist(entryId: Long) = viewModelScope.launch { c.playlists.remove(entryId) }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { c.playlists.rename(id, name) }
    fun deletePlaylist(id: Long) = viewModelScope.launch { c.playlists.delete(id) }
    fun reorderPlaylist(entries: List<PlaylistSongEntity>) = viewModelScope.launch { c.playlists.reorder(entries) }

    fun song(id: Long?): Song? = id?.let { c.library.song(it) }

    // ------------------------------------------------------------ YouTube Music

    val yt = c.youtube
    val searchYouTube = MutableStateFlow(false)
    val ytSignedIn = yt.auth.signedIn
    val ytAccount = yt.auth.account

    private val _ytHome = MutableStateFlow<Load<YtPage>>(Load.Loading)
    val ytHome: StateFlow<Load<YtPage>> = _ytHome

    fun loadYtHome(force: Boolean = false) {
        if (!force && _ytHome.value is Load.Ready) return
        _ytHome.value = Load.Loading
        viewModelScope.launch {
            _ytHome.value = runCatching { yt.home() }.fold({ Load.Ready(it) }, { Load.Error(it.message ?: "Couldn't reach YouTube Music") })
        }
    }

    init {
        // Personal feed changes when you sign in or out.
        viewModelScope.launch { ytSignedIn.drop(1).collect { loadFeed(force = true) } }
    }

    // ------------------------------------------------------------ personalised home feed

    private val _feed = MutableStateFlow(HomeFeed())
    val feed: StateFlow<HomeFeed> = _feed
    private var feedJob: kotlinx.coroutines.Job? = null
    private var feedLoadedAt = 0L

    /** Builds the home feed: taste profile → mixes, your YouTube playlists and the YouTube Music home shelves. */
    fun loadFeed(force: Boolean = false) {
        if (feedJob?.isActive == true && !force) return
        if (!force && System.currentTimeMillis() - feedLoadedAt < 30 * 60_000L && _feed.value.ytHome != null) return
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            _feed.value = _feed.value.copy(loadingMixes = true, offline = false)
            c.library.ensureLoaded()
            // What you play on YouTube Music anywhere (app, web, other devices) counts toward your taste too.
            if (ytSignedIn.value) {
                runCatching { yt.browse(com.sonora.player.youtube.YtBrowse.HISTORY) }.onSuccess { ytHistory = historyEvents(it) }
            }
            // Connected apps (Spotify, Apple Music, Last.fm…) count too: their listens and their "top tracks".
            val now = System.currentTimeMillis()
            val external = runCatching { c.external.since(now - 365L * 86_400_000) }.getOrDefault(emptyList())
                .sortedByDescending { it.playedAt }.take(20_000)
                .map { e ->
                    com.sonora.player.data.db.PlayEventEntity(
                        mediaId = "ext:${e.title.lowercase()}|${e.artist.lowercase()}", title = e.title, artist = e.artist, album = e.album, artUri = null,
                        durationMs = e.msPlayed, playedAt = e.playedAt, listenedMs = e.msPlayed, skipped = e.msPlayed in 1..29_999,
                    ) to e.source
                }
            val tops = c.connections.topTracks().take(150).map { (t, a) ->
                com.sonora.player.data.db.PlayEventEntity(
                    mediaId = "ext:${t.lowercase()}|${a.lowercase()}", title = t, artist = a, album = null, artUri = null,
                    durationMs = 200_000, playedAt = now - 5L * 86_400_000, listenedMs = 200_000, skipped = false,
                )
            }
            val p = runCatching { c.mixes.profile(ytHistory + external.map { it.first } + tops) }.getOrDefault(com.sonora.player.data.TasteProfile())
            _feed.update { it.copy(profile = p) }
            refreshRecent()
            val jobs = listOf(
                launch {
                    runCatching { yt.home() }
                        .onSuccess { page -> _feed.update { it.copy(ytHome = page) }; _ytHome.value = Load.Ready(page) }
                        .onFailure { _feed.update { it.copy(offline = true) } }
                },
                launch { if (ytSignedIn.value) runCatching { syncYtLikes() } },
                launch {
                    if (ytSignedIn.value) runCatching { yt.browse(com.sonora.player.youtube.YtBrowse.LIBRARY_PLAYLISTS) }.onSuccess { pg ->
                        val lists = pg.shelves.flatMap { it.items }.filterIsInstance<com.sonora.player.youtube.YtCollection>()
                            .filter { it.browseId != "VLLM" && it.browseId != com.sonora.player.youtube.YtBrowse.LIKED_SONGS }
                        _feed.update { it.copy(ytPlaylists = lists) }
                    }
                },
                launch { if (!p.isEmpty) c.mixes.dailyMixes(p).let { m -> _feed.update { it.copy(dailyMixes = m) } } },
                launch { if (!p.isEmpty) runCatching { c.mixes.becauseYouListened(p) }.getOrNull().let { b -> _feed.update { it.copy(because = b) } } },
                launch { if (!p.isEmpty) runCatching { c.mixes.discover(p) }.getOrNull().let { d -> _feed.update { it.copy(discover = d) } } },
                launch { if (!p.isEmpty) runCatching { c.mixes.tasteShelves(p) }.getOrDefault(emptyList()).let { t -> _feed.update { it.copy(tasteShelves = t) } } },
                launch {
                    // With two or more sources, recommend from the artists you love everywhere.
                    val own = runCatching { c.history.since(now - 180L * 86_400_000) }.getOrDefault(emptyList())
                    val bySource = buildMap<String, List<com.sonora.player.data.db.PlayEventEntity>> {
                        if (own.isNotEmpty()) put("Sonora", own)
                        if (ytHistory.isNotEmpty()) put("YouTube Music", ytHistory)
                        external.groupBy({ it.second }, { it.first }).forEach { (k, v) -> put(k, v) }
                    }
                    val mix = runCatching { c.mixes.crossApp(bySource, p) }.getOrNull()
                    _feed.update { it.copy(crossApp = mix) }
                },
            )
            jobs.forEach { it.join() }
            _feed.update { it.copy(loadingMixes = false) }
            feedLoadedAt = System.currentTimeMillis()
        }
    }

    // ---- Library analytics (YouTube-Studio style), recomputed live
    val insightsDays = MutableStateFlow<Int?>(28)
    val sourceSummary: StateFlow<List<com.sonora.player.data.db.SourceSummary>> =
        c.external.observeSummary().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val insights: StateFlow<com.sonora.player.data.Insights?> = combine(
        c.history.observeCount(),
        sourceSummary,
        insightsDays,
        kotlinx.coroutines.flow.flow { while (true) { emit(Unit); kotlinx.coroutines.delay(30_000) } },
    ) { _, _, days, _ -> days }
        .mapLatest { days ->
            val own = c.history.since(0)
            val ext = c.external.since(0)
            com.sonora.player.data.InsightsEngine.compute(com.sonora.player.data.InsightsEngine.listens(own, ext), days)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- Home banner: weather / air quality and trending charts
    private val _weather = MutableStateFlow<com.sonora.player.data.Weather?>(null)
    val weather: StateFlow<com.sonora.player.data.Weather?> = _weather
    private val _trending = MutableStateFlow<Map<String, List<YtSong>>>(emptyMap())
    val trending: StateFlow<Map<String, List<YtSong>>> = _trending

    fun refreshWeather(force: Boolean = false) = viewModelScope.launch {
        c.ambient.weather(force)?.let { _weather.value = it }
    }

    /** Pulls new listens from signed-in services (Spotify, Last.fm) in the background. */
    fun syncConnections() = viewModelScope.launch(Dispatchers.IO) { runCatching { c.connections.syncAll() } }

    fun finishSpotify(uri: android.net.Uri) = viewModelScope.launch {
        if (c.connections.finishSpotify(uri)) loadFeed(force = true)
    }

    // ---- Search → Moods & genres: live covers, artists and a playlist per mood from YouTube Music
    private val _moodCards = MutableStateFlow<Map<String, MoodCard>>(emptyMap())
    val moodCards: StateFlow<Map<String, MoodCard>> = _moodCards
    private var moodsLoadedAt = 0L

    /** Fills [moodCards] for (name, query, personal) specs; personal moods follow your top language. */
    fun loadMoods(specs: List<Triple<String, String, Boolean>>) {
        if (System.currentTimeMillis() - moodsLoadedAt < 3 * 3_600_000L && _moodCards.value.size >= specs.size) return
        moodsLoadedAt = System.currentTimeMillis()
        viewModelScope.launch {
            // Personal moods take turns across your top two languages, so the shelf stays varied.
            // Romanised titles (e.g. Hindi written in English letters) hide a language, so the phone's region adds one.
            val regional = when (java.util.Locale.getDefault().country) {
                "IN" -> "Hindi"; "PK" -> "Urdu"; "BD" -> "Bengali"; "KR" -> "K-pop"; "JP" -> "J-pop"
                "BR", "PT" -> "Portuguese"; "ES", "MX", "AR", "CO", "CL", "PE" -> "Latin"; else -> null
            }
            val langs = (_feed.value.profile.languages.map { it.name } + listOfNotNull(regional)).distinct().take(2)
            var personalIndex = 0
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            specs.map { (name, query, personal) ->
                val lang = if (personal && langs.isNotEmpty()) langs[personalIndex++ % langs.size] else null
                launch {
                    gate.acquire()
                    try {
                        val q = if (lang != null) "$query $lang" else query
                        val songs = runCatching { yt.search("$q songs", com.sonora.player.youtube.YtSearchFilter.SONGS).allSongs }.getOrDefault(emptyList())
                        val list = runCatching { yt.search("$q playlist", com.sonora.player.youtube.YtSearchFilter.PLAYLISTS) }.getOrNull()
                            ?.shelves?.flatMap { it.items }?.filterIsInstance<com.sonora.player.youtube.YtCollection>()?.firstOrNull()
                        val artists = songs.flatMap { s -> s.artists.map { it.name } }
                            .filter { it.isNotBlank() && !it.equals("YouTube Music", true) }.distinct().take(3)
                        val covers = songs.mapNotNull { com.sonora.player.youtube.hiResThumb(it.thumbnail, 226) }.distinct().take(3)
                        if (covers.isNotEmpty() || list != null) {
                            _moodCards.update {
                                it + (name to MoodCard(covers.ifEmpty { listOfNotNull(list?.thumbnail) }, artists, list?.browseId, list?.title, songs.take(30)))
                            }
                        }
                    } finally { gate.release() }
                }
            }
        }
    }

    // ---- Sky Mix: music for the weather, time of day and your taste
    fun playSkyMix(label: String, query: String) = viewModelScope.launch {
        val langs = (_feed.value.profile.languages.map { it.name } +
            listOfNotNull(if (java.util.Locale.getDefault().country == "IN") "Hindi" else null)).distinct().take(2)
        val queries = if (langs.isEmpty()) listOf(query) else langs.map { "$query $it" } + query
        val pools = kotlinx.coroutines.coroutineScope {
            queries.map { q ->
                async { runCatching { yt.search(q, com.sonora.player.youtube.YtSearchFilter.SONGS).allSongs }.getOrDefault(emptyList()) }
            }.map { it.await() }
        }
        // Interleave the languages so the mix feels like you, then add a few of your own favourites' radio.
        val mixed = ArrayList<YtSong>()
        for (i in 0 until 20) for (p in pools) p.getOrNull(i)?.let { mixed += it }
        val songs = mixed.distinctBy { it.videoId }.filter { !it.isVideo || it.durationSec in 60..600 }.take(40)
        if (songs.isNotEmpty()) {
            playYt(songs)
            android.widget.Toast.makeText(c.app, "Sky Mix · $label", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Fit my trip: a mix whose length matches your journey
    fun fitTrip(minutes: Int) = viewModelScope.launch(Dispatchers.Default) {
        val target = minutes * 60_000L
        val f = _feed.value
        val pool = (listOfNotNull(f.crossApp) + f.dailyMixes + listOfNotNull(f.discover, f.because)).flatMap { it.tracks }
            .plus(likedTracks.value).distinctBy { it.key }
            .filter { it.durationMs in 90_000..480_000 }
            .shuffled()
        if (pool.isEmpty()) return@launch
        val favKeys = likedTracks.value.map { it.key }.toSet()
        val picked = ArrayList<com.sonora.player.data.Track>()
        var total = 0L
        // Fill greedily, leaving room for a closing favourite.
        for (t in pool) {
            if (total + t.durationMs > target - 150_000) continue
            picked += t; total += t.durationMs
            if (total > target - 300_000) break
        }
        val remaining = target - total
        val closer = pool.filter { it !in picked }.minByOrNull { kotlin.math.abs(it.durationMs - remaining) + if (it.key in favKeys) 0 else 30_000 }
        if (closer != null) { picked += closer; total += closer.durationMs }
        val diff = (total - target) / 1000
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            playTracks(picked)
            android.widget.Toast.makeText(
                c.app,
                "${picked.size} songs · ${total / 60_000} min" + (if (kotlin.math.abs(diff) <= 90) " · ends right as you arrive" else ""),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    // ---- On this day: what you played a year ago (Sonora history + imported apps)
    private val _onThisDay = MutableStateFlow<List<com.sonora.player.data.Track>>(emptyList())
    val onThisDay: StateFlow<List<com.sonora.player.data.Track>> = _onThisDay

    fun loadOnThisDay() = viewModelScope.launch(Dispatchers.Default) {
        val center = System.currentTimeMillis() - 365L * 86_400_000
        val from = center - 18 * 3_600_000L
        val to = center + 18 * 3_600_000L
        val own = runCatching { c.history.since(from) }.getOrDefault(emptyList()).filter { it.playedAt <= to && !it.skipped }
        val tracks = own.distinctBy { it.mediaId }.mapNotNull { e ->
            e.mediaId.toLongOrNull()?.let { id -> return@mapNotNull c.library.song(id)?.let { com.sonora.player.data.Track.Local(it) } }
            YtIds.videoId(e.mediaId)?.let { vid -> yt.song(vid)?.let { com.sonora.player.data.Track.Online(it) } }
        }.take(15)
        _onThisDay.value = tracks
    }

    // ---- Song Story: your personal history with a song, across Sonora and imported apps
    suspend fun songStory(mediaId: String?, title: String, artist: String): SongStory = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val own = c.history.since(0).filter { (mediaId != null && it.mediaId == mediaId) }
        val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        val nt = norm(com.sonora.player.lyrics.LyricsRepository.cleanTitle(title))
        val na = norm(artist.substringBefore(","))
        val ext = c.external.since(0).filter { norm(com.sonora.player.lyrics.LyricsRepository.cleanTitle(it.title)) == nt && (na.isBlank() || norm(it.artist).contains(na) || na.contains(norm(it.artist))) }
        data class P(val at: Long, val ms: Long, val src: String, val skipped: Boolean)
        val plays = own.map { P(it.playedAt, it.listenedMs, if (it.mediaId.startsWith("yt:")) "Sonora (YouTube Music)" else "Sonora", it.skipped) } +
            ext.map { P(it.playedAt, it.msPlayed, it.source, false) }
        val heard = plays.filter { !it.skipped }
        val cal = java.util.Calendar.getInstance()
        val byPart = heard.groupBy { p ->
            cal.timeInMillis = p.at
            when (cal.get(java.util.Calendar.HOUR_OF_DAY)) { in 5..11 -> "in the morning"; in 12..16 -> "in the afternoon"; in 17..21 -> "in the evening"; else -> "late at night" }
        }
        // Rank among everything you've played (Sonora only, for a fair comparison).
        val all = c.history.since(0).filter { !it.skipped }.groupingBy { it.mediaId }.eachCount()
        val mine = all[mediaId] ?: 0
        val rank = if (mine == 0) null else all.values.count { it > mine } + 1
        SongStory(
            plays = heard.size,
            minutes = (plays.sumOf { it.ms } / 60_000).toInt(),
            first = plays.minByOrNull { it.at }?.let { it.at to it.src },
            last = plays.maxByOrNull { it.at }?.at,
            sources = plays.groupingBy { it.src }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value },
            favouriteTime = byPart.maxByOrNull { it.value.size }?.key,
            skips = plays.count { it.skipped },
            rank = rank,
            ofSongs = all.size,
        )
    }

    /** Driving mode voice search: plays the best matching songs for what was said. */
    fun playVoiceQuery(q: String) = viewModelScope.launch {
        val songs = runCatching { yt.search(q, com.sonora.player.youtube.YtSearchFilter.SONGS).allSongs }.getOrDefault(emptyList())
        if (songs.isNotEmpty()) playYt(songs.take(25))
    }

    fun loadTrending() = viewModelScope.launch {
        for (code in listOf("IN", "ZZ")) {
            if (_trending.value[code].isNullOrEmpty()) {
                runCatching { yt.topSongs(code) }.onSuccess { list -> if (list.isNotEmpty()) _trending.update { it + (code to list) } }
            }
        }
    }

    /** Your YouTube Music account history, as listen events (approximate times from the Today / Yesterday shelves). */
    private var ytHistory: List<com.sonora.player.data.db.PlayEventEntity> = emptyList()

    private fun historyEvents(page: YtPage): List<com.sonora.player.data.db.PlayEventEntity> {
        val now = System.currentTimeMillis()
        var order = 0
        return page.shelves.flatMap { shelf ->
            val daysAgo = when (shelf.title.trim().lowercase()) {
                "today" -> 0
                "yesterday" -> 1
                "this week" -> 3
                "last week" -> 9
                else -> 20
            }
            shelf.songs.map { s ->
                com.sonora.player.data.db.PlayEventEntity(
                    mediaId = s.mediaId, title = s.title, artist = s.artistNames, album = s.album?.name, artUri = s.thumbnail,
                    durationMs = s.durationSec * 1000L, playedAt = now - daysAgo * 86_400_000L - (order++) * 120_000L,
                    listenedMs = maxOf(s.durationSec * 1000L, 120_000L), skipped = false,
                )
            }
        }
    }

    /** "Recently played": Sonora's own history merged with your YouTube Music history, newest first. */
    /** A playable track for something in your listening history (phone song or YouTube Music). */
    fun trackOf(e: com.sonora.player.data.db.PlayEventEntity): com.sonora.player.data.Track? {
        e.mediaId.toLongOrNull()?.let { id -> return c.library.song(id)?.let { com.sonora.player.data.Track.Local(it) } }
        val vid = YtIds.videoId(e.mediaId) ?: return null
        val song = yt.song(vid) ?: YtSong(
            videoId = vid, title = e.title, artists = listOf(com.sonora.player.youtube.YtRef(e.artist, null)), album = null,
            durationSec = (e.durationMs / 1000).toInt(), thumbnail = e.artUri,
        )
        return com.sonora.player.data.Track.Online(song)
    }

    fun refreshRecent() = viewModelScope.launch(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val own = runCatching { c.history.since(now - 30L * 86_400_000) }.getOrDefault(emptyList()).filter { !it.skipped }
        val merged = (own + ytHistory).sortedByDescending { it.playedAt }.distinctBy { it.mediaId }.take(24)
        val tracks = merged.mapNotNull(::trackOf)
        _feed.update { it.copy(recent = tracks) }
    }

    init {
        // Keep Home alive: refresh "Recently played" after each listen, and rebuild mixes every few listens.
        viewModelScope.launch {
            var base = -1
            c.history.observeCount().collect { count ->
                if (base < 0) { base = count; return@collect }
                refreshRecent()
                if (count - base >= 3) { base = count; loadFeed(force = true) }
            }
        }
    }

    fun playTracks(tracks: List<com.sonora.player.data.Track>, index: Int = 0, shuffle: Boolean = false) {
        yt.remember(tracks.filterIsInstance<com.sonora.player.data.Track.Online>().map { it.song })
        c.playerConnection.playItems(tracks.map { it.mediaItem() }, index, shuffle)
    }

    // ------------------------------------------------------------ Smart Shuffle, downloads, Replay

    /**
     * Spotify-style Smart Shuffle: shuffles the list and slips a fitting recommendation in after every few songs
     * (radio from the list's own songs), so a familiar playlist keeps surprising you.
     */
    fun smartShuffle(tracks: List<com.sonora.player.data.Track>) = viewModelScope.launch {
        if (tracks.isEmpty()) return@launch
        val base = tracks.shuffled()
        playTracks(base, 0)
        val keys = base.map { it.key }.toSet()
        val seeds = base.filterIsInstance<com.sonora.player.data.Track.Online>().take(3).map { it.song.videoId }
            .ifEmpty {
                base.take(2).mapNotNull { t ->
                    runCatching { yt.search("${t.title} ${t.artist}", com.sonora.player.youtube.YtSearchFilter.SONGS).allSongs.firstOrNull()?.videoId }.getOrNull()
                }
            }
        val recs = seeds.flatMap { id -> runCatching { yt.radio(id) }.getOrDefault(emptyList()).drop(1).take(8) }
            .distinctBy { it.videoId }.filter { it.mediaId !in keys }.shuffled().take(base.size / 3 + 1)
        // Insert from the back so earlier positions stay valid: one recommendation after every 3 songs.
        recs.mapIndexed { k, s -> (k + 1) * 3 to s }.filter { it.first <= base.size }.asReversed().forEach { (pos, s) ->
            c.playerConnection.insertItems(pos, listOf(s.toMediaItem()))
        }
    }

    val downloads = c.downloads
    val downloadStates = c.downloads.states

    val downloadedTracks: StateFlow<List<com.sonora.player.data.Track>> = combine(c.downloads.states, yt.metaLoaded) { st, _ -> st }.map { st ->
        yt.knownSongs().filter { st[it.videoId] == com.sonora.player.playback.DlState.DONE }.map { com.sonora.player.data.Track.Online(it) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun replay(days: Int?): ReplayStats = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val since = if (days == null) 0L else now - days * 86_400_000L
        val events = c.history.since(since)
        val listens = events.filter { !it.skipped }
        val minutes = events.sumOf { it.listenedMs } / 60_000
        val topSongs = listens.groupBy { it.mediaId }.map { (_, e) -> e.first() to e.size }.sortedByDescending { it.second }.take(10)
        val artistMs = HashMap<String, Long>()
        val artistArt = HashMap<String, String?>()
        for (e in listens) for (a in com.sonora.player.data.splitArtists(e.artist)) {
            artistMs[a] = (artistMs[a] ?: 0L) + e.listenedMs
            artistArt.putIfAbsent(a, e.artUri)
        }
        val byHour = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        val dayKeys = HashSet<Int>()
        for (e in events) {
            cal.timeInMillis = e.playedAt
            byHour[cal.get(java.util.Calendar.HOUR_OF_DAY)] += (e.listenedMs / 60_000).toInt()
            dayKeys += cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        }
        val profile = com.sonora.player.data.TasteAnalyzer.analyze(events, now)
        ReplayStats(
            minutes = minutes,
            plays = listens.size,
            uniqueSongs = listens.map { it.mediaId }.distinct().size,
            topSongs = topSongs,
            topArtists = artistMs.entries.sortedByDescending { it.value }.take(10).map { Triple(it.key, (it.value / 60_000).toInt(), artistArt[it.key]) },
            byHour = byHour.toList(),
            topVibe = profile.vibes.firstOrNull()?.name,
            topLanguage = profile.languages.firstOrNull()?.name,
            activeDays = dayKeys.size,
            skipRate = if (events.isEmpty()) 0 else events.count { it.skipped } * 100 / events.size,
        )
    }

    /** Liked songs from both sources: phone favourites and liked YouTube tracks. */
    val likedTracks: StateFlow<List<com.sonora.player.data.Track>> = combine(songs, favorites, yt.liked, yt.metaLoaded) { songs, favs, ytLiked, _ ->
        val local = songs.filter { it.id in favs }.map { com.sonora.player.data.Track.Local(it) }
        // YouTube Music's own order (newest like first), then songs hearted in Sonora that YouTube doesn't list yet.
        val ordered = ytLiked.filter { YtIds.statsId(it.mediaId) in favs }
        val seen = ordered.map { it.videoId }.toHashSet()
        val extra = yt.knownSongs().filter { it.videoId !in seen && YtIds.statsId(it.mediaId) in favs }
        (ordered + extra).map { com.sonora.player.data.Track.Online(it) } + local
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playYt(songs: List<YtSong>, index: Int = 0, shuffle: Boolean = false) {
        yt.remember(songs)
        c.playerConnection.playItems(songs.map { it.toMediaItem() }, index, shuffle)
    }

    fun playNextYt(song: YtSong) { yt.remember(listOf(song)); c.playerConnection.playNextItems(listOf(song.toMediaItem())) }
    fun addToQueueYt(song: YtSong) { yt.remember(listOf(song)); c.playerConnection.addItems(listOf(song.toMediaItem())) }

    /** Plays the song now and fills the queue with YouTube Music's radio for it. */
    fun startRadio(song: YtSong) = viewModelScope.launch {
        playYt(listOf(song))
        val radio = runCatching { yt.radio(song.videoId) }.getOrDefault(emptyList()).filter { it.videoId != song.videoId }
        c.playerConnection.addItems(radio.map { it.toMediaItem() })
    }

    /**
     * Mirrors the account's liked songs into Sonora's hearts: new likes are added, and songs unliked on YouTube
     * since the last sync are un-hearted. Songs hearted only in Sonora are left alone.
     */
    private suspend fun syncYtLikes() {
        val before = yt.liked.value.map { it.videoId }.toSet()
        val now = yt.syncLiked() ?: return
        val nowIds = now.map { it.videoId }.toSet()
        fun ids(v: Collection<String>) = v.mapNotNull { YtIds.statsId(YtIds.mediaId(it)) }
        c.stats.setFavorites(ids(nowIds), true)
        c.stats.setFavorites(ids(before - nowIds), false)
    }

    fun isYtFavorite(song: YtSong) = YtIds.statsId(song.mediaId) in favorites.value

    fun toggleYtFavorite(song: YtSong) = toggleFavoriteMedia(song.mediaId)

    /** A Song stand-in for the YouTube track that's playing, so lyrics and stats work the same way. */
    fun nowPlayingAsSong(): Song? {
        val s = c.playerConnection.state.value
        song(s.songId)?.let { return it }
        val id = YtIds.statsId(s.mediaId) ?: return null
        return Song(
            id = id, title = s.title, artist = s.artist, album = s.album, albumId = -1, artistId = -1, albumArtist = null,
            durationMs = s.durationMs, trackNumber = 0, year = 0, dateAddedSec = 0, path = "", mimeType = "", sizeBytes = 0,
            bitrate = 0, genre = null,
        )
    }
}

@Immutable
data class HomeFeed(
    val profile: com.sonora.player.data.TasteProfile = com.sonora.player.data.TasteProfile(),
    val dailyMixes: List<com.sonora.player.data.SmartMix> = emptyList(),
    val because: com.sonora.player.data.SmartMix? = null,
    val discover: com.sonora.player.data.SmartMix? = null,
    val tasteShelves: List<com.sonora.player.data.TasteShelf> = emptyList(),
    val ytPlaylists: List<com.sonora.player.youtube.YtCollection> = emptyList(),
    val ytHome: YtPage? = null,
    val recent: List<com.sonora.player.data.Track> = emptyList(),
    val crossApp: com.sonora.player.data.SmartMix? = null,
    val loadingMixes: Boolean = true,
    val offline: Boolean = false,
)

@Immutable
data class ReplayStats(
    val minutes: Long,
    val plays: Int,
    val uniqueSongs: Int,
    val topSongs: List<Pair<com.sonora.player.data.db.PlayEventEntity, Int>>,
    /** name, minutes, artwork */
    val topArtists: List<Triple<String, Int, String?>>,
    val byHour: List<Int>,
    val topVibe: String?,
    val topLanguage: String?,
    val activeDays: Int,
    val skipRate: Int,
)

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}

/** A Moods & genres card filled live from YouTube Music. */
@Immutable
data class MoodCard(
    val covers: List<String>,
    val artists: List<String>,
    val playlistId: String?,
    val playlistTitle: String?,
    val songs: List<YtSong>,
)

/** Your history with one song. */
@Immutable
data class SongStory(
    val plays: Int,
    val minutes: Int,
    val first: Pair<Long, String>?,
    val last: Long?,
    val sources: List<Pair<String, Int>>,
    val favouriteTime: String?,
    val skips: Int,
    val rank: Int?,
    val ofSongs: Int,
)
