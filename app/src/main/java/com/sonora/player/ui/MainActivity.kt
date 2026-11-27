package com.sonora.player.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.displayCutoutPadding
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sonora.player.container
import com.sonora.player.data.Song
import com.sonora.player.playback.PlayerConnection
import com.sonora.player.playback.PlayerState
import com.sonora.player.ui.components.AddToPlaylistDialog
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.EmptyState
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.SongActions
import com.sonora.player.ui.player.NowPlayingScreen
import com.sonora.player.ui.screens.AlbumScreen
import com.sonora.player.ui.screens.ArtistScreen
import com.sonora.player.ui.screens.CollectionScreen
import com.sonora.player.ui.screens.EqualizerScreen
import com.sonora.player.ui.screens.HomeScreen
import com.sonora.player.ui.screens.LibraryScreen
import com.sonora.player.ui.screens.PlaylistScreen
import com.sonora.player.ui.screens.SearchScreen
import com.sonora.player.ui.screens.SettingsScreen
import com.sonora.player.ui.theme.SonoraTheme
import com.sonora.player.ui.youtube.YouTubeScreen
import com.sonora.player.ui.youtube.YtBrowseScreen
import com.sonora.player.ui.youtube.YtLoginScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels { viewModelFactory { initializer { MainViewModel(container) } } }
    private val openPlayer = mutableStateOf(false)
    /** True while Sonora is the small Map-mode window (picture-in-picture). */
    private val inMapMode = mutableStateOf(false)

    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            when (i.action) {
                PIP_PREV -> container.playerConnection.previous()
                PIP_TOGGLE -> container.playerConnection.togglePlay()
                PIP_NEXT -> container.playerConnection.next()
            }
        }
    }

    private fun pipAction(action: String, icon: Int, title: String, code: Int) = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(this, icon), title, title,
        android.app.PendingIntent.getBroadcast(
            this, code, Intent(action).setPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun pipParams(): android.app.PictureInPictureParams {
        val playing = container.playerConnection.state.value.isPlaying
        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(1, 1))
            .setActions(
                listOf(
                    pipAction(PIP_PREV, com.composables.icons.lucide.R.drawable.lucide_ic_skip_back, "Previous", 1),
                    pipAction(PIP_TOGGLE, if (playing) com.composables.icons.lucide.R.drawable.lucide_ic_pause else com.composables.icons.lucide.R.drawable.lucide_ic_play, if (playing) "Pause" else "Play", 2),
                    pipAction(PIP_NEXT, com.composables.icons.lucide.R.drawable.lucide_ic_skip_forward, "Next", 3),
                ),
            )
            .apply { if (android.os.Build.VERSION.SDK_INT >= 31) setSeamlessResizeEnabled(true) }
            .build()
    }

    /** Map mode: shrink into a floating player so it sits on top of the navigation app. */
    private fun enterMapMode() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Leaving the app from Driving mode (e.g. to open Maps) switches to Map mode automatically.
        if (com.sonora.player.ui.drive.autoMapMode) enterMapMode()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inMapMode.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.playerConnection.attach(this)
        handleIntent(intent)
        vm.syncConnections()
        androidx.core.content.ContextCompat.registerReceiver(
            this, pipReceiver,
            android.content.IntentFilter().apply { addAction(PIP_PREV); addAction(PIP_TOGGLE); addAction(PIP_NEXT) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        com.sonora.player.ui.drive.enterMapMode = { enterMapMode() }
        // Keep the floating window's play/pause button in sync.
        lifecycleScope.launch {
            container.playerConnection.state.collect { st ->
                if (inMapMode.value) runCatching { setPictureInPictureParams(pipParams()) }
            }
        }
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            SonoraTheme(settings) {
                if (inMapMode.value) com.sonora.player.ui.drive.MapModeWindow(vm)
                else SonoraAppUi(vm, openPlayer)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        container.playerConnection.connect()
        container.output.refresh()
        if (vm.hasPermission.value != container.library.checkPermission()) vm.refreshLibrary()
    }

    override fun onDestroy() {
        // Release only this screen's claim; the service stops itself when paused and no screen needs it.
        container.playerConnection.detach(this)
        runCatching { unregisterReceiver(pipReceiver) }
        if (com.sonora.player.ui.drive.enterMapMode != null) com.sonora.player.ui.drive.enterMapMode = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer.value = true
        val uri: Uri? = intent.data
        // Spotify sign-in redirect (sonora://spotify-auth?code=…).
        if (uri?.scheme == "sonora" && uri.host == "spotify-auth") {
            vm.finishSpotify(uri)
            return
        }
        if (intent.action == Intent.ACTION_VIEW && uri != null) {
            lifecycleScope.launch {
                container.playerConnection.state.first { it.connected }
                container.playerConnection.playUri(uri)
                openPlayer.value = true
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        private const val PIP_PREV = "sonora.pip.PREV"
        private const val PIP_TOGGLE = "sonora.pip.TOGGLE"
        private const val PIP_NEXT = "sonora.pip.NEXT"
    }
}

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val EQUALIZER = "equalizer"
    const val YOUTUBE = "youtube"
    const val YT_LOGIN = "yt_login"
    const val LIKED = "liked"
    const val DOWNLOADS = "downloads"
    const val REPLAY = "replay"
    const val CONNECTIONS = "connections"
    const val DRIVE = "drive"
}

/** Navigation + shared actions handed to every screen. */
class UiActions(
    val vm: MainViewModel,
    private val nav: NavHostController,
    val song: SongActions,
    val addToPlaylist: (List<Song>) -> Unit,
    val openPlayer: () -> Unit,
) {
    fun album(id: Long) = nav.navigate("album/$id")
    fun artist(name: String) = nav.navigate("artist/${Uri.encode(name)}")
    fun genre(name: String) = nav.navigate("genre/${Uri.encode(name)}")
    fun folder(path: String) = nav.navigate("folder/${Uri.encode(path)}")
    fun playlist(id: Long) = nav.navigate("playlist/$id")
    fun equalizer() = nav.navigate(Routes.EQUALIZER)
    fun back() = nav.popBackStack()
    fun albumOf(song: Song) = album(song.albumId)
    fun yt(browseId: String) = nav.navigate("yt/${Uri.encode(browseId)}")
    fun ytLogin() = nav.navigate(Routes.YT_LOGIN)
    fun settings() = nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
    fun youtubeTab() = nav.navigate(Routes.YOUTUBE) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    fun searchYouTube() = tab(Routes.SEARCH)
    fun liked() = nav.navigate(Routes.LIKED)
    fun downloads() = nav.navigate(Routes.DOWNLOADS)
    fun replay() = nav.navigate(Routes.REPLAY)
    fun connections() = nav.navigate(Routes.CONNECTIONS)
    fun drive() = nav.navigate(Routes.DRIVE)
    fun libraryTab() = tab(Routes.LIBRARY)

    /**
     * Switches bottom-nav tab. Home always pops straight back to the root (restoring saved state for the start
     * destination could bring back whatever was stacked on it, e.g. Settings).
     */
    fun tab(route: String) {
        if (route == Routes.HOME) {
            if (!nav.popBackStack(Routes.HOME, inclusive = false)) nav.navigate(Routes.HOME)
            return
        }
        nav.navigate(route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: Int)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", L.drawable.lucide_ic_house),
    Tab(Routes.SEARCH, "Search", L.drawable.lucide_ic_search),
    Tab(Routes.LIBRARY, "Library", L.drawable.lucide_ic_library),
)

@Composable
fun SonoraAppUi(vm: MainViewModel, openPlayerState: MutableState<Boolean>) {
    val nav = rememberNavController()
    // Driving mode takes over the whole screen: no dock, no status-bar gap.
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    val heroRoutes = remember { setOf("playlist/{id}", "genre/{name}", "folder/{path}", "yt/{id}", "album/{id}", "artist/{name}") }
    val immersive = currentRoute == Routes.DRIVE
    // Home's sky runs up behind the status bar.
    val edgeToEdge = immersive || currentRoute == Routes.HOME || currentRoute == Routes.SEARCH || currentRoute == Routes.LIBRARY || currentRoute == Routes.SETTINGS || currentRoute == Routes.EQUALIZER || currentRoute == Routes.CONNECTIONS || currentRoute == Routes.LIKED || currentRoute == Routes.DOWNLOADS || currentRoute == Routes.REPLAY || currentRoute in heroRoutes
    val player by vm.player.collectAsStateWithLifecycle()
    val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var showPlayer by openPlayerState
    var playlistTarget by remember { mutableStateOf<List<Song>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val sheet = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(showPlayer, player.hasMedia) {
        sheet.animateTo(if (showPlayer && player.hasMedia) 1f else 0f, sheetSpring)
    }
    // Only flips when the sheet appears/disappears, so the shell doesn't recompose every animation frame.
    val sheetVisible by remember { androidx.compose.runtime.derivedStateOf { sheet.value > 0.001f } }
    // Fully open, the player hides everything underneath: skip drawing it and pause its animations.
    val covering = remember { androidx.compose.runtime.derivedStateOf { sheet.value >= 0.999f } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionsResult()
    }
    val requestPermissions = {
        val perms = buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) requestPermissions() else vm.refreshLibrary()
    }

    val songActions = remember(favorites) {
        SongActions(
            onPlayNext = vm::playNext,
            onAddToQueue = vm::addToQueue,
            onAddToPlaylist = { playlistTarget = listOf(it) },
            onToggleFavorite = { vm.toggleFavorite(it.id) },
            isFavorite = { it.id in favorites },
            onGoToAlbum = { nav.navigate("album/${it.albumId}") },
            onGoToArtist = { nav.navigate("artist/${Uri.encode(it.primaryArtist)}") },
        )
    }
    val actions = remember(nav, songActions) {
        UiActions(vm, nav, songActions, addToPlaylist = { playlistTarget = it }, openPlayer = { showPlayer = true })
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.sonora.player.ui.components.LocalIsPlaying provides player.isPlaying,
        com.sonora.player.ui.components.LocalNowPlayingId provides player.mediaId,
        com.sonora.player.ui.components.LocalFavorites provides favorites,
        com.sonora.player.ui.components.LocalPlayerCovering provides covering,
    ) {
    // Sideways, the app goes full-screen (status bar hidden, swipe down to peek), like video and music apps;
    // otherwise the clock and icons would sit on mismatched side margins.
    val landscapeNow = wideLandscape()
    val rootView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(landscapeNow) {
        (rootView.context as? android.app.Activity)?.window?.let { w ->
            val c = androidx.core.view.WindowCompat.getInsetsController(w, rootView)
            if (landscapeNow) {
                c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            } else {
                c.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val bg = MaterialTheme.colorScheme.background
    val glass = remember(bg) {
        dev.chrisbanes.haze.blur.HazeBlurStyle {
            backgroundColor(bg)
            colorEffects(listOf(dev.chrisbanes.haze.blur.HazeColorEffect.tint(bg.copy(alpha = 0.55f))))
            blurRadius(30.dp)
            noiseFactor(0.05f)
        }
    }
    Box(Modifier.fillMaxSize().background(bg)) {
        Scaffold(
            modifier = Modifier.graphicsLayer { alpha = if (covering.value) 0f else 1f },
            containerColor = bg,
            bottomBar = {
                // One floating glass dock (now playing + tabs); content scrolls underneath and shows through blurred.
                if (!immersive) Dock(nav, actions, player, vm.c.playerConnection, haze, glass, miniAlpha = { 1f - sheet.value }) { showPlayer = true }
            },
        ) { padding ->
            androidx.compose.runtime.CompositionLocalProvider(
                com.sonora.player.ui.components.LocalBottomBarPadding provides padding.calculateBottomPadding(),
            ) {
                // Only the top inset is applied: screens add the bar height to their own bottom padding.
                Box(Modifier.fillMaxSize().hazeSource(haze).padding(top = if (edgeToEdge) 0.dp else padding.calculateTopPadding())) {
                    if (!hasPermission) {
                        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()), contentAlignment = Alignment.Center) {
                            EmptyState(
                                L.drawable.lucide_ic_folder_open, "Allow access to your music",
                                "Sonora plays the songs stored on this phone. Nothing leaves your device.",
                            ) { com.sonora.player.ui.components.AppButton(onClick = requestPermissions) { Text("Allow access") } }
                        }
                    } else {
                        if (wideLandscape()) {
                            // Landscape: pages use the full width, edge to edge (a centred column, or padding for the
                            // camera hole on one side, left bare strips that cut off the sky, shelves and lists).
                            // Grids add columns instead of stretching (see isLandscapeWide).
                            Box(Modifier.fillMaxSize()) {
                                AppNavHost(nav, actions, Modifier.fillMaxSize())
                            }
                        } else {
                            AppNavHost(nav, actions, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        // Player sheet: one continuous animation driven by `sheet` (0 = closed, 1 = open), so dragging and
        // releasing just carries on from wherever your finger left it.
        if (sheetVisible) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                NowPlayingScreen(
                    vm, actions,
                    onCollapse = { showPlayer = false },
                    onDrag = { dy -> scope.launch { sheet.snapTo((sheet.value - dy / h).coerceIn(0f, 1f)) } },
                    onDragEnd = { velocity ->
                        if (sheet.value < 0.82f || velocity > 1800f) showPlayer = false
                        else scope.launch { sheet.animateTo(1f, sheetSpring) }
                    },
                    modifier = Modifier.graphicsLayer {
                        translationY = (1f - sheet.value) * h
                        val r = (1f - sheet.value) * 40.dp.toPx()
                        shape = RoundedCornerShape(r, r, 0f, 0f)
                        clip = sheet.value < 1f
                    },
                )
            }
        }
    }
    }

    BackHandler(enabled = showPlayer) { showPlayer = false }

    playlistTarget?.let { target ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { playlistTarget = null },
            onPick = { id -> vm.addToPlaylist(id, target.map { it.id }); playlistTarget = null },
            onCreate = { name -> vm.createPlaylist(name, target.map { it.id }); playlistTarget = null },
        )
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, actions: UiActions, modifier: Modifier) {
    val vm = actions.vm
    // Tabs swap with a quick cross-fade; pages pushed on top slide in from the right and back out (iOS-style).
    val tabRoutes = setOf(Routes.HOME, Routes.SEARCH, Routes.LIBRARY)
    fun androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.betweenTabs() =
        initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes
    val backEase = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
    NavHost(
        nav, startDestination = Routes.HOME, modifier = modifier,
        enterTransition = {
            if (betweenTabs()) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(110))
            else androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(240, easing = backEase)) { it }
        },
        exitTransition = {
            if (betweenTabs()) androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(80))
            else androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(240, easing = backEase)) { -it / 5 }
        },
        // Going back is quick and plain: the page slides off fast, and the one underneath just slides back
        // into place (no slow fade-in).
        popEnterTransition = {
            if (betweenTabs()) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(110))
            else androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220, easing = backEase)) { -it / 5 }
        },
        popExitTransition = {
            if (betweenTabs()) androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(80))
            else androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(220, easing = backEase)) { it }
        },
    ) {
        page(Routes.HOME) { HomeScreen(vm, actions) }
        page(Routes.LIBRARY) { LibraryScreen(vm, actions) }
        page(Routes.SEARCH) { SearchScreen(vm, actions) }
        page(Routes.SETTINGS) { SettingsScreen(vm, actions) }
        page(Routes.EQUALIZER) { EqualizerScreen(vm, actions) }
        page(Routes.YOUTUBE) { YouTubeScreen(vm, actions) }
        page(Routes.YT_LOGIN) { YtLoginScreen(vm, actions) }
        page(Routes.LIKED) { com.sonora.player.ui.screens.LikedScreen(vm, actions) }
        page(Routes.DOWNLOADS) { com.sonora.player.ui.screens.DownloadsScreen(vm, actions) }
        page(Routes.REPLAY) { com.sonora.player.ui.screens.ReplayScreen(vm, actions) }
        page(Routes.CONNECTIONS) { com.sonora.player.ui.screens.ConnectionsScreen(vm, actions) }
        page(Routes.DRIVE) { com.sonora.player.ui.drive.DriveScreen(vm, actions) }
        page("yt/{id}") { YtBrowseScreen(vm, it.arguments?.getString("id").orEmpty(), actions) }
        page("album/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            AlbumScreen(vm, it.arguments?.getLong("id") ?: 0, actions)
        }
        page("artist/{name}") { ArtistScreen(vm, it.arguments?.getString("name").orEmpty(), actions) }
        page("genre/{name}") { entry ->
            val name = entry.arguments?.getString("name").orEmpty()
            val genres by vm.genres.collectAsStateWithLifecycle()
            CollectionScreen(name, "Genre", genres.firstOrNull { it.name == name }?.songs.orEmpty(), actions)
        }
        page("folder/{path}") { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            val folders by vm.folders.collectAsStateWithLifecycle()
            val folder = folders.firstOrNull { it.path == path }
            CollectionScreen(folder?.name ?: path.substringAfterLast('/'), path, folder?.songs.orEmpty(), actions)
        }
        page("playlist/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            PlaylistScreen(vm, it.arguments?.getLong("id") ?: 0, actions)
        }
    }
}

