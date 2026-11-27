package com.sonora.player.ui.screens

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.Track
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.EmptyState
import com.sonora.player.ui.components.HeartRed
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.SectionHeader
import com.composables.icons.lucide.R as L

/**
 * Liked songs, laid out like Home: a full-bleed hero made from the artwork of what you liked most recently that
 * melts into the page, then "Recently liked", "Artists you love" and the full list.
 */
@Composable
fun LikedScreen(vm: MainViewModel, actions: UiActions) {
    val tracks by vm.likedTracks.collectAsStateWithLifecycle()
    val signedIn by vm.ytSignedIn.collectAsStateWithLifecycle()
    val list = rememberLazyListState()

    HeroStatusBar(list)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            item(key = "hero") {
                val hours = tracks.sumOf { it.durationMs } / 3_600_000.0
                CollectionHero(
                    tracks, "Liked songs", "${tracks.size} songs" + if (hours >= 1) " · %.0f hours".format(hours) else "",
                    listOf(Color(0xFF5B2EFF), HeartRed), actions,
                    badge = { com.sonora.player.ui.components.FavoriteHeart(true, size = 24.dp, offTint = Color.White) },
                    chip = if (signedIn) L.drawable.lucide_ic_refresh_cw to "Synced with YouTube Music" else null,
                    onPlay = { vm.playTracks(tracks) }, onShuffle = { vm.smartShuffle(tracks) },
                )
            }

            if (tracks.isEmpty()) {
                item { EmptyState(L.drawable.lucide_ic_heart, "No liked songs yet", "Tap the heart on the player to save songs here.") }
                return@LazyColumn
            }

            item { SectionHeader("Recently liked") }
            item {
                val recent = tracks.take(12)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recent, key = { _, t -> "recent-${t.key}" }) { i, t -> TrackCard(t) { vm.playTracks(tracks, i) } }
                }
            }

            item { SectionHeader("Artists you love") }
            item { ArtistsRow(tracks, { if (it == 1) "1 like" else "$it likes" }) { vm.playTracks(it) } }

            item { SectionHeader("All liked songs · ${tracks.size}") }
            itemsIndexed(tracks, key = { _, t -> t.key }) { i, t -> TrackRow(t, vm) { vm.playTracks(tracks, i) } }
        }

        val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
        StatusBarScrim(scrolled)
    }
}

