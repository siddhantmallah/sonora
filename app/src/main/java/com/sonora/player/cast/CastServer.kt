package com.sonora.player.cast

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.provider.MediaStore
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A tiny HTTP server on the phone, so a TV can fetch what Sonora plays: your local songs (read straight from
 * storage) and YouTube audio (relayed through the phone, which also sidesteps stream URLs that only work for the
 * device that asked). Runs only while casting, on your Wi-Fi address, behind a random path token.
 *
 * Supports HEAD and byte ranges (TVs seek by asking for ranges), and sends the DLNA headers some TVs insist on.
 */
class CastServer(
    private val context: Context,
    private val http: OkHttpClient,
    /** Blocking: a playable audio URL for a YouTube video, preferring AAC (the format TVs play most widely). */
    private val resolveYouTube: (videoId: String) -> String?,
) {
    private val token = UUID.randomUUID().toString().replace("-", "").take(16)
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** e.g. http://192.168.1.20:41234/abcd… while running, else null. */
    @Volatile var base: String? = null
        private set

    /** Starts (if needed) and returns the base URL, or null when there's no Wi-Fi / LAN address. */
    @Synchronized
    fun start(): String? {
        base?.let { return it }
        val ip = lanAddress() ?: return null
        val ss = ServerSocket(0, 32, InetAddress.getByName("0.0.0.0"))
        socket = ss
        base = "http://$ip:${ss.localPort}/$token"
        pool.execute {
            while (!ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) }.onFailure { Log.d(TAG, "request failed: ${it.message}") }; runCatching { client.close() } }
            }
        }
        Log.d(TAG, "serving at $base")
        return base
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        base = null
    }

    /** Address the TV should use for [mediaId] (a local song id or a "yt:" id), or null if it can't be served. */
    fun urlFor(mediaId: String): String? {
        val b = base ?: return null
        mediaId.toLongOrNull()?.let { return "$b/song/$it" }
        com.sonora.player.youtube.YtIds.videoId(mediaId)?.let { return "$b/yt/$it" }
        return null
    }

    /** A content:// artwork address made reachable for the TV. */
    fun artFor(uri: android.net.Uri?): android.net.Uri? {
        uri ?: return null
        if (uri.scheme == "http" || uri.scheme == "https") return uri
        val b = base ?: return null
        return android.net.Uri.parse("$b/art?u=" + android.net.Uri.encode(uri.toString()))
    }

    /** MIME type of a local song (what MediaStore recorded), for the cast receiver. */
    fun mimeFor(mediaId: String): String {
        val id = mediaId.toLongOrNull() ?: return "audio/mp4"
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        return runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "audio/mpeg"
    }

    // ---------------------------------------------------------------- serving

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val out = client.getOutputStream()
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val prefix = "/$token/"
        if (!path.startsWith(prefix) || (method != "GET" && method != "HEAD")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
        }
        val rest = path.removePrefix(prefix)
        val head = method == "HEAD"
        when {
            rest.startsWith("song/") -> serveSong(rest.removePrefix("song/").toLongOrNull(), headers["range"], head, out)
            rest.startsWith("yt/") -> serveYouTube(rest.removePrefix("yt/"), headers["range"], head, out)
            rest == "art" -> serveArt(android.net.Uri.decode(query.substringAfter("u=", "")), head, out)
            else -> { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush() }
        }
    }

    private fun serveSong(id: Long?, range: String?, head: Boolean, out: OutputStream) {
        id ?: return notFound(out)
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "audio/mpeg"
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return notFound(out)
        pfd.use {
            val size = it.statSize
            java.io.FileInputStream(it.fileDescriptor).use { stream ->
                val (start, end) = parseRange(range, size)
                val partial = range != null && size > 0
                val len = end - start + 1
                val sb = StringBuilder()
                sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                sb.append("Content-Type: $mime\r\n")
                sb.append("Content-Length: $len\r\n")
                if (partial) sb.append("Content-Range: bytes $start-$end/$size\r\n")
                sb.append(COMMON_HEADERS)
                out.write(sb.toString().toByteArray())
                if (!head) {
                    stream.channel.position(start)
                    copy(stream, out, len)
                }
                out.flush()
            }
        }
    }

    private fun serveYouTube(videoId: String, range: String?, head: Boolean, out: OutputStream) {
        val url = resolveYouTube(videoId) ?: return notFound(out)
        val req = Request.Builder().url(url).apply { if (range != null) header("Range", range) }
            .apply { if (head) head() }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return notFound(out)
            val sb = StringBuilder()
            sb.append(if (r.code == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            val type = r.header("Content-Type")?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
            sb.append("Content-Type: $type\r\n")
            r.header("Content-Length")?.let { sb.append("Content-Length: $it\r\n") }
            r.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
            sb.append(COMMON_HEADERS)
            out.write(sb.toString().toByteArray())
            if (!head) r.body.byteStream().use { copy(it, out, Long.MAX_VALUE) }
            out.flush()
        }
    }

    private fun serveArt(uri: String, head: Boolean, out: OutputStream) {
        if (!uri.startsWith("content://")) return notFound(out)
        val bytes = runCatching { context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() } }.getOrNull()
            ?: return notFound(out)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        if (!head) out.write(bytes)
        out.flush()
    }

    private fun notFound(out: OutputStream) {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
    }

    private fun parseRange(range: String?, size: Long): Pair<Long, Long> {
        if (range == null || size <= 0) return 0L to (size - 1).coerceAtLeast(0)
        val spec = range.substringAfter("bytes=", "").substringBefore(',')
        val a = spec.substringBefore('-').trim()
        val b = spec.substringAfter('-', "").trim()
        return when {
            a.isEmpty() && b.isNotEmpty() -> (size - b.toLong()).coerceAtLeast(0) to size - 1
            else -> (a.toLongOrNull() ?: 0L).coerceIn(0, size - 1) to (b.toLongOrNull() ?: (size - 1)).coerceIn(0, size - 1)
        }
    }

    private fun copy(input: InputStream, out: OutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024)
        var left = limit
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** The phone's IPv4 address on Wi-Fi / Ethernet (casting needs both devices on the same network). */
    private fun lanAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    companion object {
        private const val TAG = "SonoraCast"
        private const val COMMON_HEADERS =
            "Accept-Ranges: bytes\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n" +
                "Connection: close\r\n\r\n"
    }
}
