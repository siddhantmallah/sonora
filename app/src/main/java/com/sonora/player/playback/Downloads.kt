package com.sonora.player.playback

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.sonora.player.R
import com.sonora.player.container
import com.sonora.player.youtube.InnerTube
import com.sonora.player.youtube.YouTubeRepository
import com.sonora.player.youtube.YtSong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

enum class DlState { QUEUED, DOWNLOADING, DONE, FAILED }

/**
 * Offline downloads for YouTube Music tracks. Files live in their own never-evicted cache keyed "yt:<videoId>",
 * so playback finds them first and works with no connection at all.
 */
@OptIn(UnstableApi::class)
class Downloads(
    private val context: Context,
    http: OkHttpClient,
    databaseProvider: DatabaseProvider,
    private val yt: YouTubeRepository,
) {
    val cache = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)

    /** Cache key used by both the downloader and the player for YouTube streams. */
    val keyFactory = CacheKeyFactory { spec: DataSpec ->
        if (spec.uri.scheme == "ytm") "yt:${spec.uri.schemeSpecificPart}" else spec.key ?: spec.uri.toString()
    }

    private val resolving = ResolvingDataSource.Factory(OkHttpDataSource.Factory(http).setUserAgent(InnerTube.USER_AGENT)) { spec ->
        if (spec.uri.scheme != "ytm") return@Factory spec
        val id = spec.uri.schemeSpecificPart
        val stream = try { yt.resolver.resolve(id, dataSaver = false) } catch (e: Exception) { throw IOException("Couldn't resolve $id", e) }
        spec.buildUpon().setUri(Uri.parse(stream.url)).setKey("yt:$id").build()
    }

    val manager: DownloadManager = DownloadManager(
        context, databaseProvider, cache,
        CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(keyFactory).setUpstreamDataSourceFactory(resolving),
        Executors.newFixedThreadPool(2),
    ).apply { maxParallelDownloads = 2 }

    private val _states = MutableStateFlow<Map<String, DlState>>(emptyMap())
    /** videoId → state. */
    val states: StateFlow<Map<String, DlState>> = _states.asStateFlow()

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(dm: DownloadManager, download: Download, finalException: Exception?) = refresh()
            override fun onDownloadRemoved(dm: DownloadManager, download: Download) = refresh()
            override fun onInitialized(dm: DownloadManager) = refresh()
        })
        refresh()
    }

    private fun refresh() {
        val out = HashMap<String, DlState>()
        runCatching {
            manager.downloadIndex.getDownloads().use { c ->
                while (c.moveToNext()) {
                    val d = c.download
                    out[d.request.id] = when (d.state) {
                        Download.STATE_COMPLETED -> DlState.DONE
                        Download.STATE_DOWNLOADING -> DlState.DOWNLOADING
                        Download.STATE_FAILED -> DlState.FAILED
                        else -> DlState.QUEUED
                    }
                }
            }
        }
        _states.value = out
    }

    fun isDownloaded(videoId: String) = _states.value[videoId] == DlState.DONE

    fun download(songs: List<YtSong>) {
        yt.remember(songs)
        for (s in songs) {
            if (_states.value[s.videoId] == DlState.DONE) continue
            val req = DownloadRequest.Builder(s.videoId, Uri.parse("ytm:${s.videoId}"))
                .setCustomCacheKey("yt:${s.videoId}")
                .setData(s.title.toByteArray())
                .build()
            DownloadService.sendAddDownload(context, SonoraDownloadService::class.java, req, false)
        }
    }

    fun remove(videoId: String) = DownloadService.sendRemoveDownload(context, SonoraDownloadService::class.java, videoId, false)

    /** Approximate space used by downloads. */
    fun bytesUsed(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)
}

/** Foreground service that runs downloads with a progress notification, even with the app closed. */
@OptIn(UnstableApi::class)
class SonoraDownloadService : DownloadService(
    FOREGROUND_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, CHANNEL_ID, R.string.downloads_channel, 0,
) {
    override fun getDownloadManager(): DownloadManager = container.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, CHANNEL_ID).buildProgressNotification(
            this, R.drawable.ic_stat_sonora, null,
            "Downloading ${downloads.count { it.state != Download.STATE_COMPLETED }} song(s)",
            downloads, notMetRequirements,
        )

    companion object {
        private const val FOREGROUND_ID = 2001
        private const val CHANNEL_ID = "downloads"
    }
}
