package com.sonora.player.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsTopHeight
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.Sky
import com.sonora.player.data.Weather
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.LIcon
import kotlinx.coroutines.delay
import com.composables.icons.lucide.R as L

/**
 * The colour of the sky right now (same sun model as Home's hero): pale blue by day, warm around sunrise/sunset,
 * deep blue at night, greyer when it's overcast.
 */
fun skyWash(now: Long, weather: Weather?): Color {
    val rise = weather?.sunrise ?: todayAt(6, 30)
    val set = weather?.sunset ?: todayAt(18, 15)
    val (_, alt) = sunAltitude(now, rise, set)
    val day = ((alt + 0.10f) / 0.22f).coerceIn(0f, 1f)
    val low = (1f - (kotlin.math.abs(alt - 0.02f) / 0.30f)).coerceIn(0f, 1f) * ((alt + 0.14f) / 0.12f).coerceIn(0f, 1f)
    var c = lerp(Color(0xFF26315E), Color(0xFF8CC4F2), day)
    // Peach at sunset, deepening to plum as it gets dark (never brown), same as the sky.
    c = lerp(c, lerp(Color(0xFF5A3460), Color(0xFFF4A36E), day), low * 0.75f)
    val grey = when (weather?.kind) {
        Sky.CLOUDY, Sky.FOG -> 0.45f
        Sky.RAIN, Sky.DRIZZLE, Sky.SNOW -> 0.55f
        Sky.STORM -> 0.65f
        else -> 0f
    }
    return lerp(c, Color(0xFF8A96A6), grey)
}

/**
 * Header shared by the Search and Library tabs so they match Home: a soft wash of the current sky colour that
 * runs up behind the status bar and fades into the page, a large title, and your avatar (→ Settings) on the right.
 */
@Composable
fun AmbientHeader(
    vm: MainViewModel,
    actions: UiActions,
    title: String,
    modifier: Modifier = Modifier,
    /** Shows a back button above the title (for pushed screens like Settings) instead of the avatar. */
    onBack: (() -> Unit)? = null,
    /** Extra controls on the right of the back button row (e.g. a Reset pill). */
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val weather by vm.weather.collectAsStateWithLifecycle()
    val account by vm.ytAccount.collectAsStateWithLifecycle()
    val signedIn by vm.ytSignedIn.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(60_000); value = System.currentTimeMillis() } }
    val page = MaterialTheme.colorScheme.background
    val wash by animateColorAsState(lerp(skyWash(now, weather), page, 0.35f), tween(900), label = "wash")
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to wash.copy(alpha = 0.85f), 0.55f to wash.copy(alpha = 0.35f), 1f to page),
            ),
        )
        Column(Modifier.fillMaxWidth().padding(top = topInset)) {
            if (onBack != null) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { LIcon(L.drawable.lucide_ic_chevron_left, "Back", size = 22.dp) }
                    Box(Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = if (onBack != null) 4.dp else 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onBack == null) Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)).clickable(onClick = actions::settings),
                    contentAlignment = Alignment.Center,
                ) {
                    val photo = account?.photo?.takeIf { signedIn }
                    if (photo != null) Artwork(Uri.parse(photo), Modifier.fillMaxSize(), CircleShape, L.drawable.lucide_ic_circle_user)
                    else LIcon(L.drawable.lucide_ic_settings, "Settings", size = 20.dp)
                }
            }
            content()
        }
    }
}

/**
 * The app's one tab / filter style: horizontally scrolling pills; the selected one is solid (like Insights'
 * period picker), the rest sit on a soft surface.
 */
@Composable
fun <T> PillTabs(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    sidePadding: androidx.compose.ui.unit.Dp = 16.dp,
    /** Background of unselected pills; inside a grouped list use a shade that differs from the group. */
    idle: Color = Color.Unspecified,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = sidePadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            val idleColor = if (idle == Color.Unspecified) MaterialTheme.colorScheme.surfaceContainerHigh else idle
            val bg by animateColorAsState(if (sel) MaterialTheme.colorScheme.onSurface else idleColor, androidx.compose.animation.core.tween(120), label = "pillBg")
            val fg by animateColorAsState(if (sel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, androidx.compose.animation.core.tween(120), label = "pillFg")
            Text(
                label, style = MaterialTheme.typography.labelLarge, color = fg,
                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Quick jump to a pager page when a tab is tapped: hops straight next to the target (so far-away tabs don't
 * scroll through, and build, every page in between) and then slides the last page in, in ~220ms.
 */
suspend fun androidx.compose.foundation.pager.PagerState.quickScrollTo(page: Int) {
    val from = currentPage
    if (page == from) return
    if (kotlin.math.abs(page - from) > 1) scrollToPage(if (page > from) page - 1 else page + 1)
    animateScrollToPage(page, animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.2f, 1f)))
}

/** Frosted strip behind the status bar that fades in once a screen's content scrolls under it. */
@Composable
fun StatusBarScrim(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (visible) 0.96f else 0f, label = "statusScrim")
    Box(
        modifier.fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background.copy(alpha = alpha)),
    )
}

/** A thin slider in the app's style (like the volume slider), with whole-number steps. */
@Composable
fun ThinSlider(value: Int, range: IntRange, modifier: Modifier = Modifier, onChange: (Int) -> Unit, onDone: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val span = (range.last - range.first).coerceAtLeast(1)
    fun at(x: Float, w: Int) = (range.first + Math.round((x / w).coerceIn(0f, 1f) * span))
    androidx.compose.foundation.Canvas(
        modifier.fillMaxWidth().height(32.dp)
            .pointerInput(range) { detectTapGestures { o -> at(o.x, size.width).let { onChange(it); onDone(it) } } }
            .pointerInput(range) {
                var last = value
                detectHorizontalDragGestures(onDragEnd = { onDone(last) }) { ch, _ ->
                    ch.consume(); last = at(ch.position.x, size.width); onChange(last)
                }
            },
    ) {
        val y = size.height / 2
        val f = (value - range.first).toFloat() / span
        val stroke = 5.dp.toPx()
        drawLine(track, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(accent, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * f, y), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White, 11.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * f, y))
        drawCircle(Color.Black.copy(alpha = 0.08f), 11.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * f, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}
