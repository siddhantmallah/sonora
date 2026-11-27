package com.sonora.player.youtube

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class ResolvedStream(val url: String, val mimeType: String?, val bitrate: Int, val codec: String?, val expiresAt: Long)

/**
 * Turns a YouTube video id into a direct, playable audio URL. NewPipe Extractor does the hard part
 * (signature / throttling deobfuscation) and is updated quickly when YouTube changes things.
 */
class StreamResolver(http: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, ResolvedStream>()

    init {
        NewPipe.init(NewPipeDownloader(http))
    }

    fun invalidate(videoId: String) { cache.remove(videoId) }

    /** Blocking; call from a background / loader thread. */
    fun resolve(videoId: String, dataSaver: Boolean): ResolvedStream {
        cache[videoId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it }
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val streams = extractor.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { extractor.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } }
        if (streams.isEmpty()) throw IOException("No playable audio stream for $videoId")
        // NewPipe gives averageBitrate in kbps but bitrate in bps; compare (and report) both in bps.
        fun bps(st: org.schabi.newpipe.extractor.stream.AudioStream): Int =
            st.averageBitrate.takeIf { it > 0 }?.let { if (it < 10_000) it * 1000 else it } ?: st.bitrate.coerceAtLeast(0)
        val pick = if (dataSaver) streams.minBy { bps(it) } else streams.maxBy { bps(it) }
        val url = pick.content
        val expire = Uri.parse(url).getQueryParameter("expire")?.toLongOrNull()?.times(1000)
            ?: (System.currentTimeMillis() + 5 * 3600_000L)
        val resolved = ResolvedStream(
            url = url,
            mimeType = pick.format?.mimeType,
            bitrate = bps(pick),
            codec = pick.codec,
            expiresAt = expire - 10 * 60_000L,
        )
        Log.d("SonoraYT", "Resolved $videoId → ${resolved.codec} ${resolved.bitrate / 1000} kbps")
        cache[videoId] = resolved
        return resolved
    }
}

private class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body?.toRequestBody() ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody() else null)
            .header("User-Agent", InnerTube.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { res ->
            if (res.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return Response(res.code, res.message, res.headers.toMultimap(), res.body.string(), res.request.url.toString())
        }
    }
}
