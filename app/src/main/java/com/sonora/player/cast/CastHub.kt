package com.sonora.player.cast

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where Sonora is casting right now. */
data class CastTarget(val name: String, val kind: Kind) {
    enum class Kind { GOOGLE_CAST, DLNA }
}

/** App-wide casting state, so the player screen can show "Playing on Living Room TV". */
object CastState {
    private val _target = MutableStateFlow<CastTarget?>(null)
    val target: StateFlow<CastTarget?> = _target.asStateFlow()
    internal fun set(t: CastTarget?) { _target.value = t }
}

/**
 * Runs casting inside the playback service. Google Cast goes through Media3's CastPlayer (it hands playback between
 * the phone and the TV by itself; [transfer] rewrites the queue so the TV gets addresses it can reach). DLNA swaps the
 * session over to a [DlnaPlayer] and back.
 */
@OptIn(UnstableApi::class)
class CastHub(
    private val context: Context,
    private val server: CastServer,
    private val http: okhttp3.OkHttpClient,
) {
    /** The queue as it was on the phone, to restore it exactly when casting ends. */
    private var phoneQueue: List<MediaItem>? = null
    private var dlna: DlnaPlayer? = null
    /** What the session normally plays through (the CastPlayer, or the ExoPlayer when Cast isn't available). */
    var sessionPlayer: Player? = null

    /** TransferCallback for CastPlayer: phone → TV and TV → phone. */
    fun transfer(from: Player, to: Player) {
        val toRemote = to.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        val index = from.currentMediaItemIndex.coerceAtLeast(0)
        val position = from.currentPosition.coerceAtLeast(0)
        val play = from.playWhenReady
        if (toRemote) {
            val queue = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
            phoneQueue = queue
            server.start()
            val remote = queue.map { toRemoteItem(it) ?: it }
            from.pause()
            to.setMediaItems(remote, index, position)
            to.playWhenReady = play
            to.prepare()
            CastState.set(CastTarget(castDeviceName() ?: "TV", CastTarget.Kind.GOOGLE_CAST))
        } else {
            val queue = phoneQueue ?: (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
            to.setMediaItems(queue, index.coerceAtMost((queue.size - 1).coerceAtLeast(0)), position)
            to.playWhenReady = play
            to.prepare()
            phoneQueue = null
            if (dlna == null) { server.stop(); CastState.set(null) }
        }
    }

    /** Moves playback from [local] to a DLNA [device]; returns the player the session should use. */
    fun startDlna(device: DlnaDevice, local: Player, session: MediaSession) {
        stopDlna(local, session)
        if (server.start() == null) { Log.d(TAG, "no Wi-Fi address; can't cast"); return }
        val queue = (0 until local.mediaItemCount).map { local.getMediaItemAt(it) }
        phoneQueue = queue
        val p = DlnaPlayer(DlnaControl(http, device)) { describe(it) }
        p.setMediaItems(queue, local.currentMediaItemIndex.coerceAtLeast(0), local.currentPosition.coerceAtLeast(0))
        p.playWhenReady = local.playWhenReady
        local.pause()
        p.prepare()
        dlna = p
        session.player = p
        CastState.set(CastTarget(device.name, CastTarget.Kind.DLNA))
    }

    /** Brings playback back to the phone from DLNA, where the TV left off. */
    fun stopDlna(local: Player, session: MediaSession) {
        val p = dlna ?: return
        val index = p.currentMediaItemIndex.coerceAtLeast(0)
        val position = p.currentPosition.coerceAtLeast(0)
        val play = p.playWhenReady
        session.player = sessionPlayer ?: local
        p.release()
        dlna = null
        phoneQueue = null
        if (index < local.mediaItemCount) local.seekTo(index, position)
        local.playWhenReady = play
        server.stop()
        CastState.set(null)
    }

    /** Ends a Google Cast session (playback comes back to the phone through [transfer]). */
    fun stopGoogleCast() {
        runCatching { androidx.media3.cast.Cast.getSingletonInstance(context).endCurrentSession(true) }
    }

    fun release() {
        dlna?.release(); dlna = null
        server.stop()
        CastState.set(null)
    }

    private fun castDeviceName(): String? = runCatching {
        androidx.media3.cast.Cast.getSingletonInstance(context).currentCastSession?.castDevice?.friendlyName
    }.getOrNull()

    /** The same song, pointed at the phone's server, with artwork the TV can load. */
    private fun toRemoteItem(item: MediaItem): MediaItem? {
        val url = server.urlFor(item.mediaId) ?: return null
        val mime = if (item.mediaId.toLongOrNull() != null) server.mimeFor(item.mediaId) else "audio/mp4"
        val meta = item.mediaMetadata.buildUpon().setArtworkUri(server.artFor(item.mediaMetadata.artworkUri)).build()
        return MediaItem.Builder().setMediaId(item.mediaId).setUri(url).setMimeType(mime).setMediaMetadata(meta).build()
    }

    private fun describe(item: MediaItem): DlnaPlayer.Remote? {
        val url = server.urlFor(item.mediaId) ?: return null
        val mime = if (item.mediaId.toLongOrNull() != null) server.mimeFor(item.mediaId) else "audio/mp4"
        val m = item.mediaMetadata
        return DlnaPlayer.Remote(
            url, m.title?.toString() ?: "Sonora", m.artist?.toString() ?: "",
            server.artFor(m.artworkUri)?.toString(), mime,
        )
    }

    companion object { private const val TAG = "SonoraCast" }
}
