package com.sonora.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.sonora.player.data.Song

object Commands {
    const val SLEEP_TIMER = "sonora.SLEEP_TIMER"
    const val TOGGLE_FAVORITE = "sonora.TOGGLE_FAVORITE"
    /** Now-playing card: lyrics under the title, on/off. */
    const val LYRICS = "sonora.LYRICS"
    /** Now-playing card: cycle up next / album / for you / artist. */
    const val MORE = "sonora.MORE"
    const val SET_OUTPUT = "sonora.SET_OUTPUT"
    /** Cast the queue to a DLNA renderer (args: name, location, AVTransport and RenderingControl addresses). */
    const val CAST_DLNA = "sonora.CAST_DLNA"
    /** Stop any casting (Google Cast or DLNA) and continue on the phone. */
    const val STOP_CAST = "sonora.STOP_CAST"
    const val ARG_NAME = "name"
    const val ARG_LOCATION = "location"
    const val ARG_AV = "av"
    const val ARG_RC = "rc"
    const val ARG_DEVICE_ID = "device_id"
    const val ARG_MINUTES = "minutes"
    const val ARG_END_OF_TRACK = "end_of_track"
}

/** Keys of the session extras the service publishes to every controller. */
object Extras {
    const val CODEC = "codec"
    const val SAMPLE_RATE = "sample_rate"
    const val BIT_DEPTH = "bit_depth"
    const val BITRATE = "bitrate"
    const val CHANNELS = "channels"
    const val OUT_ENCODING = "out_encoding"
    const val OUT_SAMPLE_RATE = "out_sample_rate"
    const val OFFLOAD = "offload"
    const val SLEEP_ENDS_AT = "sleep_ends_at"
    const val SLEEP_END_OF_TRACK = "sleep_end_of_track"
    const val FAVORITE = "favorite"
}

fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setArtworkUri(artUri)
                .setTrackNumber(trackNumber % 1000)
                .setReleaseYear(year.takeIf { it > 0 })
                .setGenre(genre)
                .setDurationMs(durationMs)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putString("path", path); putString("mime", mimeType) })
                .build(),
        )
        .build()

fun browsableItem(id: String, title: String, artwork: Uri? = null, mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(artwork)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build(),
        )
        .build()
