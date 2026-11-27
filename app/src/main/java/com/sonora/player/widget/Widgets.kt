package com.sonora.player.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.sonora.player.playback.Artwork
import com.sonora.player.playback.Commands
import com.sonora.player.playback.PlaybackService
import com.sonora.player.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

data class WidgetData(
    val title: String? = null,
    val artist: String? = null,
    val artUri: Uri? = null,
    val art: Bitmap? = null,
    val isPlaying: Boolean = false,
    val upNext: String? = null,
    val liked: Boolean = false,
    /** A soft gradient in the album's own colours, behind everything. */
    val backdrop: Bitmap? = null,
    val accent: Int = 0xFFEFFF1A.toInt(),
)

/** Feeds now-playing state from the service to every widget, and survives process restarts. */
object WidgetBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(WidgetData())
    val state: StateFlow<WidgetData> = _state.asStateFlow()
    private var restored = false

    fun push(
        context: Context, title: String?, artist: String?, artUri: Uri?, isPlaying: Boolean,
        upNext: String? = null, liked: Boolean = false,
    ) {
        val app = context.applicationContext
        scope.launch {
            val prev = _state.value
            val same = artUri == prev.artUri && prev.art != null
            val art = if (same) prev.art else crisp(Artwork.load(app, artUri, ART_PX))
            val (backdrop, accent) = if (same) prev.backdrop to prev.accent else colours(art)
            val next = WidgetData(title, artist, artUri, art, isPlaying, upNext, liked, backdrop, accent)
            if (next == prev) return@launch
            _state.value = next
            app.getSharedPreferences("widget", Context.MODE_PRIVATE).edit()
                .putString("title", title).putString("artist", artist).putString("art", artUri?.toString())
                .putString("next", upNext).apply()
            PlayerWidget().updateAll(app)
            CoverWidget().updateAll(app)
        }
    }

    suspend fun ensureRestored(context: Context) {
        if (restored || _state.value.title != null) return
        restored = true
        val p = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
        val uri = p.getString("art", null)?.let(Uri::parse)
        val art = withContext(Dispatchers.IO) { crisp(Artwork.load(context, uri, ART_PX)) }
        val (backdrop, accent) = colours(art)
        _state.value = WidgetData(p.getString("title", null), p.getString("artist", null), uri, art, false, p.getString("next", null), false, backdrop, accent)
    }

    /** Connects a short-lived controller to the service and runs [block] on it. */
    suspend fun control(context: Context, block: (MediaController) -> Unit) = withContext(Dispatchers.Main) {
        // A widget tap runs inside a broadcast receiver, which Android doesn't allow to bind services: connect
        // through the app's context instead.
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val controller = MediaController.Builder(app, token).buildAsync().await()
        try {
            // The service restores the last queue on start; give it a moment when cold-starting.
            var waited = 0
            while (controller.mediaItemCount == 0 && waited < 2000) { delay(100); waited += 100 }
            block(controller)
            delay(800)
        } finally {
            controller.release()
        }
    }

    // Widgets travel to the launcher in one parcel (one copy per size), so the images stay small: 300 px artwork
    // without alpha (rounded by the widget itself) and a tiny gradient that scales up smoothly.
    private const val ART_PX = 240

    private fun crisp(b: Bitmap?): Bitmap? = b?.let {
        val s = minOf(it.width, it.height, ART_PX)
        val sq = Bitmap.createScaledBitmap(it, s, s, true)
        sq.copy(Bitmap.Config.RGB_565, false)
    }

    /** The album's deep colours as a diagonal gradient, plus a bright accent for the play button. */
    private fun colours(art: Bitmap?): Pair<Bitmap?, Int> {
        val p = art?.let { runCatching { Palette.from(it).maximumColorCount(16).generate() }.getOrNull() }
        val dark = p?.darkMutedSwatch?.rgb ?: p?.darkVibrantSwatch?.rgb ?: p?.dominantSwatch?.rgb ?: 0xFF1C2033.toInt()
        val deep = p?.darkVibrantSwatch?.rgb ?: p?.mutedSwatch?.rgb ?: dark
        val accent = p?.lightVibrantSwatch?.rgb ?: p?.vibrantSwatch?.rgb ?: 0xFFEFFF1A.toInt()
        fun darken(c: Int, f: Float) = android.graphics.Color.rgb(
            (android.graphics.Color.red(c) * f).toInt(), (android.graphics.Color.green(c) * f).toInt(), (android.graphics.Color.blue(c) * f).toInt(),
        )
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.RGB_565)
        Canvas(bmp).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 48f, 48f, darken(deep, 0.78f), darken(dark, 0.42f), Shader.TileMode.CLAMP)
        })
        return bmp to accent
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetBridge.control(context) {
            if (it.isPlaying) it.pause() else {
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                it.play()
            }
        }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetBridge.control(context) { it.seekToNextMediaItem() }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetBridge.control(context) { it.seekToPrevious() }
}

class LikeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetBridge.control(context) { it.sendCustomCommand(SessionCommand(Commands.TOGGLE_FAVORITE, Bundle.EMPTY), Bundle.EMPTY) }
}

private val SMALL = DpSize(110.dp, 50.dp)
private val WIDE = DpSize(250.dp, 50.dp)
private val SQUARE = DpSize(140.dp, 140.dp)
private val LARGE = DpSize(250.dp, 180.dp)

/** Resizable player: compact pill, wide bar with controls, or a large card with the artwork, up next and a like. */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, SQUARE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetBridge.ensureRestored(context)
        provideContent {
            val data by WidgetBridge.state.collectAsState()
            val size = LocalSize.current
            when {
                size.height >= LARGE.height && size.width >= LARGE.width -> LargeLayout(data)
                // Narrow but tall (2x2, 3x2, 3x3): a stacked card instead of a stretched bar.
                size.height >= SQUARE.height && size.width >= SQUARE.width -> SquareLayout(data)
                size.width >= WIDE.width -> WideLayout(data)
                else -> SmallLayout(data)
            }
        }
    }
}

/** Square artwork tile: the cover full bleed, the song over a soft shade, and a play button. */
class CoverWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetBridge.ensureRestored(context)
        provideContent {
            val data by WidgetBridge.state.collectAsState()
            Box(GlanceModifier.fillMaxSize().cornerRadius(28.dp).clickable(actionStartActivity<MainActivity>())) {
                Backdrop(data)
                data.art?.let { Image(ImageProvider(it), "Album art", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0x33000000))), contentAlignment = Alignment.BottomStart) {
                    Row(GlanceModifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight()) {
                            Text(data.title ?: "Sonora", maxLines = 1, style = TextStyle(color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                            Text(data.artist ?: "Tap to play", maxLines = 1, style = TextStyle(color = Soft, fontSize = 12.sp))
                        }
                        PlayButton(data, 46)
                    }
                }
            }
        }
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

class CoverWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverWidget()
}

// ------------------------------------------------------------------ layouts

private val White = ColorProvider(Color.White)
private val Soft = ColorProvider(Color(0xCCFFFFFF))
private val Faint = ColorProvider(Color(0x99FFFFFF))

/** The album-coloured gradient behind a widget (dark enough for white text, whatever the cover). */
@Composable
private fun Backdrop(data: WidgetData) {
    val b = data.backdrop
    if (b != null) Image(ImageProvider(b), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    else Box(GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF1C2033)))) {}
}

/** 2x2 / 3x2 / 3x3: artwork on top, the song, and the three controls. */
@Composable
private fun SquareLayout(data: WidgetData) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(26.dp)) {
        Backdrop(data)
        Column(GlanceModifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                ArtImage(data, GlanceModifier.size(64.dp).cornerRadius(16.dp).clickable(actionStartActivity<MainActivity>()))
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                    Text(data.title ?: "Sonora", maxLines = 2, style = TextStyle(color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text(data.artist ?: "Tap play", maxLines = 1, style = TextStyle(color = Soft, fontSize = 12.sp))
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(L.drawable.lucide_ic_skip_back, "Previous", 38, actionRunCallback<PreviousAction>())
                Spacer(GlanceModifier.width(8.dp))
                PlayButton(data, 46)
                Spacer(GlanceModifier.width(8.dp))
                IconAction(L.drawable.lucide_ic_skip_forward, "Next", 38, actionRunCallback<NextAction>())
            }
        }
    }
}

@Composable
private fun SmallLayout(data: WidgetData) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(24.dp)) {
        Backdrop(data)
        Row(GlanceModifier.fillMaxSize().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(data, GlanceModifier.size(42.dp).cornerRadius(13.dp).clickable(actionStartActivity<MainActivity>()))
            Spacer(GlanceModifier.defaultWeight())
            PlayButton(data, 40)
        }
    }
}

