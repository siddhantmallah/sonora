package com.sonora.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sonora.player.youtube.InnerTube
import com.sonora.player.youtube.ResolvedStream
import com.sonora.player.youtube.YtIds
import com.sonora.player.youtube.toMediaItem
import java.io.IOException
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sonora.player.R
import com.sonora.player.container
import com.sonora.player.data.Song
import com.sonora.player.ui.MainActivity
import com.sonora.player.widget.WidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Background playback: ExoPlayer + a Media3 library session. The session powers the media
 * notification, lock screen, Bluetooth/headset buttons, Android Auto, Wear OS and our own UI.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    // Casting: Google Cast via Media3's CastPlayer (wraps the ExoPlayer), DLNA via the hub.
    private var castPlayer: androidx.media3.cast.CastPlayer? = null
    private lateinit var castHub: com.sonora.player.cast.CastHub
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val c by lazy { container }

    private var playCountJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepEndsAt = 0L
    private var sleepEndOfTrack = false
    private var extras = Bundle()
    private var source: SourceFormat? = null
    private var currentFavorite = false

    private val sleepCommand = SessionCommand(Commands.SLEEP_TIMER, Bundle.EMPTY)
    private val favoriteCommand = SessionCommand(Commands.TOGGLE_FAVORITE, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val settings = runBlocking { c.settings.current() }

        val tap = c.audioTap
        c.dsp.apply(settings)
        scope.launch { c.settings.state.collect { c.dsp.apply(it) } }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Sonora's audio stage first (Enhance, pre-amp, balance, mono; a plain copy when all off), then
                // the tap that feeds the visualizer, so it shows what you actually hear.
                .setAudioProcessors(arrayOf(DspProcessor(c.dsp), TapProcessor(tap)))
                .build()
        }
            // 32-bit float PCM keeps 24-bit / hi-res sources intact instead of truncating to 16-bit.
            .setEnableAudioFloatOutput(settings.hiResOutput)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(DefaultMediaSourceFactory(buildDataSourceFactory()))
            .build()

        player.skipSilenceEnabled = settings.skipSilence
        player.playbackParameters = PlaybackParameters(settings.playbackSpeed, settings.playbackPitch)
        applyOffload(settings.audioOffload)

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                c.effects.attach(audioSessionId)
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                extras.putString(Extras.OUT_ENCODING, encodingName(audioTrackConfig.encoding))
                extras.putInt(Extras.OUT_SAMPLE_RATE, audioTrackConfig.sampleRate)
                extras.putBoolean(Extras.OFFLOAD, audioTrackConfig.offload)
                publishExtras()
                // Visualizer sync: the tap runs one AudioTrack buffer ahead, plus Bluetooth latency.
                val frameBytes = when (audioTrackConfig.encoding) {
                    C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
                    C.ENCODING_PCM_24BIT -> 3
                    else -> 2
                } * Integer.bitCount(audioTrackConfig.channelConfig).coerceAtLeast(1)
                val bufferMs = audioTrackConfig.bufferSize * 1000L / (frameBytes.toLong() * audioTrackConfig.sampleRate.coerceAtLeast(1))
                val btExtra = if (c.output.state.value.type == OutputType.BLUETOOTH) 200 else 30
                tap.latencyMs = (bufferMs + btExtra).toInt().coerceIn(40, 1200)
                if (c.settings.state.value.bitPerfectUsb) {
                    c.output.applyBitPerfect(true, source?.sampleRate ?: audioTrackConfig.sampleRate)
                }
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) {
                extras.putString(Extras.CODEC, codecName(format.sampleMimeType))
                // Auto Enhance only works on lossy sources.
                c.dsp.lossySource = format.sampleMimeType !in LOSSLESS_MIMES
                if (source == null) {
                    extras.putInt(Extras.SAMPLE_RATE, format.sampleRate.coerceAtLeast(0))
                    extras.putInt(Extras.CHANNELS, format.channelCount.coerceAtLeast(0))
                }
                // Opus/AAC formats rarely carry a bitrate; YouTube streams know theirs from when they were resolved.
                val br = format.bitrate.takeIf { it > 0 }
                    ?: YtIds.videoId(player.currentMediaItem?.mediaId)?.let { ytStreams[it]?.bitrate }?.takeIf { it > 0 }
                if (br != null) extras.putInt(Extras.BITRATE, br)
                publishExtras()
            }
        })
        c.effects.attach(player.audioSessionId)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onTrackChanged(mediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pushWidget()
                if (!isPlaying) saveQueue()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfTrack) {
                    player.pauseAtEndOfMediaItems = false
                    sleepEndOfTrack = false
                    publishExtras()
                }
            }

            override fun onTracksChanged(tracks: Tracks) = extractEmbeddedLyrics(tracks)

            override fun onPlayerError(error: PlaybackException) = recoverFromError(error)
        })

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Casting. The phone serves local songs and relays YouTube to the TV; CastPlayer hands playback over to a
        // Chromecast / Google TV and back by itself (the hub rewrites the queue so the TV can reach every song).
        val castServer = com.sonora.player.cast.CastServer(this, c.http) { vid ->
            runCatching { c.youtube.resolver.resolve(vid, dataSaver = false, forCast = true).url }.getOrNull()
        }
        castHub = com.sonora.player.cast.CastHub(this, castServer, c.http)
        castPlayer = runCatching {
            androidx.media3.cast.CastPlayer.Builder(this)
                .setLocalPlayer(player)
                .setTransferCallback { from, to -> castHub.transfer(from, to) }
                .build()
        }.onFailure { android.util.Log.d("SonoraCast", "Cast unavailable: ${it.message}") }.getOrNull()
        val sessionPlayer: androidx.media3.common.Player = castPlayer ?: player
        castHub.sessionPlayer = sessionPlayer

        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(buttons(false))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_sonora) },
        )

        // Live-apply settings that don't need a new player.
        scope.launch {
            c.settings.flow.map { Triple(it.skipSilence, it.playbackSpeed, it.playbackPitch) }
                .distinctUntilChanged().drop(1).collect { (skip, speed, pitch) ->
                    player.skipSilenceEnabled = skip
                    player.playbackParameters = PlaybackParameters(speed, pitch)
                }
        }
        scope.launch {
            c.settings.flow.map { it.audioOffload }.distinctUntilChanged().drop(1).collect { applyOffload(it) }
        }
        scope.launch {
            c.settings.flow.map { it.bitPerfectUsb }.distinctUntilChanged().drop(1).collect {
                c.output.applyBitPerfect(it, source?.sampleRate ?: 48_000)
            }
        }
        scope.launch {
            c.stats.favorites.collect { favs ->
                val id = YtIds.statsId(player.currentMediaItem?.mediaId)
                setFavoriteState(id != null && id in favs)
            }
        }

        restoreQueue()
    }

    // ---------------------------------------------------------------- data sources (local + YouTube)

    /**
     * content:// and file:// play directly; http(s) goes through a disk cache; "ytm:<videoId>" is resolved
     * to a fresh googlevideo URL on the loader thread (cached by NewPipe resolver until it expires).
     */
    private fun buildDataSourceFactory(): DataSource.Factory {
        val network = OkHttpDataSource.Factory(c.http).setUserAgent(InnerTube.USER_AGENT)
        val cached = CacheDataSource.Factory()
            .setCache(c.mediaCache)
            .setUpstreamDataSourceFactory(network)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val base = DefaultDataSource.Factory(this, cached)
        val online = ResolvingDataSource.Factory(base) { spec ->
            if (spec.uri.scheme != "ytm") return@Factory spec
            val videoId = spec.uri.schemeSpecificPart
            val s = c.settings.state.value
            // Separate quality ceilings for Wi-Fi and mobile data.
            val dataSaver = if (isMetered()) s.ytDataSaver else s.ytDataSaverWifi
            val stream = try {
                c.youtube.resolver.resolve(videoId, dataSaver)
            } catch (e: Exception) {
                throw IOException("Couldn't load YouTube stream: ${e.message}", e)
            }
            ytStreams[videoId] = stream
            spec.buildUpon().setUri(Uri.parse(stream.url)).setKey("yt:$videoId").build()
        }
        // Downloaded songs are read straight from the offline store (no network, no URL resolution);
        // the store is read-only here so streaming never fills it.
        return CacheDataSource.Factory()
            .setCache(c.downloads.cache)
            .setCacheKeyFactory(c.downloads.keyFactory)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(online)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun isMetered(): Boolean = runCatching {
        getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(false)

    // ---------------------------------------------------------------- crossfade

    private var crossfadeJob: Job? = null
    private var fadingIn = false

    /**
     * Crossfade-style transitions: the last N seconds fade out and the next song fades in. (A true overlap needs two
     * decoders; this keeps gapless playback and EQ intact while removing hard cuts.)
     */
    private fun startCrossfadeLoop() {
        if (crossfadeJob?.isActive == true) return
        crossfadeJob = scope.launch {
            while (true) {
                delay(50)
                val fadeMs = c.settings.state.value.crossfadeSec * 1000L
                if (fadeMs <= 0 || sleepJob?.isActive == true) {
                    if (!fadingIn && player.volume < 1f && sleepJob?.isActive != true) player.volume = 1f
                    continue
                }
                if (!player.isPlaying) continue
                val dur = player.duration
                val pos = player.currentPosition
                if (fadingIn) {
                    val v = (pos.toFloat() / (fadeMs / 2f)).coerceIn(0.05f, 1f)
                    player.volume = v
                    if (v >= 1f) fadingIn = false
                } else if (dur > fadeMs * 2 && player.hasNextMediaItem() && player.repeatMode != Player.REPEAT_MODE_ONE) {
                    val remaining = dur - pos
                    player.volume = if (remaining < fadeMs) (remaining.toFloat() / fadeMs).coerceIn(0.05f, 1f) else 1f
                }
            }
        }
    }

    private val ytStreams = java.util.concurrent.ConcurrentHashMap<String, ResolvedStream>()
    private val retried = HashSet<String>()

    /** Expired / rejected YouTube URL → re-resolve once; otherwise skip the broken track. */
    private fun recoverFromError(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        val videoId = YtIds.videoId(item.mediaId)
        if (videoId != null && retried.add(videoId)) {
            c.youtube.resolver.invalidate(videoId)
            player.prepare()
            player.play()
            return
        }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    /** Keeps YouTube queues going forever with "song radio" when you near the end. */
    private var radioJob: Job? = null
    private fun maybeExtendWithRadio() {
        if (!c.settings.state.value.ytAutoplay || radioJob?.isActive == true) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining > 2 || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val lastYt = (player.mediaItemCount - 1 downTo 0).firstNotNullOfOrNull { YtIds.videoId(player.getMediaItemAt(it).mediaId) } ?: return
        val currentYt = YtIds.videoId(player.currentMediaItem?.mediaId) ?: return
        radioJob = scope.launch {
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val more = runCatching { c.youtube.radio(lastYt.ifBlank { currentYt }) }.getOrDefault(emptyList())
                .filter { it.mediaId !in existing }.take(25)
            if (more.isNotEmpty()) player.addMediaItems(more.map { it.toMediaItem() })
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        finishHistory()
        saveQueue()
        c.effects.release()
        castHub.release()
        session.release()
        // CastPlayer releases the ExoPlayer it wraps.
        castPlayer?.release() ?: player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- track change

    // ---- listening history (feeds the taste analyser)
    private var historyItem: MediaItem? = null
    private var listenedMs = 0L
    private var historyTicker: Job? = null

    /** Row id of the current listen once it has been saved (it is saved as soon as it counts). */
    private var historyRowId: Long? = null
    private var historyStartedAt = 0L

    private fun startHistoryTicker() {
        if (historyTicker?.isActive == true) return
        historyTicker = scope.launch {
            var sinceFlush = 0L
            while (true) {
                delay(1000)
                if (!player.isPlaying) continue
                listenedMs += 1000
                sinceFlush += 1000
                val item = historyItem ?: continue
                // Save the listen the moment it counts, so a killed app or a crash never loses it…
                if (historyRowId == null && historyRowPending.not() && listenedMs >= thresholdFor(item)) {
                    historyRowPending = true
                    val event = eventFor(item, listenedMs, skipped = false)
                    c.scope.launch {
                        historyRowId = runCatching { c.history.insert(event) }
                            .onFailure { android.util.Log.w("SonoraHistory", "insert failed", it) }.getOrNull()
                        c.scrobbler.listened(event.title, event.artist, event.album, event.durationMs, event.playedAt)
                    }
                }
                // …and keep its listening time up to date every 30 s.
                val id = historyRowId
                if (id != null && sinceFlush >= 30_000) {
                    sinceFlush = 0
                    val ms = listenedMs
                    c.scope.launch { runCatching { c.history.updateListen(id, ms, false) } }
                }
            }
        }
    }

    private var historyRowPending = false

    private fun durationOf(item: MediaItem): Long =
        item.mediaMetadata.durationMs?.takeIf { it > 0 } ?: c.library.song(item.mediaId.toLongOrNull() ?: -1)?.durationMs ?: 0L

    private fun thresholdFor(item: MediaItem): Long {
        val d = durationOf(item)
        return if (d > 0) min(30_000L, d / 2) else 30_000L
    }

    private fun eventFor(item: MediaItem, heard: Long, skipped: Boolean): com.sonora.player.data.db.PlayEventEntity {
        val md = item.mediaMetadata
        return com.sonora.player.data.db.PlayEventEntity(
            mediaId = item.mediaId,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString(),
            artUri = md.artworkUri?.toString(),
            durationMs = durationOf(item),
            playedAt = historyStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            listenedMs = heard,
            skipped = skipped,
        )
    }

    /** Closes the track that just ended: final listening time for a saved listen, or a skip if abandoned early. */
    private fun finishHistory() {
        val item = historyItem ?: return
        val heard = listenedMs
        val id = historyRowId
        historyItem = null
        historyRowId = null
        historyRowPending = false
        listenedMs = 0
        if (id != null) {
            c.scope.launch { runCatching { c.history.updateListen(id, heard, false) } }
        } else if (heard >= 2000) {
            val event = eventFor(item, heard, skipped = heard < thresholdFor(item))
            c.scope.launch { runCatching { c.history.insert(event) } }
        }
    }

    private fun onTrackChanged(item: MediaItem?) {
        finishHistory()
        historyItem = item
        historyStartedAt = System.currentTimeMillis()
        startHistoryTicker()
        startCrossfadeLoop()
        if (c.settings.state.value.crossfadeSec > 0 && player.isPlaying) { fadingIn = true; player.volume = 0.05f }
        item?.mediaMetadata?.let { md ->
            c.scope.launch { c.scrobbler.nowPlaying(md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(), md.albumTitle?.toString()) }
        }
        val songId = item?.mediaId?.toLongOrNull()
        val statsId = YtIds.statsId(item?.mediaId)
        source = null
        extras = Bundle(extras).apply {
            remove(Extras.BIT_DEPTH); remove(Extras.SAMPLE_RATE); remove(Extras.BITRATE); remove(Extras.CODEC)
        }
        setFavoriteState(statsId != null && c.stats.isFavorite(statsId))
        pushWidget()
        saveQueue()
        maybeExtendWithRadio()

        // Count a play once the listener has heard 30s (or half of a short track).
        playCountJob?.cancel()
        if (statsId != null) {
            val song = songId?.let { c.library.song(it) }
            val durationMs = song?.durationMs ?: item?.mediaMetadata?.durationMs ?: 60_000L
            val threshold = min(30_000L, durationMs / 2)
            playCountJob = scope.launch {
                var heard = 0L
                while (heard < threshold) {
                    delay(1000)
                    if (player.isPlaying) heard += 1000
                }
                c.stats.recordPlay(statsId)
            }
            if (song != null) probeSource(song)
        }
        YtIds.videoId(item?.mediaId)?.let { vid ->
            retried.remove(vid)
            ytStreams[vid]?.let { s ->
                extras.putString(Extras.CODEC, s.codec?.substringBefore('.')?.uppercase()?.replace("MP4A", "AAC") ?: "YouTube")
                if (s.bitrate > 0) extras.putInt(Extras.BITRATE, s.bitrate)
                publishExtras()
            }
        }
    }

    private fun probeSource(song: Song) {
        scope.launch {
            val f = withContext(Dispatchers.IO) { AudioProbe.probe(this@PlaybackService, song.uri, song.mimeType) }
            if (player.currentMediaItem?.mediaId != song.id.toString()) return@launch
            source = f
            if (f != null) {
                extras.putInt(Extras.SAMPLE_RATE, f.sampleRate)
                extras.putInt(Extras.BIT_DEPTH, f.bitDepth)
                extras.putInt(Extras.CHANNELS, f.channels)
            }
            if (song.bitrate > 0 && !extras.containsKey(Extras.BITRATE)) extras.putInt(Extras.BITRATE, song.bitrate)
            if (!extras.containsKey(Extras.CODEC)) extras.putString(Extras.CODEC, song.extension)
            publishExtras()
        }
    }

    private fun extractEmbeddedLyrics(tracks: Tracks) {
        val songId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                for (j in 0 until metadata.length()) {
                    val text = when (val e = metadata.get(j)) {
                        is VorbisComment -> e.value.takeIf { e.key.equals("LYRICS", true) || e.key.equals("UNSYNCEDLYRICS", true) }
                        is TextInformationFrame -> e.values.firstOrNull()
                            .takeIf { e.id == "TXXX" && (e.description?.contains("LYRICS", true) == true) }
                        is BinaryFrame -> if (e.id == "USLT") parseUslt(e.data) else null
                        else -> null
                    }
                    if (!text.isNullOrBlank()) {
                        c.lyrics.putEmbedded(songId, text)
                        return
                    }
                }
            }
        }
    }

    /** ID3v2 USLT: encoding(1) language(3) descriptor(\0-terminated) text. */
    private fun parseUslt(data: ByteArray): String? = runCatching {
        if (data.size < 5) return null
        val enc = data[0].toInt()
        val charset = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        val wide = enc == 1 || enc == 2
        var i = 4
        if (wide) {
            while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < data.size && data[i].toInt() != 0) i++
            i += 1
        }
        if (i >= data.size) null else String(data, i, data.size - i, charset).trim('\u0000', ' ', '\n')
    }.getOrNull()

    // ---------------------------------------------------------------- extras, widget, favorites

    private fun publishExtras() {
        extras.putLong(Extras.SLEEP_ENDS_AT, sleepEndsAt)
        extras.putBoolean(Extras.SLEEP_END_OF_TRACK, sleepEndOfTrack)
        extras.putBoolean(Extras.FAVORITE, currentFavorite)
        session.setSessionExtras(Bundle(extras))
    }

    private fun setFavoriteState(fav: Boolean) {
        if (fav == currentFavorite) return
        currentFavorite = fav
        session.setMediaButtonPreferences(buttons(fav))
        publishExtras()
    }

    private fun buttons(favorite: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (favorite) "Remove from favorites" else "Add to favorites")
            .setSessionCommand(favoriteCommand)
            .build(),
    )

    private fun pushWidget() {
        val item = player.currentMediaItem
        val md = item?.mediaMetadata ?: MediaMetadata.EMPTY
        WidgetBridge.push(
            this,
            title = md.title?.toString(),
            artist = md.artist?.toString(),
            artUri = md.artworkUri,
            isPlaying = player.isPlaying,
        )
    }

    private fun applyOffload(enabled: Boolean) {
        val prefs = AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (enabled) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            )
            .setIsGaplessSupportRequired(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(prefs).build()
    }

    // ---------------------------------------------------------------- sleep timer

    private fun setSleepTimer(minutes: Int, endOfTrack: Boolean) {
        sleepJob?.cancel()
        sleepEndsAt = 0
        sleepEndOfTrack = false
        player.pauseAtEndOfMediaItems = false
        when {
            endOfTrack -> {
                sleepEndOfTrack = true
                player.pauseAtEndOfMediaItems = true
            }
            minutes > 0 -> {
                sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
                sleepJob = scope.launch {
                    delay(minutes * 60_000L - FADE_MS)
                    // Gentle fade-out over the last seconds instead of an abrupt stop.
                    val start = player.volume
                    val steps = 20
                    repeat(steps) { i ->
                        player.volume = start * (1f - (i + 1f) / steps)
                        delay(FADE_MS / steps)
                    }
                    player.pause()
                    player.volume = start
                    sleepEndsAt = 0
                    publishExtras()
                }
            }
        }
        publishExtras()
    }

    // ---------------------------------------------------------------- queue persistence

    private fun saveQueue() {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val q = SavedQueue(ids, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        c.scope.launch { c.queueStore.save(q) }
    }

    private suspend fun savedItems(): Pair<List<MediaItem>, SavedQueue>? {
        c.library.ensureLoaded()
        val q = c.queueStore.load() ?: return null
        val items = q.mediaIds.mapNotNull { id -> resolve(MediaItem.Builder().setMediaId(id).build()).takeIf { it.localConfiguration != null } }
        if (items.isEmpty()) return null
        return items to q
    }

    private fun restoreQueue() {
        scope.launch {
            val (items, q) = savedItems() ?: return@launch
            if (player.mediaItemCount > 0) return@launch
            val index = q.index.coerceIn(0, items.lastIndex)
            player.setMediaItems(items, index, q.positionMs)
            player.shuffleModeEnabled = q.shuffle
            player.repeatMode = q.repeatMode
            player.prepare()
        }
    }

    private fun resolve(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val id = item.mediaId
        id.toLongOrNull()?.let { songId -> c.library.song(songId)?.let { return it.toMediaItem() } }
        YtIds.videoId(id)?.let { videoId ->
            // Keep the metadata the controller sent; just attach the resolvable URI.
            return if (item.mediaMetadata.title != null) item.buildUpon().setUri(Uri.parse("ytm:$videoId")).build()
            else c.youtube.mediaItem(videoId)
        }
        if (id.startsWith("content://") || id.startsWith("file://")) {
            return item.buildUpon().setUri(Uri.parse(id)).build()
        }
        return item
    }

    // ---------------------------------------------------------------- session callback

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(sleepCommand)
                .add(favoriteCommand)
                .add(SessionCommand(Commands.SET_OUTPUT, Bundle.EMPTY))
                .add(SessionCommand(Commands.CAST_DLNA, Bundle.EMPTY))
                .add(SessionCommand(Commands.STOP_CAST, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons(currentFavorite))
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            publishExtras()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                Commands.SLEEP_TIMER -> setSleepTimer(
                    args.getInt(Commands.ARG_MINUTES, 0),
                    args.getBoolean(Commands.ARG_END_OF_TRACK, false),
                )
                Commands.SET_OUTPUT -> {
                    // Route Sonora's audio to a specific output (-1 = let Android decide).
                    val id = args.getInt(Commands.ARG_DEVICE_ID, -1)
                    val device = if (id < 0) null else getSystemService(android.media.AudioManager::class.java)
                        .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                    player.setPreferredAudioDevice(device)
                    c.output.preferredDeviceId = device?.id ?: -1
                    c.output.refresh()
                }
                Commands.CAST_DLNA -> {
                    val device = com.sonora.player.cast.DlnaDevice(
                        args.getString(Commands.ARG_NAME).orEmpty(), args.getString(Commands.ARG_LOCATION).orEmpty(),
                        args.getString(Commands.ARG_AV).orEmpty(), args.getString(Commands.ARG_RC),
                    )
                    if (device.avTransport.isNotEmpty()) castHub.startDlna(device, player, this@PlaybackService.session)
                }
                Commands.STOP_CAST -> {
                    castHub.stopDlna(player, this@PlaybackService.session)
                    castHub.stopGoogleCast()
                }
                Commands.TOGGLE_FAVORITE -> {
                    val mediaId = player.currentMediaItem?.mediaId
                    val id = YtIds.statsId(mediaId)
                    if (id != null) scope.launch {
                        val fav = c.stats.toggleFavorite(id)
                        setFavoriteState(fav)
                        // Mirror the heart to a YouTube Music "like" when signed in.
                        YtIds.videoId(mediaId)?.let { c.youtube.setLiked(it, fav) }
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            c.library.ensureLoaded()
            mediaItems.map { resolve(it) }.toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val (items, q) = savedItems() ?: throw UnsupportedOperationException("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(items, q.index.coerceIn(0, items.lastIndex), q.positionMs)
        }

        // ---- Android Auto / external browsers

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browsableItem(ROOT, "Sonora"), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            c.library.ensureLoaded()
            val item = resolve(MediaItem.Builder().setMediaId(mediaId).build())
            LibraryResult.ofItem(item, null)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            c.library.ensureLoaded()
            val lib = c.library
            val items: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    browsableItem(NODE_FAVORITES, "Favorites"),
                    browsableItem(NODE_RECENT, "Recently added"),
                    browsableItem(NODE_ALBUMS, "Albums", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                    browsableItem(NODE_SONGS, "All songs"),
                )
                parentId == NODE_SONGS -> lib.songs.value.map { it.toMediaItem() }
                parentId == NODE_FAVORITES -> lib.songs.value.filter { it.id in c.stats.favorites.value }.map { it.toMediaItem() }
                parentId == NODE_RECENT -> lib.songs.value.sortedByDescending { it.dateAddedSec }.take(100).map { it.toMediaItem() }
                parentId == NODE_ALBUMS -> lib.albums.value.map {
                    browsableItem("$NODE_ALBUMS/${it.id}", it.title, it.artUri, MediaMetadata.MEDIA_TYPE_ALBUM)
                }
                parentId.startsWith("$NODE_ALBUMS/") -> {
                    val id = parentId.substringAfter('/').toLongOrNull()
                    lib.albums.value.firstOrNull { it.id == id }?.songs.orEmpty().map { it.toMediaItem() }
                }
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(items.size)
            val to = (from + pageSize).coerceAtMost(items.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params)
        }
    }

    companion object {
        private const val ROOT = "root"
        private const val NODE_SONGS = "songs"
        private const val NODE_ALBUMS = "albums"
        private const val NODE_FAVORITES = "favorites"
        private const val NODE_RECENT = "recent"
        private const val FADE_MS = 10_000L

        fun codecName(mime: String?): String? = when (mime) {
            null -> null
            "audio/flac" -> "FLAC"
            "audio/alac" -> "ALAC"
            "audio/mpeg" -> "MP3"
            "audio/mp4a-latm" -> "AAC"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/raw" -> "PCM"
            "audio/ac3" -> "AC-3"
            "audio/eac3" -> "E-AC-3"
            "audio/x-ms-wma" -> "WMA"
            else -> mime.substringAfter('/').uppercase()
        }

        fun encodingName(encoding: Int): String = when (encoding) {
            C.ENCODING_PCM_16BIT -> "16-bit PCM"
            C.ENCODING_PCM_24BIT -> "24-bit PCM"
            C.ENCODING_PCM_32BIT -> "32-bit PCM"
            C.ENCODING_PCM_FLOAT -> "32-bit float"
            C.ENCODING_PCM_8BIT -> "8-bit PCM"
            else -> "Compressed (offload)"
        }
    }
}

/** Codecs that carry every bit of the original (Auto Enhance leaves these alone). */
private val LOSSLESS_MIMES = setOf(
    androidx.media3.common.MimeTypes.AUDIO_FLAC, androidx.media3.common.MimeTypes.AUDIO_ALAC,
    androidx.media3.common.MimeTypes.AUDIO_RAW, androidx.media3.common.MimeTypes.AUDIO_WAV,
    "audio/x-wav", "audio/x-flac", "audio/wav",
)
