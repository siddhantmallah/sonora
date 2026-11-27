package com.sonora.player.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** A DLNA / UPnP media renderer on the network (most Samsung, LG, Sony, Panasonic smart TVs, many speakers). */
data class DlnaDevice(val name: String, val location: String, val avTransport: String, val rendering: String?)

/** Finds renderers with an SSDP search on the local network. */
object DlnaDiscovery {
    private const val TAG = "SonoraDlna"

    suspend fun discover(context: Context, http: OkHttpClient, timeoutMs: Long = 3000): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("sonora-dlna")?.apply { setReferenceCounted(false); acquire() }
        try {
            val locations = LinkedHashSet<String>()
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                for (st in listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")) {
                    val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
                    sock.send(DatagramPacket(msg, msg.size, group, 1900))
                }
                val buf = ByteArray(4096)
                val end = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    text.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.let { locations += it }
                }
            }
            locations.mapNotNull { runCatching { describe(it, http) }.getOrNull() }.distinctBy { it.avTransport }
        } catch (e: Exception) {
            Log.d(TAG, "discovery failed: ${e.message}")
            emptyList()
        } finally {
            runCatching { lock?.release() }
        }
    }

    /** Reads a device description: its name and the control addresses of the services we need. */
    private fun describe(location: String, http: OkHttpClient): DlnaDevice? {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body.string() }
        val name = tag(xml, "friendlyName") ?: return null
        val base = tag(xml, "URLBase")?.trimEnd('/') ?: URL(location).let { "${it.protocol}://${it.host}:${if (it.port > 0) it.port else 80}" }
        var av: String? = null
        var rc: String? = null
        Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val s = m.groupValues[1]
            val type = tag(s, "serviceType") ?: return@forEach
            val ctl = tag(s, "controlURL") ?: return@forEach
            val abs = if (ctl.startsWith("http")) ctl else base + (if (ctl.startsWith("/")) ctl else "/$ctl")
            if (type.contains("AVTransport")) av = abs
            if (type.contains("RenderingControl")) rc = abs
        }
        return av?.let { DlnaDevice(unescape(name), location, it, rc) }
    }

    internal fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name[^>]*>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    internal fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/** The SOAP calls a renderer understands. */
class DlnaControl(private val http: OkHttpClient, val device: DlnaDevice) {