@Composable
private fun WideLayout(data: WidgetData) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(26.dp)) {
        Backdrop(data)
        Row(GlanceModifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(data, GlanceModifier.size(58.dp).cornerRadius(15.dp).clickable(actionStartActivity<MainActivity>()))
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                Text(data.title ?: "Sonora", maxLines = 1, style = TextStyle(color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Text(subtitle(data), maxLines = 1, style = TextStyle(color = Soft, fontSize = 12.sp))
            }
            IconAction(L.drawable.lucide_ic_skip_back, "Previous", 34, actionRunCallback<PreviousAction>())
            PlayButton(data, 40)
            IconAction(L.drawable.lucide_ic_skip_forward, "Next", 34, actionRunCallback<NextAction>())
        }
    }
}

@Composable
private fun LargeLayout(data: WidgetData) {
    // Fit the height it's given: room for "Up next" and big artwork only when the widget is tall enough.
    val h = LocalSize.current.height
    val roomy = h >= 240.dp
    val art = if (roomy) 112.dp else if (h >= 205.dp) 88.dp else 68.dp
    Box(GlanceModifier.fillMaxSize().cornerRadius(28.dp)) {
        Backdrop(data)
        Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
            // Header: what this is, and the like.
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (data.isPlaying) "NOW PLAYING" else if (data.title != null) "PAUSED" else "SONORA",
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = ColorProvider(Color(data.accent)), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                )
                if (data.title != null) IconAction(
                    L.drawable.lucide_ic_heart, if (data.liked) "Unlike" else "Like", 30,
                    actionRunCallback<LikeAction>(), tint = if (data.liked) Color(0xFFFF2D55) else Color(0xB3FFFFFF),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                ArtImage(data, GlanceModifier.size(art).cornerRadius(20.dp).clickable(actionStartActivity<MainActivity>()))
                Spacer(GlanceModifier.width(16.dp))
                Column(GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                    Text(data.title ?: "Nothing playing", maxLines = 2, style = TextStyle(color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(data.artist ?: "Tap play for your music", maxLines = 1, style = TextStyle(color = Soft, fontSize = 14.sp))
                    data.upNext?.takeIf { roomy }?.let {
                        Spacer(GlanceModifier.height(10.dp))
                        Text("Up next", style = TextStyle(color = Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                        Text(it, maxLines = 1, style = TextStyle(color = Soft, fontSize = 13.sp))
                    }
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                IconAction(L.drawable.lucide_ic_skip_back, "Previous", 48, actionRunCallback<PreviousAction>())
                Spacer(GlanceModifier.width(18.dp))
                PlayButton(data, 58)
                Spacer(GlanceModifier.width(18.dp))
                IconAction(L.drawable.lucide_ic_skip_forward, "Next", 48, actionRunCallback<NextAction>())
            }
        }
    }
}

private fun subtitle(data: WidgetData): String = when {
    data.title == null -> "Tap play for your music"
    data.upNext != null && !data.isPlaying -> data.artist ?: ""
    else -> data.artist ?: ""
}

@Composable
private fun ArtImage(data: WidgetData, modifier: GlanceModifier) {
    val art = data.art
    if (art != null) {
        Image(ImageProvider(art), contentDescription = "Album art", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(ColorProvider(Color(0x26FFFFFF))), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(L.drawable.lucide_ic_music), contentDescription = null, modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(White),
            )
        }
    }
}

/** A solid circle in the album's bright colour (Sonora's volt when there's no cover), black icon on top. */
@Composable
private fun PlayButton(data: WidgetData, size: Int) {
    Box(
        GlanceModifier.size(size.dp).cornerRadius((size / 2).dp).background(ColorProvider(Color(data.accent)))
            .clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (data.isPlaying) L.drawable.lucide_ic_pause else L.drawable.lucide_ic_play),
            contentDescription = if (data.isPlaying) "Pause" else "Play",
            modifier = GlanceModifier.size((size * 0.44f).dp),
            colorFilter = ColorFilter.tint(ColorProvider(Color.Black)),
        )
    }
}

@Composable
private fun IconAction(res: Int, desc: String, size: Int, action: androidx.glance.action.Action, tint: Color = Color.White) {
    Box(GlanceModifier.size(size.dp).cornerRadius((size / 2).dp).clickable(action), contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(res), contentDescription = desc, modifier = GlanceModifier.size((size * 0.5f).dp),
            colorFilter = ColorFilter.tint(ColorProvider(tint)),
        )
    }
}
