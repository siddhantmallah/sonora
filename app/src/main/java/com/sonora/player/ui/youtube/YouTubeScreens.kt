package com.sonora.player.ui.youtube

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.statusBarsPadding
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.formatDuration
import com.sonora.player.ui.Load
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.EmptyState
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.MenuItem
import com.sonora.player.ui.components.SectionHeader
import com.sonora.player.ui.screens.DetailTopBar
import com.sonora.player.youtube.InnerTube
import com.sonora.player.youtube.YouTubeAuth
import com.sonora.player.youtube.YtBrowse
import com.sonora.player.youtube.YtCollection
import com.sonora.player.youtube.YtItem
import com.sonora.player.youtube.YtKind
import com.sonora.player.youtube.YtPage
import com.sonora.player.youtube.YtShelf
import com.sonora.player.youtube.YtSong
import com.sonora.player.youtube.hiResThumb
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

// ------------------------------------------------------------------ YouTube tab

@Composable
fun YouTubeScreen(vm: MainViewModel, actions: UiActions) {
    val home by vm.ytHome.collectAsStateWithLifecycle()
    val signedIn by vm.ytSignedIn.collectAsStateWithLifecycle()
    val account by vm.ytAccount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadYtHome() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YouTube Music", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (signedIn) "Signed in as ${account?.name ?: "your account"}" else "Browsing without an account",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = actions::searchYouTube) { LIcon(L.drawable.lucide_ic_search, "Search YouTube Music") }
                if (signedIn) {
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = actions::settings)) {
                        Artwork(account?.photo?.let(Uri::parse), Modifier.fillMaxSize(), CircleShape, L.drawable.lucide_ic_circle_user)
                    }
                } else {
                    com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = actions::ytLogin) { Text("Sign in") }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signedIn) {
                    item { Chip("Liked songs", L.drawable.lucide_ic_heart) { actions.yt(YtBrowse.LIKED_SONGS) } }
                    item { Chip("Playlists", L.drawable.lucide_ic_list_music) { actions.yt(YtBrowse.LIBRARY_PLAYLISTS) } }
                    item { Chip("Albums", L.drawable.lucide_ic_disc_3) { actions.yt(YtBrowse.LIBRARY_ALBUMS) } }
                    item { Chip("Artists", L.drawable.lucide_ic_mic_vocal) { actions.yt(YtBrowse.LIBRARY_ARTISTS) } }
                    item { Chip("History", L.drawable.lucide_ic_history) { actions.yt(YtBrowse.HISTORY) } }
                }
                item { Chip("Charts", L.drawable.lucide_ic_trending_up) { actions.yt(YtBrowse.CHARTS) } }
                item { Chip("Explore", L.drawable.lucide_ic_compass) { actions.yt(YtBrowse.EXPLORE) } }
            }
        }
        when (val h = home) {
            is Load.Loading -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            is Load.Error -> item {
                EmptyState(L.drawable.lucide_ic_wifi_off, "Couldn't load YouTube Music", h.message) {
                    com.sonora.player.ui.components.AppButton(onClick = { vm.loadYtHome(force = true) }) { Text("Try again") }
                }
            }
            is Load.Ready -> shelves(h.data.shelves, vm, actions, homeStyle = true)
        }
    }
}

@Composable
private fun Chip(label: String, icon: Int, onClick: () -> Unit) {
    com.sonora.player.ui.components.AppButton(onClick = onClick, small = true, solid = false) {
        LIcon(icon, null, size = 16.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

/** Renders a list of shelves: song shelves as a 4-row grid (home) or a vertical list, others as card rows. */
fun LazyListScope.shelves(shelves: List<YtShelf>, vm: MainViewModel, actions: UiActions, homeStyle: Boolean) {
    shelves.forEachIndexed { si, shelf ->
        if (shelf.title.isNotBlank()) {
            item(key = "h$si-${shelf.title}") {
                SectionHeader(shelf.title) {
                    shelf.moreBrowseId?.takeIf { it.isNotBlank() }?.let { id -> com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = { actions.yt(id) }) { Text("More") } }
                }
            }
        }
        if (shelf.isSongList) {
            val songs = shelf.songs
            if (homeStyle && songs.size > 4) {
                item(key = "g$si") {
                    LazyHorizontalGrid(
                        GridCells.Fixed(4),
                        modifier = Modifier.fillMaxWidth().height(64.dp * 4),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        items(songs, key = { it.videoId }) { s ->
                            YtSongRow(s, vm, actions, Modifier.width(320.dp)) { vm.startRadio(s) }
                        }
                    }
                }
            } else {
                itemsIndexed(songs, key = { i, s -> "s$si-$i-${s.videoId}" }) { i, s ->
                    YtSongRow(s, vm, actions) { vm.playYt(songs, i) }
                }
            }
        } else {
            item(key = "r$si") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shelf.items, key = { it.key }) { it -> YtCard(it, vm, actions) }
                }
            }
        }
    }
}

