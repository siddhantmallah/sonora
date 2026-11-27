package com.sonora.player.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sonora.player.playback.Artwork
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
)

/** Feeds now-playing state from the service to every widget, and survives process restarts. */
object WidgetBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(WidgetData())
    val state: StateFlow<WidgetData> = _state.asStateFlow()
    private var restored = false

    fun push(context: Context, title: String?, artist: String?, artUri: Uri?, isPlaying: Boolean) {
        val app = context.applicationContext
        scope.launch {
            val prev = _state.value
            val art = if (artUri == prev.artUri && prev.art != null) prev.art else Artwork.load(app, artUri, 360)
            _state.value = WidgetData(title, artist, artUri, art, isPlaying)
            app.getSharedPreferences("widget", Context.MODE_PRIVATE).edit()
                .putString("title", title).putString("artist", artist).putString("art", artUri?.toString())
                .apply()
            PlayerWidget().updateAll(app)
            CoverWidget().updateAll(app)
        }
    }

    suspend fun ensureRestored(context: Context) {
        if (restored || _state.value.title != null) return
        restored = true
        val p = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
        val uri = p.getString("art", null)?.let(Uri::parse)
        val art = withContext(Dispatchers.IO) { Artwork.load(context, uri, 360) }
        _state.value = WidgetData(p.getString("title", null), p.getString("artist", null), uri, art, false)
    }

    /** Connects a short-lived controller to the service and runs [block] on it. */
    suspend fun control(context: Context, block: (MediaController) -> Unit) = withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().await()
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

private val SMALL = DpSize(110.dp, 50.dp)
private val WIDE = DpSize(250.dp, 50.dp)
private val LARGE = DpSize(250.dp, 180.dp)

/** Resizable player: compact pill, wide bar with controls, or large card with big artwork. */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetBridge.ensureRestored(context)
        provideContent {
            val data by WidgetBridge.state.collectAsState()
            GlanceTheme {
                val size = LocalSize.current
                when {
                    size.height >= LARGE.height && size.width >= LARGE.width -> LargeLayout(data)
                    size.width >= WIDE.width -> WideLayout(data)
                    else -> SmallLayout(data)
                }
            }
        }
    }
}

/** Square artwork tile with a floating play button. */
class CoverWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetBridge.ensureRestored(context)
        provideContent {
            val data by WidgetBridge.state.collectAsState()
            GlanceTheme {
                Box(
                    GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(GlanceTheme.colors.secondaryContainer)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    ArtImage(data, GlanceModifier.fillMaxSize())
                    Box(GlanceModifier.padding(10.dp)) {
                        PlayButton(data.isPlaying, 48)
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

@Composable
private fun SmallLayout(data: WidgetData) {
    Row(
        GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(data, GlanceModifier.size(40.dp).cornerRadius(12.dp).clickable(actionStartActivity<MainActivity>()))
        Spacer(GlanceModifier.defaultWeight())
        PlayButton(data.isPlaying, 40)
    }
}

@Composable
private fun WideLayout(data: WidgetData) {
    Row(
        GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(data, GlanceModifier.size(56.dp).cornerRadius(14.dp))
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
            TitleText(data, 15)
        }
        Controls(data, 36)
    }
}

@Composable
private fun LargeLayout(data: WidgetData) {
    Column(
        GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(GlanceTheme.colors.widgetBackground).padding(14.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            ArtImage(data, GlanceModifier.size(110.dp).cornerRadius(20.dp).clickable(actionStartActivity<MainActivity>()))
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    "Now playing",
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(GlanceModifier.height(4.dp))
                TitleText(data, 18)
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Controls(data, 44)
        }
    }
}

@Composable
private fun TitleText(data: WidgetData, size: Int) {
    Text(
        data.title ?: "Sonora",
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = size.sp, fontWeight = FontWeight.Bold),
    )
    Text(
        data.artist ?: "Tap play to resume",
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = (size - 3).sp),
    )
}

@Composable
private fun ArtImage(data: WidgetData, modifier: GlanceModifier) {
    val art = data.art
    if (art != null) {
        Image(ImageProvider(art), contentDescription = "Album art", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(GlanceTheme.colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(L.drawable.lucide_ic_music),
                contentDescription = null,
                modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
            )
        }
    }
}

@Composable
private fun Controls(data: WidgetData, size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(L.drawable.lucide_ic_skip_back, "Previous", size, actionRunCallback<PreviousAction>())
        Spacer(GlanceModifier.width(6.dp))
        PlayButton(data.isPlaying, size + 8)
        Spacer(GlanceModifier.width(6.dp))
        IconAction(L.drawable.lucide_ic_skip_forward, "Next", size, actionRunCallback<NextAction>())
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, size: Int) {
    Box(
        GlanceModifier.size(size.dp).cornerRadius((size / 2).dp).background(GlanceTheme.colors.primary)
            .clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(if (isPlaying) L.drawable.lucide_ic_pause else L.drawable.lucide_ic_play),
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = GlanceModifier.size((size * 0.45f).dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
    }
}

@Composable
private fun IconAction(res: Int, desc: String, size: Int, action: androidx.glance.action.Action) {
    Box(GlanceModifier.size(size.dp).clickable(action), contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(res),
            contentDescription = desc,
            modifier = GlanceModifier.size((size * 0.55f).dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurface),
        )
    }
}
