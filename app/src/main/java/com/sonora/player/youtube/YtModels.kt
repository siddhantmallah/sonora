package com.sonora.player.youtube

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

@Immutable
data class YtRef(val name: String, val browseId: String?)

@Immutable
sealed interface YtItem {
    val key: String
    val title: String
    val subtitle: String
    val thumbnail: String?
}

@Immutable
data class YtSong(
    val videoId: String,
    override val title: String,
    val artists: List<YtRef>,
    val album: YtRef?,
    val durationSec: Int,
    override val thumbnail: String?,
    val isVideo: Boolean = false,
) : YtItem {
    override val key: String get() = "s:$videoId"
    val artistNames: String get() = artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" }
    override val subtitle: String get() = listOfNotNull(artistNames, album?.name).joinToString(" • ")
    val mediaId: String get() = YtIds.mediaId(videoId)
}

enum class YtKind { ALBUM, PLAYLIST, ARTIST, OTHER }

@Immutable
data class YtCollection(
    val browseId: String,
    override val title: String,
    override val subtitle: String,
    override val thumbnail: String?,
    val kind: YtKind,
    val playlistId: String? = null,
) : YtItem {
    override val key: String get() = "c:$browseId"
}

@Immutable
data class YtShelf(val title: String, val items: List<YtItem>, val moreBrowseId: String? = null, val strapline: String? = null) {
    val songs: List<YtSong> get() = items.filterIsInstance<YtSong>()
    val isSongList: Boolean get() = items.isNotEmpty() && songs.size * 2 >= items.size
}

@Immutable
data class YtPage(
    val title: String? = null,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val description: String? = null,
    val shelves: List<YtShelf> = emptyList(),
    val continuation: String? = null,
    val playlistId: String? = null,
) {
    val allSongs: List<YtSong> get() = shelves.flatMap { it.songs }.distinctBy { it.videoId }
}

@Immutable
data class YtAccount(val name: String, val handle: String?, val photo: String?)

object YtIds {
    private const val PREFIX = "yt:"
    fun mediaId(videoId: String) = PREFIX + videoId
    fun videoId(mediaId: String?): String? = mediaId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
    fun isYouTube(mediaId: String?) = mediaId?.startsWith(PREFIX) == true

    /** Stable negative id so YouTube tracks can share the stats/favorites/lyrics tables with local songs. */
    fun statsId(mediaId: String?): Long? {
        mediaId ?: return null
        mediaId.toLongOrNull()?.let { return it }
        val v = videoId(mediaId) ?: return null
        var h = 1125899906842597L
        for (ch in v) h = 31 * h + ch.code
        return -((h and Long.MAX_VALUE) % 1_000_000_000_000L) - 1
    }
}

/** Upgrade googleusercontent / ytimg thumbnail URLs to a sharp size. */
fun hiResThumb(url: String?, size: Int = 544): String? {
    url ?: return null
    return when {
        url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
            url.replace(Regex("=w\\d+-h\\d+[^&]*$"), "=w$size-h$size-l90-rj").replace(Regex("=s\\d+[^&]*$"), "=s$size")
        url.contains("i.ytimg.com") -> url.replace(Regex("/(default|mqdefault|hqdefault|sddefault)\\.jpg"), "/hqdefault.jpg")
        else -> url
    }
}

fun YtSong.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(Uri.parse("ytm:$videoId"))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistNames)
                .setAlbumTitle(album?.name)
                .setArtworkUri(hiResThumb(thumbnail)?.let(Uri::parse))
                .setDurationMs(durationSec * 1000L)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putString("source", "youtube") })
                .build(),
        )
        .build()