@Composable
fun YtCard(item: YtItem, vm: MainViewModel, actions: UiActions, modifier: Modifier = Modifier, fill: Boolean = false) {
    val round = item is YtCollection && item.kind == YtKind.ARTIST
    val wide = item is YtSong && item.isVideo
    val width = if (wide) 220.dp else 150.dp
    Column(
        (if (fill) modifier else modifier.width(width)).clickable {
            when (item) {
                is YtSong -> vm.startRadio(item)
                is YtCollection -> actions.yt(item.browseId)
            }
        },
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(
            hiResThumb(item.thumbnail)?.let(Uri::parse),
            Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f),
            if (round) CircleShape else RoundedCornerShape(12.dp),
            if (round) L.drawable.lucide_ic_mic_vocal else L.drawable.lucide_ic_disc_3,
        )
        Spacer(Modifier.height(6.dp))
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        if (item.subtitle.isNotBlank()) {
            Text(
                item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun YtSongRow(song: YtSong, vm: MainViewModel, actions: UiActions, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val favorites = com.sonora.player.ui.components.LocalFavorites.current
    val isCurrent = com.sonora.player.ui.components.LocalNowPlayingId.current == song.mediaId
    val dlStates by vm.downloadStates.collectAsStateWithLifecycle()
    val dlState = dlStates[song.videoId]
    val downloaded = dlState == com.sonora.player.playback.DlState.DONE
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val art = hiResThumb(song.thumbnail, 226)?.let(Uri::parse)
        if (isCurrent) com.sonora.player.ui.components.NowPlayingArtwork(art, Modifier.size(48.dp), RoundedCornerShape(8.dp))
        else Artwork(art, Modifier.size(48.dp), RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dlState != null) {
                    LIcon(
                        if (downloaded) L.drawable.lucide_ic_circle_arrow_down else L.drawable.lucide_ic_loader_circle, if (downloaded) "Downloaded" else "Downloading",
                        tint = MaterialTheme.colorScheme.primary, size = 12.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (com.sonora.player.youtube.YtIds.statsId(song.mediaId) in favorites) {
                    com.sonora.player.ui.components.SmallHeart()
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    (if (song.isVideo) "Video • " else "") + song.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (song.durationSec > 0) {
            Spacer(Modifier.width(10.dp))
            Text(formatDuration(song.durationSec * 1000L), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { LIcon(L.drawable.lucide_ic_ellipsis_vertical, "More", size = 20.dp) }
            com.sonora.player.ui.components.AppMenu(menu, { menu = false }) {
                MenuItem("Start radio", L.drawable.lucide_ic_radio) { menu = false; vm.startRadio(song) }
                MenuItem("Play next", L.drawable.lucide_ic_list_start) { menu = false; vm.playNextYt(song) }
                MenuItem("Add to queue", L.drawable.lucide_ic_list_end) { menu = false; vm.addToQueueYt(song) }
                MenuItem(if (downloaded) "Remove download" else "Download", L.drawable.lucide_ic_download) {
                    menu = false
                    if (downloaded) vm.downloads.remove(song.videoId) else vm.downloads.download(listOf(song))
                }
                val liked = com.sonora.player.youtube.YtIds.statsId(song.mediaId) in favorites
                MenuItem(if (liked) "Unlike" else "Like", L.drawable.lucide_ic_heart) { menu = false; vm.toggleYtFavorite(song) }
                song.artists.firstOrNull { it.browseId != null }?.let { a ->
                    MenuItem("Go to artist", L.drawable.lucide_ic_mic_vocal) { menu = false; actions.yt(a.browseId!!) }
                }
                song.album?.browseId?.let { id -> MenuItem("Go to album", L.drawable.lucide_ic_disc_3) { menu = false; actions.yt(id) } }
                MenuItem("Share", L.drawable.lucide_ic_share_2) {
                    menu = false
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${song.videoId}")
                    context.startActivity(Intent.createChooser(send, song.title))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ album / playlist / artist page

@Composable
fun YtBrowseScreen(vm: MainViewModel, browseId: String, actions: UiActions) {
    var state by remember(browseId) { mutableStateOf<Load<YtPage>>(Load.Loading) }
    val extra = remember(browseId) { mutableStateListOf<YtSong>() }
    var token by remember(browseId) { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val isArtist = browseId.startsWith("UC")

    LaunchedEffect(browseId) {
        state = runCatching { vm.yt.browse(browseId) }.fold({ token = it.continuation; Load.Ready(it) }, { Load.Error(it.message ?: "Couldn't load") })
    }
    // Infinite scroll for long playlists (e.g. thousands of liked songs).
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 6 } == true } }
    LaunchedEffect(nearEnd, token) {
        val t = token
        if (nearEnd && t != null && !loadingMore && !isArtist) {
            loadingMore = true
            runCatching { vm.yt.more(t) }.onSuccess { more ->
                extra += more.allSongs
                token = more.continuation?.takeIf { it != t }
            }.onFailure { token = null }
            loadingMore = false
        }
    }

    val ready = (state as? Load.Ready)?.data
    if (ready != null) {
        // Playlists, albums and artists: Home-style hero with the cover (round for artists), then the content.
        val page = ready
        val songs = (page.allSongs + extra).distinctBy { it.videoId }
        val tracks = remember(songs.size) { songs.map { com.sonora.player.data.Track.Online(it) } }
        val cover = hiResThumb(page.thumbnail, 720)?.let(Uri::parse)
        com.sonora.player.ui.screens.HeroStatusBar(list)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
                item(key = "hero") {
                    com.sonora.player.ui.screens.CollectionHero(
                        tracks, page.title ?: titleFor(browseId),
                        page.subtitle ?: if (isArtist) "Artist" else if (songs.isNotEmpty()) "${songs.size} songs" else {
                            val n = page.shelves.sumOf { it.items.size }
                            when (browseId) {
                                YtBrowse.LIBRARY_ARTISTS -> "$n artists"
                                YtBrowse.LIBRARY_ALBUMS -> "$n albums"
                                YtBrowse.LIBRARY_PLAYLISTS -> "$n playlists"
                                else -> "$n items"
                            }
                        },
                        com.sonora.player.ui.screens.heroColors(cover, listOf(Color(0xFF3B2F8F), Color(0xFFB4235A))), actions,
                        badge = { LIcon(L.drawable.lucide_ic_list_music, null, tint = Color.White, size = 22.dp) },
                        cover = cover,
                        coverShape = if (isArtist) CircleShape else RoundedCornerShape(14.dp),
                        topActions = if (songs.isEmpty()) null else ({
                            com.sonora.player.ui.screens.HeroCircle(L.drawable.lucide_ic_radio, "Start radio") { vm.startRadio(songs.first()) }
                            if (!isArtist) com.sonora.player.ui.screens.HeroCircle(L.drawable.lucide_ic_download, "Download all") { vm.downloads.download(songs) }
                        }),
                        onPlay = { vm.playYt(songs) }, onShuffle = { vm.smartShuffle(tracks) },
                    )
                }
                page.description?.let { d ->
                    item {
                        var expanded by remember { mutableStateOf(false) }
                        Text(
                            d, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).clickable { expanded = !expanded },
                        )
                    }
                }
                if (!isArtist && songs.size >= 6) {
                    item { com.sonora.player.ui.components.SectionHeader("Artists in here") }
                    item { com.sonora.player.ui.screens.ArtistsRow(tracks, { if (it == 1) "1 song" else "$it songs" }) { t -> vm.playTracks(t) } }
                }
                // The header already says how many tracks; this list may still be loading more.
                if (!isArtist && songs.isNotEmpty()) item { com.sonora.player.ui.components.SectionHeader("Songs") }
                if (browseId.startsWith("FE")) {
                    page.shelves.forEachIndexed { si, shelf ->
                        if (shelf.title.isNotBlank()) item(key = "lh$si") { com.sonora.player.ui.components.SectionHeader(shelf.title) }
                        if (shelf.isSongList) {
                            itemsIndexed(shelf.songs, key = { i, x -> "ls$si-$i-${x.videoId}" }) { i, x -> YtSongRow(x, vm, actions) { vm.playYt(shelf.songs, i) } }
                        } else {
                            val cols = if (shelf.items.all { it is YtCollection && it.kind == YtKind.ARTIST }) 3 else 2
                            val rows = shelf.items.chunked(cols)
                            items(rows.size, key = { r -> "lg$si-$r" }) { r ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    rows[r].forEach { YtCard(it, vm, actions, Modifier.weight(1f), fill = true) }
                                    repeat(cols - rows[r].size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                } else {
                    shelves(page.shelves, vm, actions, homeStyle = false)
                }
                if (extra.isNotEmpty()) {
                    itemsIndexed(extra, key = { i, s -> "x$i-${s.videoId}" }) { i, s ->
                        YtSongRow(s, vm, actions) { vm.playYt(songs, songs.indexOfFirst { it.videoId == s.videoId }.coerceAtLeast(0)) }
                    }
                }
                if (loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                if (page.shelves.isEmpty()) {
                    item { EmptyState(L.drawable.lucide_ic_list_music, "Nothing here yet", "This list is empty on YouTube Music.") }
                }
            }
            val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
            com.sonora.player.ui.screens.StatusBarScrim(scrolled)
        }
        return
    }

    // Artists, and while loading: the plain page (this route draws under the status bar, so pad for it).
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        DetailTopBar(actions::back)
        when (val s = state) {
            is Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Load.Error -> EmptyState(L.drawable.lucide_ic_wifi_off, "Couldn't load this page", s.message) {
                com.sonora.player.ui.components.AppButton(onClick = { state = Load.Loading; scope.launch { state = runCatching { vm.yt.browse(browseId) }.fold({ Load.Ready(it) }, { Load.Error(it.message ?: "") }) } }) { Text("Retry") }
            }
            is Load.Ready -> {
                val page = s.data
                val songs = (page.allSongs + extra).distinctBy { it.videoId }
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (page.thumbnail != null) {
                                Artwork(
                                    hiResThumb(page.thumbnail, 720)?.let(Uri::parse),
                                    Modifier.size(if (isArtist) 200.dp else 230.dp),
                                    if (isArtist) CircleShape else RoundedCornerShape(20.dp),
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                            Text(page.title ?: titleFor(browseId), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            page.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                            page.description?.let { d ->
                                var expanded by remember { mutableStateOf(false) }
                                Text(
                                    d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp).clickable { expanded = !expanded },
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    if (songs.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                com.sonora.player.ui.components.AppButton(onClick = { vm.playYt(songs) }, modifier = Modifier.weight(1f)) {
                                    LIcon(L.drawable.lucide_ic_play, null, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("Play")
                                }
                                com.sonora.player.ui.components.AppButton(solid = false, onClick = { vm.smartShuffle(songs.map { com.sonora.player.data.Track.Online(it) }) }, modifier = Modifier.weight(1f)) {
                                    LIcon(L.drawable.lucide_ic_shuffle, null, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("Shuffle", maxLines = 1)
                                }
                                IconButton(onClick = { vm.startRadio(songs.first()) }) { LIcon(L.drawable.lucide_ic_radio, "Start radio", size = 20.dp) }
                                IconButton(onClick = { vm.downloads.download(songs) }) { LIcon(L.drawable.lucide_ic_download, "Download all", size = 20.dp) }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    shelves(page.shelves, vm, actions, homeStyle = false)
                    if (extra.isNotEmpty()) {
                        itemsIndexed(extra, key = { i, s -> "x$i-${s.videoId}" }) { i, s ->
                            YtSongRow(s, vm, actions) { vm.playYt(songs, songs.indexOfFirst { it.videoId == s.videoId }.coerceAtLeast(0)) }
                        }
                    }
                    if (loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    if (page.shelves.isEmpty()) {
                        item { EmptyState(L.drawable.lucide_ic_list_music, "Nothing here yet", "This list is empty on YouTube Music.") }
                    }
                }
            }
        }
    }
}

private fun titleFor(id: String) = when (id) {
    YtBrowse.LIKED_SONGS -> "Liked songs"
    YtBrowse.LIBRARY_PLAYLISTS -> "Your playlists"
    YtBrowse.LIBRARY_ALBUMS -> "Your albums"
    YtBrowse.LIBRARY_ARTISTS -> "Your artists"
    YtBrowse.HISTORY -> "History"
    YtBrowse.CHARTS -> "Charts"
    YtBrowse.EXPLORE -> "Explore"
    else -> "YouTube Music"
}

// ------------------------------------------------------------------ sign in

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YtLoginScreen(vm: MainViewModel, actions: UiActions) {
    var done by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(actions::back)
        Text(
            "Sign in with Google. Sonora keeps only your music.youtube.com session on this phone and never sees your password.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (done || !url.startsWith(InnerTube.ORIGIN)) return
                            val cookie = CookieManager.getInstance().getCookie(InnerTube.ORIGIN)
                            if (YouTubeAuth.hasSession(cookie)) {
                                done = true
                                vm.yt.onSignedIn(cookie)
                                actions.back()
                            }
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=" + Uri.encode("${InnerTube.ORIGIN}/"))
                }
            },
        )
    }
}

// ------------------------------------------------------------------ search results

@Composable
fun YtSearchResults(vm: MainViewModel, actions: UiActions, page: YtPage) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        shelves(page.shelves, vm, actions, homeStyle = false)
    }
}
