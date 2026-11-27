package com.sonora.player.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.zIndex
import androidx.compose.runtime.setValue
import androidx.compose.animation.togetherWith
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.Album
import com.sonora.player.data.SmartMix
import com.sonora.player.data.Track
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.SmartPlaylists
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.HeartRed
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.LocalNowPlayingId
import com.sonora.player.ui.components.MadeWithLove
import com.sonora.player.ui.components.NowPlayingArtwork
import com.sonora.player.ui.components.SectionHeader
import com.sonora.player.ui.youtube.YtCard
import com.sonora.player.ui.youtube.shelves
import com.sonora.player.youtube.hiResThumb
import java.util.Calendar
import com.composables.icons.lucide.R as L

@Composable
fun HomeScreen(vm: MainViewModel, actions: UiActions) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val account by vm.ytAccount.collectAsStateWithLifecycle()
    val signedIn by vm.ytSignedIn.collectAsStateWithLifecycle()
    val liked by vm.likedTracks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadFeed(); vm.refreshWeather(); vm.loadTrending() }
    val weather by vm.weather.collectAsStateWithLifecycle()
    val trending by vm.trending.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasLocation by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(vm.c.ambient.hasPermission()) }
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocation = granted; if (granted) vm.refreshWeather(force = true) }
    val firstName = account?.name?.takeIf { signedIn }?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        ?.lowercase()?.replaceFirstChar { it.uppercase() }?.takeIf { it.length in 2..14 }
    val onThisDay by vm.onThisDay.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadOnThisDay() }
    val profile = feed.profile
    val list = androidx.compose.foundation.lazy.rememberLazyListState()

    // White status-bar icons while the sky hero is on screen; the theme's own once it scrolls away.
    val view = androidx.compose.ui.platform.LocalView.current
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    LaunchedEffect(list, darkTheme) {
        // Re-apply after the page transition: the screen we came from resets the icons when it closes.
        launch {
            kotlinx.coroutines.delay(700)
            val overSky = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 600
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !overSky && !darkTheme
            }
        }
        androidx.compose.runtime.snapshotFlow { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 600 }
            .collect { overSky ->
                (view.context as? android.app.Activity)?.window?.let { w ->
                    androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !overSky && !darkTheme
                }
            }
    }

    PullToRefreshBox(
        isRefreshing = feed.loadingMixes && feed.ytHome != null,
        onRefresh = { vm.loadFeed(force = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            // ---- Sky hero: greeting, time, weather, air quality and Sky Mix; the sky follows the real sun.
            item(key = "sky") {
                SkyBanner(
                    weather = weather,
                    firstName = firstName,
                    avatar = account?.photo?.takeIf { signedIn },
                    hasLocation = hasLocation,
                    onAvatar = actions::settings,
                    onEnableWeather = { askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) },
                    onSkyMix = { label, query -> vm.playSkyMix(label, query) },
                    // Drawn above the rows below it, so its sunlight can spill over them.
                    modifier = Modifier.zIndex(1f),
                )
            }

            // ---- Quick access (Spotify-style grid), right under the sky
            item(key = "tiles") {
                val tiles = buildList<ShortcutItem> {
                    if (liked.isNotEmpty()) add(ShortcutItem("Liked songs", null, true) { actions.liked() })
                    add(ShortcutItem("Driving mode", null, icon = L.drawable.lucide_ic_car) { actions.drive() })
                    feed.ytPlaylists.take(4).forEach { p -> add(ShortcutItem(p.title, hiResThumb(p.thumbnail, 226)?.let(Uri::parse)) { actions.yt(p.browseId) }) }
                    if (!profile.isEmpty) add(ShortcutItem("Your Replay", null, replay = true) { actions.replay() })
                    if (songs.isNotEmpty()) add(ShortcutItem("Shuffle my phone", songs.firstOrNull()?.artUri) { vm.shuffleAll() })
                }.take(6)
                if (tiles.isNotEmpty()) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { ShortcutTile(it, Modifier.weight(1f)) }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // ---- Top picks
            val picks = buildList {
                feed.crossApp?.let { add("Across your apps" to it) }
                feed.dailyMixes.take(3).forEach { add("Made for you" to it) }
                feed.discover?.let { add("New to you" to it) }
            }
            if (picks.isNotEmpty()) {
                item { SectionHeader("Top picks") }
                item {
                    val pager = androidx.compose.foundation.pager.rememberPagerState { picks.size }
                    val dragging by pager.interactionSource.collectIsDraggedAsState()
                    // Gently rotates through your picks; pauses while you're touching it.
                    LaunchedEffect(pager, dragging, picks.size) {
                        if (dragging || picks.size < 2) return@LaunchedEffect
                        while (true) {
                            kotlinx.coroutines.delay(7_000)
                            pager.animateScrollToPage((pager.currentPage + 1) % picks.size, animationSpec = androidx.compose.animation.core.tween(700))
                        }
                    }
                    androidx.compose.foundation.pager.HorizontalPager(
                        pager,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        pageSpacing = 12.dp,
                        pageSize = androidx.compose.foundation.pager.PageSize.Fixed(236.dp),
                    ) { page ->
                        val (label, mix) = picks[page]
                        TopPickCard(label, mix) { vm.playTracks(mix.tracks) }
                    }
                }
            } else if (feed.loadingMixes && !profile.isEmpty) {
                item { LoadingRow() }
            }

            // ---- Recently played (Sonora + your YouTube Music history)
            if (feed.recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(feed.recent, key = { _, t -> "recent-${t.key}" }) { i, t -> TrackCard(t) { vm.playTracks(feed.recent, i) } }
                    }
                }
            }

            // ---- On this day: what you played a year ago
            if (onThisDay.isNotEmpty()) {
                item { SectionHeader("On this day, a year ago") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(onThisDay, key = { _, t -> "otd-${t.key}" }) { i, t -> TrackCard(t) { vm.playTracks(onThisDay, i) } }
                    }
                }
            }

            // ---- Fit my trip: a mix that ends when you arrive
            if (!profile.isEmpty) item(key = "trip") { FitTripCard(vm) }

            // ---- Top songs today (YouTube Music charts)
            if (trending.isNotEmpty()) {
                item(key = "trending") { TrendingSection(trending, vm) }
            }

            // ---- Made for you
            val made = feed.dailyMixes.drop(3)
            if (made.isNotEmpty()) {
                item { SectionHeader("More mixes for you") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(made, key = { it.id }) { m -> MixCard(m) { vm.playTracks(m.tracks) } }
                    }
                }
            }

            // ---- Because you listened to…
            feed.because?.let { mix ->
                item { SectionHeader(mix.subtitle) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(mix.tracks.take(20), key = { _, t -> t.key }) { i, t -> TrackCard(t) { vm.playTracks(mix.tracks, i) } }
                    }
                }
            }

            // ---- Your playlists (YouTube + phone)
            if (feed.ytPlaylists.isNotEmpty() || playlists.isNotEmpty()) {
                item {
                    SectionHeader("Your playlists") { com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = actions::libraryTab) { Text("Library") } }
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(feed.ytPlaylists, key = { "yt-${it.browseId}" }) { p -> YtCard(p, vm, actions) }
                        items(playlists, key = { "pl-${it.id}" }) { p ->
                            Column(Modifier.width(140.dp).clickable { actions.playlist(p.id) }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) { LIcon(L.drawable.lucide_ic_list_music, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 36.dp) }
                                Spacer(Modifier.height(6.dp))
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text("${p.songCount} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // ---- Taste-matched playlists
            feed.tasteShelves.forEach { shelf ->
                item(key = "taste-${shelf.title}") { SectionHeader(shelf.title) }
                item(key = "taste-row-${shelf.title}") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shelf.playlists, key = { it.browseId }) { p -> YtCard(p, vm, actions) }
                    }
                }
            }

            // ---- YouTube Music feed (Listen again, Quick picks, charts…)
            feed.ytHome?.let { page -> shelves(page.shelves, vm, actions, homeStyle = true) }
            if (feed.ytHome == null && feed.loadingMixes) item { LoadingRow() }
            if (feed.offline && feed.ytHome == null) {
                item {
                    Text(
                        "You're offline. Your phone's music is below, and online mixes will appear when you reconnect.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }

            // ---- On this phone
            if (albums.isNotEmpty()) {
                item {
                    SectionHeader("Recently added") {
                        com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = { actions.playlist(SmartPlaylists.RECENTLY_ADDED) }) { Text("See all") }
                    }
                }
                item { AlbumRow(albums.sortedByDescending { it.dateAddedSec }.take(16), actions) }
            }

            if (!signedIn) {
                item {
                    Surface(
                        onClick = actions::ytLogin,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            LIcon(L.drawable.lucide_ic_log_in, null, size = 22.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Sign in to YouTube Music", style = MaterialTheme.typography.titleMedium)
                                Text("Playlists, likes and history", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item { MadeWithLove() }
        }
        // Once the sky scrolls away, a frosted strip keeps the status bar readable over the content.
        val overSky by androidx.compose.runtime.remember {
            androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 600 }
        }
        val scrim by androidx.compose.animation.core.animateFloatAsState(if (overSky) 0f else 0.96f, label = "statusScrim")
        Box(
            Modifier.fillMaxWidth()
                .windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.background.copy(alpha = scrim)),
        )
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good evening"
}

internal class ShortcutItem(val title: String, val art: Uri?, val liked: Boolean = false, val replay: Boolean = false, val icon: Int? = null, val onClick: () -> Unit)

@Composable
internal fun ShortcutTile(item: ShortcutItem, modifier: Modifier) {
    Surface(
        onClick = item.onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.liked) {
                Box(
                    Modifier.size(56.dp).background(HeartRed),
                    contentAlignment = Alignment.Center,
                ) { LIcon(L.drawable.lucide_ic_heart, null, tint = Color.White, size = 24.dp) }
            } else if (item.icon != null) {
                Box(Modifier.size(56.dp).background(Color(0xFF1C2333)), contentAlignment = Alignment.Center) {
                    LIcon(item.icon, null, tint = Color.White, size = 24.dp)
                }
            } else if (item.replay) {
                Box(
                    Modifier.size(56.dp).background(com.sonora.player.ui.theme.Brand.Violet),
                    contentAlignment = Alignment.Center,
                ) { LIcon(L.drawable.lucide_ic_rotate_ccw, null, tint = Color.White, size = 24.dp) }
            } else {
                Artwork(item.art, Modifier.size(56.dp), RoundedCornerShape(0.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/** 2×2 artwork collage (or a single cover when there are fewer than four). */
@Composable
fun MixCollage(covers: List<Uri?>, modifier: Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val half = maxWidth / 2
        if (covers.size >= 4) {
            Column {
                Row { Artwork(covers[0], Modifier.size(half), RoundedCornerShape(0.dp)); Artwork(covers[1], Modifier.size(half), RoundedCornerShape(0.dp)) }
                Row { Artwork(covers[2], Modifier.size(half), RoundedCornerShape(0.dp)); Artwork(covers[3], Modifier.size(half), RoundedCornerShape(0.dp)) }
            }
        } else {
            Artwork(covers.firstOrNull(), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        }
    }
}

/**
 * Apple-Music-style "Top Picks" card: the whole card takes its colours from the mix's artwork, with a big collage,
 * bold title and a play button.
 */
@Composable
private fun TopPickCard(label: String, mix: SmartMix, onPlay: () -> Unit) {
    val fallback = listOf(com.sonora.player.ui.theme.Brand.Violet, com.sonora.player.ui.theme.Brand.Indigo, com.sonora.player.ui.theme.Brand.Pink, com.sonora.player.ui.theme.Brand.Coral)
    val colors by com.sonora.player.ui.player.rememberArtPalette(mix.covers.firstOrNull(), fallback)
    val top by androidx.compose.animation.animateColorAsState(androidx.compose.ui.graphics.lerp(colors.getOrElse(0) { fallback[0] }, Color.Black, 0.15f), label = "pickTop")
    val bottom by androidx.compose.animation.animateColorAsState(androidx.compose.ui.graphics.lerp(colors.getOrElse(1) { fallback[1] }, Color.Black, 0.55f), label = "pickBottom")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .clickable(onClick = onPlay)
            .padding(14.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(10.dp))
        MixCollage(
            mix.covers,
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mix.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mix.subtitle, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                LIcon(L.drawable.lucide_ic_play, "Play", tint = Color.Black, size = 18.dp)
            }
        }
    }
}


/** Mix cover: 2×2 artwork collage with a colour wash and the mix name. */
@Composable
fun MixCard(mix: SmartMix, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))) {
            val covers = mix.covers
            if (covers.size >= 4) {
                Column {
                    Row { Artwork(covers[0], Modifier.size(80.dp), RoundedCornerShape(0.dp)); Artwork(covers[1], Modifier.size(80.dp), RoundedCornerShape(0.dp)) }
                    Row { Artwork(covers[2], Modifier.size(80.dp), RoundedCornerShape(0.dp)); Artwork(covers[3], Modifier.size(80.dp), RoundedCornerShape(0.dp)) }
                }
            } else {
                Artwork(covers.firstOrNull(), Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(mix.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(mix.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TrackCard(track: Track, onClick: () -> Unit) {
    val current = LocalNowPlayingId.current == track.key
    Column(Modifier.width(140.dp).clickable(onClick = onClick)) {
        if (current) NowPlayingArtwork(track.art, Modifier.size(140.dp), RoundedCornerShape(12.dp))
        else Artwork(track.art, Modifier.size(140.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
}

@Composable
fun AlbumRow(albums: List<Album>, actions: UiActions) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.id }) { album -> AlbumCard(album, Modifier.width(140.dp)) { actions.album(album.id) } }
    }
}

@Composable
fun AlbumCard(album: Album, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Artwork(album.artUri, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(12.dp), L.drawable.lucide_ic_disc_3)
        Spacer(Modifier.height(6.dp))
        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        Text(
            album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** Today's top songs (YouTube Music charts) as a swipeable numbered chart, India or global. */
@Composable
private fun TrendingSection(charts: Map<String, List<com.sonora.player.youtube.YtSong>>, vm: MainViewModel) {
    var region by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(if (charts["IN"].isNullOrEmpty()) "ZZ" else "IN") }
    val list = charts[region].orEmpty()
    val nowPlaying = LocalNowPlayingId.current
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Top songs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val options = listOf("IN" to "India", "ZZ" to "Global").filter { !charts[it.first].isNullOrEmpty() }
            if (options.size > 1) {
                // iOS-style segmented control.
                Row(
                    Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)).padding(2.dp),
                ) {
                    options.forEach { (code, label) ->
                        val sel = region == code
                        Text(
                            label, style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (sel) 1f else 0.7f),
                            modifier = Modifier.clip(RoundedCornerShape(7.dp))
                                .then(if (sel) Modifier.shadow(1.dp, RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surface) else Modifier)
                                .clickable { region = code }
                                .padding(horizontal = 14.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedContent(
            list, label = "trending",
            transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) },
        ) { songs ->
            val rows = 4
            val colWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 52.dp
            androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Fixed(rows),
                modifier = Modifier.fillMaxWidth().height(62.dp * rows),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(songs.size, key = { songs[it].videoId }) { i ->
                    val s = songs[i]
                    val current = nowPlaying == s.mediaId
                    Row(
                        Modifier.width(colWidth).height(62.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.playYt(songs, i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(com.sonora.player.youtube.hiResThumb(s.thumbnail, 160)?.let(Uri::parse), Modifier.size(48.dp), RoundedCornerShape(6.dp))
                        Text(
                            "${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(34.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(s.artistNames, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (i % rows != rows - 1 && i != songs.lastIndex) {
                                Spacer(Modifier.height(10.dp))
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Fit my trip": pick how long your journey is and get a mix that ends right as you arrive. */
@Composable
private fun FitTripCard(vm: MainViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LIcon(L.drawable.lucide_ic_route, null, tint = MaterialTheme.colorScheme.primary, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Fit my trip", style = MaterialTheme.typography.titleMedium)
                Text("A mix of your music that ends right as you arrive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60, 90).forEach { m ->
                Text(
                    if (m < 60) "$m min" else "${m / 60}${if (m % 60 == 30) ".5" else ""} h",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface)
                        .clickable { vm.fitTrip(m) }.padding(vertical = 9.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