    private fun call(url: String, service: String, action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">$args</u:$action></s:Body></s:Envelope>"""
        val req = Request.Builder().url(url)
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:$service:1#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        return http.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw IllegalStateException("$action failed: ${r.code} ${DlnaDiscovery.tag(text, "errorDescription") ?: ""}")
            text
        }
    }

    private fun av(action: String, args: String = "") = call(device.avTransport, "AVTransport", action, "<InstanceID>0</InstanceID>$args")

    fun setUri(url: String, title: String, artist: String, art: String?, mime: String) {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>${esc(title)}</dc:title><upnp:artist>${esc(artist)}</upnp:artist>${if (art != null) "<upnp:albumArtURI>${esc(art)}</upnp:albumArtURI>" else ""}<upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:$mime:*">${esc(url)}</res></item></DIDL-Lite>"""
        av("SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
    }

    fun play() { av("Play", "<Speed>1</Speed>") }
    fun pause() { av("Pause") }
    fun stop() { av("Stop") }
    fun seek(ms: Long) { av("Seek", "<Unit>REL_TIME</Unit><Target>${hms(ms)}</Target>") }

    /** Position and duration in ms (null when the renderer doesn't say). */
    fun position(): Pair<Long?, Long?> {
        val r = av("GetPositionInfo")
        return parseHms(DlnaDiscovery.tag(r, "RelTime")) to parseHms(DlnaDiscovery.tag(r, "TrackDuration"))
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT… */
    fun state(): String = DlnaDiscovery.tag(av("GetTransportInfo"), "CurrentTransportState") ?: "UNKNOWN"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun hms(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    private fun parseHms(v: String?): Long? {
        v ?: return null
        val p = v.split(':')
        if (p.size != 3) return null
        val sec = p[2].toDoubleOrNull() ?: return null
        val h = p[0].toLongOrNull() ?: return null
        val m = p[1].toLongOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000 + (sec * 1000).toLong()).takeIf { it >= 0 }
    }
}

/**
 * A Media3 [Player] that plays the queue on a DLNA renderer, so the notification, lock screen and Sonora's own
 * controls work unchanged while casting. Polls the TV once a second for position and state and moves to the next
 * song when one finishes.
 */
@OptIn(UnstableApi::class)
class DlnaPlayer(
    private val control: DlnaControl,
    /** Metadata + address for item [i] as the TV should see it: url, title, artist, art, mime. */
    private val describe: (MediaItem) -> Remote?,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    data class Remote(val url: String, val title: String, val artist: String, val art: String?, val mime: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var positionMs = 0L
    private var positionAt = SystemClock.elapsedRealtime()
    private var durationMs = C.TIME_UNSET
    private var playing = false
    private var poll: Job? = null
    /** Set once the TV reports it actually started this track (so a STOPPED before then isn't "finished"). */
    private var started = false

    val deviceName: String get() = control.device.name

    override fun getState(): State {
        val cmds = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
        ).build()
        val playlist = items.mapIndexed { i, m ->
            MediaItemData.Builder(m.mediaId.ifEmpty { "item$i" } + "#" + i)
                .setMediaItem(m)
                .setDurationUs(if (i == index && durationMs != C.TIME_UNSET) durationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val st = if (playlist.isEmpty() && playbackState != Player.STATE_IDLE) Player.STATE_ENDED else playbackState
        return State.Builder()
            .setAvailableCommands(cmds)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else index.coerceIn(0, playlist.lastIndex))
            .setContentPositionMs(PositionSupplier { estimate() })
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(st)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build())
            .build()
    }

    private fun estimate(): Long =
        if (playing) positionMs + (SystemClock.elapsedRealtime() - positionAt) else positionMs

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.toList()
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        positionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        positionAt = SystemClock.elapsedRealtime()
        if (playbackState != Player.STATE_IDLE) load(positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        playbackState = Player.STATE_BUFFERING
        load(positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (!playWhenReady) { positionMs = estimate(); positionAt = SystemClock.elapsedRealtime(); playing = false }
        io { if (playWhenReady) control.play() else control.pause() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val pos = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (mediaItemIndex != index && mediaItemIndex in items.indices) {
            index = mediaItemIndex
            durationMs = C.TIME_UNSET
            load(pos)
        } else {
            this.positionMs = pos; positionAt = SystemClock.elapsedRealtime()
            io { control.seek(pos) }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playbackState = Player.STATE_IDLE
        playing = false
        io { control.stop() }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        poll?.cancel()
        val c = control
        CoroutineScope(Dispatchers.IO).launch { runCatching { c.stop() } }
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    /** Sends the current item to the TV, then starts it (and seeks) if we should be playing. */
    private fun load(startMs: Long) {
        val item = items.getOrNull(index) ?: return
        val r = describe(item)
        started = false
        playbackState = Player.STATE_BUFFERING
        positionMs = startMs; positionAt = SystemClock.elapsedRealtime(); playing = false
        invalidateState()
        if (r == null) { advance(); return }
        io {
            runCatching { control.stop() }
            control.setUri(r.url, r.title, r.artist, r.art, r.mime)
            if (playWhenReady) control.play()
            if (startMs > 2_000) { delay(1200); runCatching { control.seek(startMs) } }
        }
        startPolling()
    }

    private fun advance() {
        if (index + 1 < items.size) { index++; durationMs = C.TIME_UNSET; load(0) }
        else { playbackState = Player.STATE_ENDED; playing = false; invalidateState() }
    }

    private fun startPolling() {
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                delay(1000)
                val snap = withContext(Dispatchers.IO) { runCatching { control.state() to control.position() }.getOrNull() } ?: continue
                val (state, pos) = snap
                pos.first?.let { positionMs = it; positionAt = SystemClock.elapsedRealtime() }
                pos.second?.takeIf { it > 0 }?.let { durationMs = it }
                when (state) {
                    "PLAYING" -> { started = true; playing = true; playbackState = Player.STATE_READY }
                    "PAUSED_PLAYBACK" -> { playing = false; playbackState = Player.STATE_READY }
                    "TRANSITIONING" -> playbackState = Player.STATE_BUFFERING
                    "STOPPED", "NO_MEDIA_PRESENT" -> {
                        playing = false
                        // Finished on its own: on to the next song.
                        if (started && playWhenReady) { started = false; advance(); continue }
                    }
                }
                invalidateState()
            }
        }
    }

    private fun io(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) { runCatching { block() }.onFailure { Log.d("SonoraDlna", "control: ${it.message}") } }
    }
}
