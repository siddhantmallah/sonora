package com.sonora.player.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable

private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val albumArtist: String?,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    val dateAddedSec: Long,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val bitrate: Int,
    val genre: String?,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artUri: Uri get() = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)
    val folder: String get() = path.substringBeforeLast('/', "")
    val extension: String get() = path.substringAfterLast('.', "").uppercase()
    val displayArtist: String get() = albumArtist?.takeIf { it.isNotBlank() } ?: artist
    /** Individual artists from tags like "A/B", "A; B", "A feat. B". */
    val artists: List<String> get() = splitArtists(artist)
    val primaryArtist: String get() = artists.first()
}

private val KEEP_TOGETHER = setOf(
    "ac/dc", "tyler, the creator", "earth, wind & fire", "crosby, stills, nash & young", "emerson, lake & palmer",
    "blood, sweat & tears", "peter, paul and mary", "black/white",
)
private val ARTIST_SEPARATORS = Regex("""\s*(?:/|;|,|\bfeat\.?\s|\bft\.?\s|\bfeaturing\s|\(feat\.?\s|\(ft\.?\s)\s*""", RegexOption.IGNORE_CASE)

fun splitArtists(raw: String): List<String> {
    if (raw.lowercase() in KEEP_TOGETHER) return listOf(raw)
    return raw.split(ARTIST_SEPARATORS).map { it.trim().trimEnd(')') }.filter { it.isNotBlank() }.distinct().ifEmpty { listOf(raw) }
}

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) {
    val artUri: Uri get() = ContentUris.withAppendedId(ALBUM_ART_BASE, id)
    val durationMs: Long get() = songs.sumOf { it.durationMs }
    val dateAddedSec: Long get() = songs.maxOfOrNull { it.dateAddedSec } ?: 0
}

@Immutable
data class Artist(val name: String, val songs: List<Song>, val albums: List<Album>) {
    val artUri: Uri? get() = albums.firstOrNull()?.artUri
}

@Immutable
data class Genre(val name: String, val songs: List<Song>)

@Immutable
data class Folder(val path: String, val songs: List<Song>) {
    val name: String get() = path.substringAfterLast('/').ifBlank { path }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatLongDuration(ms: Long): String {
    val minutes = ms / 60000
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}