// Stiff and critically damped: the player snaps open/closed in about a quarter of a second, no bounce.
private val sheetSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = 900f, visibilityThreshold = 0.002f)

/** Hairline rim that catches light along the top of glass capsules, like iOS. */
@Composable
private fun glassRim() = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
    ),
)

/** Phone held sideways (or a wide window): pages get a centred column and a slim dock. */
@Composable
private fun wideLandscape(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && c.screenWidthDp >= 600
}

/**
 * The bottom dock: one frosted-glass panel holding the now-playing row (when something is loaded) above the tabs.
 * A single surface instead of two stacked pills; the hairline between them doubles as the progress line.
 */
@Composable
private fun Dock(
    nav: NavHostController,
    actions: UiActions,
    state: PlayerState,
    connection: PlayerConnection,
    haze: dev.chrisbanes.haze.HazeState,
    glass: dev.chrisbanes.haze.blur.HazeBlurStyle,
    miniAlpha: () -> Float,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    if (wideLandscape()) {
        // Landscape: one slim bar (song on the left, tabs on the right) so the page keeps its height.
        Box(Modifier.fillMaxWidth().navigationBarsPadding().displayCutoutPadding().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 12.dp)
                    .shadow(12.dp, shape, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                    .clip(shape)
                    .hazeBlur(dev.chrisbanes.haze.HazeInput.Sources(haze), glass)
                    .border(0.6.dp, glassRim(), shape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (state.hasMedia) Box(Modifier.graphicsLayer { alpha = miniAlpha() }) { NowPlayingRow(state, connection, onOpen) }
                }
                Box(Modifier.width(260.dp)) { DockTabs(nav, actions) }
            }
        }
        return
    }
    Column(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .shadow(12.dp, shape, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
            .clip(shape)
            .hazeBlur(dev.chrisbanes.haze.HazeInput.Sources(haze), glass)
            .border(0.6.dp, glassRim(), shape)
            .animateContentSize(androidx.compose.animation.core.spring(stiffness = 500f)),
    ) {
        if (state.hasMedia) {
            Box(Modifier.graphicsLayer { alpha = miniAlpha() }) { NowPlayingRow(state, connection, onOpen) }
        }
        DockTabs(nav, actions)
    }
}

