package com.sonora.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sonora.player.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class QueueEntry(val index: Int, val mediaId: String, val title: String, val artist: String, val artUri: Uri?)

@Immutable
data class AudioInfo(
    val codec: String? = null,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val bitrate: Int = 0,
    val outEncoding: String? = null,
    val outSampleRate: Int = 0,
    val offload: Boolean = false,
) {
    val isLossless: Boolean get() = codec in setOf("FLAC", "ALAC", "WAV", "PCM", "AIFF", "APE", "WV")
    val isHiRes: Boolean get() = isLossless && (bitDepth >= 24 || sampleRate > 48_000)

    /** e.g. "FLAC · 24-bit / 96 kHz" */
    val summary: String? get() {
        val parts = mutableListOf<String>()
        codec?.let(parts::add)
        when {
            bitDepth > 0 && sampleRate > 0 -> parts += "$bitDepth-bit / ${khz(sampleRate)}"
            sampleRate > 0 -> parts += khz(sampleRate)
        }
        if (!isLossless && bitrate >= 1000) parts += "${(bitrate + 500) / 1000} kbps"
        return parts.joinToString(" · ").ifBlank { null }
    }

    companion object {
        fun khz(hz: Int): String = if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)
    }
}

@Immutable
data class PlayerState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val audio: AudioInfo = AudioInfo(),
    val sleepEndsAt: Long = 0,
    val sleepEndOfTrack: Boolean = false,
    val favorite: Boolean = false,
) {
    val hasMedia: Boolean get() = mediaId != null
    val songId: Long? get() = mediaId?.toLongOrNull()
    val youtubeId: String? get() = com.sonora.player.youtube.YtIds.videoId(mediaId)
}

