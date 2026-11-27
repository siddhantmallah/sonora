package com.sonora.player.ui.aod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.composables.icons.lucide.R as L
import com.sonora.player.SonoraApp
import com.sonora.player.data.Song
import com.sonora.player.lyrics.Lyrics
import com.sonora.player.ui.theme.appFontFamily
import com.sonora.player.ui.components.Artwork
import com.sonora.player.youtube.YtIds
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.random.Random

/**
 * Sonora's always-on face: a big clock, the song and the line being sung, on pure black so an OLED screen stays
 * almost entirely off. Everything drifts a few pixels every minute so nothing burns in. Used both as an Activity (from
 * the player or Settings) and as Android's screen saver while charging ([SonoraDream]).
 */
@Composable
fun AlwaysOnFace(onClose: (() -> Unit)?) {
    val context = LocalContext.current
    val c = remember { (context.applicationContext as SonoraApp).container }
    val state by c.playerConnection.state.collectAsState()
    val volt = Color(0xFFEFFF1A)
    val dim = Color(0xFFB8B8B8)

    // Clock, ticking once a second (cheap: one Text).
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000L - value % 1000L) } }
    val is24 = remember { DateFormat.is24HourFormat(context) }
    val time = remember(now / 60_000, is24) { DateFormat.format(if (is24) "HH:mm" else "h:mm", Date(now)).toString() }
    val date = remember(now / 3_600_000) { DateFormat.format("EEEE, d MMMM", Date(now)).toString() }

    // Burn-in protection: the whole face moves to a new spot every minute.
    var shift by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(now / 60_000) { shift = Random.nextInt(-14, 15) to Random.nextInt(-22, 23) }
    val dx by animateDpAsState(shift.first.dp, tween(2400), label = "dx")
    val dy by animateDpAsState(shift.second.dp, tween(2400), label = "dy")

    // The line being sung right now (synced lyrics only).
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(state.mediaId) {
        lyrics = null
        val song = state.songId?.let { c.library.song(it) } ?: YtIds.statsId(state.mediaId)?.let { id ->
            Song(
                id = id, title = state.title, artist = state.artist, album = state.album, albumId = -1, artistId = -1,
                albumArtist = null, durationMs = state.durationMs, trackNumber = 0, year = 0, dateAddedSec = 0, path = "",
                mimeType = "", sizeBytes = 0, bitrate = 0, genre = null,
            )
        }
        if (song != null) lyrics = runCatching { c.lyrics.load(song) }.getOrNull()?.takeIf { it.synced }
    }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isPlaying, lyrics) {
        while (true) { pos = c.playerConnection.positionMs; if (!state.isPlaying || lyrics == null) break; delay(250) }
    }
    val line = lyrics?.let { l -> l.lines.getOrNull(l.indexAt(pos))?.text?.takeIf { it.isNotBlank() } }

    val fontName = c.settings.state.collectAsState().value.fontStyle
    val font = remember(fontName) { appFontFamily(fontName) }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onClose?.invoke() },
    ) {
        Column(
            Modifier.fillMaxSize().offset(dx, dy).padding(horizontal = 32.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.6f))
            Text(time, color = Color.White.copy(alpha = 0.9f), fontSize = 92.sp, fontWeight = FontWeight.Light, fontFamily = font, letterSpacing = (-2).sp)
            Text(date, color = dim, fontSize = 17.sp, fontFamily = font)
            Spacer(Modifier.weight(1f))
            if (state.hasMedia) {
                Artwork(state.artUri, Modifier.size(150.dp).alpha(0.72f), RoundedCornerShape(22.dp))
                Spacer(Modifier.height(22.dp))
                Text(
                    state.title, color = Color.White.copy(alpha = 0.92f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Text(
                    listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" · "),
                    color = dim, fontSize = 15.sp, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Box(Modifier.fillMaxWidth().height(72.dp).padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
                    if (line != null) Text(
                        line, color = volt.copy(alpha = 0.85f), fontSize = 19.sp, fontFamily = font, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    AodButton(L.drawable.lucide_ic_skip_back, "Previous", 26) { c.playerConnection.previous() }
                    AodButton(if (state.isPlaying) L.drawable.lucide_ic_pause else L.drawable.lucide_ic_play, "Play/pause", 34) { c.playerConnection.togglePlay() }
                    AodButton(L.drawable.lucide_ic_skip_forward, "Next", 26) { c.playerConnection.next() }
                }
            } else {
                Text("Nothing playing", color = dim, fontSize = 16.sp, fontFamily = font)
            }
            Spacer(Modifier.weight(0.5f))
            if (onClose != null) Text("Tap anywhere to close", color = Color.White.copy(alpha = 0.28f), fontSize = 12.sp, fontFamily = font)
        }
    }
}

@Composable
private fun AodButton(icon: Int, label: String, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.size((size + 30).dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), label, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(size.dp)) }
}

/** Full-screen Always-On view, opened from the player or Settings. Shows over the lock screen and keeps the screen on, dimmed. */
class AlwaysOnActivity : ComponentActivity() {
    private val c get() = (application as SonoraApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.12f }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        c.playerConnection.attach(this)
        setContent { AlwaysOnFace(onClose = { finish() }) }
    }

    override fun onDestroy() {
        c.playerConnection.detach(this)
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, AlwaysOnActivity::class.java))
    }
}

/** The same face as Android's screen saver (Settings → Display → Screen saver → Sonora), shown while charging or docked. */
class SonoraDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    private val c get() = (application as SonoraApp).container

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true // the buttons work; Back or the power key leaves
        isFullscreen = true
        isScreenBright = false
        c.playerConnection.attach(this)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SonoraDream)
            setViewTreeSavedStateRegistryOwner(this@SonoraDream)
            setContent { AlwaysOnFace(onClose = null) }
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.currentState = Lifecycle.State.DESTROYED
        c.playerConnection.detach(this)
        super.onDetachedFromWindow()
    }
}
