package com.sonora.player.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads the device's music from MediaStore and derives albums, artists, genres and folders.
 * Re-scans automatically when MediaStore changes (new downloads, deleted files...).
 */
class LibraryRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _hasPermission = MutableStateFlow(checkPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    @Volatile private var byId: Map<Long, Song> = emptyMap()
    private val mutex = Mutex()
    private var refreshJob: Job? = null

    val albums: StateFlow<List<Album>> = songs.map(::buildAlbums)
        .flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<Artist>> = albums.map { albums ->
        // A song tagged "A/B feat. C" appears under A, B and C.
        val songsByArtist = LinkedHashMap<String, MutableList<Song>>()
        for (s in _songs.value) for (a in s.artists) songsByArtist.getOrPut(a) { ArrayList() } += s
        val albumsByArtist = HashMap<String, MutableList<Album>>()
        for (al in albums) for (a in splitArtists(al.artist)) albumsByArtist.getOrPut(a) { ArrayList() } += al
        songsByArtist.map { (name, songs) ->
            val own = albumsByArtist[name].orEmpty()
            val appearsOn = if (own.isNotEmpty()) own else albums.filter { a -> a.songs.any { name in it.artists } }
            Artist(name, songs.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber })), appearsOn)
        }.sortedBy { it.name.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    val genres: StateFlow<List<Genre>> = songs.map { list ->
        list.filter { !it.genre.isNullOrBlank() }
            .groupBy { it.genre!!.trim() }
            .map { (name, songs) -> Genre(name, songs.sortedBy { it.title.lowercase() }) }
            .sortedBy { it.name.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<Folder>> = songs.map { list ->
        list.groupBy { it.folder }
            .map { (path, songs) -> Folder(path, songs.sortedBy { it.path.lowercase() }) }
            .sortedBy { it.name.lowercase() }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleRefresh()
    }

    init {
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer,
            )
        }
        scope.launch {
            settings.flow.map { it.minDurationSec to it.excludedFolders }.distinctUntilChanged().drop(1).collect { refresh() }
        }
    }

    fun song(id: Long): Song? = byId[id]

    fun checkPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(1500)
            refresh()
        }
    }

    suspend fun ensureLoaded() {
        if (!_loaded.value) refresh()
    }

    suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) {
            _hasPermission.value = checkPermission()
            if (!_hasPermission.value) return@withContext
            val s = settings.current()
            val minMs = s.minDurationSec * 1000L
            val excluded = s.excludedFolders
            val list = runCatching {
                query(minMs).filter { song -> excluded.none { song.path.contains(it, ignoreCase = true) } }
            }.getOrDefault(_songs.value)
            byId = list.associateBy { it.id }
            _songs.value = list
            _loaded.value = true
        }
    }

    private fun query(minDurationMs: Long): List<Song> {
        val cols = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.ARTIST_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.ALBUM_ARTIST)
            if (Build.VERSION.SDK_INT >= 30) {
                add(MediaStore.Audio.Media.BITRATE)
                add(MediaStore.Audio.Media.GENRE)
            }
        }.toTypedArray()

        var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        // Android 12+ flags voice/call recordings; never treat them as music.
        if (Build.VERSION.SDK_INT >= 31) selection += " AND ${MediaStore.Audio.Media.IS_RECORDING} = 0"
        val out = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, selection,
            arrayOf(minDurationMs.toString()), "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iArtistId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iAlbumArtist = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val iBitrate = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.BITRATE) else -1
            val iGenre = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1

            while (c.moveToNext()) {
                val artist = c.getString(iArtist).orEmpty().let { if (it == "<unknown>") UNKNOWN else it }
                val track = c.getInt(iTrack)
                out += Song(
                    id = c.getLong(iId),
                    title = c.getString(iTitle).orEmpty().ifBlank { "Unknown title" },
                    artist = artist.ifBlank { UNKNOWN },
                    album = c.getString(iAlbum).orEmpty().ifBlank { "Unknown album" },
                    albumId = c.getLong(iAlbumId),
                    artistId = c.getLong(iArtistId),
                    albumArtist = if (iAlbumArtist >= 0) c.getString(iAlbumArtist) else null,
                    durationMs = c.getLong(iDur),
                    // MediaStore encodes the disc as thousands (2005 = disc 2, track 5), which sorts correctly as-is.
                    trackNumber = track,
                    year = c.getInt(iYear),
                    dateAddedSec = c.getLong(iAdded),
                    path = c.getString(iData).orEmpty(),
                    mimeType = c.getString(iMime).orEmpty(),
                    sizeBytes = c.getLong(iSize),
                    bitrate = if (iBitrate >= 0) c.getInt(iBitrate) else 0,
                    genre = if (iGenre >= 0) c.getString(iGenre) else null,
                )
            }
        }
        return out
    }

    private fun buildAlbums(songs: List<Song>): List<Album> =
        songs.groupBy { it.albumId }.map { (id, list) ->
            val sorted = list.sortedWith(compareBy({ it.trackNumber }, { it.title.lowercase() }))
            Album(
                id = id,
                title = sorted.first().album,
                artist = sorted.firstNotNullOfOrNull { it.albumArtist?.takeIf(String::isNotBlank) } ?: sorted.first().artist,
                year = sorted.maxOf { it.year },
                songs = sorted,
            )
        }.sortedBy { it.title.lowercase() }

    companion object {
        const val UNKNOWN = "Unknown artist"
    }
}
