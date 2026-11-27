package com.sonora.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.media3.common.Player
import com.sonora.player.R
import com.sonora.player.data.Song
import com.sonora.player.lyrics.Lyrics
import com.sonora.player.ui.MainActivity
import com.sonora.player.youtube.YtIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sonora in the status bar next to the camera, as an Android 16 Live Update: the chip shows the time left (a
 * countdown Android keeps ticking by itself); pull it open for the song, Up next, the album and the line being sung,
 * with previous / play-pause / next. Refreshed only when something changes (track, play state, a seek, a new lyric
 * line), never every second.
 */
class LiveUpdate(private val service: Service, private val scope: CoroutineScope) {
    private val app = service.applicationContext as com.sonora.player.SonoraApp
    private val c get() = app.container
    private val nm = service.getSystemService(NotificationManager::class.java)
    private var job: Job? = null
    private var lastKey = ""
    private var lyricsFor: String? = null
    private var lyrics: Lyrics? = null
    private var artFor: String? = null
    private var art: Bitmap? = null

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

    fun start(player: () -> Player) {
        if (!supported || job?.isActive == true) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Now playing in the status bar", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The Live Update chip by the camera: time left, up next, album and lyrics."
                setSound(null, null); enableVibration(false); setShowBadge(false)
            },
        )
        job = scope.launch {
            while (isActive) {
                runCatching { tick(player()) }
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        nm.cancel(ID)
        lastKey = ""
    }

    private suspend fun tick(p: Player) {
        val enabled = c.settings.state.value.liveUpdate
        val item = p.currentMediaItem
        if (!enabled || item == null || (!p.isPlaying && p.playbackState == Player.STATE_IDLE)) {
            if (lastKey.isNotEmpty()) { nm.cancel(ID); lastKey = "" }
            return
        }
        val meta = item.mediaMetadata
        val title = meta.title?.toString().orEmpty().ifBlank { "Sonora" }
        val artist = meta.artist?.toString().orEmpty()
        val album = meta.albumTitle?.toString()?.takeIf { it.isNotBlank() }
        val next = p.nextMediaItemIndex.takeIf { it >= 0 && it < p.mediaItemCount }?.let { p.getMediaItemAt(it).mediaMetadata }
        val upNext = next?.let { listOfNotNull(it.title?.toString(), it.artist?.toString()?.takeIf { a -> a.isNotBlank() }).joinToString(" · ") }
        val pos = p.currentPosition.coerceAtLeast(0)
        val dur = p.duration.takeIf { it > 0 } ?: meta.durationMs ?: 0L
        val line = currentLine(item.mediaId, title, artist, album, dur, pos)
        // Re-post only when what you'd see changes; a seek moves the countdown, so its 5-second bucket counts too.
        val endBucket = if (p.isPlaying && dur > 0) (System.currentTimeMillis() + dur - pos) / 5000 else 0
        val key = listOf(item.mediaId, p.isPlaying, line, upNext, album, endBucket).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        val bmp = artwork(item.mediaId, meta.artworkUri)
        nm.notify(ID, build(title, artist, album, upNext, line, bmp, p.isPlaying, pos, dur))
    }

    private fun build(
        title: String, artist: String, album: String?, upNext: String?, line: String?, art: Bitmap?,
        playing: Boolean, pos: Long, dur: Long,
    ): Notification {
        val details = buildList {
            if (artist.isNotBlank()) add(artist)
            line?.let { add("♪ $it") }
            upNext?.let { add("Up next · $it") }
            album?.let { add("Album · $it") }
        }.joinToString("\n")
        val b = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sonora)
            .setContentTitle(title)
            .setContentText(line?.let { "♪ $it" } ?: artist)
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(lyrics = false))
            .addAction(action(com.composables.icons.lucide.R.drawable.lucide_ic_skip_back, "Previous", CMD_PREV))
            .addAction(action(if (playing) com.composables.icons.lucide.R.drawable.lucide_ic_pause else com.composables.icons.lucide.R.drawable.lucide_ic_play, if (playing) "Pause" else "Play", CMD_TOGGLE))
            .addAction(action(com.composables.icons.lucide.R.drawable.lucide_ic_skip_forward, "Next", CMD_NEXT))
        if (art != null) b.setLargeIcon(art)
        if (playing && dur > 0) {
            // The chip counts down the time left on its own.
            b.setWhen(System.currentTimeMillis() + (dur - pos)).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
            if (Build.VERSION.SDK_INT >= 36) b.setShortCriticalText("Paused")
        }
        if (Build.VERSION.SDK_INT >= 36) b.setRequestPromotedOngoing(true)
        return b.build()
    }

    private fun action(icon: Int, label: String, code: Int): Notification.Action {
        val intent = Intent(service, PlaybackService::class.java).setAction(ACTION).putExtra(EXTRA_CMD, code)
        val pi = PendingIntent.getService(service, 700 + code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(service, icon), label, pi).build()
    }

    private fun openApp(lyrics: Boolean): PendingIntent = PendingIntent.getActivity(
        service, if (lyrics) 711 else 710,
        Intent(service, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The lyric line at [pos] (loaded once per track, from Sonora's own lyrics store). */
    private suspend fun currentLine(mediaId: String, title: String, artist: String, album: String?, dur: Long, pos: Long): String? {
        if (lyricsFor != mediaId) {
            lyricsFor = mediaId
            lyrics = null
            val song = songFor(mediaId, title, artist, album, dur)
            if (song != null) lyrics = runCatching { c.lyrics.load(song) }.getOrNull()
        }
        val l = lyrics ?: return null
        if (!l.synced) return null
        val i = l.indexAt(pos)
        return l.lines.getOrNull(i)?.text?.takeIf { it.isNotBlank() }
    }

    private fun songFor(mediaId: String, title: String, artist: String, album: String?, dur: Long): Song? {
        mediaId.toLongOrNull()?.let { return c.library.song(it) }
        val id = YtIds.statsId(mediaId) ?: return null
        return Song(
            id = id, title = title, artist = artist, album = album.orEmpty(), albumId = -1, artistId = -1, albumArtist = null,
            durationMs = dur, trackNumber = 0, year = 0, dateAddedSec = 0, path = "", mimeType = "", sizeBytes = 0,
            bitrate = 0, genre = null,
        )
    }

    private suspend fun artwork(mediaId: String, uri: android.net.Uri?): Bitmap? {
        if (artFor == mediaId) return art
        artFor = mediaId
        art = uri?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = coil3.request.ImageRequest.Builder(service).data(u.toString()).size(256).build()
                    val r = coil3.SingletonImageLoader.get(service).execute(req)
                    (r as? coil3.request.SuccessResult)?.image?.let { img -> (img as? coil3.BitmapImage)?.bitmap }
                }.getOrNull()
            }
        }
        return art
    }

    companion object {
        private const val CHANNEL = "sonora_live_update"
        private const val ID = 4242
        const val ACTION = "com.sonora.player.LIVE_UPDATE_ACTION"
        const val EXTRA_CMD = "cmd"
        const val CMD_PREV = 1
        const val CMD_TOGGLE = 2
        const val CMD_NEXT = 3

        /** Runs a button from the Live Update on [p]; true when [intent] was one. */
        fun handle(intent: Intent?, p: Player): Boolean {
            if (intent?.action != ACTION) return false
            when (intent.getIntExtra(EXTRA_CMD, 0)) {
                CMD_PREV -> p.seekToPrevious()
                CMD_TOGGLE -> if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
                CMD_NEXT -> p.seekToNext()
            }
            return true
        }
    }
}
