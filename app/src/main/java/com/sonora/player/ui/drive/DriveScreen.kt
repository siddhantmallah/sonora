package com.sonora.player.ui.drive

import androidx.compose.runtime.LaunchedEffect
import com.sonora.player.ui.player.glassPill
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.blur
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.LocalFavorites
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import com.composables.icons.lucide.R as L

/** Set by MainActivity: shrink Sonora into a picture-in-picture window (Map mode). */
var enterMapMode: (() -> Unit)? = null
/** Whether leaving the app should automatically switch to Map mode (true while Driving mode is open). */
var autoMapMode: Boolean = false

/**
 * Driving mode: a glanceable, high-contrast screen with very large controls, swipe-to-skip, voice search,
 * one-tap mixes and Map mode (a floating player over your navigation app). Keeps the screen on.
 */
@Composable
fun DriveScreen(vm: MainViewModel, actions: UiActions) {
    val state by vm.player.collectAsStateWithLifecycle()
    val feed by vm.feed.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val favorites = LocalFavorites.current
    val context = LocalContext.current
    val view = LocalView.current
    val conn = vm.c.playerConnection

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        autoMapMode = true
        // Light status-bar icons on the dark driving screen.
        val window = (context as? Activity)?.window
        val insets = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val wasLight = insets?.isAppearanceLightStatusBars
        insets?.isAppearanceLightStatusBars = false
        onDispose {
            view.keepScreenOn = false
            autoMapMode = false
            if (wasLight != null) insets.isAppearanceLightStatusBars = wasLight
        }
    }
    // Re-apply once the page transition settles: the screen we came from resets the icons when it closes.
    LaunchedEffect(Unit) {
        delay(700)
        (context as? Activity)?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = false }
    }

    val clock by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    val progress by produceState(0f, state.mediaId, state.isPlaying, state.durationMs) {
        while (true) {
            value = if (state.durationMs > 0) (conn.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            delay(1000)
        }
    }
    val palette by com.sonora.player.ui.player.rememberArtPalette(state.artUri, listOf(Color(0xFF3A4E8C), Color(0xFF1C2540), Color(0xFF6D5BD0)))
    // Same tone as the player: the album's darkest colour, deepened so white text always reads.
    val baseTarget = remember(palette) { lerp(palette.minBy { it.luminance() }, Color.Black, 0.55f) }
    val base by animateColorAsState(baseTarget, tween(700), label = "driveBase")
    val bright = remember(palette) { palette.map { lerp(it, Color.White, 0.35f) }.let { if (it.size > 1) it else it + it } }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) vm.playVoiceQuery(text)
    }
    val openMaps: () -> Unit = {
        // Map mode: Sonora floats over the map.
        enterMapMode?.invoke()
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    val weather by vm.weather.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshWeather() }
    // Today's sky colour (same as Home's hero), deepened a little so the big controls stay readable.
    val skyTarget = remember(clock / 600_000, weather) { lerp(com.sonora.player.ui.screens.skyWash(clock, weather), Color.Black, 0.35f) }
    val sky by animateColorAsState(skyTarget, tween(900), label = "driveSky")

    Box(Modifier.fillMaxSize().background(base)) {
        // The artwork, blurred softly into the lower half, under today's sky at the top — like Home's hero.
        Artwork(state.artUri, Modifier.fillMaxSize().blur(56.dp).graphicsLayer { alpha = 0.35f }, RoundedCornerShape(0.dp))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to sky, 0.30f to sky.copy(alpha = 0.75f), 0.55f to base.copy(alpha = 0.75f), 1f to base,
                ),
            ),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            // Top bar: glass exit + Map mode, with a small "Driving" pill between.
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(L.drawable.lucide_ic_chevron_down, "Exit driving mode", 52) { actions.back() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.glassPill(RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LIcon(L.drawable.lucide_ic_car, null, tint = Color.White.copy(alpha = 0.85f), size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Driving", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
                GlassButton(L.drawable.lucide_ic_map, "Map mode", 52, onClick = openMaps)
            }

            // Home-style sky header: the time big, then the day, weather and air at a glance.
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(clock)),
                    color = Color.White, fontSize = 56.sp, lineHeight = 58.sp, fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.weight(1f))
                weather?.let { w ->
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("${w.tempC.toInt()}° ${w.kind.label}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        w.aqi?.let { Text("AQI $it · ${com.sonora.player.data.aqiLabel(it)}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            Text(
                java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(Date(clock)) +
                    (weather?.place?.let { " · $it" } ?: ""),
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyLarge,
            )

            // Now playing: swipe the artwork to skip.
            var drag by remember { mutableFloatStateOf(0f) }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { if (drag < -120f) conn.next() else if (drag > 120f) conn.previous(); drag = 0f },
                            onDragCancel = { drag = 0f },
                        ) { ch, dx -> ch.consume(); drag += dx }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    state.artUri,
                    Modifier.size(132.dp)
                        .graphicsLayer {
                            translationX = drag * 0.4f; alpha = 1f - (kotlin.math.abs(drag) / 600f).coerceAtMost(0.5f)
                            shadowElevation = 18.dp.toPx(); shape = RoundedCornerShape(18.dp); clip = true
                        },
                    RoundedCornerShape(18.dp),
                )
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title.ifBlank { "Nothing playing" }, color = Color.White, fontSize = 30.sp, lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(state.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LIcon(
                            if (output.deviceName != null) L.drawable.lucide_ic_bluetooth else L.drawable.lucide_ic_smartphone, null,
                            tint = Color.White.copy(alpha = 0.55f), size = 15.dp,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            output.deviceName ?: output.type.label, color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            // Progress in the album's colours, like the player's seek bar.
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(bright)))
            }

            // Giant transport controls: glass circles around a solid play button.
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassButton(L.drawable.lucide_ic_skip_back, "Previous", 96) { conn.previous() }
                Box(
                    Modifier.size(140.dp)
                        .graphicsLayer { shadowElevation = 24.dp.toPx(); shape = CircleShape; clip = true }
                        .background(Color.White).clickable { conn.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    LIcon(if (state.isPlaying) L.drawable.lucide_ic_pause else L.drawable.lucide_ic_play, "Play or pause", tint = Color.Black, size = 58.dp)
                }
                GlassButton(L.drawable.lucide_ic_skip_forward, "Next", 96) { conn.next() }
            }

            // Big action tiles.
            val liked = state.mediaId?.let { com.sonora.player.youtube.YtIds.statsId(it) ?: it.toLongOrNull() }?.let { it in favorites } == true
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(if (liked) "Liked" else "Like", L.drawable.lucide_ic_heart, Modifier.weight(1f), active = liked) {
                    vm.toggleFavoriteMedia(state.mediaId)
                }
                Tile("Voice", L.drawable.lucide_ic_mic, Modifier.weight(1f)) {
                    runCatching {
                        voice.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song, artist or mood"),
                        )
                    }
                }
                Tile("Maps", L.drawable.lucide_ic_navigation, Modifier.weight(1f), onClick = openMaps)
            }

            // One-tap music for the road.
            val mixes = buildList {
                feed.crossApp?.let { add(it) }
                addAll(feed.dailyMixes)
                feed.discover?.let { add(it) }
            }
            if (mixes.isNotEmpty()) {
                Text(
                    "FOR THE ROAD", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
                )
                LazyRow(contentPadding = PaddingValues(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(mixes, key = { it.id }) { m ->
                        Row(
                            Modifier.height(76.dp).glassPill(RoundedCornerShape(18.dp))
                                .clickable { vm.playTracks(m.tracks) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(m.covers.firstOrNull(), Modifier.fillMaxHeight().aspectRatio(1f), RoundedCornerShape(11.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(m.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(end = 10.dp))
                        }
                    }
                }
            } else Spacer(Modifier.height(14.dp))
        }
    }
}

/** A round glass button, the same glass as the player's controls. */
@Composable
private fun GlassButton(icon: Int, label: String, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).glassPill(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LIcon(icon, label, tint = Color.White, size = (size * 0.42f).dp)
    }
}

@Composable
private fun Tile(label: String, icon: Int, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.height(92.dp)
            .then(if (active) Modifier.clip(shape).background(Color(0xFFFF2D55).copy(alpha = 0.88f)) else Modifier.glassPill(shape))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LIcon(icon, null, tint = Color.White, size = 30.dp)
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** What the floating Map-mode window shows: artwork, title and progress (controls are the window's buttons). */
@Composable
fun MapModeWindow(vm: MainViewModel) {
    val state by vm.player.collectAsStateWithLifecycle()
    val conn = vm.c.playerConnection
    val progress by produceState(0f, state.mediaId, state.isPlaying, state.durationMs) {
        while (true) {
            value = if (state.durationMs > 0) (conn.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            delay(1000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Artwork(state.artUri, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(state.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.White))
            }
        }
    }
}
