package com.sonora.player

import android.app.Application
import android.content.Context
import com.sonora.player.data.LibraryRepository
import com.sonora.player.data.PlaylistRepository
import com.sonora.player.data.SettingsRepository
import com.sonora.player.data.StatsRepository
import com.sonora.player.data.db.SonoraDatabase
import com.sonora.player.lyrics.LyricsRepository
import com.sonora.player.playback.AudioEffectsController
import com.sonora.player.playback.AudioOutputMonitor
import com.sonora.player.playback.PlayerConnection
import com.sonora.player.playback.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import coil3.request.crossfade

class SonoraApp : Application(), coil3.SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /**
     * One image loader for the whole app: covers fade in quickly instead of popping in while you scroll, and a
     * bigger memory cache keeps covers you scrolled past ready when you scroll back.
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .crossfade(120)
            .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.3).build() }
            .build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Build Home's sky (shader + moon photo) in the background so opening Home never waits on it.
        Thread { com.sonora.player.ui.screens.preloadSky(this) }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}

/** Hand-rolled dependency container: one instance per process, shared by UI, service and widgets. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app, scope)
    private val database = SonoraDatabase.build(app)
    val library = LibraryRepository(app, settings, scope)
    val stats = StatsRepository(database.statsDao(), scope)
    val playlists = PlaylistRepository(database.playlistDao())
    val lyrics = LyricsRepository(database.lyricsDao(), settings)
    val history = database.historyDao()
    val external = database.externalDao()
    val connections by lazy { com.sonora.player.data.ConnectionsRepository(app, http, external) }
    val effects = AudioEffectsController(app, settings, scope)
    val output = AudioOutputMonitor(app)
    val queueStore = QueueStore(app)
    val audioTap = com.sonora.player.playback.AudioTap()
    /** Live settings for Sonora's audio stage (Enhance, pre-amp, balance, mono). */
    val dsp = com.sonora.player.playback.DspParams()

    val http: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val youtube = com.sonora.player.youtube.YouTubeRepository(app, http, scope)
    val mixes = com.sonora.player.data.MixEngine(youtube, library, history, stats)
    val ambient by lazy { com.sonora.player.data.AmbientRepository(app, http) }
    /** Self-update from GitHub Releases. */
    val updater by lazy { com.sonora.player.data.Updater(app, http) }

    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val mediaDatabase: androidx.media3.database.StandaloneDatabaseProvider by lazy {
        androidx.media3.database.StandaloneDatabaseProvider(app)
    }

    /** Disk cache for streamed audio so replays and seeks don't re-download. */
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val mediaCache: androidx.media3.datasource.cache.SimpleCache by lazy {
        androidx.media3.datasource.cache.SimpleCache(
            java.io.File(app.cacheDir, "media"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
            mediaDatabase,
        )
    }

    /** Offline downloads of YouTube Music tracks. */
    val downloads: com.sonora.player.playback.Downloads by lazy {
        com.sonora.player.playback.Downloads(app, http, mediaDatabase, youtube)
    }

    val scrobbler = com.sonora.player.playback.Scrobbler(http, settings)
    val playerConnection = PlayerConnection(app)
}

val Context.container: AppContainer
    get() = (applicationContext as SonoraApp).container
