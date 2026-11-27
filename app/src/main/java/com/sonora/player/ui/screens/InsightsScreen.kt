package com.sonora.player.ui.screens

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.Insights
import com.sonora.player.data.Metric
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.EmptyState
import com.sonora.player.ui.components.LIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.composables.icons.lucide.R as L

private val periods = listOf<Pair<Int?, String>>(7 to "7 days", 28 to "28 days", 90 to "90 days", 365 to "365 days", null to "Lifetime")
private val Positive = Color(0xFF1E9E5A)
private val Negative = Color(0xFFD93A3A)

/** Library → Insights: YouTube-Studio-style listening analytics, recomputed live. */
@Composable
fun InsightsTab(vm: MainViewModel, actions: UiActions) {
    val data by vm.insights.collectAsStateWithLifecycle()
    val days by vm.insightsDays.collectAsStateWithLifecycle()
    val sources by vm.sourceSummary.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        item { PillTabs(periods, days) { vm.insightsDays.value = it } }
        val d = data
        if (d == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }
        item { InsightsHero(d, vm) }
        item { OverviewCard(d) }
        item { RealtimeCard(d) }
        item { ConnectCard(sources.size, actions) }
        if (d.empty) {
            item { EmptyState(L.drawable.lucide_ic_chart_column, "Nothing in this period yet", "Play some music and your stats appear here as you listen.") }
            return@LazyColumn
        }
        item { HabitsRow(d) }
        if (d.topSongs.isNotEmpty()) {
            item { com.sonora.player.ui.components.SectionHeader("Your top songs") }
            item { TopSongsRow(d, vm) }
        }
        if (d.topArtists.isNotEmpty()) {
            item { com.sonora.player.ui.components.SectionHeader("Your top artists") }
            item { TopArtistsRow(d, actions) }
        }
        item { HeatmapCard(d) }
        if (d.sources.size > 1 || sources.isNotEmpty()) item { SourcesCard(d) }
    }
}

@Composable
private fun Card(title: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = if (title == null) 12.dp else 0.dp)) {
        if (title != null) {
            com.sonora.player.ui.components.SectionHeader(title) {
                if (trailing != null) Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        ) { content() }
    }
}

private fun format(m: Metric, v: Long): String = when (m) {
    Metric.TIME -> if (v >= 120) "%.1f h".format(v / 60f) else "$v min"
    else -> if (v >= 10_000) "%.1fK".format(v / 1000f) else "$v"
}

private fun periodName(days: Int?) = when (days) {
    null -> "all time"
    else -> "previous $days days"
}

