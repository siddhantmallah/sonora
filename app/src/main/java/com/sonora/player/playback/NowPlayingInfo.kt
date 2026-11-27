package com.sonora.player.playback

import android.app.NotificationManager
import android.app.Service
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.sonora.player.data.Song
import com.sonora.player.lyrics.Lyrics
import com.sonora.player.youtube.YtIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The player the media session (and so the system's now-playing card, the Now Bar, the lock screen, cars and
 * watches) sees. It's Sonora's player, except that the line under the title can be swapped for the lyric being sung,
 * what's up next (with that song's cover), the album, or picks for you. Sonora's own screens read the real song info
 * from the media item, so they never see the swap.
 */
@OptIn(UnstableApi::class)
class InfoPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    var overlay: ((MediaMetadata) -> MediaMetadata)? = null
        private set

    /** Sonora's own player underneath (real song info, never the swapped line). */
    val inner: Player get() = player

    fun show(f: ((MediaMetadata) -> MediaMetadata)?) {
        overlay = f
        invalidateState()
    }

    override fun getState(): State {
        val s = super.getState()
        val f = overlay ?: return s
        return s.buildUpon().setPlaylist(player.currentTimeline, player.currentTracks, f(s.currentMetadata)).build()
    }
}

/**
 * The two buttons on the now-playing card: **Lyrics** (the line under the title follows the song) and **More**
 * (cycles Up next → Album → For you → back to the artist). Nothing is shown until you ask for it.
 */
class NowPlayingInfo(
    private val service: Service,
    private val scope: CoroutineScope,
    private val info: InfoPlayer,
    private val onModeChanged: () -> Unit,
) {
    enum class Mode { OFF, LYRICS, UP_NEXT, ALBUM, FOR_YOU }

    private val c get() = (service.applicationContext as com.sonora.player.SonoraApp).container
    var mode = Mode.OFF
        private set
    private var loop: Job? = null
    private var shownKey: String? = null

    private var lyricsFor: String? = null
    private var lyrics: Lyrics? = null
    private var recsFor: String? = null
    private var recs: List<String> = emptyList()

    init {
        // The Live Update pill from 1.4.0 is gone: clear its notification channels.
        val nm = service.getSystemService(NotificationManager::class.java)
        listOf("sonora_playing", "sonora_now_playing", "sonora_live_update").forEach { runCatching { nm.deleteNotificationChannel(it) } }
    }

    fun toggleLyrics() = set(if (mode == Mode.LYRICS) Mode.OFF else Mode.LYRICS)

    fun cycleMore() = set(
        when (mode) {
            Mode.UP_NEXT -> Mode.ALBUM
            Mode.ALBUM -> Mode.FOR_YOU
            Mode.FOR_YOU -> Mode.OFF
            else -> Mode.UP_NEXT
        },
    )

    fun release() { loop?.cancel() }

    private fun set(m: Mode) {
        mode = m
        onModeChanged()
        shownKey = null
        if (m == Mode.OFF) {
            loop?.cancel(); loop = null
            info.show(null)
            return
        }
        if (loop?.isActive != true) loop = scope.launch {
            while (isActive) {
                runCatching { update() }
                delay(if (mode == Mode.LYRICS) 250 else 1000)
            }
        }
    }

    private suspend fun update() {
        val p = info.inner
        val item = p.currentMediaItem ?: return
        val id = item.mediaId
        val meta = item.mediaMetadata
        val artist = meta.artist?.toString().orEmpty()
        var art: android.net.Uri? = null
        val line: String = when (mode) {
            Mode.OFF -> return
            Mode.LYRICS -> {
                if (lyricsFor != id) {
                    lyrics = songFor(id, meta)?.let { runCatching { c.lyrics.load(it) }.getOrNull() }?.takeIf { it.synced }
                    lyricsFor = id
                }
                val l = lyrics
                if (l == null) "No synced lyrics · $artist"
                else l.lines.getOrNull(l.indexAt(p.currentPosition))?.text?.takeIf { it.isNotBlank() } ?: "♪"
            }
            Mode.UP_NEXT -> {
                val n = p.nextMediaItemIndex
                if (n == C.INDEX_UNSET) "Nothing up next"
                else p.getMediaItemAt(n).mediaMetadata.let { m ->
                    art = m.artworkUri
                    "Up next · ${m.title ?: "Unknown"}${m.artist?.let { " — $it" } ?: ""}"
                }
            }
            Mode.ALBUM -> {
                val local = id.toLongOrNull()?.let { c.library.song(it) }
                val name = (local?.album ?: meta.albumTitle?.toString())?.takeIf { it.isNotBlank() }
                if (name == null) "No album info"
                else listOfNotNull("Album · $name", local?.year?.takeIf { it > 0 }?.toString(),
                    local?.let { s -> c.library.songs.value.count { it.albumId == s.albumId }.let { n -> "$n songs" } }).joinToString(" · ")
            }
            Mode.FOR_YOU -> {
                if (recsFor != id) { recs = recommendations(p, id); recsFor = id }
                if (recs.isEmpty()) "No picks for this one yet"
                else {
                    // Rotate through the picks every few seconds.
                    val i = ((System.currentTimeMillis() / 5000) % recs.size).toInt()
                    "For you · ${recs[i]}"
                }
            }
        }
        val key = "$id|$mode|$line|$art"
        if (key == shownKey) return
        shownKey = key
        val pic = art
        info.show { m ->
            m.buildUpon().setArtist(line).setSubtitle(line).apply {
                if (pic != null) { setArtworkData(null, null); setArtworkUri(pic) }
            }.build()
        }
    }

    /** Songs like this one that aren't queued: YouTube's song radio, or your own songs by the same artist / genre. */
    private suspend fun recommendations(p: Player, mediaId: String): List<String> {
        val queued = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }.toSet()
        YtIds.videoId(mediaId)?.let { v ->
            return runCatching { c.youtube.radio(v) }.getOrDefault(emptyList())
                .filter { it.mediaId !in queued }.take(4)
                .map { s -> listOfNotNull(s.title, s.artists.firstOrNull()?.name).joinToString(" — ") }
        }
        val local = mediaId.toLongOrNull()?.let { c.library.song(it) } ?: return emptyList()
        val all = c.library.songs.value.filter { it.id.toString() !in queued }
        val same = all.filter { it.primaryArtist.equals(local.primaryArtist, true) }.shuffled()
        val genre = local.genre?.let { g -> all.filter { it.genre == g && it !in same }.shuffled() }.orEmpty()
        return (same + genre).take(4).map { "${it.title} — ${it.artist}" }
    }

    private fun songFor(mediaId: String, meta: MediaMetadata): Song? {
        mediaId.toLongOrNull()?.let { return c.library.song(it) }
        val id = YtIds.statsId(mediaId) ?: return null
        return Song(
            id = id, title = meta.title?.toString().orEmpty(), artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString().orEmpty(), albumId = -1, artistId = -1, albumArtist = null,
            durationMs = meta.durationMs ?: 0, trackNumber = 0, year = 0, dateAddedSec = 0, path = "", mimeType = "",
            sizeBytes = 0, bitrate = 0, genre = null,
        )
    }
}