/** UI-side handle on the playback service, exposing its state as a StateFlow. */
class PlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val queueChanged = events.containsAny(
                Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            )
            sync(player, rebuildQueue = queueChanged)
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var wanted = false
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) = applyExtras(extras)

        // The service can be stopped/recreated by the system; reconnect so the UI never shows stale state.
        override fun onDisconnected(controller: MediaController) {
            if (this@PlayerConnection.controller !== controller) return
            this@PlayerConnection.controller = null
            future = null
            _state.update { it.copy(connected = false) }
            if (wanted) main.postDelayed({ connect() }, 300)
        }
    }

    fun connect() {
        wanted = true
        val existing = controller
        if (existing != null && !existing.isConnected) { controller = null; future = null }
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).setListener(controllerListener).buildAsync()
        future = f
        f.addListener({
            val mc = runCatching { f.get() }.getOrNull() ?: run {
                future = null
                if (wanted) main.postDelayed({ connect() }, 1000)
                return@addListener
            }
            controller = mc
            mc.addListener(listener)
            sync(mc, rebuildQueue = true)
            applyExtras(mc.sessionExtras)
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(mc) }
        }, ContextCompat.getMainExecutor(context))
    }

    private val clients = HashSet<Any>()

    /** A screen that needs the player. The connection stays up while at least one client is attached. */
    fun attach(client: Any) {
        clients += client
        connect()
    }

    /**
     * Releases [client]'s claim. Only the last client disconnects, after a grace period, so an old activity
     * instance finishing (app update, rotation, task relaunch) can't cut the connection a newer one is using.
     */
    fun detach(client: Any) {
        clients -= client
        main.postDelayed({ if (clients.isEmpty()) disconnect() }, 1500)
    }

    fun disconnect() {
        wanted = false
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        pending.clear()
        _state.update { it.copy(connected = false) }
    }

    /** Runs now if connected, otherwise reconnects and runs once the controller is ready. */
    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) block(c) else {
            pending += block
            connect()
        }
    }

    private fun sync(p: Player, rebuildQueue: Boolean) {
        val item = p.currentMediaItem
        val md = item?.mediaMetadata
        _state.update { s ->
            s.copy(
                connected = true,
                mediaId = item?.mediaId,
                title = md?.title?.toString().orEmpty(),
                artist = md?.artist?.toString().orEmpty(),
                album = md?.albumTitle?.toString().orEmpty(),
                artUri = md?.artworkUri,
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady,
                durationMs = p.duration.takeIf { it > 0 } ?: (md?.durationMs ?: 0),
                shuffle = p.shuffleModeEnabled,
                repeatMode = p.repeatMode,
                currentIndex = p.currentMediaItemIndex,
                speed = p.playbackParameters.speed,
                pitch = p.playbackParameters.pitch,
                queue = if (rebuildQueue) buildQueue(p) else s.queue,
            )
        }
    }

    /** Queue in play order (respects shuffle). */
    private fun buildQueue(p: Player): List<QueueEntry> {
        val tl = p.currentTimeline
        if (tl.isEmpty) return emptyList()
        val order = ArrayList<Int>(tl.windowCount)
        var i = tl.getFirstWindowIndex(p.shuffleModeEnabled)
        while (i != C_INDEX_UNSET && order.size < tl.windowCount) {
            order += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        val window = Timeline.Window()
        return order.map { idx ->
            val mi: MediaItem = tl.getWindow(idx, window).mediaItem
            QueueEntry(idx, mi.mediaId, mi.mediaMetadata.title?.toString().orEmpty(),
                mi.mediaMetadata.artist?.toString().orEmpty(), mi.mediaMetadata.artworkUri)
        }
    }

    private fun applyExtras(b: Bundle) {
        _state.update {
            it.copy(
                audio = AudioInfo(
                    codec = b.getString(Extras.CODEC),
                    sampleRate = b.getInt(Extras.SAMPLE_RATE),
                    bitDepth = b.getInt(Extras.BIT_DEPTH),
                    channels = b.getInt(Extras.CHANNELS),
                    bitrate = b.getInt(Extras.BITRATE),
                    outEncoding = b.getString(Extras.OUT_ENCODING),
                    outSampleRate = b.getInt(Extras.OUT_SAMPLE_RATE),
                    offload = b.getBoolean(Extras.OFFLOAD),
                ),
                sleepEndsAt = b.getLong(Extras.SLEEP_ENDS_AT),
                sleepEndOfTrack = b.getBoolean(Extras.SLEEP_END_OF_TRACK),
                favorite = b.getBoolean(Extras.FAVORITE),
            )
        }
    }

    // ------------------------------------------------------------- commands

    val positionMs: Long get() = controller?.currentPosition ?: 0
    val bufferedMs: Long get() = controller?.bufferedPosition ?: 0

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) =
        playItems(songs.map { it.toMediaItem() }, startIndex, shuffle)

    fun playItems(items: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (items.isEmpty()) return
        withController { c ->
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle) items.indices.random() else startIndex.coerceIn(items.indices)
            c.setMediaItems(items, start, 0)
            c.prepare()
            c.play()
        }
    }

    fun playNextItems(items: List<MediaItem>) = withController { c ->
        if (c.mediaItemCount == 0) playItems(items) else c.addMediaItems(c.currentMediaItemIndex + 1, items)
    }

    /** Inserts [items] at queue position [index] (clamped), without interrupting playback. */
    fun insertItems(index: Int, items: List<MediaItem>) = withController { c ->
        c.addMediaItems(index.coerceIn(0, c.mediaItemCount), items)
    }

    fun addItems(items: List<MediaItem>) = withController { c ->
        if (c.mediaItemCount == 0) playItems(items) else c.addMediaItems(items)
    }

    fun playUri(uri: Uri) = withController { c ->
        c.setMediaItem(MediaItem.Builder().setMediaId(uri.toString()).setUri(uri).build())
        c.prepare()
        c.play()
    }

    fun playNext(songs: List<Song>) = playNextItems(songs.map { it.toMediaItem() })
    fun addToQueue(songs: List<Song>) = addItems(songs.map { it.toMediaItem() })

    fun togglePlay() = withController { c ->
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }
    fun remove(index: Int) = withController { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun clearUpcoming() = withController { c ->
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setPlaybackParameters(speed: Float, pitch: Float) =
        withController { it.setPlaybackParameters(PlaybackParameters(speed, pitch)) }

    fun setSleepTimer(minutes: Int, endOfTrack: Boolean = false) = withController {
        it.sendCustomCommand(
            SessionCommand(Commands.SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply { putInt(Commands.ARG_MINUTES, minutes); putBoolean(Commands.ARG_END_OF_TRACK, endOfTrack) },
        )
    }

    /** Sends Sonora's audio to [deviceId] (an AudioDeviceInfo id), or back to automatic with -1. */
    fun setOutputDevice(deviceId: Int) = withController {
        it.sendCustomCommand(
            SessionCommand(Commands.SET_OUTPUT, Bundle.EMPTY),
            Bundle().apply { putInt(Commands.ARG_DEVICE_ID, deviceId) },
        )
    }

    /** Plays the queue on a DLNA TV / speaker. */
    fun castToDlna(device: com.sonora.player.cast.DlnaDevice) = withController {
        it.sendCustomCommand(
            SessionCommand(Commands.CAST_DLNA, Bundle.EMPTY),
            Bundle().apply {
                putString(Commands.ARG_NAME, device.name); putString(Commands.ARG_LOCATION, device.location)
                putString(Commands.ARG_AV, device.avTransport); putString(Commands.ARG_RC, device.rendering)
            },
        )
    }

    /** Stops casting and carries on on the phone. */
    fun stopCasting() = withController { it.sendCustomCommand(SessionCommand(Commands.STOP_CAST, Bundle.EMPTY), Bundle.EMPTY) }

    fun toggleFavorite() = withController {
        it.sendCustomCommand(SessionCommand(Commands.TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
    }

    companion object {
        private const val C_INDEX_UNSET = -1
    }
}
