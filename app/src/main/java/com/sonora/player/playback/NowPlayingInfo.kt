package com.sonora.player.playback

import android.app.NotificationManager
import android.app.Service
import androidx.annotation.OptIn
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
 * Lyrics on the now-playing pill (notification, lock screen, Now Bar, car and watch): while a song with synced
 * lyrics plays, the line under the title is the line being sung. Between lines and for songs without synced lyrics it
 * shows the artist and the album. Turn it off in Settings > Now playing.
 */
class NowPlayingInfo(
    private val service: Service,
    private val scope: CoroutineScope,
    private val info: InfoPlayer,
) {
    private val c get() = (service.applicationContext as com.sonora.player.SonoraApp).container
    private var loop: Job? = null
    private var shownKey: String? = null
    private var lyricsFor: String? = null
    private var lyrics: Lyrics? = null

    init {
        // The Live Update pill from 1.4.0 is gone: clear its notification channels.
        val nm = service.getSystemService(NotificationManager::class.java)
        listOf("sonora_playing", "sonora_now_playing", "sonora_live_update").forEach { runCatching { nm.deleteNotificationChannel(it) } }
        loop = scope.launch {
            while (isActive) {
                runCatching { update() }
                delay(200)
            }
        }
    }

    fun release() { loop?.cancel() }

    private suspend fun update() {
        val p = info.inner
        val item = p.currentMediaItem
        if (item == null || !c.settings.state.value.cardLyrics) {
            if (shownKey != null) { shownKey = null; info.show(null) }
            return
        }
        val id = item.mediaId
        val meta = item.mediaMetadata
        val artist = meta.artist?.toString().orEmpty()
        val album = meta.albumTitle?.toString()?.takeIf { it.isNotBlank() && it != meta.title?.toString() }
        if (lyricsFor != id) {
            lyricsFor = id
            lyrics = null
            lyrics = songFor(id, meta)?.let { runCatching { c.lyrics.load(it) }.getOrNull() }?.takeIf { it.synced }
        }
        val l = lyrics
        val sung = l?.lines?.getOrNull(l.indexAt(p.currentPosition))?.text?.takeIf { it.isNotBlank() }
        val line = sung ?: listOfNotNull(artist.ifBlank { null }, album).joinToString(" — ")
        val key = "$id|$line"
        if (key == shownKey) return
        shownKey = key
        info.show { m -> m.buildUpon().setArtist(line).setSubtitle(line).build() }
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
