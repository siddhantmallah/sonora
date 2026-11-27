package com.sonora.player.ui.screens

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.background
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.SettingsRepository
import com.sonora.player.data.Song
import com.sonora.player.data.SongSort
import com.sonora.player.data.formatLongDuration
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.SmartPlaylists
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.EmptyState
import com.sonora.player.ui.components.LetterRail
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.MenuItem
import com.sonora.player.ui.components.PlayShuffleRow
import com.sonora.player.ui.components.SongActions
import com.sonora.player.ui.components.SongRow
import com.sonora.player.ui.components.TextInputDialog
import com.sonora.player.ui.components.dragHandle
import com.sonora.player.ui.components.rememberDragReorder
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

private val libraryTabs = listOf("Insights", "Playlists", "Songs", "Albums", "Artists", "Genres", "Folders")

@Composable
fun LibraryScreen(vm: MainViewModel, actions: UiActions) {
    val pager = rememberPagerState(initialPage = 1) { libraryTabs.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        AmbientHeader(vm, actions, "Library") {
            PillTabs(libraryTabs.mapIndexed { i, l -> i to l }, pager.currentPage) { i -> scope.launch { pager.quickScrollTo(i) } }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            // Neighbouring tabs are built ahead of time; tell them whether they're actually showing so their
            // animations only run when you can see them.
            val onScreen = remember(page) {
                androidx.compose.runtime.derivedStateOf { kotlin.math.abs(pager.currentPage - page + pager.currentPageOffsetFraction) < 1f }
            }
            androidx.compose.runtime.CompositionLocalProvider(com.sonora.player.ui.components.LocalOnScreen provides onScreen) {
            when (page) {
                0 -> InsightsTab(vm, actions)
                1 -> PlaylistsTab(vm, actions)
                2 -> SongsTab(vm, actions)
                3 -> AlbumsTab(vm, actions)
                4 -> ArtistsTab(vm, actions)
                5 -> GenresTab(vm, actions)
                else -> FoldersTab(vm, actions)
            }
            }
        }
    }
}

@Composable
private fun SongsTab(vm: MainViewModel, actions: UiActions) {
    val songs by vm.sortedSongs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Letter index for the A–Z rail (only meaningful for alphabetical sorts).
    val keyOf: (Song) -> String = when (settings.songSort) {
        SongSort.ARTIST -> { s -> s.artist }
        SongSort.ALBUM -> { s -> s.album }
        else -> { s -> s.title }
    }
    val recent = remember(songs) { songs.sortedByDescending { it.dateAddedSec }.take(12) }
    // Items above the song list: hero, (recently added header + row), "All songs" header.
    val before = 1 + (if (recent.size >= 4) 2 else 0) + 1
    val firstIndexByLetter = remember(songs, settings.songSort, before) {
        val m = LinkedHashMap<Char, Int>()
        songs.forEachIndexed { i, s -> m.putIfAbsent(com.sonora.player.ui.components.indexLetter(keyOf(s)), i + before) }
        m
    }
    val alphabetical = settings.songSort == SongSort.TITLE || settings.songSort == SongSort.ARTIST || settings.songSort == SongSort.ALBUM

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        item(key = "hero") {
            val hours = songs.sumOf { it.durationMs } / 3_600_000.0
            TabHero(
                arts = recent.map { it.artUri }, label = "ON THIS PHONE", big = "${songs.size}", unit = if (songs.size == 1) "song" else "songs",
                line = listOf(
                    if (hours >= 1) "%.0f hours".format(hours) else "${songs.sumOf { it.durationMs } / 60_000} min",
                    "${songs.map { it.artist }.distinct().size} artists", "${songs.map { it.albumId }.distinct().size} albums",
                ).joinToString(" · "),
                primary = "Shuffle all" to { vm.shuffle(songs) }, primaryIcon = L.drawable.lucide_ic_shuffle,
                secondary = "Play" to { vm.play(songs) }, secondaryIcon = L.drawable.lucide_ic_play,
            )
        }
        if (recent.size >= 4) {
            item(key = "recent-h") { com.sonora.player.ui.components.SectionHeader("Recently added") }
            item(key = "recent") {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recent, key = { _, s -> "ra-${s.id}" }) { i, s -> TrackCard(com.sonora.player.data.Track.Local(s)) { vm.play(recent, i) } }
                }
            }
        }
        item(key = "all-h") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("All songs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = { sortMenu = true }) {
                        LIcon(L.drawable.lucide_ic_arrow_down_up, null, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(settings.songSort.label)
                    }
                    com.sonora.player.ui.components.AppMenu(sortMenu, { sortMenu = false }) {
                        SongSort.entries.forEach { s ->
                            MenuItem(s.label, if (s == settings.songSort) L.drawable.lucide_ic_check else L.drawable.lucide_ic_dot) {
                                vm.c.settings.set(SettingsRepository.Keys.songSort, s.name)
                                sortMenu = false
                            }
                        }
                    }
                }
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(
                song, { vm.play(songs, i) }, actions.song, isCurrent = player.songId == song.id,
                modifier = if (alphabetical) Modifier.padding(end = 18.dp) else Modifier,
            )
        }
    }
    // The A–Z rail only appears once you're in the song list, so it never covers the hero card.
    val inList by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex >= before - 1 } }
    androidx.compose.animation.AnimatedVisibility(
        visible = alphabetical && inList,
        enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        LetterRail(firstIndexByLetter.keys.toList(), listState) { letter -> firstIndexByLetter[letter] }
    }
    }
}

