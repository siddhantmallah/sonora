package com.sonora.player.ui.screens

import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.SettingsRepository.Keys
import com.sonora.player.playback.EqInfo
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.IconSquare
import com.sonora.player.ui.components.IosColors
import com.sonora.player.ui.components.IosGroup
import com.sonora.player.ui.components.IosRow
import com.sonora.player.ui.components.IosToggleRow
import com.composables.icons.lucide.R as L

/**
 * Equalizer & effects, in the same style as Settings: sky header, a curve you drag directly (one handle per
 * band), presets as pills, and grouped thin sliders for the effects and speed / pitch.
 */
@Composable
fun EqualizerScreen(vm: MainViewModel, actions: UiActions) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val info by vm.eqInfo.collectAsStateWithLifecycle()
    val set = vm.c.settings
    val context = LocalContext.current
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            val reset = {
                set.set(Keys.eqPreset, -1)
                set.setEqBands(List(info?.bandCount ?: 5) { 0 })
                set.set(Keys.bassBoost, 0); set.set(Keys.virtualizer, 0); set.set(Keys.loudnessGainMb, 0)
                set.set(Keys.playbackSpeed, 1f); set.set(Keys.playbackPitch, 1f)
            }
            EqHero(vm, actions, s.eqEnabled, s.audioOffload, info, s.eqPreset, onToggle = { set.set(Keys.eqEnabled, !s.eqEnabled) }, onReset = reset)
            if (s.audioOffload) {
                Text(
                    "Battery saver (audio offload) is on, so effects can't apply. Turn it off in Settings › Audio quality.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            val eq = info
            val dim = if (s.eqEnabled && !s.audioOffload) 1f else 0.5f
            if (eq == null) {
                Text(
                    "Start playing a song to load your phone's equalizer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp),
                )
            } else {
                com.sonora.player.ui.components.SectionHeader("Bands")
                EqCurve(
                    eq, List(eq.bandCount) { i -> s.eqBands.getOrElse(i) { 0 } },
                    Modifier.padding(horizontal = 16.dp).alpha(dim),
                ) { bands ->
                    set.set(Keys.eqPreset, -1)
                    set.setEqBands(bands)
                }
                PillTabs(
                    listOf(-1 to "Custom") + eq.presets.mapIndexed { i, n -> i to n },
                    s.eqPreset, Modifier.padding(top = 6.dp).alpha(dim),
                ) { i ->
                    set.set(Keys.eqPreset, i)
                    if (i >= 0) vm.c.effects.presetLevels(i)?.let(set::setEqBands)
                }
            }

            IosGroup("Effects", modifier = Modifier.alpha(dim), largeTitle = true) {
                row {
                    SliderRow("Bass boost", L.drawable.lucide_ic_speaker, s.bassBoost / 10, 0..100, { "$it%" }) { set.set(Keys.bassBoost, it * 10) }
                }
                row {
                    SliderRow("Virtualizer", L.drawable.lucide_ic_headphones, s.virtualizer / 10, 0..100, { "$it%" }) { set.set(Keys.virtualizer, it * 10) }
                }
                row {
                    SliderRow("Loudness", L.drawable.lucide_ic_volume_2, s.loudnessGainMb / 10, 0..150, { "+%.1f dB".format(it / 10f) }) {
                        set.set(Keys.loudnessGainMb, it * 10)
                    }
                }
            }

            IosGroup("Playback", footer = "Speed and pitch work even when effects are off.", largeTitle = true) {
                row {
                    SliderRow("Speed", L.drawable.lucide_ic_gauge, Math.round(s.playbackSpeed * 20), 10..40, { "%.2f×".format(it / 20f) }) {
                        set.set(Keys.playbackSpeed, it / 20f)
                    }
                }
                row {
                    SliderRow("Pitch", L.drawable.lucide_ic_music_2, Math.round(s.playbackPitch * 20), 10..40, { "%.2f×".format(it / 20f) }) {
                        set.set(Keys.playbackPitch, it / 20f)
                    }
                }
            }

            IosGroup("More", largeTitle = true) {
                row {
                    IosRow(
                        "Phone's equalizer", L.drawable.lucide_ic_external_link, IosColors.Gray,
                        subtitle = "Dolby Atmos, Wavelet and other system effects",
                    ) {
                        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, vm.c.effects.audioSessionId)
                            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "No system equalizer found", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        // Read the scroll position through derivedStateOf so the screen only recomposes when this flips,
        // not on every scrolled frame.
        val scrolled by remember { androidx.compose.runtime.derivedStateOf { scroll.value > 600 } }
        StatusBarScrim(scrolled)
    }
    HeroStatusBar(scroll)
}

/** A grouped-list row with an icon, title, live value and a thin slider underneath. */
@Composable
private fun SliderRow(
    title: String,
    @DrawableRes icon: Int,
    value: Int,
    range: IntRange,
    display: (Int) -> String,
    onDone: (Int) -> Unit,
) {
    var local by remember(value) { mutableIntStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(icon, IosColors.Gray)
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(display(local), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ThinSlider(local, range, Modifier.padding(start = 44.dp, top = 2.dp), onChange = { local = it }, onDone = onDone)
    }
}

/**
 * The equalizer as one smooth curve on a card: drag anywhere to move the nearest band, tap to jump it there.
 * Values above each handle, frequencies below.
 */
@Composable
private fun EqCurve(eq: EqInfo, levels: List<Int>, modifier: Modifier = Modifier, onCommit: (List<Int>) -> Unit) {
    val local = remember(levels) { levels.toMutableStateList() }
    val current by rememberUpdatedState(local)
    val commit by rememberUpdatedState(onCommit)
    var active by remember { mutableStateOf(-1) }
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val n = eq.bandCount.coerceAtLeast(1)
    val maxAbs = maxOf(kotlin.math.abs(eq.minLevel), kotlin.math.abs(eq.maxLevel), 1)

    fun label(mb: Int): String {
        val db = mb / 100f
        return if (kotlin.math.abs(db) < 0.05f) "0" else "%+.0f".format(db)
    }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            local.forEachIndexed { i, mb ->
                Text(
                    label(mb), modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (i == active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (i == active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 8.dp)
                .pointerInput(n, eq.minLevel, eq.maxLevel) {
                    val pad = 14.dp.toPx()
                    fun band(x: Float) = ((x / size.width) * n).toInt().coerceIn(0, n - 1)
                    fun level(y: Float): Int {
                        val half = size.height / 2f - pad
                        val v = ((size.height / 2f - y) / half * maxAbs).toInt()
                        return (v / 50 * 50).coerceIn(eq.minLevel, eq.maxLevel)
                    }
                    // Only a drag that starts on (or right next to) a handle moves a band; any other swipe over the
                    // curve is left alone, so it scrolls the page instead of changing your sound by accident.
                    val grab = 36.dp.toPx()
                    fun handleY(i: Int) = size.height / 2f - current[i].toFloat() / maxAbs * (size.height / 2f - pad)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val i = band(down.position.x)
                        val hx = (i + 0.5f) * size.width / n
                        if (kotlin.math.abs(down.position.x - hx) > grab || kotlin.math.abs(down.position.y - handleY(i)) > grab) return@awaitEachGesture
                        down.consume()
                        active = i
                        drag(down.id) { ch ->
                            ch.consume()
                            current[i] = level(ch.position.y)
                        }
                        active = -1
                        commit(current.toList())
                    }
                }
                .pointerInput(n, eq.minLevel, eq.maxLevel) {
                    val pad = 14.dp.toPx()
                    detectTapGestures { o ->
                        val i = ((o.x / size.width) * n).toInt().coerceIn(0, n - 1)
                        val half = size.height / 2f - pad
                        current[i] = (((size.height / 2f - o.y) / half * maxAbs).toInt() / 50 * 50).coerceIn(eq.minLevel, eq.maxLevel)
                        commit(current.toList())
                    }
                },
        ) {
            val pad = 14.dp.toPx()
            val mid = size.height / 2f
            val half = mid - pad
            val colW = size.width / n
            val pts = local.mapIndexed { i, mb -> Offset(colW * (i + 0.5f), mid - mb.toFloat() / maxAbs * half) }

            // Grid: top / bottom limits and a dashed 0 dB line; a faint guide under each band.
            drawLine(grid.copy(alpha = 0.5f), Offset(0f, pad), Offset(size.width, pad), 1f)
            drawLine(grid.copy(alpha = 0.5f), Offset(0f, size.height - pad), Offset(size.width, size.height - pad), 1f)
            drawLine(grid, Offset(0f, mid), Offset(size.width, mid), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            pts.forEach { drawLine(grid.copy(alpha = 0.35f), Offset(it.x, pad), Offset(it.x, size.height - pad), 1f) }

            // Smooth curve through the band points, flat out to the edges.
            val curve = Path().apply {
                moveTo(0f, pts.first().y)
                lineTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]; val b = pts[i]
                    val cx = (a.x + b.x) / 2f
                    cubicTo(cx, a.y, cx, b.y, b.x, b.y)
                }
                lineTo(size.width, pts.last().y)
            }
            val fill = Path().apply {
                addPath(curve)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.02f))))
            drawPath(curve, accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))

            pts.forEachIndexed { i, p ->
                val r = if (i == active) 12.dp.toPx() else 9.dp.toPx()
                if (i == active) drawCircle(accent.copy(alpha = 0.18f), r * 2f, p)
                drawCircle(Color.White, r, p)
                drawCircle(accent, r, p, style = Stroke(2.5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            eq.centerFreqsHz.take(n).forEach { hz ->
                Text(
                    if (hz >= 1000) "${hz / 1000}k" else "$hz",
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Home-style hero: the song that's playing blurred into the background, the equalizer's state in big type, a mini
 * curve, and On/Off + Reset pills.
 */
@Composable
private fun EqHero(
    vm: MainViewModel, actions: UiActions, enabled: Boolean, offload: Boolean, info: EqInfo?, preset: Int,
    onToggle: () -> Unit, onReset: () -> Unit,
) {
    val player by vm.player.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val page = MaterialTheme.colorScheme.background
    val topInset = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val glass = Color.Black.copy(alpha = 0.28f)
    val on = enabled && !offload
    val presetName = info?.presets?.getOrNull(preset) ?: "Custom"
    Box(Modifier.fillMaxWidth().height(topInset + 340.dp).androidx_clip()) {
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(if (on) listOf(Color(0xFF2B4FD8), Color(0xFF7C3AED), Color(0xFFB4235A)) else listOf(Color(0xFF3A3F4B), Color(0xFF23262E))),
            ),
        )
        com.sonora.player.ui.components.BlurredArt(player.artUri, Modifier.matchParentSize(), blur = 60.dp, zoom = 1.3f, alpha = if (on) 0.55f else 0.3f)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.30f), 0.55f to Color.Black.copy(alpha = 0.12f),
                    0.80f to page.copy(alpha = 0.55f), 1f to page,
                ),
            ),
        )
        Column(Modifier.fillMaxSize().padding(top = topInset)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(glass).clickable { actions.back() },
                    contentAlignment = Alignment.Center,
                ) { com.sonora.player.ui.components.LIcon(L.drawable.lucide_ic_chevron_left, "Back", tint = Color.White, size = 22.dp) }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(horizontal = 20.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(glass), contentAlignment = Alignment.Center) {
                    com.sonora.player.ui.components.LIcon(L.drawable.lucide_ic_sliders_horizontal, null, tint = Color.White, size = 22.dp)
                }
                Spacer(Modifier.height(10.dp))
                Text("Equalizer", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    when {
                        offload -> "Paused while battery saver is on"
                        !enabled -> "Off · your music plays untouched"
                        else -> listOfNotNull(
                            presetName,
                            (s.bassBoost / 10).takeIf { it > 0 }?.let { "Bass +$it%" },
                            (s.virtualizer / 10).takeIf { it > 0 }?.let { "Space $it%" },
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 1,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onToggle).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.sonora.player.ui.components.LIcon(L.drawable.lucide_ic_power, null, tint = Color.Black, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (enabled) "Turn off" else "Turn on", color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(glass).clickable(onClick = onReset).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        com.sonora.player.ui.components.LIcon(L.drawable.lucide_ic_rotate_ccw, null, tint = Color.White, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reset", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(56.dp))
        }
    }
}

private fun Modifier.androidx_clip(): Modifier = this.clipToBounds()