@Composable
private fun OverviewCard(d: Insights) {
    var metric by rememberSaveable { mutableStateOf(Metric.TIME) }
    Card("Trends") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Metric.entries.forEach { m ->
                val sel = m == metric
                val bg by androidx.compose.animation.animateColorAsState(
                    if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest, label = "metricBg",
                )
                val fg = if (sel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { metric = m }
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(if (m == Metric.TIME) "Time" else m.label, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Number large, unit ("min", "h") small, so it fits a quarter-width tile.
                    val (num, unit) = format(m, d.totals[m] ?: 0).split(' ', limit = 2).let { it[0] to it.getOrNull(1) }
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(num)
                            if (unit != null) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(fontSize = MaterialTheme.typography.labelMedium.fontSize))
                                append(" $unit"); pop()
                            }
                        },
                        style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 1, softWrap = false,
                    )
                    val ch = d.change(m)
                    if (ch != null) {
                        Text(
                            (if (ch >= 0) "▲ " else "▼ ") + "${kotlin.math.abs(ch)}%",
                            style = MaterialTheme.typography.labelSmall, color = if (ch >= 0) Positive else Negative,
                        )
                    } else Spacer(Modifier.height(14.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        LineChart(d.series[metric].orEmpty(), Modifier.fillMaxWidth().height(150.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            val f = SimpleDateFormat("d MMM", Locale.getDefault())
            Text(f.format(Date(d.seriesStart)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val ch = d.change(metric)
        if (ch != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${metric.label} is ${if (ch >= 0) "up" else "down"} ${kotlin.math.abs(ch)}% compared with the ${periodName(d.days)}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Smooth area chart with a dashed average line; animates when the data changes. */
@Composable
private fun LineChart(values: List<Float>, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val progress by animateFloatAsState(1f, tween(600), label = "chart")
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f) * 1.15f
        val w = size.width
        val h = size.height
        val step = if (values.size > 1) w / (values.size - 1) else w
        for (i in 1..3) drawLine(grid.copy(alpha = 0.5f), Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1f)
        val pts = values.mapIndexed { i, v -> Offset(i * step, h - (v / max) * h * progress) }
        val line = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
                val cx = (p0.x + p1.x) / 2
                cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
        }
        val fill = Path().apply { addPath(line); lineTo(pts.last().x, h); lineTo(pts.first().x, h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.02f))))
        drawPath(line, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        val avg = values.average().toFloat()
        val ay = h - (avg / max) * h
        drawLine(accent.copy(alpha = 0.45f), Offset(0f, ay), Offset(w, ay), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        drawCircle(accent, 4.dp.toPx(), pts.last())
        drawCircle(Color.White, 2.dp.toPx(), pts.last())
    }
}

@Composable
private fun RealtimeCard(d: Insights) {
    // The live dot pulses only while you can see it, and the pulse is applied at draw time, so the card itself
    // isn't rebuilt on every frame.
    val onScreen by com.sonora.player.ui.components.LocalOnScreen.current
    val pulse = if (onScreen) {
        rememberInfiniteTransition(label = "live").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }
    Card(
        "Realtime",
        trailing = {
            Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse.value }.clip(CircleShape).background(Positive))
            Spacer(Modifier.width(6.dp))
            Text("Updating live", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        Text(format(Metric.TIME, d.last48Minutes), style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text("Listening time · last 48 hours", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val accent = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val max = (d.last48.maxOrNull() ?: 0f).coerceAtLeast(1f)
            val bw = size.width / 48f
            d.last48.forEachIndexed { i, v ->
                val bh = (v / max) * size.height
                drawRoundRect(track, Offset(i * bw + 1f, 0f), Size(bw - 2f, size.height), CornerRadius(2f))
                if (v > 0f) drawRoundRect(if (i == 47) accent else accent.copy(alpha = 0.7f), Offset(i * bw + 1f, size.height - bh), Size(bw - 2f, bh), CornerRadius(2f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("48h ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("Now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectCard(connected: Int, actions: UiActions) {
    com.sonora.player.ui.components.IosGroup {
        row {
            com.sonora.player.ui.components.IosRow(
                if (connected == 0) "Connect your other music apps" else "Connected apps",
                L.drawable.lucide_ic_link, com.sonora.player.ui.components.IosColors.Blue,
                subtitle = if (connected == 0) "Spotify, Apple Music, Last.fm and more" else "Your stats include them",
                detail = if (connected == 0) null else "$connected",
            ) { actions.connections() }
        }
    }
}

@Composable
private fun HabitsRow(d: Insights) {
    val tiles = listOf(
        "${d.streakDays}" to "Day streak",
        "${d.skipRate}%" to "Skip rate",
        (if (d.days == null) "—" else "${d.newArtists}") to "New artists",
        "${d.repeatShare}%" to "On repeat",
    )
    Card("Habits") {
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            tiles.forEachIndexed { i, (v, label) ->
                if (i > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TopSongsCard(d: Insights) {
    Card("Top songs") {
        val max = d.topSongs.maxOf { it.plays }.coerceAtLeast(1)
        d.topSongs.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                Artwork(s.art?.let(Uri::parse), Modifier.size(40.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth(s.plays.toFloat() / max).height(3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)))
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (s.plays == 1) "1 play" else "${s.plays} plays", style = MaterialTheme.typography.labelMedium)
                    Text("${s.minutes} min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TopArtistsCard(d: Insights, actions: UiActions) {
    Card("Top artists") {
        d.topArtists.forEach { a ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { actions.artist(a.name) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(a.art?.let(Uri::parse), Modifier.size(40.dp), CircleShape, L.drawable.lucide_ic_mic_vocal)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${(a.share * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Box(Modifier.fillMaxWidth(a.share.coerceIn(0.02f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCard(d: Insights) {
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Card("When you listen") {
        val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val max = d.heat.flatten().maxOrNull()?.coerceAtLeast(1f) ?: 1f
        d.heat.forEachIndexed { row, hours ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.5.dp)) {
                Text(labels[row], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    val cw = size.width / 24f
                    hours.forEachIndexed { h, v ->
                        val a = (v / max).coerceIn(0f, 1f)
                        drawRoundRect(if (v > 0f) accent.copy(alpha = 0.15f + 0.85f * a) else empty, Offset(h * cw + 1f, 0f), Size(cw - 2f, size.height), CornerRadius(3f))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 30.dp, top = 4.dp)) {
            listOf("12am", "6am", "12pm", "6pm").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        val best = d.heat.flatMapIndexed { r, hs -> hs.mapIndexed { h, v -> Triple(r, h, v) } }.maxByOrNull { it.third }
        if (best != null && best.third > 0f) {
            Spacer(Modifier.height(8.dp))
            val hour = best.second
            val time = when { hour == 0 -> "midnight"; hour < 12 -> "$hour am"; hour == 12 -> "noon"; else -> "${hour - 12} pm" }
            Text("You listen most on ${labels[best.first]}s around $time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourcesCard(d: Insights) {
    val colors = listOf(MaterialTheme.colorScheme.primary, Color(0xFF1DB954), Color(0xFFFF4E45), Color(0xFF7C5CFF), Color(0xFFFFB020), Color(0xFF2EA8FF), Color(0xFF9AA0A6))
    val total = d.sources.sumOf { it.second }.coerceAtLeast(1)
    Card("Where you listen") {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            d.sources.forEachIndexed { i, (_, m) ->
                Box(Modifier.weight((m.toFloat() / total).coerceAtLeast(0.01f)).fillMaxHeight().background(colors[i % colors.size]))
            }
        }
        Spacer(Modifier.height(10.dp))
        d.sources.forEachIndexed { i, (name, m) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i % colors.size]))
                Spacer(Modifier.width(8.dp))
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${format(Metric.TIME, m)} · ${m * 100 / total}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A playable track for one of the top songs (YouTube Music or phone); null for imports from other apps. */
private fun playable(vm: MainViewModel, t: com.sonora.player.data.TopSong) = vm.trackOf(
    com.sonora.player.data.db.PlayEventEntity(
        mediaId = t.key, title = t.title, artist = t.artist, album = null, artUri = t.art,
        durationMs = 0, playedAt = 0, listenedMs = 0, skipped = false,
    ),
)

/** Home-style hero card: your top songs' artwork blurred behind the period's headline numbers. */
@Composable
private fun InsightsHero(d: Insights, vm: MainViewModel) {
    val white = Color.White
    val arts = remember(d) { d.topSongs.mapNotNull { it.art }.distinct().take(6) }
    val tracks = remember(d) { d.topSongs.mapNotNull { playable(vm, it) } }
    val nf = java.text.NumberFormat.getIntegerInstance()
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B1A6B), Color(0xFF7C3AED), Color(0xFFB4235A)))),
    ) {
        if (arts.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 31) {
            Column(Modifier.matchParentSize().blur(40.dp)) {
                arts.chunked(3).let { r -> if (r.size == 1) r + listOf(r[0].reversed()) else r }.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        row.forEach { a -> Artwork(Uri.parse(a), Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) }
                    }
                }
            }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.55f)))))
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                (if (d.days == null) "YOUR LISTENING · ALL TIME" else "YOUR LISTENING · LAST ${d.days} DAYS"),
                style = MaterialTheme.typography.labelMedium, color = white.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(6.dp))
            val (num, unit) = format(Metric.TIME, d.totals[Metric.TIME] ?: 0).split(' ', limit = 2).let { it[0] to it.getOrNull(1) }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(num, color = white, fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold)
                if (unit != null) Text(" $unit", color = white.copy(alpha = 0.9f), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                d.change(Metric.TIME)?.let { ch ->
                    Text(
                        (if (ch >= 0) "▲ " else "▼ ") + "${kotlin.math.abs(ch)}%",
                        color = white, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(50))
                            .background((if (ch >= 0) Positive else Negative).copy(alpha = 0.85f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                listOf(
                    "${nf.format(d.totals[Metric.PLAYS] ?: 0)} plays", "${nf.format(d.totals[Metric.SONGS] ?: 0)} songs",
                    "${nf.format(d.totals[Metric.ARTISTS] ?: 0)} artists",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge, color = white.copy(alpha = 0.85f),
            )
            if (tracks.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(white).clickable { vm.playTracks(tracks) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LIcon(L.drawable.lucide_ic_play, null, tint = Color.Black, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Play your top songs", color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Top songs as Home's big numbered artwork cards. */
@Composable
private fun TopSongsRow(d: Insights, vm: MainViewModel) {
    val tracks = remember(d) { d.topSongs.map { playable(vm, it) } }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(d.topSongs, key = { _, s -> "ts-${s.key}" }) { i, s ->
            Column(Modifier.width(140.dp).clickable { tracks[i]?.let { t -> vm.playTracks(tracks.filterNotNull(), tracks.filterNotNull().indexOf(t)) } }) {
                Box {
                    Artwork(s.art?.let(Uri::parse), Modifier.size(140.dp), RoundedCornerShape(12.dp))
                    Text(
                        "${i + 1}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.displaySmall.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)),
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(
                    (if (s.plays == 1) "1 play" else "${s.plays} plays") + " · ${s.minutes} min", maxLines = 1,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Top artists as round photos with their share of your listening. */
@Composable
private fun TopArtistsRow(d: Insights, actions: UiActions) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(d.topArtists, key = { _, a -> "ta-${a.name}" }) { _, a ->
            Column(Modifier.width(96.dp).clickable { actions.artist(a.name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(a.art?.let(Uri::parse), Modifier.size(96.dp), CircleShape, L.drawable.lucide_ic_mic_vocal)
                Spacer(Modifier.height(6.dp))
                Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${(a.share * 100).toInt()}% · ${a.minutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