@Composable
private fun DockTabs(nav: NavHostController, actions: UiActions) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    var lastTab by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val idx = tabs.indexOfFirst { it.route == current }
    if (idx >= 0 && idx != lastTab) lastTab = idx
    val sel = lastTab
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp)) {
        tabs.forEachIndexed { i, tab ->
            val selected = i == sel
            val color by androidx.compose.animation.animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tabColor",
            )
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { actions.tab(tab.route) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                LIcon(tab.icon, tab.label, tint = color, size = 22.dp)
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label, color = color, style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingRow(state: PlayerState, connection: PlayerConnection, onOpen: () -> Unit) {
    val progress by produceState(0f, state.mediaId, state.isPlaying, state.durationMs) {
        while (true) {
            value = if (state.durationMs > 0) (connection.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            if (!state.isPlaying) break
            delay(500)
        }
    }
    val smooth by androidx.compose.animation.core.animateFloatAsState(progress, androidx.compose.animation.core.tween(500, easing = androidx.compose.animation.core.LinearEasing), label = "miniProgress")
    val accent = MaterialTheme.colorScheme.primary
    val palette by com.sonora.player.ui.player.rememberArtPalette(state.artUri, listOf(accent))
    val barTarget = remember(palette, accent) {
        val hsv = FloatArray(3)
        palette.firstOrNull { c ->
            android.graphics.Color.colorToHSV(android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
            hsv[1] > 0.22f && hsv[2] > 0.18f
        } ?: accent
    }
    val bar by androidx.compose.animation.animateColorAsState(barTarget, label = "miniBar")
    // Swipe left/right to change song, swipe up to open the player.
    var dragX by remember { mutableStateOf(0f) }
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX < -160f) connection.next() else if (dragX > 160f) connection.previous()
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { ch, dx -> ch.consume(); dragX += dx },
                )
            }
            .pointerInput(Unit) {
                var dy = 0f
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f },
                    onDragEnd = { if (dy < -50f) onOpen() },
                    onVerticalDrag = { ch, d -> ch.consume(); dy += d },
                )
            },
    ) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(start = 10.dp, end = 4.dp)
                .graphicsLayer { translationX = dragX * 0.35f; alpha = 1f - (kotlin.math.abs(dragX) / 700f).coerceAtMost(0.6f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(state.artUri, Modifier.size(42.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Text(
                    state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { connection.togglePlay() }) {
                LIcon(if (state.isPlaying) L.drawable.lucide_ic_pause else L.drawable.lucide_ic_play, "Play/pause", size = 24.dp)
            }
            IconButton(onClick = { connection.next() }) { LIcon(L.drawable.lucide_ic_skip_forward, "Next", size = 22.dp) }
        }
        // Divider between the song and the tabs; the played part is drawn in the artwork's colour.
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            val y = size.height / 2
            drawLine(androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.18f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
            if (smooth > 0f) {
                drawLine(bar, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * smooth, y), 2.dp.toPx())
            }
        }
    }
}

/** A navigation destination drawn on the page colour, so it covers the page underneath while sliding. */
private fun androidx.navigation.NavGraphBuilder.page(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(entry) }
}