/** White status-bar icons while [list]'s hero is on screen; the theme's own once it scrolls away (same as Home). */
@Composable
fun HeroStatusBar(list: androidx.compose.foundation.lazy.LazyListState) {
    val view = LocalView.current
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    DisposableEffect(Unit) {
        onDispose {
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    LaunchedEffect(list, darkTheme) {
        fun apply(overHero: Boolean) = (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !overHero && !darkTheme
        }
        val overHero = { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 500 }
        // Re-apply after the page transition: the screen we came from resets the icons when it closes.
        launch { kotlinx.coroutines.delay(700); apply(overHero()) }
        snapshotFlow { overHero() }.collect { apply(it) }
    }
}

/** Your most-featured artists in [tracks], as a row of round photos; tapping one plays just their songs. */
@Composable
fun ArtistsRow(tracks: List<Track>, count: (Int) -> String, onPlay: (List<Track>) -> Unit) {
    val artists = remember(tracks) {
        tracks.groupBy { it.artist.split(",", "&", " x ", " feat").first().trim() }
            .filterKeys { it.isNotBlank() && it != "YouTube Music" && it != "<unknown>" }
            .entries.sortedByDescending { it.value.size }.take(12)
    }
    if (artists.size < 2) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(artists, key = { _, e -> "artist-${e.key}" }) { _, (name, songs) ->
            Column(
                Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).clickable { onPlay(songs) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(songs.first().art, Modifier.size(96.dp), CircleShape, L.drawable.lucide_ic_mic_vocal)
                Spacer(Modifier.height(6.dp))
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count(songs.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Home-style full-bleed hero for a collection: a blurred mosaic of its artwork over [colors], a badge, title,
 * one-line summary and Play / shuffle pills, melting into the page.
 */
@Composable
fun CollectionHero(
    tracks: List<Track>,
    title: String,
    meta: String,
    colors: List<Color>,
    actions: UiActions,
    badge: @Composable () -> Unit,
    chip: Pair<Int, String>? = null,
    shuffleLabel: String = "Smart shuffle",
    shuffleIcon: Int = L.drawable.lucide_ic_shuffle,
    playLabel: String = "Play",
    /** The collection's own cover, shown big instead of the small badge (playlists, albums). */
    cover: android.net.Uri? = null,
    coverShape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    /** Extra round glass buttons in the top bar (rename, download, radio…). */
    topActions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val page = MaterialTheme.colorScheme.background
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val arts = remember(tracks) { tracks.mapNotNull { it.art }.distinct().take(6) }
    val glass = Color.Black.copy(alpha = 0.28f)

    Box(Modifier.fillMaxWidth().height(topInset + if (cover != null) 420.dp else 360.dp)) {
        Box(Modifier.matchParentSize().background(Brush.linearGradient(colors)))
        if (arts.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 31) {
            Column(Modifier.matchParentSize().blur(48.dp)) {
                arts.chunked(3).let { rows -> if (rows.size == 1) rows + listOf(rows[0].reversed()) else rows }.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        row.forEach { Artwork(it, Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp)) }
                    }
                }
            }
        }
        // Readability at the top, then melt into the page like Home's sky.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f), 0.55f to Color.Black.copy(alpha = 0.15f),
                    0.80f to page.copy(alpha = 0.55f), 1f to page,
                ),
            ),
        )

        Column(Modifier.fillMaxSize().padding(top = topInset)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(glass).clickable { actions.back() },
                    contentAlignment = Alignment.Center,
                ) { LIcon(L.drawable.lucide_ic_chevron_left, "Back", tint = Color.White, size = 22.dp) }
                Spacer(Modifier.weight(1f))
                if (topActions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = topActions)
                if (chip != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LIcon(chip.first, null, tint = Color.White, size = 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(chip.second, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (cover != null) {
                    Artwork(
                        cover,
                        Modifier.size(116.dp).graphicsLayer { shadowElevation = 16.dp.toPx(); shape = coverShape; clip = true },
                        coverShape, if (coverShape == CircleShape) L.drawable.lucide_ic_mic_vocal else L.drawable.lucide_ic_list_music,
                    )
                    Spacer(Modifier.height(14.dp))
                } else {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(glass), contentAlignment = Alignment.Center) { badge() }
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    title, style = if (title.length > 18) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(meta, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                if (tracks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.height(46.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onPlay)
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LIcon(L.drawable.lucide_ic_play, null, tint = Color.Black, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(playLabel, color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Row(
                            Modifier.height(46.dp).clip(RoundedCornerShape(50)).background(glass).clickable(onClick = onShuffle)
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LIcon(shuffleIcon, null, tint = Color.White, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(shuffleLabel, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(64.dp))
        }
    }
}

/** Round glass button for a collection hero's top bar. */
@Composable
fun HeroCircle(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LIcon(icon, label, tint = Color.White, size = 19.dp) }
}

/** Hero colours from a cover: its own palette, deepened a little so white text reads. */
@Composable
fun heroColors(art: android.net.Uri?, fallback: List<Color>): List<Color> {
    val palette by com.sonora.player.ui.player.rememberArtPalette(art, fallback)
    return remember(palette) { palette.take(3).map { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.25f) }.let { if (it.size > 1) it else it + it } }
}

/** [HeroStatusBar] for pages that scroll with a ScrollState instead of a list. */
@Composable
fun HeroStatusBar(scroll: androidx.compose.foundation.ScrollState) {
    val view = LocalView.current
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    DisposableEffect(Unit) {
        onDispose {
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    LaunchedEffect(scroll, darkTheme) {
        fun apply(overHero: Boolean) = (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightStatusBars = !overHero && !darkTheme
        }
        launch { kotlinx.coroutines.delay(700); apply(scroll.value < 500) }
        snapshotFlow { scroll.value < 500 }.collect { apply(it) }
    }
}