@Composable
private fun AlbumsTab(vm: MainViewModel, actions: UiActions) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = com.sonora.player.ui.components.bottomBarSpace(16.dp)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            run {
                TabHero(inset = 0.dp, 
                    arts = albums.shuffled(java.util.Random(albums.size.toLong())).take(6).map { it.artUri },
                    label = "ON THIS PHONE", big = "${albums.size}", unit = if (albums.size == 1) "album" else "albums",
                    line = "${albums.map { it.artist }.distinct().size} artists · ${albums.sumOf { it.songs.size }} songs",
                    primary = "Play a random album" to { albums.randomOrNull()?.let { vm.play(it.songs) } }, primaryIcon = L.drawable.lucide_ic_disc_3,
                    secondary = "Shuffle all" to { vm.shuffle(albums.flatMap { it.songs }) }, secondaryIcon = L.drawable.lucide_ic_shuffle,
                )
            }
        }
        item(key = "all-h", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Text("All albums", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        }
        items(albums, key = { it.id }) { album -> AlbumCard(album) { actions.album(album.id) } }
    }
}

private val gridFull: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
    { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }

@Composable
private fun ArtistsTab(vm: MainViewModel, actions: UiActions) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val top = remember(artists) { artists.sortedByDescending { it.songs.size }.take(10) }
    LazyVerticalGrid(
        GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = com.sonora.player.ui.components.bottomBarSpace(16.dp)),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero", span = gridFull) {
            TabHero(
                arts = top.mapNotNull { it.artUri }, label = "ON THIS PHONE", big = "${artists.size}",
                unit = if (artists.size == 1) "artist" else "artists",
                line = "${artists.sumOf { it.songs.size }} songs · ${artists.sumOf { it.albums.size }} albums",
                primary = "Shuffle all" to { vm.shuffle(artists.flatMap { it.songs }) }, primaryIcon = L.drawable.lucide_ic_shuffle,
                secondary = top.firstOrNull()?.let { t -> "Play ${t.name}" to { vm.play(t.songs) } }, secondaryIcon = L.drawable.lucide_ic_mic_vocal,
                inset = 0.dp,
            )
        }
        if (top.size >= 3) {
            item(key = "top-h", span = gridFull) { Text("Most on your phone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
            item(key = "top", span = gridFull) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(top.size, key = { "t-" + top[it].name }) { i ->
                        val a = top[i]
                        Column(Modifier.width(132.dp).clickable { actions.artist(a.name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(a.artUri, Modifier.size(132.dp), CircleShape, L.drawable.lucide_ic_mic_vocal)
                            Spacer(Modifier.height(8.dp))
                            Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(songCount(a.songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item(key = "all-h", span = gridFull) { Text("All artists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        items(artists, key = { it.name }) { a ->
            Column(Modifier.clickable { actions.artist(a.name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(a.artUri, Modifier.fillMaxWidth().aspectRatio(1f), CircleShape, L.drawable.lucide_ic_mic_vocal)
                Spacer(Modifier.height(6.dp))
                Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(songCount(a.songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A colour for a name that stays the same every time (for genre tiles). */
private fun tint(name: String): Color {
    val palette = listOf(0xFF7C3AED, 0xFFB4235A, 0xFF0E7C86, 0xFF2B4FD8, 0xFFC2410C, 0xFF15803D, 0xFF9D174D, 0xFF4338CA)
    return Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
}

@Composable
private fun GenresTab(vm: MainViewModel, actions: UiActions) {
    val genres by vm.genres.collectAsStateWithLifecycle()
    if (genres.isEmpty()) {
        EmptyState(L.drawable.lucide_ic_tags, "No genres", "Genre tags are read from your files on Android 11 and newer.")
        return
    }
    val sorted = remember(genres) { genres.sortedByDescending { it.songs.size } }
    LazyVerticalGrid(
        GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = com.sonora.player.ui.components.bottomBarSpace(16.dp)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero", span = gridFull) {
            TabHero(
                arts = sorted.mapNotNull { it.songs.firstOrNull()?.artUri }, label = "ON THIS PHONE", big = "${genres.size}",
                unit = if (genres.size == 1) "genre" else "genres",
                line = "Biggest: " + sorted.take(3).joinToString(", ") { it.name },
                primary = "Shuffle all" to { vm.shuffle(genres.flatMap { it.songs }.distinctBy { it.id }) }, primaryIcon = L.drawable.lucide_ic_shuffle,
                secondary = sorted.firstOrNull()?.let { g -> "Play ${g.name}" to { vm.play(g.songs) } }, secondaryIcon = L.drawable.lucide_ic_tags,
                inset = 0.dp,
            )
        }
        item(key = "all-h", span = gridFull) { Text("All genres", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        items(sorted, key = { it.name }) { g ->
            // A colour tile per genre with its album art tucked in the corner, like Search's mood cards.
            Box(
                Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(16.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(tint(g.name), tint(g.name).copy(alpha = 0.75f))))
                    .clickable { actions.genre(g.name) },
            ) {
                Artwork(
                    g.songs.firstOrNull()?.artUri,
                    Modifier.size(64.dp).align(Alignment.BottomEnd).offset(x = 12.dp, y = 8.dp)
                        .graphicsLayer { rotationZ = 18f; shadowElevation = 8.dp.toPx(); shape = RoundedCornerShape(8.dp); clip = true },
                    RoundedCornerShape(8.dp), L.drawable.lucide_ic_tags,
                )
                Column(Modifier.padding(12.dp)) {
                    Text(g.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(songCount(g.songs.size), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FoldersTab(vm: MainViewModel, actions: UiActions) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    var hiding by remember { mutableStateOf<String?>(null) }
    val sorted = remember(folders) { folders.sortedByDescending { it.songs.size } }
    LazyVerticalGrid(
        GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = com.sonora.player.ui.components.bottomBarSpace(16.dp)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero", span = gridFull) {
            TabHero(
                arts = sorted.mapNotNull { it.songs.firstOrNull()?.artUri }, label = "ON THIS PHONE", big = "${folders.size}",
                unit = if (folders.size == 1) "folder" else "folders",
                line = "${folders.sumOf { it.songs.size }} songs",
                primary = "Shuffle all" to { vm.shuffle(folders.flatMap { it.songs }) }, primaryIcon = L.drawable.lucide_ic_shuffle,
                inset = 0.dp,
            )
        }
        item(key = "all-h", span = gridFull) { Text("All folders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
        items(sorted, key = { it.path }) { f ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { actions.folder(f.path) }.padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    // The folder's own music, as a small 2×2 cover stack.
                    val covers = f.songs.map { it.artUri }.distinct().take(4)
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        if (covers.size >= 4) {
                            Column { covers.chunked(2).forEach { r -> Row { r.forEach { c -> Artwork(c, Modifier.size(28.dp), RoundedCornerShape(0.dp)) } } } }
                        } else if (covers.isNotEmpty()) {
                            Artwork(covers.first(), Modifier.fillMaxSize(), RoundedCornerShape(10.dp), L.drawable.lucide_ic_folder)
                        } else LIcon(L.drawable.lucide_ic_folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).clickable { hiding = f.path },
                        contentAlignment = Alignment.Center,
                    ) { LIcon(L.drawable.lucide_ic_eye_off, "Hide folder", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp) }
                }
                Spacer(Modifier.height(10.dp))
                Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(songCount(f.songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    f.path.removePrefix("/storage/emulated/0/"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    hiding?.let { path ->
        com.sonora.player.ui.components.AppDialog(
            onDismissRequest = { hiding = null },
            title = { Text("Hide “${path.substringAfterLast('/')}”?") },
            text = { Text("Songs in this folder and its subfolders won't appear in Sonora. Files are not deleted. Undo any time in Settings → Hidden folders.") },
            confirmButton = { com.sonora.player.ui.components.AppButton(small = true, onClick = { vm.c.settings.excludeFolder(path, true); hiding = null }) { Text("Hide") } },
            dismissButton = { com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = { hiding = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlaylistsTab(vm: MainViewModel, actions: UiActions) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val signedIn by vm.ytSignedIn.collectAsStateWithLifecycle()
    val liked by vm.likedTracks.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadFeed() }
    var creating by remember { mutableStateOf(false) }
    val smart = listOf(
        SmartPlaylists.MOST_PLAYED to L.drawable.lucide_ic_trending_up,
        SmartPlaylists.RECENTLY_PLAYED to L.drawable.lucide_ic_history,
        SmartPlaylists.RECENTLY_ADDED to L.drawable.lucide_ic_sparkles,
        SmartPlaylists.NEVER_PLAYED to L.drawable.lucide_ic_compass,
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        item {
            // Same quick-access tiles as Home.
            val dls by vm.downloadedTracks.collectAsStateWithLifecycle()
            val tiles = listOf(
                ShortcutItem(if (liked.isEmpty()) "Liked songs" else "Liked songs · ${liked.size}", null, liked = true) { actions.liked() },
                ShortcutItem(if (dls.isEmpty()) "Downloads" else "Downloads · ${dls.size}", null, icon = L.drawable.lucide_ic_circle_arrow_down) { actions.downloads() },
                ShortcutItem("Your Replay", null, replay = true) { actions.replay() },
                ShortcutItem("New playlist", null, icon = L.drawable.lucide_ic_plus) { creating = true },
            )
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { ShortcutTile(it, Modifier.weight(1f)) } }
                }
            }
        }
        if (signedIn) {
            item {
                com.sonora.player.ui.components.SectionHeader("YouTube Music")
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Albums" to com.sonora.player.youtube.YtBrowse.LIBRARY_ALBUMS,
                        "Artists" to com.sonora.player.youtube.YtBrowse.LIBRARY_ARTISTS,
                        "History" to com.sonora.player.youtube.YtBrowse.HISTORY,
                    ).forEach { (label, id) ->
                        Text(
                            label, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { actions.yt(id) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // Your YouTube Music playlists as big cards, like Home's rows.
            if (feed.ytPlaylists.isNotEmpty()) item {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(feed.ytPlaylists, key = { "yt-${it.browseId}" }) { p ->
                        Column(Modifier.width(150.dp).clickable { actions.yt(p.browseId) }) {
                            Artwork(
                                com.sonora.player.youtube.hiResThumb(p.thumbnail, 400)?.let(Uri::parse),
                                Modifier.size(150.dp), RoundedCornerShape(12.dp), L.drawable.lucide_ic_list_music,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(p.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                p.subtitle.ifBlank { "YouTube Music playlist" }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else {
            item {
                ListRow("Connect YouTube Music", "Sign in to see your playlists here", null, fallback = L.drawable.lucide_ic_log_in) { actions.ytLogin() }
            }
        }

        // Smart playlists as colour tiles.
        item { com.sonora.player.ui.components.SectionHeader("Made for you") }
        item {
            val tints = listOf(listOf(Color(0xFFB4235A), Color(0xFFFF7A59)), listOf(Color(0xFF2B4FD8), Color(0xFF7C3AED)),
                listOf(Color(0xFF0E7C86), Color(0xFF34C759)), listOf(Color(0xFF4338CA), Color(0xFF0EA5E9)))
            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(smart, key = { _, it -> "smart-${it.first}" }) { i, (id, icon) ->
                    Column(Modifier.width(150.dp).clickable { actions.playlist(id) }) {
                        Box(
                            Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(tints[i % tints.size])),
                        ) {
                            LIcon(icon, null, tint = Color.White.copy(alpha = 0.9f), size = 34.dp, modifier = Modifier.align(Alignment.TopStart).padding(14.dp))
                            Text(
                                SmartPlaylists.name(id), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                maxLines = 2, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                            )
                        }
                    }
                }
            }
        }

        // Your own playlists.
        item {
            com.sonora.player.ui.components.SectionHeader("Your playlists") {
                com.sonora.player.ui.components.AppButton(onClick = { creating = true }, small = true, solid = false) {
                    LIcon(L.drawable.lucide_ic_plus, null, size = 16.dp); Spacer(Modifier.width(6.dp)); Text("New")
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "Make a playlist from any song's menu, or tap New.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else item {
            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(playlists, key = { "pl-${it.id}" }) { p ->
                    Column(Modifier.width(150.dp).clickable { actions.playlist(p.id) }) {
                        Box(
                            Modifier.size(150.dp).clip(RoundedCornerShape(12.dp))
                                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(tint(p.name), tint(p.name + "x")))),
                            contentAlignment = Alignment.Center,
                        ) { LIcon(L.drawable.lucide_ic_list_music, null, tint = Color.White, size = 44.dp) }
                        Spacer(Modifier.height(6.dp))
                        Text(p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(songCount(p.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (creating) {
        TextInputDialog("New playlist", "", "Create", { creating = false }) { vm.createPlaylist(it); creating = false }
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String,
    art: Uri?,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    @DrawableRes fallback: Int = L.drawable.lucide_ic_music,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, Modifier.size(48.dp), shape, fallback)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) trailing()
        else LIcon(L.drawable.lucide_ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
    }
}

// ------------------------------------------------------------------ detail screens

@Composable
fun DetailTopBar(onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.sonora.player.ui.components.TopCircle(L.drawable.lucide_ic_chevron_left, "Back", onBack)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun DetailHeader(art: Uri?, title: String, subtitle: String, meta: String, @DrawableRes fallback: Int = L.drawable.lucide_ic_disc_3, round: Boolean = false) {
    val shape = if (round) CircleShape else RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 8.dp), contentAlignment = Alignment.Center) {
            // A soft glow of the artwork's own colours behind it.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Artwork(
                    art,
                    Modifier.size(220.dp)
                        .graphicsLayer { scaleX = 1.06f; scaleY = 1.06f; translationY = 16.dp.toPx(); alpha = 0.6f }
                        .blur(40.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
                    shape, fallback,
                )
            }
            Artwork(art, Modifier.size(220.dp), shape, fallback)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
    }
}

private fun meta(songs: List<Song>) = "${songs.size} songs • ${formatLongDuration(songs.sumOf { it.durationMs })}"

@Composable
fun AlbumScreen(vm: MainViewModel, albumId: Long, actions: UiActions) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == albumId }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    HeroStatusBar(list)
    if (album == null) return
    val tracks = remember(album) { album.songs.map { com.sonora.player.data.Track.Local(it) } }
    val more = remember(album, artists) { artists.firstOrNull { it.name == album.artist }?.albums.orEmpty().filter { it.id != album.id } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            item(key = "hero") {
                CollectionHero(
                    tracks, album.title,
                    listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString(), meta(album.songs)).joinToString(" · "),
                    heroColors(album.artUri, listOf(Color(0xFF3B2F8F), Color(0xFF0E7C86))), actions,
                    badge = {}, cover = album.artUri, shuffleLabel = "Shuffle",
                    topActions = { HeroCircle(L.drawable.lucide_ic_list_plus, "Add to playlist") { actions.addToPlaylist(album.songs) } },
                    onPlay = { vm.play(album.songs) }, onShuffle = { vm.shuffle(album.songs) },
                )
            }
            item { com.sonora.player.ui.components.SectionHeader("Songs") }
            itemsIndexed(album.songs, key = { _, s -> s.id }) { i, song ->
                SongRow(
                    song, { vm.play(album.songs, i) }, actions.song, isCurrent = player.songId == song.id,
                    subtitle = song.artist,
                    leading = {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                (song.trackNumber % 1000).takeIf { it > 0 }?.toString() ?: "–",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
            if (more.isNotEmpty()) {
                item {
                    com.sonora.player.ui.components.SectionHeader("More by ${album.artist}") {
                        com.sonora.player.ui.components.AppButton(onClick = { actions.artist(album.artist) }, small = true, solid = false) { Text("Artist") }
                    }
                }
                item { AlbumRow(more, actions) }
            }
        }
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
        StatusBarScrim(scrolled)
    }
}

@Composable
fun ArtistScreen(vm: MainViewModel, name: String, actions: UiActions) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val artist = artists.firstOrNull { it.name == name }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    HeroStatusBar(list)
    if (artist == null) return
    val tracks = remember(artist) { artist.songs.map { com.sonora.player.data.Track.Local(it) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            item(key = "hero") {
                CollectionHero(
                    tracks, artist.name,
                    listOf(if (artist.albums.size == 1) "1 album" else "${artist.albums.size} albums", meta(artist.songs)).joinToString(" · "),
                    heroColors(artist.artUri, listOf(Color(0xFF3B2F8F), Color(0xFFB4235A))), actions,
                    badge = {}, cover = artist.artUri, coverShape = CircleShape, shuffleLabel = "Shuffle",
                    topActions = { HeroCircle(L.drawable.lucide_ic_list_plus, "Add to playlist") { actions.addToPlaylist(artist.songs) } },
                    onPlay = { vm.play(artist.songs) }, onShuffle = { vm.shuffle(artist.songs) },
                )
            }
            if (artist.albums.isNotEmpty()) {
                item { com.sonora.player.ui.components.SectionHeader("Albums") }
                item { AlbumRow(artist.albums, actions) }
            }
            item { com.sonora.player.ui.components.SectionHeader("Songs") }
            itemsIndexed(artist.songs, key = { _, s -> s.id }) { i, song ->
                SongRow(song, { vm.play(artist.songs, i) }, actions.song, isCurrent = player.songId == song.id, subtitle = song.album)
            }
        }
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
        StatusBarScrim(scrolled)
    }
}

@Composable
fun CollectionScreen(title: String, subtitle: String, songs: List<Song>, actions: UiActions, loading: Boolean = false) {
    val vm = actions.vm
    val player by vm.player.collectAsStateWithLifecycle()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    HeroStatusBar(list)
    val tracks = remember(songs) { songs.map { com.sonora.player.data.Track.Local(it) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            item(key = "hero") {
                CollectionHero(
                    tracks, title, listOf(subtitle, meta(songs)).filter { it.isNotBlank() }.joinToString(" · "),
                    heroColors(songs.firstOrNull()?.artUri, listOf(Color(0xFF3B2F8F), Color(0xFF0E7C86))), actions,
                    badge = { LIcon(L.drawable.lucide_ic_list_music, null, tint = Color.White, size = 22.dp) },
                    shuffleLabel = "Shuffle",
                    topActions = { HeroCircle(L.drawable.lucide_ic_list_plus, "Add to playlist") { actions.addToPlaylist(songs) } },
                    onPlay = { vm.play(songs) }, onShuffle = { vm.shuffle(songs) },
                )
            }
            if (loading) {
                // Still loading: keep the page calm instead of flashing an empty state.
            } else if (songs.isEmpty()) {
                item { EmptyState(L.drawable.lucide_ic_list_music, "Nothing here yet", "Songs you add will show up here.") }
            } else {
                item { com.sonora.player.ui.components.SectionHeader("Artists in here") }
                item { ArtistsRow(tracks, { if (it == 1) "1 song" else "$it songs" }) { t -> vm.playTracks(t) } }
                item { com.sonora.player.ui.components.SectionHeader("Songs · ${songs.size}") }
            }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                SongRow(song, { vm.play(songs, i) }, actions.song, isCurrent = player.songId == song.id)
            }
        }
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
        StatusBarScrim(scrolled)
    }
}

@Composable
fun PlaylistScreen(vm: MainViewModel, id: Long, actions: UiActions) {
    if (id < 0) {
        val loaded by remember(id) { vm.smartPlaylist(id) }.collectAsState(initial = null)
        val songs = loaded.orEmpty()
        CollectionScreen(SmartPlaylists.name(id), "", songs, actions, loading = loaded == null)
        return
    }
    val name by remember(id) { vm.playlistName(id) }.collectAsState(initial = "")
    val loadedEntries by remember(id) { vm.playlistEntries(id) }.collectAsState(initial = null)
    val entries = loadedEntries.orEmpty()
    val player by vm.player.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val songs = entries.map { it.second }

    // Local copy so drag-reorder feels instant; persisted on drop.
    val order = remember(entries) { mutableStateListOf(*entries.toTypedArray()) }
    val reorder = rememberDragReorder(64.dp) { from, to ->
        val moved = order.removeAt(from)
        order.add(to, moved)
        vm.reorderPlaylist(order.map { it.first })
    }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    HeroStatusBar(list)
    val tracks = remember(songs) { songs.map { com.sonora.player.data.Track.Local(it) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            item(key = "hero") {
                CollectionHero(
                    tracks, name ?: "", meta(songs),
                    heroColors(songs.firstOrNull()?.artUri, listOf(Color(0xFF3B2F8F), Color(0xFF0E7C86))), actions,
                    badge = { LIcon(L.drawable.lucide_ic_list_music, null, tint = Color.White, size = 22.dp) },
                    shuffleLabel = "Shuffle",
                    topActions = {
                        HeroCircle(if (editing) L.drawable.lucide_ic_check else L.drawable.lucide_ic_arrow_down_up, "Reorder") { editing = !editing }
                        HeroCircle(L.drawable.lucide_ic_pencil, "Rename") { renaming = true }
                        HeroCircle(L.drawable.lucide_ic_trash_2, "Delete") { deleting = true }
                    },
                    onPlay = { vm.play(songs) }, onShuffle = { vm.shuffle(songs) },
                )
            }
            if (loadedEntries == null) {
                // Loading.
            } else if (songs.isEmpty()) {
                item { EmptyState(L.drawable.lucide_ic_list_plus, "Empty playlist", "Use “Add to playlist” from any song's menu.") }
            } else {
                item { com.sonora.player.ui.components.SectionHeader("Artists in this playlist") }
                item { ArtistsRow(tracks, { if (it == 1) "1 song" else "$it songs" }) { t -> vm.playTracks(t) } }
                item {
                    com.sonora.player.ui.components.SectionHeader(if (editing) "Drag to reorder" else "Songs · ${songs.size}") {
                        if (editing) com.sonora.player.ui.components.AppButton(onClick = { editing = false }, small = true) { Text("Done") }
                    }
                }
            }
            itemsIndexed(order, key = { _, e -> e.first.entryId }) { i, (entry, song) ->
                val rowActions = SongActions(
                    onPlayNext = actions.song.onPlayNext, onAddToQueue = actions.song.onAddToQueue,
                    onAddToPlaylist = actions.song.onAddToPlaylist, onToggleFavorite = actions.song.onToggleFavorite,
                    isFavorite = actions.song.isFavorite, onGoToAlbum = actions.song.onGoToAlbum,
                    onGoToArtist = actions.song.onGoToArtist, onRemove = { vm.removeFromPlaylist(entry.entryId) },
                )
                SongRow(
                    song, { vm.play(order.map { it.second }, i) }, rowActions,
                    modifier = reorder.rowModifier(i).height(64.dp),
                    isCurrent = player.songId == song.id,
                    leading = if (editing) {
                        {
                            Box(Modifier.size(50.dp).dragHandle(reorder, i, order.size), contentAlignment = Alignment.Center) {
                                LIcon(L.drawable.lucide_ic_grip_vertical, "Drag to reorder")
                            }
                        }
                    } else null,
                )
            }
        }
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
        StatusBarScrim(scrolled)
    }

    if (renaming) {
        TextInputDialog("Rename playlist", name ?: "", "Save", { renaming = false }) { vm.renamePlaylist(id, it); renaming = false }
    }
    if (deleting) {
        com.sonora.player.ui.components.AppDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${name ?: ""}”?") },
            text = { Text("The songs stay on your phone; only the playlist is removed.") },
            confirmButton = { com.sonora.player.ui.components.AppButton(small = true, onClick = { vm.deletePlaylist(id); deleting = false; actions.back() }) { Text("Delete") } },
            dismissButton = { com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Home-style hero card for a Library tab: a blurred mosaic of artwork behind a big number, a summary line and
 * two pills (solid white + glass).
 */
@Composable
fun TabHero(
    arts: List<Uri>,
    label: String,
    big: String,
    unit: String,
    line: String,
    primary: Pair<String, () -> Unit>,
    primaryIcon: Int,
    secondary: Pair<String, () -> Unit>? = null,
    secondaryIcon: Int = L.drawable.lucide_ic_play,
    inset: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val white = Color.White
    val mosaic = arts.distinct().take(6)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 8.dp).clip(RoundedCornerShape(24.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF3B2F8F), Color(0xFF0E7C86)))),
    ) {
        if (mosaic.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 31) {
            Column(Modifier.matchParentSize().blur(40.dp)) {
                mosaic.chunked(3).let { r -> if (r.size == 1) r + listOf(r[0].reversed()) else r }.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        row.forEach { a -> Artwork(a, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) }
                    }
                }
            }
        }
        Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.55f))),
            ),
        )
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = white.copy(alpha = 0.8f))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(big, color = white, fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold)
                Text(" $unit", color = white.copy(alpha = 0.9f), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(line, style = MaterialTheme.typography.bodyLarge, color = white.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(white).clickable(onClick = primary.second).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LIcon(primaryIcon, null, tint = Color.Black, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(primary.first, color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                if (secondary != null) {
                    Row(
                        Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.28f))
                            .clickable(onClick = secondary.second).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LIcon(secondaryIcon, null, tint = white, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(secondary.first, color = white, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"
