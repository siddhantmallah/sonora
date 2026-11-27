package com.sonora.player.ui.screens

import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.ReplayStats
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.EmptyState
import com.sonora.player.ui.components.HeartRed
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.PlayShuffleRow
import com.sonora.player.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import com.composables.icons.lucide.R as L

// ------------------------------------------------------------------ Downloads

/** Downloads, laid out like Home: artwork hero, what's still downloading, artists you saved, then everything. */
@Composable
fun DownloadsScreen(vm: MainViewModel, actions: UiActions) {
    val tracks by vm.downloadedTracks.collectAsStateWithLifecycle()
    val states by vm.downloadStates.collectAsStateWithLifecycle()
    val pending = remember(states) {
        states.filter { it.value != com.sonora.player.playback.DlState.DONE }.map { (id, st) -> id to st }
    }
    val usedMb = remember(states) { vm.downloads.bytesUsed() / 1_048_576 }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    HeroStatusBar(list)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            item(key = "hero") {
                val size = if (usedMb >= 1024) "%.1f GB".format(usedMb / 1024f) else "$usedMb MB"
                CollectionHero(
                    tracks, "Downloads",
                    listOfNotNull("${tracks.size} songs", size, if (pending.isNotEmpty()) "${pending.size} downloading" else null).joinToString(" · "),
                    listOf(Color(0xFF0E7C86), Color(0xFF2B4FD8)), actions,
                    badge = { LIcon(L.drawable.lucide_ic_download, null, tint = Color.White, size = 22.dp) },
                    chip = L.drawable.lucide_ic_wifi_off to "Plays offline",
                    shuffleLabel = "Shuffle",
                    onPlay = { vm.playTracks(tracks) }, onShuffle = { vm.smartShuffle(tracks) },
                )
            }

            if (pending.isNotEmpty()) {
                item { com.sonora.player.ui.components.SectionHeader("Downloading · ${pending.size}") }
                items(pending.size) { i ->
                    val (id, st) = pending[i]
                    val song = vm.yt.song(id)
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.sonora.player.ui.components.Artwork(
                            song?.thumbnail?.let { com.sonora.player.youtube.hiResThumb(it) }?.let(android.net.Uri::parse),
                            Modifier.size(48.dp), RoundedCornerShape(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song?.title ?: "Song", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (st) {
                                    com.sonora.player.playback.DlState.DOWNLOADING -> "Downloading…"
                                    com.sonora.player.playback.DlState.FAILED -> "Couldn't download"
                                    else -> "Waiting"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (st == com.sonora.player.playback.DlState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (st == com.sonora.player.playback.DlState.DOWNLOADING) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Box(
                                Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable { vm.downloads.remove(id) },
                                contentAlignment = Alignment.Center,
                            ) { LIcon(L.drawable.lucide_ic_x, "Cancel", size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }

            if (tracks.isEmpty()) {
                if (pending.isEmpty()) item {
                    EmptyState(
                        L.drawable.lucide_ic_download, "No downloads yet",
                        "Download songs or playlists from their menu to listen offline.",
                    )
                }
            } else {
                item { com.sonora.player.ui.components.SectionHeader("Artists you saved") }
                item { ArtistsRow(tracks, { if (it == 1) "1 song" else "$it songs" }) { vm.playTracks(it) } }
                item { com.sonora.player.ui.components.SectionHeader("All downloads · ${tracks.size}") }
                itemsIndexed(tracks, key = { _, t -> t.key }) { i, t ->
                    TrackRow(t, vm, onRemoveDownload = {
                        (t as? com.sonora.player.data.Track.Online)?.let { vm.downloads.remove(it.song.videoId) }
                    }) { vm.playTracks(tracks, i) }
                }
            }
        }
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
        StatusBarScrim(scrolled)
    }
}

// ------------------------------------------------------------------ Replay

private val periods = listOf(7 to "This week", 30 to "This month", 365 to "This year", -1 to "All time")

@Composable
fun ReplayScreen(vm: MainViewModel, actions: UiActions) {
    var period by rememberSaveable { mutableIntStateOf(30) }
    val stats by produceState<ReplayStats?>(null, period) { value = null; value = vm.replay(period.takeIf { it > 0 }) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val label = periods.first { it.first == period }.second

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    HeroStatusBar(list)
    val topTracks = remember(stats) { stats?.topSongs.orEmpty().mapNotNull { vm.trackOf(it.first) } }
    val share: () -> Unit = {
        scope.launch {
            val bmp = layer.toImageBitmap().asAndroidBitmap()
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, "share").apply { mkdirs() }.let { dir ->
                    File(dir, "sonora-replay.png").also { f -> f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Share your Replay"))
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            // ---- Hero: your top songs' artwork, the headline numbers and a play button, melting into the page.
            item(key = "hero") {
                val s = stats
                val nf = NumberFormat.getIntegerInstance()
                CollectionHero(
                    topTracks, "Your Replay",
                    when {
                        s == null -> "Counting your listening…"
                        s.plays == 0 && s.minutes == 0L -> "Nothing yet ${label.lowercase()}"
                        else -> "${nf.format(s.minutes)} minutes · ${nf.format(s.plays)} plays"
                    },
                    listOf(Color(0xFF2B1A6B), Color(0xFF7C3AED), HeartRed), actions,
                    badge = { LIcon(L.drawable.lucide_ic_rotate_ccw, null, tint = Color.White, size = 22.dp) },
                    chip = L.drawable.lucide_ic_calendar to label,
                    playLabel = "Play top songs", shuffleLabel = "Shuffle",
                    onPlay = { vm.playTracks(topTracks) }, onShuffle = { vm.smartShuffle(topTracks) },
                )
            }
            item { PillTabs(periods, period, Modifier.padding(top = 4.dp)) { period = it } }

            val s = stats
            when {
                s == null -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                s.plays == 0 && s.minutes == 0L -> item {
                    EmptyState(
                        L.drawable.lucide_ic_rotate_ccw, "Nothing here yet",
                        "Songs count once you've listened for 30 seconds (or half of a short song). Your Replay fills in as you listen.",
                    )
                }
                else -> {
                    // ---- Top songs as big numbered cards (like Home's rows)
                    if (topTracks.isNotEmpty()) {
                        item { SectionHeader("Your top songs") }
                        item {
                            androidx.compose.foundation.lazy.LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(topTracks, key = { _, t -> "top-${t.key}" }) { i, t ->
                                    val plays = s.topSongs.getOrNull(i)?.second ?: 0
                                    Column(Modifier.width(140.dp).clickable { vm.playTracks(topTracks, i) }) {
                                        Box {
                                            Artwork(t.art, Modifier.size(140.dp), RoundedCornerShape(12.dp))
                                            Text(
                                                "${i + 1}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black,
                                                style = MaterialTheme.typography.displaySmall.copy(
                                                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f),
                                                ),
                                                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp),
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                        Text(
                                            if (plays == 1) "1 play" else "$plays plays", maxLines = 1,
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---- Top artists as round photos
                    if (s.topArtists.isNotEmpty()) {
                        item { SectionHeader("Your top artists") }
                        item {
                            androidx.compose.foundation.lazy.LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                itemsIndexed(s.topArtists, key = { _, a -> "artist-${a.first}" }) { _, (name, mins, art) ->
                                    Column(
                                        Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).clickable { actions.artist(name) },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Artwork(art?.let(Uri::parse), Modifier.size(96.dp), CircleShape, L.drawable.lucide_ic_mic_vocal)
                                        Spacer(Modifier.height(6.dp))
                                        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("$mins min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    // ---- When you listen
                    item { SectionHeader("When you listen") }
                    item { HourChart(s.byHour) }
                    item {
                        Text(
                            "${s.activeDays} listening days · ${s.uniqueSongs} different songs · you skip ${s.skipRate}% of songs",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }

                    // ---- The shareable card
                    item {
                        SectionHeader("Your card") {
                            com.sonora.player.ui.components.AppButton(onClick = share, small = true, solid = false) {
                                LIcon(L.drawable.lucide_ic_share_2, null, size = 16.dp); Spacer(Modifier.width(6.dp)); Text("Share")
                            }
                        }
                    }
                    item {
                        // This card is recorded into a layer so it can be shared as an image.
                        Box(
                            Modifier.padding(horizontal = 16.dp).drawWithContent {
                                layer.record { this@drawWithContent.drawContent() }
                                drawLayer(layer)
                            },
                        ) { ReplayCard(s, label) }
                    }
                }
            }
        }
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 500 } }
        StatusBarScrim(scrolled)
    }
}

@Composable
private fun ReplayCard(s: ReplayStats, period: String) {
    val nf = NumberFormat.getIntegerInstance()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B1A6B), Color(0xFF7C3AED), HeartRed)))
            .padding(22.dp),
    ) {
        Text("SONORA REPLAY · ${period.uppercase()}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(10.dp))
        Text(nf.format(s.minutes), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp)
        Text("minutes listened · ${nf.format(s.plays)} plays", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
        s.topArtists.firstOrNull()?.let { (name, _, art) ->
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(art?.let(Uri::parse), Modifier.size(56.dp), CircleShape, L.drawable.lucide_ic_mic_vocal)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("TOP ARTIST", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    Text(name, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("TOP SONGS", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        s.topSongs.take(5).forEachIndexed { i, (e, _) ->
            Text(
                "${i + 1}  ${e.title}", color = Color.White, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        }
        val vibe = listOfNotNull(s.topLanguage, s.topVibe).joinToString(" · ")
        if (vibe.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text("YOUR SOUND", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Text(vibe, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text("Made with ♥ by Mr.Blanko · Sonora", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HourChart(byHour: List<Int>) {
    val color = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.surfaceContainerHighest
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.padding(horizontal = 20.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val gap = size.width / 24
            val w = gap * 0.62f
            byHour.forEachIndexed { h, m ->
                val bh = (m.toFloat() / max) * size.height
                drawRoundRect(dim, Offset(h * gap, 0f), Size(w, size.height), CornerRadius(w / 2))
                // Only hours you actually listened in get a bar (no stray dots for empty hours).
                if (m > 0) drawRoundRect(color, Offset(h * gap, size.height - bh.coerceAtLeast(w)), Size(w, bh.coerceAtLeast(w)), CornerRadius(w / 2))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12a", "6a", "12p", "6p", "11p").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
