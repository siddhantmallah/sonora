package com.sonora.player.ui.player

import kotlin.math.min
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.max
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.preferredFrameRate
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.windowInsetsPadding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.sonora.player.data.Song
import com.sonora.player.data.formatDuration
import com.sonora.player.lyrics.Lyrics
import com.sonora.player.lyrics.LyricsCandidate
import com.sonora.player.lyrics.LyricsRepository
import com.sonora.player.playback.PlayerState
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.MenuItem
import com.sonora.player.ui.components.dragHandle
import com.sonora.player.ui.components.rememberDragReorder
import com.sonora.player.ui.screens.OutputCard
import com.sonora.player.ui.screens.quickScrollTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import com.composables.icons.lucide.R as L

private enum class Sheet { NONE, QUEUE, SLEEP, AUDIO, LYRICS_SEARCH, OUTPUT, STORY, MORE }

private val pages = listOf("Cover", "Visualizer", "Lyrics")
private val White = Color.White

@Composable
fun NowPlayingScreen(
    vm: MainViewModel,
    actions: UiActions,
    onCollapse: () -> Unit,
    onDrag: (Float) -> Unit = {},
    onDragEnd: (velocityY: Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by vm.player.collectAsStateWithLifecycle()
    val conn = vm.c.playerConnection
    val localSong: Song? = vm.song(state.songId)
    val ytSong = state.youtubeId?.let { vm.yt.song(it) }
    // YouTube tracks get a Song stand-in so lyrics, favorites and stats work the same way.
    val song: Song? = localSong ?: remember(state.mediaId, state.durationMs) { vm.nowPlayingAsSong() }
    val scope = rememberCoroutineScope()

    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var menu by remember { mutableStateOf(false) }
    // The visualizer style you picked is remembered across launches.
    val playerPrefs = run {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        remember { ctx.getSharedPreferences("player", android.content.Context.MODE_PRIVATE) }
    }
    var vizStyle by remember { mutableStateOf(VizStyle.entries.getOrElse(playerPrefs.getInt("viz_style2", 0)) { VizStyle.ORB }) }
    val nextViz = {
        vizStyle = VizStyle.entries[(vizStyle.ordinal + 1) % VizStyle.entries.size]
        playerPrefs.edit().putInt("viz_style2", vizStyle.ordinal).apply()
    }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val pager = rememberPagerState { pages.size }
    // Where the portrait visualizer page sits, and the player's own bounds, for the full-screen visualizer.
    var vizArea by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var screenBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // Landscape shows the visualizer or the lyrics on its stage. Keep that in step with the portrait pages, so
    // turning the phone keeps you on what you were looking at.
    var stageLyrics by rememberSaveable { mutableStateOf(false) }
    var pendingPage by remember { mutableStateOf(-1) }
    LaunchedEffect(landscape) {
        if (landscape) stageLyrics = pager.currentPage == 2
        else if (pendingPage >= 0) { val p = pendingPage; pendingPage = -1; pager.scrollToPage(p) }
    }

    val live = rememberLiveAudio(state.isPlaying)
    val fallback = listOf(Color(0xFF6D5BD0), Color(0xFF2B2250), Color(0xFFB2537A), Color(0xFF3A6EA5))
    val colors by rememberArtPalette(state.artUri, fallback)

    // Kept as State and only read by the seek bar / lyrics, so ticking doesn't recompose the whole screen.
    val position = produceState(0L, state.mediaId, state.isPlaying) {
        while (true) {
            value = conn.positionMs
            if (!state.isPlaying) break
            delay(200)
        }
    }

    // Background tone: the album's darkest colour, deepened so white text always reads.
    val baseTarget = remember(colors) {
        val darkest = colors.minBy { it.luminance() }
        androidx.compose.ui.graphics.lerp(darkest, Color.Black, 0.55f)
    }
    val base by androidx.compose.animation.animateColorAsState(baseTarget, androidx.compose.animation.core.tween(700), label = "base")

    // Swipe down anywhere (outside scrolling lyrics) to close; the shell animates the sheet itself.
    val velocity = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }
    // The player itself is what glass sheets blur.
    val glass = remember { dev.chrisbanes.haze.HazeState() }

    Box(
        modifier.fillMaxSize()
            .background(base)
            .pointerInput(Unit) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { velocity.resetTracking(); travelled = 0f },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        travelled += dy
                        velocity.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, travelled))
                        onDrag(dy)
                    },
                    onDragEnd = { onDragEnd(velocity.calculateVelocity().y) },
                    onDragCancel = { onDragEnd(0f) },
                )
            },
    ) {
      Box(Modifier.fillMaxSize().hazeSource(glass)) {
        LiveBackdrop(state.artUri, state.isPlaying, live, tiltActive = pager.currentPage == 0, base = base, showCover = !landscape)
        // Visualizer / lyrics pages dim the artwork progressively as you swipe to them.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (landscape) 0f else (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f) }
                // Near-black stage so the neon visualizer pops (the lyrics page paints its own sky on top).
                .background(androidx.compose.ui.graphics.lerp(base, Color.Black, 0.8f).copy(alpha = 0.93f)),
        )
        // The lyrics page gets its own Home-style "sky": the album's colours as a slowly drifting gradient that replaces
        // the artwork, so the words always read first (like the hero on Home, but painted from this song).
        // Only drifts while the lyrics page is (partly) on screen and music is playing; on the cover, or paused, it
        // would redraw the whole player every frame for nothing. A 16-second sweep looks the same at ~30 fps.
        val onLyrics by remember(landscape) {
            androidx.compose.runtime.derivedStateOf { if (landscape) stageLyrics else pager.currentPage + pager.currentPageOffsetFraction > 1.001f }
        }
        val landLyrics by animateFloatAsState(if (landscape && stageLyrics) 1f else 0f, tween(400), label = "landLyrics")
        val driftClock = remember { androidx.compose.runtime.mutableFloatStateOf(0f) } // 0..2, ping-pong
        LaunchedEffect(onLyrics, state.isPlaying) {
            if (!onLyrics || !state.isPlaying) return@LaunchedEffect
            var last = System.nanoTime()
            while (true) {
                delay(33)
                val now = System.nanoTime()
                driftClock.floatValue = (driftClock.floatValue + (now - last) / 16e9f) % 2f
                last = now
            }
        }
        val skyColors = remember(colors) {
            val sorted = colors.sortedByDescending { c -> maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) }
            sorted.take(3).map { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.40f) }.let { if (it.size > 1) it else it + base }
        }
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (landscape) landLyrics else (pager.currentPage + pager.currentPageOffsetFraction - 1f).coerceIn(0f, 1f) }
                .drawBehind {
                    if (!onLyrics) return@drawBehind
                    val drift = driftClock.floatValue.let { if (it <= 1f) it else 2f - it }
                    drawRect(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            skyColors,
                            start = androidx.compose.ui.geometry.Offset(size.width * drift, 0f),
                            end = androidx.compose.ui.geometry.Offset(size.width * (1f - drift), size.height),
                        ),
                    )
                    // A soft glow drifting across, like the light in Home's sky.
                    drawCircle(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(skyColors.first().copy(alpha = 0.55f), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(size.width * (0.2f + 0.6f * drift), size.height * 0.3f),
                            radius = size.width * 0.9f,
                        ),
                        radius = size.width * 0.9f,
                        center = androidx.compose.ui.geometry.Offset(size.width * (0.2f + 0.6f * drift), size.height * 0.3f),
                    )
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.45f))))
                },
        )

        // Portrait: the visualizer spans the whole screen behind the player while its page is showing, fading as you
        // swipe away. Its shape is centred where the page is.
        if (!landscape) {
            val nearViz by remember { androidx.compose.runtime.derivedStateOf { abs(pager.currentPage + pager.currentPageOffsetFraction - 1f) < 1f } }
            Box(Modifier.fillMaxSize().onGloballyPositioned { screenBounds = it.boundsInRoot() })
            val area = vizArea
            val scr = screenBounds
            if (nearViz && area.width > 0f && scr.width > 0f) {
                Visualizer(
                    live, colors, vizStyle, state.artUri,
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = (1f - abs(pager.currentPage + pager.currentPageOffsetFraction - 1f)).coerceIn(0f, 1f)
                    },
                    focusX = (area.center.x - scr.left) / scr.width,
                    span = area.width / scr.width,
                    focusY = (area.center.y - scr.top) / scr.height,
                    spanY = area.height / scr.height,
                    edges = false,
                )
            }
        }
        if (landscape) LandscapeStage(
            vm, state, live, colors, position, vizStyle, nextViz, song, onCollapse, base,
            showLyrics = stageLyrics,
            onShowLyrics = { stageLyrics = it; pendingPage = if (it) 2 else 1 },
            openSheet = { sheet = it },
        ) else
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                // On the lyrics page, the whole player (seek wave, glass buttons) runs at a steady 60 fps too, so it
                // doesn't drag the screen back to 120 Hz and squeeze the lyrics into 8 ms frames.
                .then(if (onLyrics) Modifier.preferredFrameRate(60f) else Modifier),
        ) {
            // ---- Top bar: glass buttons, page dots in a glass capsule (same glass as the app's dock)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassCircle(L.drawable.lucide_ic_chevron_down, "Close", onCollapse)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.glassPill(RoundedCornerShape(50)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pages.forEachIndexed { i, label ->
                            val selected = pager.currentPage == i
                            val w by animateDpAsState(if (selected) 18.dp else 6.dp, label = "dot")
                            Box(
                                Modifier.clip(CircleShape)
                                    .clickable { scope.launch { pager.quickScrollTo(i) } }
                                    .padding(horizontal = 4.dp, vertical = 9.dp)
                                    .semantics { contentDescription = label },
                            ) {
                                Box(Modifier.width(w).height(6.dp).clip(CircleShape).background(White.copy(alpha = if (selected) 0.95f else 0.4f)))
                            }
                        }
                    }
                }
                GlassCircle(L.drawable.lucide_ic_ellipsis, "More") { sheet = Sheet.MORE }
            }

            // ---- Pages: live cover / visualizer / lyrics
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), beyondViewportPageCount = 0) { page ->
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    when (page) {
                        // The live artwork is the backdrop of the whole screen; the cover page just leaves it clear.
                        0 -> Spacer(Modifier.fillMaxSize())
                        1 -> Box(Modifier.fillMaxSize()) {
                            // Marks where the shape sits (above the style chip); the visualizer itself is drawn across
                            // the whole screen behind the player, so it isn't boxed into this page.
                            Spacer(Modifier.fillMaxSize().padding(top = 16.dp, bottom = 48.dp).onGloballyPositioned { vizArea = it.boundsInRoot() })
                            Surface(
                                onClick = nextViz,
                                shape = RoundedCornerShape(50),
                                color = White.copy(alpha = 0.14f),
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) {
                                Text(
                                    vizStyle.label,
                                    color = White, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                )
                            }
                            when {
                                state.audio.offload -> Column(
                                    Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "Battery saver (audio offload) is on, so your phone's audio chip decodes the music and the visualizer can't see it.",
                                        color = White.copy(alpha = 0.85f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Box(
                                        Modifier.clip(RoundedCornerShape(50)).background(White).clickable {
                                            vm.c.settings.set(com.sonora.player.data.SettingsRepository.Keys.audioOffload, false)
                                        }.padding(horizontal = 18.dp, vertical = 11.dp),
                                    ) { Text("Turn off battery saver", color = Color.Black, style = MaterialTheme.typography.labelLarge) }
                                }
                                !state.isPlaying -> Text("Play something to see it move", color = White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
                            }
                        }
                        else -> if (song != null) {
                            LyricsPanel(vm, song, position, onSearch = { sheet = Sheet.LYRICS_SEARCH }, depthBlur = true, live = live)
                        } else {
                            Text("Lyrics are available for library songs", color = White.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 24.dp)) {
                // ---- Title
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = White, maxLines = 1, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500),
                        )
                        Text(
                            state.artist, style = MaterialTheme.typography.titleMedium, color = White.copy(alpha = 0.72f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = localSong != null || ytSong?.artists?.any { it.browseId != null } == true) {
                                onCollapse()
                                if (localSong != null) actions.artist(localSong.primaryArtist)
                                else ytSong?.artists?.firstOrNull { it.browseId != null }?.browseId?.let(actions::yt)
                            },
                        )
                    }
                    val favorites by vm.favorites.collectAsStateWithLifecycle()
                    val liked = com.sonora.player.youtube.YtIds.statsId(state.mediaId) in favorites
                    IconButton(onClick = { vm.toggleFavoriteMedia(state.mediaId) }) {
                        com.sonora.player.ui.components.FavoriteHeart(liked, size = 28.dp)
                    }
                }

                // ---- Seek bar + quality badge (Apple puts "Lossless" right under the scrubber)
                Spacer(Modifier.height(10.dp))
                SeekBar(position, state.durationMs, state.isPlaying, live, colors) { conn.seekTo(it) }
                if (state.audio.summary != null) {
                    Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
                        QualityChip(state) { sheet = Sheet.AUDIO }
                    }
                }

                // ---- Transport
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { conn.toggleShuffle() }) {
                        LIcon(L.drawable.lucide_ic_shuffle, "Shuffle", tint = if (state.shuffle) White else White.copy(alpha = 0.45f))
                    }
                    IconButton(onClick = { conn.previous() }, Modifier.size(60.dp)) {
                        LIcon(L.drawable.lucide_ic_skip_back, "Previous", tint = White, size = 34.dp)
                    }
                    PlayPauseButton(state.isPlaying, state.isBuffering) { conn.togglePlay() }
                    IconButton(onClick = { conn.next() }, Modifier.size(60.dp)) {
                        LIcon(L.drawable.lucide_ic_skip_forward, "Next", tint = White, size = 34.dp)
                    }
                    IconButton(onClick = { conn.cycleRepeat() }) {
                        LIcon(
                            if (state.repeatMode == Player.REPEAT_MODE_ONE) L.drawable.lucide_ic_repeat_1 else L.drawable.lucide_ic_repeat,
                            "Repeat", tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) White else White.copy(alpha = 0.45f),
                        )
                    }
                }

                // ---- Volume (system media volume, like Apple Music)
                VolumeSlider()

                // ---- Up next: a glass row with the next song's artwork
                val next = remember(state.queue, state.currentIndex) {
                    val pos = state.queue.indexOfFirst { it.index == state.currentIndex }
                    state.queue.getOrNull(pos + 1)
                }
                if (next != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).glassPill(RoundedCornerShape(16.dp))
                            .clickable { sheet = Sheet.QUEUE }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(next.artUri, Modifier.size(36.dp), RoundedCornerShape(8.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Up next", style = MaterialTheme.typography.labelSmall, color = White.copy(alpha = 0.55f))
                            Text(
                                next.title + if (next.artist.isNotBlank()) " · ${next.artist}" else "",
                                style = MaterialTheme.typography.bodyMedium, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        LIcon(L.drawable.lucide_ic_chevron_right, null, tint = White.copy(alpha = 0.5f), size = 18.dp)
                    }
                }

                // ---- Bottom bar: lyrics · output device · sleep · queue, in a glass capsule like the app's dock
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp).height(56.dp)
                        .glassPill(RoundedCornerShape(26.dp)).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { scope.launch { pager.quickScrollTo(if (pager.currentPage == 2) 0 else 2) } }) {
                        LIcon(L.drawable.lucide_ic_mic_vocal, "Lyrics", tint = if (pager.currentPage == 2) White else White.copy(alpha = 0.6f))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { OutputPill(vm) { sheet = Sheet.OUTPUT } }
                    IconButton(onClick = { sheet = Sheet.SLEEP }) {
                        LIcon(L.drawable.lucide_ic_timer, "Sleep timer",
                            tint = if (state.sleepEndsAt > 0 || state.sleepEndOfTrack) White else White.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { sheet = Sheet.QUEUE }) {
                        LIcon(L.drawable.lucide_ic_list_music, "Queue", tint = White.copy(alpha = 0.6f))
                    }
                }
            }
        }
      }

        // Glass sheets live inside the player so they can blur it.
        when (sheet) {
            Sheet.OUTPUT -> OutputSheet(vm, state.audio, glass, onAudioDetails = { sheet = Sheet.AUDIO }) { sheet = Sheet.NONE }
            Sheet.AUDIO -> AudioSheet(vm, state, glass) { sheet = Sheet.NONE }
            Sheet.QUEUE -> QueueSheet(vm, state, glass) { sheet = Sheet.NONE }
            Sheet.SLEEP -> SleepSheet(state, glass, onSet = { m, eot -> conn.setSleepTimer(m, eot) }) { sheet = Sheet.NONE }
            Sheet.STORY -> SongStorySheet(vm, state, glass) { sheet = Sheet.NONE }
            Sheet.MORE -> MoreSheet(vm, actions, state, localSong, ytSong, song != null, glass, onCollapse, onOpen = { sheet = it }) { sheet = Sheet.NONE }
            Sheet.LYRICS_SEARCH -> if (song != null) {
                LyricsSearchSheet(vm, song, glass) { sheet = Sheet.NONE; scope.launch { pager.quickScrollTo(2) } }
            }
            else -> Unit
        }
    }

    when (sheet) {
        Sheet.QUEUE, Sheet.SLEEP, Sheet.STORY, Sheet.MORE -> Unit
        Sheet.AUDIO, Sheet.OUTPUT -> Unit
        Sheet.LYRICS_SEARCH -> Unit
        Sheet.NONE -> Unit
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, buffering: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(if (playing) 1f else 0.94f, spring(dampingRatio = 0.5f), label = "pp")
    Box(
        Modifier.size(68.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(White)
            .clickable(interactionSource = press, indication = androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (buffering) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
        else LIcon(if (playing) L.drawable.lucide_ic_pause else L.drawable.lucide_ic_play, "Play/pause", tint = Color.Black, size = 34.dp)
    }
}

/**
 * Wavy seek bar in the style of Samsung / Android's media controls.
 *
 *  - The thumb moves continuously: the player position is extrapolated every frame between updates instead of
 *    jumping every 200 ms.
 *  - The played part is a wave in the artwork's colours. It always flows at a steady pace while playing (even
 *    without audio analysis), and its height follows an eased envelope of the bass and beats: it swells on
 *    loud, beat-heavy passages and relaxes in quiet ones instead of flickering frame to frame.
 *  - The wave tapers to flat at both ends so it meets the thumb cleanly, and flattens out when paused or dragging.
 *  - After a tap or drag the bar holds the new spot until the player confirms it, so it never snaps back.
 */
@Composable
fun SeekBar(
    positionState: androidx.compose.runtime.State<Long>,
    duration: Long,
    playing: Boolean,
    live: LiveAudio,
    colors: List<Color>,
    onSeek: (Long) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek = remember { SeekMotion() }
    val currentOnSeek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    fun commit(f: Float) {
        val ms = (f * duration).toLong()
        seek.hold(ms)
        currentOnSeek(ms)
    }
    // Feed the clock with real positions as they arrive.
    LaunchedEffect(seek) {
        androidx.compose.runtime.snapshotFlow { positionState.value }.collect { seek.report(it) }
    }
    // Frame loop: runs while playing, or until the wave has settled after a pause.
    LaunchedEffect(seek, playing, duration) {
        var last = 0L
        while (true) {
            val settled = androidx.compose.runtime.withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                seek.step(dt, playing, drag != null, live.analyzer.bass, live.analyzer.beat)
            }
            if (!playing && settled) break
        }
    }
    val thumbH by animateDpAsState(if (drag != null) 26.dp else 18.dp, label = "thumbH")
    val gradient = remember(colors) {
        // Black-and-white or washed-out artwork would give a grey wave; use Sonora's own colours instead.
        val hsv = FloatArray(3)
        val colourful = colors.any { c ->
            android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            hsv[1] > 0.22f && hsv[2] > 0.18f
        }
        val src = if (colourful) colors else listOf(
            com.sonora.player.ui.theme.Brand.Violet, com.sonora.player.ui.theme.Brand.Pink, com.sonora.player.ui.theme.Brand.Coral,
        )
        src.take(3).map { androidx.compose.ui.graphics.lerp(it, White, 0.25f) } + androidx.compose.ui.graphics.lerp(src.last(), White, 0.55f)
    }
    val fraction by remember(duration) {
        androidx.compose.runtime.derivedStateOf {
            drag ?: if (duration > 0) (seek.shownMs / duration).coerceIn(0f, 1f) else 0f
        }
    }
    Column {
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .pointerInput(duration) {
                    detectTapGestures { o -> if (duration > 0) commit((o.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { ch, dx -> ch.consume(); drag = ((drag ?: 0f) + dx / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { if (duration > 0) commit(it) }; drag = null },
                        onDragCancel = { drag = null },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                val y = size.height / 2
                val x = size.width * fraction
                val stroke = 4.dp.toPx()
                val gap = 4.dp.toPx()
                // Unplayed: calm straight line, separated from the thumb by a small gap.
                if (x + gap < size.width) {
                    drawLine(White.copy(alpha = 0.22f), Offset(x + gap, y), Offset(size.width, y), stroke, StrokeCap.Round)
                }
                // Played: the wave.
                val end = (x - gap).coerceAtLeast(0f)
                if (end > 1f) {
                    val a = seek.amp * (2.6.dp.toPx() + seek.env * 4.2.dp.toPx())
                    val wavelength = 30.dp.toPx()
                    val k = 2f * Math.PI.toFloat() / wavelength
                    val rampIn = 10.dp.toPx()
                    val rampOut = 16.dp.toPx()
                    val phase = seek.phase
                    val path = androidx.compose.ui.graphics.Path()
                    var px = 0f
                    path.moveTo(0f, y)
                    while (px < end) {
                        px = (px + 2f).coerceAtMost(end)
                        val taper = smooth01(px / rampIn) * smooth01((end - px) / rampOut)
                        path.lineTo(px, y + a * taper * kotlin.math.sin(px * k - phase))
                    }
                    drawPath(
                        path, androidx.compose.ui.graphics.Brush.horizontalGradient(gradient, 0f, end.coerceAtLeast(1f)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                    )
                }
                // Vertical pill thumb.
                val tw = 5.dp.toPx()
                val th = thumbH.toPx()
                drawRoundRect(White, Offset(x - tw / 2, y - th / 2), androidx.compose.ui.geometry.Size(tw, th), androidx.compose.ui.geometry.CornerRadius(tw / 2))
            }
        }
        val shownSec by remember(duration) { androidx.compose.runtime.derivedStateOf { (fraction * duration / 1000).toLong() } }
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(shownSec * 1000), style = MaterialTheme.typography.labelSmall, color = White.copy(alpha = 0.65f))
            Spacer(Modifier.weight(1f))
            Text("-" + formatDuration((duration - shownSec * 1000).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = White.copy(alpha = 0.65f))
        }
    }
}

private fun smooth01(v: Float): Float {
    val t = v.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Per-frame state for [SeekBar]: an extrapolated position clock plus the wave's phase, height and envelope. */
@androidx.compose.runtime.Stable
private class SeekMotion {
    var shownMs by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var phase by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var amp by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set
    var env by androidx.compose.runtime.mutableFloatStateOf(0f)
        private set

    private var anchorMs = 0L
    private var anchorNs = 0L
    private var heldMs = -1L
    private var heldAtNs = 0L
    private var initialised = false

    /** A real position from the player. */
    fun report(ms: Long) {
        val now = System.nanoTime()
        if (heldMs >= 0) {
            // Ignore stale positions until the player has caught up with the seek (or 2.5 s pass).
            if (kotlin.math.abs(ms - heldMs) > 1500 && now - heldAtNs < 2_500_000_000L) return
            heldMs = -1
        }
        anchorMs = ms
        anchorNs = now
        if (!initialised || kotlin.math.abs(shownMs - ms) > 1500f) {
            shownMs = ms.toFloat()
            initialised = true
        }
    }

    fun hold(ms: Long) {
        heldMs = ms
        heldAtNs = System.nanoTime()
        anchorMs = ms
        anchorNs = heldAtNs
        shownMs = ms.toFloat()
    }

    /** Advances one frame; returns true once everything has come to rest (only meaningful when paused). */
    fun step(dt: Float, playing: Boolean, dragging: Boolean, bass: Float, beat: Float): Boolean {
        // Position: extrapolate from the last report (capped so a stalled player doesn't run away), then ease.
        val sinceAnchor = (System.nanoTime() - anchorNs) / 1e6f
        val target = anchorMs + if (playing) sinceAnchor.coerceAtMost(1200f) else 0f
        val diff = target - shownMs
        shownMs = if (kotlin.math.abs(diff) > 1500f) target else shownMs + diff * (1f - kotlin.math.exp(-dt * 14f))

        // Height envelope: quick attack, slow release, so it breathes with the music instead of flickering.
        val energy = (bass * 0.85f + beat * 0.6f).coerceIn(0f, 1f)
        val rate = if (energy > env) 9f else 2.2f
        env += (energy - env) * (1f - kotlin.math.exp(-dt * rate))
        val ampTarget = if (playing && !dragging) 1f else 0f
        amp += (ampTarget - amp) * (1f - kotlin.math.exp(-dt * 6f))

        // Flow: steady pace, a little quicker when the music is intense.
        val twoPi = 2f * Math.PI.toFloat()
        phase = (phase + dt * twoPi * (0.75f + env * 0.6f) * amp) % (twoPi * 1000f)
        return amp < 0.01f && kotlin.math.abs(diff) < 5f
    }
}

@Composable
private fun QualityChip(state: PlayerState, onClick: () -> Unit) {
    val a = state.audio
    val badge = when {
        a.isHiRes -> "HI-RES"
        a.isLossless -> "LOSSLESS"
        else -> null
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = White.copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (badge != null) {
                Text(
                    badge, color = Color.Black, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(com.sonora.player.ui.theme.Brand.Gold, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(8.dp))
            } else {
                LIcon(L.drawable.lucide_ic_audio_lines, null, size = 14.dp, tint = White)
                Spacer(Modifier.width(6.dp))
            }
            Text(a.summary.orEmpty(), color = White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ lyrics

@Composable
private fun LyricsPanel(vm: MainViewModel, song: Song, positionState: androidx.compose.runtime.State<Long>, onSearch: () -> Unit, depthBlur: Boolean = true, live: LiveAudio? = null) {
    val playing by remember(vm) { vm.player.map { it.isPlaying }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    val version by vm.c.lyrics.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Re-run once the length is known (streams report it a moment after they start), which matching relies on.
    val result by produceState<Pair<Boolean, Lyrics?>>(false to null, song.id, version, song.durationMs > 0) {
        if (value.second == null) value = false to null
        value = true to vm.c.lyrics.load(song)
    }
    val (done, loaded) = result
    // Unsynced lyrics get estimated timings so the current line can still be highlighted and followed.
    val lyrics = remember(loaded, song.durationMs) {
        if (loaded != null && !loaded.synced && song.durationMs > 0) com.sonora.player.lyrics.LrcParser.estimate(loaded, song.durationMs) else loaded
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { vm.c.app.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull()
            }
            if (!text.isNullOrBlank()) vm.c.lyrics.save(song.id, text, "Imported file")
        }
    }
    var timing by remember(song.id) { mutableStateOf(false) }

    // A steady 60 fps for the lyrics: word highlights and the glide look the same as at 120, but each frame gets a
    // full 16 ms instead of racing 8 ms deadlines (the fading list edges redraw the whole list every frame).
    Box(Modifier.fillMaxSize().preferredFrameRate(60f)) {
    when {
        !done -> LyricsSkeleton()
        lyrics == null || lyrics.lines.none { it.text.isNotBlank() } -> Column(
            Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(64.dp).glassPill(CircleShape), contentAlignment = Alignment.Center) {
                LIcon(L.drawable.lucide_ic_mic_vocal, null, tint = White.copy(alpha = 0.85f), size = 28.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text("No lyrics for this song yet", style = MaterialTheme.typography.titleMedium, color = White)
            Spacer(Modifier.height(4.dp))
            Text(
                "Search LRCLIB with a different title, or add an .lrc file.",
                style = MaterialTheme.typography.bodySmall, color = White.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LyricsPill("Search", L.drawable.lucide_ic_search, onSearch)
                LyricsPill("Add .lrc file", L.drawable.lucide_ic_file_text) { importer.launch(arrayOf("*/*")) }
            }
        }
        !lyrics.synced -> Column(Modifier.fillMaxSize()) {
            val scroll = rememberScrollState()
            Column(
                Modifier.weight(1f).fillMaxWidth().fadingEdges().verticalScroll(scroll).padding(vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                lyrics.lines.filter { it.text.isNotBlank() }.forEach { line ->
                    Text(line.text, style = PlainLyricStyle.copy(fontFamily = com.sonora.player.ui.theme.LocalAppFontFamily.current), color = White.copy(alpha = 0.88f))
                }
            }
            LyricsFooter(lyrics, synced = false, timing = false, onTiming = {}, onSearch = onSearch, onOffset = null)
        }
        else -> {
            val conn = vm.c.playerConnection
            val current by produceState(lyrics.indexAt(conn.positionMs), lyrics, playing) {
                while (true) {
                    val pos = conn.positionMs
                    val idx = lyrics.indexAt(pos)
                    value = idx
                    if (!playing) break
                    // Sleep until the next line starts (a touch early so the scroll lands as it's sung), but never
                    // longer than 250 ms, so seeks and timing changes are picked up quickly.
                    val nextAt = lyrics.lines.getOrNull(idx + 1)?.let { it.timeMs - lyrics.offsetMs - LINE_LEAD_MS }
                    val wait = if (nextAt == null) 250L else (nextAt - pos).coerceIn(8L, 250L)
                    delay(wait)
                }
            }
            val list = rememberLazyListState()
            // Follow the song, but leave the list alone for a few seconds after you scroll it yourself.
            var userScrolledAt by remember { mutableStateOf(0L) }
            LaunchedEffect(list.isScrollInProgress) {
                if (list.isScrollInProgress && !autoScrolling) userScrolledAt = System.currentTimeMillis()
            }
            LaunchedEffect(current) {
                if (current >= 0 && System.currentTimeMillis() - userScrolledAt > 3500) {
                    autoScrolling = true
                    try {
                        val info = list.layoutInfo
                        val anchor = (info.viewportSize.height * LYRIC_ANCHOR).toInt()
                        val item = info.visibleItemsInfo.firstOrNull { it.index == current + 1 }
                        if (item != null) {
                            // On screen: glide it to the anchor. Item offsets don't include the top padding, so add
                            // it back (viewportStartOffset is minus that padding) to get where the line really is.
                            list.animateScrollBy(
                                (item.offset - info.viewportStartOffset - anchor).toFloat(),
                                tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)),
                            )
                        } else {
                            // Far away (after a seek): jump straight there. Offset 0 already sits below the top
                            // padding, which is the anchor.
                            list.scrollToItem(current + 1)
                        }
                    } finally {
                        autoScrolling = false
                    }
                }
            }
            Column(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = list,
                    // No fade layer over the whole list (it had to be redrawn every frame as the sung line
                    // changed); each line fades itself near the edges instead, below.
                    modifier = Modifier.fillMaxSize(),
                    // Room above the first line and below the last, sized to the visible height, so any line can reach the anchor.
                    contentPadding = PaddingValues(top = maxHeight * LYRIC_ANCHOR, bottom = maxHeight * (1f - LYRIC_ANCHOR)),
                ) {
                    item { Spacer(Modifier.height(1.dp)) }
                    itemsIndexed(lyrics.lines) { i, line ->
                        val active = i == current
                        val distance = if (current < 0) i + 1 else abs(i - current)
                        val seek = { userScrolledAt = 0L; vm.c.playerConnection.seekTo((line.timeMs - lyrics.offsetMs).coerceAtLeast(0)) }
                        val mod = Modifier.fillMaxWidth()
                            // Fades out near the top and bottom of the list, by where the line sits. Alpha is applied
                            // straight to the line's drawing (no extra layer), and only re-read when it scrolls.
                            .graphicsLayer {
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                alpha = list.edgeFade(i + 1)
                            }
                            .then(
                                // Depth: lines further from the one being sung soften a little.
                                // (Off in landscape: the wide lines make the per-frame blur too costly while scrolling.)
                                // Only the two lines either side get it (further ones sit in the fade), which keeps it cheap.
                                if (depthBlur && !active && current >= 0 && distance in 1..2 && android.os.Build.VERSION.SDK_INT >= 31)
                                    Modifier.blur(distance.toFloat().dp * 0.6f) else Modifier,
                            )
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = seek)
                            .padding(vertical = 9.dp, horizontal = 6.dp)
                        when {
                            line.text.isBlank() -> InstrumentalDots(active, mod)
                            active && line.words.isNotEmpty() -> KaraokeLine(line, lyrics.offsetMs, vm.c.playerConnection, playing, live, mod)
                            else -> {
                                val alpha by animateFloatAsState(if (active) 1f else if (i < current) 0.30f else 0.42f, tween(350), label = "lyric-alpha")
                                Text(line.text, style = LyricStyle.copy(fontFamily = com.sonora.player.ui.theme.LocalAppFontFamily.current), color = White.copy(alpha = alpha), modifier = mod)
                            }
                        }
                    }
                }
                }
                LyricsFooter(
                    lyrics, synced = true, timing = timing, onTiming = { timing = !timing }, onSearch = onSearch,
                    onOffset = if (lyrics.estimated) null else { delta -> scope.launch { vm.c.lyrics.adjustOffset(song.id, delta, lyrics) } },
                )
            }
        }
    }
    }
}

/** Where the line being sung sits, as a share of the lyrics' visible height from the top. */
private const val LYRIC_ANCHOR = 0.3f

/** Lines switch (and start scrolling) this much before they're sung, so the glide finishes on the first word. */
private const val LINE_LEAD_MS = 150L

/**
 * How visible item [index] is near the list's edges: fading in over the top 14% of the visible height and out over
 * the bottom 22%, judged by the item's centre. Same feel as the old full-list gradient, without its extra layer.
 */
private fun androidx.compose.foundation.lazy.LazyListState.edgeFade(index: Int): Float {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 1f
    val h = info.viewportSize.height.toFloat()
    if (h <= 0f) return 1f
    val y = (item.offset - info.viewportStartOffset + item.size / 2f) / h
    val top = (y / 0.14f).coerceIn(0f, 1f)
    val bottom = ((1f - y) / 0.22f).coerceIn(0f, 1f)
    val a = min(top, bottom)
    return a * a * (3f - 2f * a)
}

/** Set while the lyrics list scrolls itself, so that isn't mistaken for the user scrolling. */
private var autoScrolling = false

private val LyricStyle = androidx.compose.ui.text.TextStyle(
    fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp),
    lineHeight = androidx.compose.ui.unit.TextUnit(37f, androidx.compose.ui.unit.TextUnitType.Sp),
    fontWeight = FontWeight.Bold,
    letterSpacing = androidx.compose.ui.unit.TextUnit(-0.4f, androidx.compose.ui.unit.TextUnitType.Sp),
)

private val PlainLyricStyle = androidx.compose.ui.text.TextStyle(
    fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
    lineHeight = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp),
    fontWeight = FontWeight.SemiBold,
    letterSpacing = androidx.compose.ui.unit.TextUnit(-0.2f, androidx.compose.ui.unit.TextUnitType.Sp),
)

/** Fades content out at the top and bottom instead of a hard cut. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Transparent, 0.12f to Color.Black, 0.80f to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/** Gentle placeholder lines while lyrics load. */
@Composable
private fun LyricsSkeleton() {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.08f, 0.16f, androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse",
    )
    Column(Modifier.fillMaxSize().padding(top = 150.dp, start = 6.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf(0.82f, 0.64f, 0.9f, 0.52f, 0.74f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(26.dp).clip(RoundedCornerShape(8.dp)).background(White.copy(alpha = pulse)))
        }
    }
}

/** "• • •" for instrumental breaks; breathes while it's the current line. */
@Composable
private fun InstrumentalDots(active: Boolean, modifier: Modifier) {
    if (!active) {
        // Not the current line: still dots, no animation running.
        Row(modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { Box(Modifier.size(10.dp).graphicsLayer { scaleX = 0.905f; scaleY = 0.905f }.clip(CircleShape).background(White.copy(alpha = 0.35f))) }
        }
        return
    }
    val t by androidx.compose.animation.core.rememberInfiniteTransition(label = "dots").animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "t",
    )
    Row(modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            val phase = ((t * 3f - i) % 3f + 3f) % 3f
            val lit = if (active) (1f - (phase - 0.5f).let { it * it }).coerceIn(0.35f, 1f) else 0.35f
            Box(Modifier.size(10.dp).graphicsLayer { scaleX = 0.8f + 0.3f * lit; scaleY = scaleX }.clip(CircleShape).background(White.copy(alpha = lit)))
        }
    }
}

@Composable
private fun LyricsPill(label: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier.glassPill(RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LIcon(icon, null, tint = White, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = White, style = MaterialTheme.typography.labelLarge)
    }
}

/** Quiet footer: where the lyrics came from, timing adjustment (synced only), and "find other lyrics", in the player's glass. */
@Composable
private fun LyricsFooter(lyrics: Lyrics, synced: Boolean, timing: Boolean, onTiming: () -> Unit, onSearch: () -> Unit, onOffset: ((Long) -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (timing && onOffset != null) {
            Row(Modifier.glassPill(RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) {
                Text("Earlier", color = White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { onOffset(500) }.padding(horizontal = 14.dp, vertical = 9.dp))
                Text("%+.1fs".format(lyrics.offsetMs / 1000f), color = White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Text("Later", color = White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { onOffset(-500) }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        } else {
            Row(
                Modifier.weight(1f, fill = false).glassPill(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LIcon(
                    if (synced && !lyrics.estimated) L.drawable.lucide_ic_mic_vocal else L.drawable.lucide_ic_file_text, null,
                    tint = White.copy(alpha = 0.75f), size = 14.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        lyrics.estimated -> "${lyrics.source} · timing estimated"
                        synced -> "${lyrics.source} · synced"
                        else -> "${lyrics.source} · not synced"
                    },
                    style = MaterialTheme.typography.labelMedium, color = White.copy(alpha = 0.8f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        if (onOffset != null) {
            FooterCircle(L.drawable.lucide_ic_timer_reset, "Adjust timing", active = timing, onClick = onTiming)
            Spacer(Modifier.width(8.dp))
        }
        FooterCircle(L.drawable.lucide_ic_search, "Find other lyrics", onClick = onSearch)
    }
}

@Composable
private fun FooterCircle(icon: Int, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp)
            .then(if (active) Modifier.clip(CircleShape).background(White) else Modifier.glassPill(CircleShape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LIcon(icon, label, tint = if (active) Color.Black else White.copy(alpha = 0.85f), size = 17.dp) }
}

/**
 * The current lyric line, filled word by word as it is sung (Apple-Music style). Reads the player position every
 * frame, but only this one line recomposes.
 */
/**
 * Voice-driven words: the live audio shapes each word as it's sung. Its level in the vocal range (~250 Hz-3.5 kHz)
 * swells the word and, for loud words, thickens the letters; a held note slowly stretches and glows; whispers stay
 * thin and a little dim; the whole line nudges up on each beat. Everything eases back once the word is done, so the
 * line settles into the plain lyric look when the next one starts.
 */
private class VoiceFx(n: Int) {
    /** Loudest the voice got while each word was sung, 0..1. */
    val loud = FloatArray(n)
    val scale = FloatArray(n) { 1f }
    val stretch = FloatArray(n)
    val glow = FloatArray(n)
    val bold = FloatArray(n)
    val bounds = arrayOfNulls<androidx.compose.ui.geometry.Rect>(n)
    /** Voice level right now, relative to how loud this song's vocals get (0..1). */
    var voice = 0f
    private var peak = 0.2f
    var kick = 0f
    private var last = 0L

    /** Reads the analyser and returns the seconds since the last frame. */
    fun step(a: com.sonora.player.playback.SpectrumAnalyzer?): Float {
        val now = System.nanoTime()
        val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        if (a == null) return dt
        var sum = 0f
        for (i in VOICE_FROM until VOICE_TO) sum += a.bands[i]
        val raw = sum / (VOICE_TO - VOICE_FROM)
        // Auto-level: compare with this song's recent loudest, so quiet and loud masters both move.
        peak = max(raw, (peak - dt * 0.06f).coerceAtLeast(0.12f))
        val target = ((raw / peak - 0.35f) / 0.65f).coerceIn(0f, 1f)
        voice = ease(voice, target, dt, up = 28f, down = 9f)
        kick = ease(kick, a.beat, dt, up = 40f, down = 6f)
        return dt
    }

    companion object {
        const val VOICE_FROM = 21
        const val VOICE_TO = 49
        fun ease(cur: Float, target: Float, dt: Float, up: Float, down: Float): Float =
            cur + (target - cur) * (1f - kotlin.math.exp(-dt * (if (target > cur) up else down)))
    }
}

@Composable
private fun KaraokeLine(line: com.sonora.player.lyrics.LyricLine, offsetMs: Long, conn: com.sonora.player.playback.PlayerConnection, playing: Boolean, live: LiveAudio?, modifier: Modifier) {
    // Follows the position every frame while playing; paused, the words just hold where they are.
    val pos = produceState(conn.positionMs, line, playing) {
        value = conn.positionMs
        while (playing) {
            androidx.compose.runtime.withFrameNanos { }
            value = conn.positionMs
        }
    }
    // The text is laid out once. Each word is then drawn on its own (its glyphs clipped from the line), so it can
    // brighten, swell, stretch, thicken and glow with the voice without re-measuring the line every frame.
    val plain = remember(line) { line.words.joinToString("") { it.text } }
    val starts = remember(line) { IntArray(line.words.size).also { var o = 0; line.words.forEachIndexed { i, w -> it[i] = o; o += w.text.length } } }
    val layout = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    val wordPaths = remember(line) { arrayOfNulls<androidx.compose.ui.graphics.Path>(line.words.size) }
    val fx = remember(line) { VoiceFx(line.words.size) }
    Text(
        plain, style = LyricStyle.copy(fontFamily = com.sonora.player.ui.theme.LocalAppFontFamily.current), color = Color.Transparent,
        onTextLayout = { layout[0] = it; wordPaths.fill(null); fx.bounds.fill(null) },
        modifier = modifier.drawWithContent {
            val lay = layout[0] ?: return@drawWithContent
            val t = pos.value + offsetMs
            val dt = fx.step(if (playing) live?.analyzer else null)
            val lift = -fx.kick * 3.dp.toPx()
            line.words.forEachIndexed { i, w ->
                if (w.text.isEmpty()) return@forEachIndexed
                val next = line.words.getOrNull(i + 1)?.startMs ?: line.endMs
                val span = (next - w.startMs).coerceIn(120, 1400)
                val p = ((t - w.startMs).toFloat() / span).coerceIn(0f, 1f)
                val singing = p > 0f && p < 1f
                if (singing) fx.loud[i] = max(fx.loud[i], fx.voice)
                // A held note: a long word, partway through.
                val hold = if (singing && next - w.startMs >= 650) p else 0f
                val v = if (singing) fx.voice else 0f
                fx.scale[i] = VoiceFx.ease(fx.scale[i], 1f + 0.10f * v, dt, up = 22f, down = 5f)
                fx.stretch[i] = VoiceFx.ease(fx.stretch[i], 0.035f * hold * (0.4f + 0.6f * fx.voice), dt, up = 6f, down = 4f)
                fx.glow[i] = VoiceFx.ease(fx.glow[i], if (singing) (0.25f * v + 0.75f * hold * fx.voice) else 0f, dt, up = 10f, down = 2.5f)
                val boldTarget = if (p > 0f) ((fx.loud[i] - 0.6f) / 0.4f).coerceIn(0f, 1f) * (if (singing) 1f else 0f) else 0f
                fx.bold[i] = VoiceFx.ease(fx.bold[i], boldTarget, dt, up = 14f, down = 1.6f)
                // Lit words reach full white; a whispered word (never got loud) tops out a little dimmer.
                val whisper = if (p >= 1f && fx.loud[i] < 0.3f) 0.85f else 1f
                val alpha = 0.34f + (0.66f * whisper) * p
                val path = wordPaths[i] ?: run {
                    val lead = w.text.length - w.text.trimStart().length
                    val trail = w.text.length - w.text.trimEnd().length
                    val a = starts[i] + lead
                    val b = starts[i] + w.text.length - trail
                    lay.getPathForRange(a, if (b > a) b else starts[i] + w.text.length)
                }.also { wordPaths[i] = it }
                val box = fx.bounds[i] ?: path.getBounds().also { fx.bounds[i] = it }
                // Grows mostly upward: full lift in height, only about a third of it in width, so a loud word
                // stands out without closing the gaps to its neighbours.
                val sy = fx.scale[i]
                val sx = 1f + (fx.scale[i] - 1f) * 0.35f + fx.stretch[i]
                withTransform({
                    translate(0f, lift)
                    // Grows up and out from the word's baseline, like it's being lifted by the voice.
                    scale(sx, sy, androidx.compose.ui.geometry.Offset(box.center.x, box.bottom))
                }) {
                    // Glow: a soft oval of light behind the word, shaped to it. A plain gradient, so it costs the GPU
                    // almost nothing and never has to be re-rasterised (a blurred text shadow did, as words changed).
                    val g = fx.glow[i]
                    if (g > 0.03f) {
                        val r = box.height * 0.75f
                        withTransform({ scale((box.width / box.height).coerceAtLeast(1f) * 0.9f, 1f, box.center) }) {
                            drawCircle(
                                androidx.compose.ui.graphics.Brush.radialGradient(
                                    0f to White.copy(alpha = 0.30f * g), 0.55f to White.copy(alpha = 0.12f * g), 1f to Color.Transparent,
                                    center = box.center, radius = r,
                                ),
                                radius = r, center = box.center,
                            )
                        }
                    }
                    // A plain rectangle around the word's letters: the GPU clips that for free, where a clip shaped
                    // like the glyph boxes had to be prepared on the CPU for every word, every frame.
                    clipRect(box.left - 1f, box.top, box.right + 1f, box.bottom) {
                        // The line's text paint is shared and remembers the last style / shadow it was given, so
                        // every draw states its own (solid fill, no shadow).
                        val bw = fx.bold[i]
                        if (bw > 0.03f) {
                            // Loud word: fill and thickening composited together, then faded once, so where they
                            // overlap it doesn't read as an outline.
                            val c = drawContext.canvas
                            c.saveLayer(box.inflate(4.dp.toPx()), androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
                            drawText(lay, color = White, shadow = androidx.compose.ui.graphics.Shadow.None, drawStyle = androidx.compose.ui.graphics.drawscope.Fill)
                            drawText(
                                lay, color = White, alpha = bw, shadow = androidx.compose.ui.graphics.Shadow.None,
                                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx() * bw),
                            )
                            c.restore()
                        } else {
                            drawText(
                                lay, color = White, alpha = alpha,
                                shadow = androidx.compose.ui.graphics.Shadow.None,
                                drawStyle = androidx.compose.ui.graphics.drawscope.Fill,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.LyricsSearchSheet(
    vm: MainViewModel, song: Song, haze: dev.chrisbanes.haze.HazeState, onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(LyricsRepository.cleanTitle(song.title)) }
    var artist by remember { mutableStateOf(song.artist) }
    var results by remember { mutableStateOf<List<LyricsCandidate>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val search = {
        loading = true
        scope.launch {
            results = vm.c.lyrics.search(title, artist)
            loading = false
        }
    }
    LaunchedEffect(Unit) { search() }

    GlassSheet(haze, onDone) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Find lyrics", style = MaterialTheme.typography.titleLarge, color = White, modifier = Modifier.weight(1f))
            Text(
                "Clear saved", color = White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { scope.launch { vm.c.lyrics.clear(song.id); onDone() } }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        GlassField(title, "Title") { title = it }
        Spacer(Modifier.height(8.dp))
        GlassField(artist, "Artist", onSearch = { search() }) { artist = it }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(50)).background(White).clickable { search() },
            contentAlignment = Alignment.Center,
        ) { Text("Search LRCLIB", color = Color.Black, style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.height(14.dp))
        val list = results?.filter { it.best != null }.orEmpty()
        when {
            loading -> Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = White, strokeWidth = 2.dp) }
            results != null && list.isEmpty() -> Text(
                "No matches. Try a shorter title or just the artist's main name.",
                style = MaterialTheme.typography.bodySmall, color = White.copy(alpha = 0.6f),
            )
            list.isNotEmpty() -> Column(Modifier.fillMaxWidth().glassGroup()) {
                list.take(12).forEachIndexed { i, c ->
                    val diff = abs(c.durationSec - song.durationMs / 1000.0)
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                vm.c.lyrics.save(song.id, c.best!!, "LRCLIB")
                                onDone()
                            }
                        }.padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.track, style = MaterialTheme.typography.bodyLarge, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (!c.synced.isNullOrBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text("SYNCED", color = Color.Black, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.background(White.copy(alpha = 0.85f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                        Text(
                            listOf(c.artist, formatDuration((c.durationSec * 1000).toLong()) + if (diff <= 2) " · same length" else "")
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        c.best?.lineSequence()?.map { it.replace(Regex("""\[[^]]*]"""), "").trim() }?.firstOrNull { it.isNotBlank() }?.let {
                            Text("“$it”", style = MaterialTheme.typography.bodySmall, color = White.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (i < list.take(12).lastIndex) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(0.5.dp).background(White.copy(alpha = 0.10f)))
                }
            }
        }
    }
}

@Composable
private fun GlassField(value: String, label: String, onSearch: (() -> Unit)? = null, onChange: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = White.copy(alpha = 0.55f))
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = White),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(White),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (onSearch != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Next),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

// ------------------------------------------------------------------ sheets

@Composable
private fun androidx.compose.foundation.layout.BoxScope.QueueSheet(vm: MainViewModel, state: PlayerState, haze: dev.chrisbanes.haze.HazeState, onDismiss: () -> Unit) {
    val conn = vm.c.playerConnection
    val queue = remember(state.queue) { mutableStateListOf(*state.queue.toTypedArray()) }
    val canReorder = !state.shuffle
    val currentPos = queue.indexOfFirst { it.index == state.currentIndex }
    val reorder = rememberDragReorder(64.dp) { from, to ->
        // Positions are queue-wide; never drop a song above the one that's playing.
        val target = to.coerceAtLeast(currentPos + 1)
        if (target == from) return@rememberDragReorder
        val fromIdx = queue[from].index
        val toIdx = queue[target].index
        val moved = queue.removeAt(from)
        queue.add(target, moved)
        conn.move(fromIdx, toIdx)
    }
    val dim = White.copy(alpha = 0.6f)
    val earlier = if (currentPos > 0) queue.subList(0, currentPos).toList().asReversed() else emptyList()
    val upcoming = if (currentPos >= 0) queue.size - currentPos - 1 else queue.size

    GlassSheet(haze, onDismiss, scrollable = false) {
        // ---- Hero: the song that's playing, big, like Home's cards.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                state.artUri,
                Modifier.size(96.dp).graphicsLayer { shadowElevation = 14.dp.toPx(); shape = RoundedCornerShape(14.dp); clip = true },
                RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LIcon(L.drawable.lucide_ic_audio_lines, null, tint = White.copy(alpha = 0.8f), size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = White.copy(alpha = 0.75f))
                }
                Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.artist, style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.height(38.dp).clip(RoundedCornerShape(50)).background(White).clickable { conn.clearUpcoming() }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LIcon(L.drawable.lucide_ic_list_x, null, tint = Color.Black, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("Clear up next", color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier.height(38.dp).glassPill(RoundedCornerShape(50)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LIcon(if (canReorder) L.drawable.lucide_ic_grip_vertical else L.drawable.lucide_ic_shuffle, null, tint = White.copy(alpha = 0.85f), size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(if (canReorder) "Drag to reorder" else "Shuffle is on", color = White, style = MaterialTheme.typography.labelLarge)
            }
        }

        // ---- Earlier: what already played, as a row of small cards (tap to go back to one).
        if (earlier.isNotEmpty()) {
            Text("Earlier", style = MaterialTheme.typography.titleLarge, color = White, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(earlier, key = { _, e -> "e-${e.index}-${e.mediaId}" }) { _, e ->
                    Column(Modifier.width(84.dp).clickable { conn.skipTo(e.index) }) {
                        Artwork(e.artUri, Modifier.size(84.dp), RoundedCornerShape(10.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(e.title, style = MaterialTheme.typography.labelMedium, color = White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // ---- Up next: big title, then the list (drag to reorder, ✕ to remove).
        Text(
            if (upcoming == 1) "Up next · 1 song" else "Up next · $upcoming songs",
            style = MaterialTheme.typography.titleLarge, color = White, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        if (upcoming == 0) {
            Text(
                "Nothing queued. Autoplay keeps the music going with similar songs.",
                style = MaterialTheme.typography.bodyMedium, color = dim, modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            val textStart = 12.dp + (if (canReorder) 36.dp else 0.dp) + 44.dp + 12.dp
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).glassGroup()) {
                items(upcoming, key = { i -> queue[currentPos + 1 + i].let { "${it.index}-${it.mediaId}" } }) { i ->
                    val pos = currentPos + 1 + i
                    val entry = queue[pos]
                    val last = pos == queue.lastIndex
                    Row(
                        reorder.rowModifier(pos).height(64.dp).fillMaxWidth()
                            .drawBehind {
                                if (!last) drawLine(
                                    White.copy(alpha = 0.10f), androidx.compose.ui.geometry.Offset(textStart.toPx(), size.height),
                                    androidx.compose.ui.geometry.Offset(size.width, size.height), 0.5.dp.toPx(),
                                )
                            }
                            .clickable { conn.skipTo(entry.index) }
                            .padding(start = if (canReorder) 0.dp else 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canReorder) {
                            Box(Modifier.width(48.dp).fillMaxHeight().dragHandle(reorder, pos, queue.size), contentAlignment = Alignment.Center) {
                                LIcon(L.drawable.lucide_ic_grip_vertical, "Drag", size = 18.dp, tint = White.copy(alpha = 0.45f))
                            }
                        }
                        Artwork(entry.artUri, Modifier.size(44.dp), RoundedCornerShape(7.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = White, style = MaterialTheme.typography.bodyLarge)
                            Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = dim)
                        }
                        IconButton(onClick = { conn.remove(entry.index) }) { LIcon(L.drawable.lucide_ic_x, "Remove", size = 18.dp, tint = White.copy(alpha = 0.5f)) }
                    }
                }
            }
        }
    }
}

/** A small glass circle button for the player's top bar. */
@Composable
private fun GlassCircle(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).glassPill(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LIcon(icon, label, tint = White, size = 22.dp) }
}

/** The player's "More" actions as a glass sheet (instead of a white dropdown), with the song at the top. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MoreSheet(
    vm: MainViewModel,
    actions: UiActions,
    state: PlayerState,
    localSong: Song?,
    ytSong: com.sonora.player.youtube.YtSong?,
    canMatchLyrics: Boolean,
    haze: dev.chrisbanes.haze.HazeState,
    onCollapse: () -> Unit,
    onOpen: (Sheet) -> Unit,
    onDismiss: () -> Unit,
) {
    data class Action(val label: String, val icon: Int, val run: () -> Unit)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val list = buildList {
        if (localSong != null) {
            add(Action("Add to playlist", L.drawable.lucide_ic_list_plus) { onDismiss(); actions.addToPlaylist(listOf(localSong)) })
            add(Action("Go to album", L.drawable.lucide_ic_disc_3) { onDismiss(); onCollapse(); actions.albumOf(localSong) })
            add(Action("Go to artist", L.drawable.lucide_ic_mic_vocal) { onDismiss(); onCollapse(); actions.artist(localSong.primaryArtist) })
        }
        if (ytSong != null) {
            add(Action("Start radio", L.drawable.lucide_ic_radio) { onDismiss(); vm.startRadio(ytSong) })
            val downloaded = vm.c.downloads.isDownloaded(ytSong.videoId)
            add(Action(if (downloaded) "Remove download" else "Download", L.drawable.lucide_ic_download) {
                onDismiss()
                if (downloaded) vm.c.downloads.remove(ytSong.videoId) else vm.c.downloads.download(listOf(ytSong))
            })
            ytSong.album?.browseId?.let { id -> add(Action("Go to album", L.drawable.lucide_ic_disc_3) { onDismiss(); onCollapse(); actions.yt(id) }) }
            ytSong.artists.firstOrNull { it.browseId != null }?.let { a ->
                add(Action("Go to artist", L.drawable.lucide_ic_mic_vocal) { onDismiss(); onCollapse(); actions.yt(a.browseId!!) })
            }
        }
        add(Action("Song story", L.drawable.lucide_ic_book_open) { onOpen(Sheet.STORY) })
        if (canMatchLyrics) add(Action("Find lyrics", L.drawable.lucide_ic_search) { onOpen(Sheet.LYRICS_SEARCH) })
        add(Action("Equalizer", L.drawable.lucide_ic_sliders_horizontal) { onDismiss(); onCollapse(); actions.equalizer() })
        add(Action("Driving mode", L.drawable.lucide_ic_car) { onDismiss(); onCollapse(); actions.drive() })
        add(Action("Always-On screen", L.drawable.lucide_ic_clock) { onDismiss(); com.sonora.player.ui.aod.AlwaysOnActivity.open(ctx) })
    }
    GlassSheet(haze, onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(state.artUri, Modifier.size(48.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.artist, style = MaterialTheme.typography.bodySmall, color = White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().glassGroup()) {
            list.forEachIndexed { i, a ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = a.run).padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LIcon(a.icon, null, tint = White.copy(alpha = 0.85f), size = 20.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(a.label, style = MaterialTheme.typography.bodyLarge, color = White)
                }
                if (i < list.lastIndex) Box(Modifier.padding(start = 50.dp).fillMaxWidth().height(0.5.dp).background(White.copy(alpha = 0.10f)))
            }
        }
    }
}

/** Song Story: your personal history with the song that's playing, laid out like Home (hero, big numbers, moment cards). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SongStorySheet(
    vm: MainViewModel, state: PlayerState, haze: dev.chrisbanes.haze.HazeState, onDismiss: () -> Unit,
) {
    val story by produceState<com.sonora.player.ui.SongStory?>(null, state.mediaId) {
        value = vm.songStory(state.mediaId, state.title, state.artist)
    }
    val dim = White.copy(alpha = 0.6f)
    GlassSheet(haze, onDismiss) {
        // ---- Hero: the song, big.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                state.artUri,
                Modifier.size(96.dp).graphicsLayer { shadowElevation = 14.dp.toPx(); shape = RoundedCornerShape(14.dp); clip = true },
                RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LIcon(L.drawable.lucide_ic_book_open, null, tint = White.copy(alpha = 0.8f), size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("YOUR STORY WITH", style = MaterialTheme.typography.labelSmall, color = White.copy(alpha = 0.75f))
                }
                Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.artist, style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(18.dp))
        val st = story
        if (st == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = White, strokeWidth = 2.dp) }
            return@GlassSheet
        }
        if (st.plays == 0 && st.first == null) {
            Row(Modifier.fillMaxWidth().glassGroup().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LIcon(L.drawable.lucide_ic_sparkles, null, tint = White.copy(alpha = 0.85f), size = 20.dp)
                Spacer(Modifier.width(14.dp))
                Text("First time hearing this one. Your story with it starts now.", style = MaterialTheme.typography.bodyLarge, color = White)
            }
            return@GlassSheet
        }

        // ---- Big numbers, as three glass tiles (like Home's quick tiles).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "${st.plays}" to if (st.plays == 1) "play" else "plays",
                "${st.minutes}" to if (st.minutes == 1) "minute" else "minutes",
                (st.rank?.let { "#$it" } ?: "—") to "of ${st.ofSongs} songs",
            ).forEach { (v, l) ->
                Column(Modifier.weight(1f).glassGroup().padding(vertical = 14.dp, horizontal = 12.dp)) {
                    Text(v, color = White, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                    Text(l, style = MaterialTheme.typography.labelMedium, color = dim, maxLines = 1)
                }
            }
        }

        // ---- Moments as a row of cards.
        val df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        val moments = buildList {
            st.first?.let { (at, src) -> add(Triple(L.drawable.lucide_ic_sparkles, "First heard", "${df.format(java.util.Date(at))}\n$src")) }
            st.last?.let { add(Triple(L.drawable.lucide_ic_history, "Last played", android.text.format.DateUtils.getRelativeTimeSpanString(it).toString())) }
            st.favouriteTime?.let { add(Triple(L.drawable.lucide_ic_clock, "Usually", it.replaceFirstChar { c -> c.uppercase() })) }
            if (st.sources.size > 1) add(Triple(L.drawable.lucide_ic_link, "Played on", st.sources.joinToString("\n") { "${it.first} · ${it.second}" }))
            if (st.skips > 0) add(Triple(L.drawable.lucide_ic_skip_forward, "Skipped", "${st.skips} time${if (st.skips == 1) "" else "s"}"))
        }
        if (moments.isNotEmpty()) {
            Text("Moments", style = MaterialTheme.typography.titleLarge, color = White, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(moments, key = { _, m -> m.second }) { _, (icon, label, value) ->
                    Column(Modifier.width(156.dp).height(132.dp).glassGroup().padding(14.dp)) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            LIcon(icon, null, tint = White, size = 18.dp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = dim)
                        Text(value, style = MaterialTheme.typography.titleSmall, color = White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SleepSheet(
    state: PlayerState, haze: dev.chrisbanes.haze.HazeState, onSet: (Int, Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val running = state.sleepEndsAt > now
    val active = running || state.sleepEndOfTrack
    val dim = White.copy(alpha = 0.6f)
    GlassSheet(haze, onDismiss) {
        // ---- Hero: a big countdown (or "Off"), like the big numbers on Home's cards.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).glassPill(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                LIcon(L.drawable.lucide_ic_moon, null, tint = White, size = 30.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("SLEEP TIMER", style = MaterialTheme.typography.labelSmall, color = White.copy(alpha = 0.75f))
                Text(
                    when {
                        running -> formatDuration(state.sleepEndsAt - now)
                        state.sleepEndOfTrack -> "End of song"
                        else -> "Off"
                    },
                    color = White, fontSize = if (running) 44.sp else 34.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (active) "Music fades out, then stops" else "Pick when the music should fade out",
                    style = MaterialTheme.typography.bodyMedium, color = dim,
                )
            }
        }
        if (active) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(50)).background(White).clickable { onSet(0, false); onDismiss() }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LIcon(L.drawable.lucide_ic_x, null, tint = Color.Black, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Turn off", color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                if (running) {
                    Row(
                        Modifier.height(40.dp).glassPill(RoundedCornerShape(50)).clickable {
                            val left = ((state.sleepEndsAt - now) / 60_000L).toInt()
                            onSet(left + 10, false)
                        }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LIcon(L.drawable.lucide_ic_plus, null, tint = White, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("10 min", color = White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ---- Durations: big-number tiles.
        Text("Stop after", style = MaterialTheme.typography.titleLarge, color = White, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
        listOf(5, 15, 30, 45, 60, 90).chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    Column(
                        Modifier.weight(1f).height(72.dp).glassPill(RoundedCornerShape(18.dp)).clickable { onSet(m, false); onDismiss() }.padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (m < 60) "$m" else "${m / 60}${if (m % 60 == 30) ".5" else ""}",
                            color = White, fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(if (m < 60) "minutes" else if (m == 60) "hour" else "hours", color = dim, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        // ---- End of this song: a wide card, like Home's tiles.
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).glassGroup().clickable { onSet(0, true); onDismiss() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                LIcon(L.drawable.lucide_ic_music, null, tint = White, size = 20.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("End of this song", style = MaterialTheme.typography.titleMedium, color = White)
                Text(state.title, style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.sleepEndOfTrack) LIcon(L.drawable.lucide_ic_check, null, tint = White, size = 18.dp)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.AudioSheet(vm: MainViewModel, state: PlayerState, haze: dev.chrisbanes.haze.HazeState, onDismiss: () -> Unit) {
    val output by vm.output.collectAsStateWithLifecycle()
    val song = vm.song(state.songId)
    GlassSheet(haze, onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Audio quality", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.audio.isHiRes) {
                    Surface(shape = CircleShape, color = com.sonora.player.ui.theme.Brand.Gold) {
                        Text("Hi-Res", color = Color.Black, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (song != null) {
                Text(
                    "${song.extension} file · ${"%.1f".format(song.sizeBytes / 1_048_576f)} MB\n${song.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
            OutputCard(output, state.audio)
        }
    }
}

/**
 * Landscape player: the cover and controls on the left, a big live visualizer (or the lyrics) on a dark stage on the
 * right. Swipe down still closes it.
 */
@Composable
private fun LandscapeStage(
    vm: MainViewModel,
    state: PlayerState,
    live: LiveAudio,
    colors: List<Color>,
    position: androidx.compose.runtime.State<Long>,
    vizStyle: VizStyle,
    nextViz: () -> Unit,
    song: Song?,
    onCollapse: () -> Unit,
    base: Color,
    showLyrics: Boolean,
    onShowLyrics: (Boolean) -> Unit,
    openSheet: (Sheet) -> Unit,
) {
    val conn = vm.c.playerConnection
    // The stage's buttons fade away while music plays and come back on any touch of the stage.
    var controls by remember { mutableStateOf(true) }
    var poke by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(poke, showLyrics, state.isPlaying) {
        controls = true
        if (!state.isPlaying) return@LaunchedEffect
        delay(3500)
        controls = false
    }
    // One page: a single dark wash over the blurred artwork behind both the player and the stage.
    // Lyrics: just a light veil, so the album-colour sky shows across the page behind the words.
    val wash by animateFloatAsState(if (showLyrics) 0.18f else 0.82f, tween(400), label = "wash")
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(androidx.compose.ui.graphics.lerp(base, Color.Black, 0.7f).copy(alpha = wash))
        },
    )
    // Where the stage sits on screen, so the full-page visualizer centres its shape there.
    var stageBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var pageWidth by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val vizAlpha by animateFloatAsState(if (showLyrics) 0f else 1f, tween(350), label = "viz")
    if (vizAlpha > 0f && pageWidth > 0f && stageBounds.width > 0f) {
        // The visualizer spans the whole screen behind the player: stars and glow everywhere, the shape on the right.
        Visualizer(
            live, colors, vizStyle, state.artUri,
            Modifier.fillMaxSize().graphicsLayer { alpha = vizAlpha },
            focusX = stageBounds.center.x / pageWidth,
            span = stageBounds.width / pageWidth,
            edges = false,
        )
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { pageWidth = it.size.width.toFloat() })
    Row(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // ---- Left: cover, title, seek, transport.
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(0.42f).widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Artwork(
                    state.artUri,
                    Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).graphicsLayer {
                        shadowElevation = 20.dp.toPx(); shape = RoundedCornerShape(18.dp); clip = true
                    },
                    RoundedCornerShape(18.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = White,
                        maxLines = 1, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500),
                    )
                    Text(state.artist, style = MaterialTheme.typography.bodyMedium, color = White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val favorites by vm.favorites.collectAsStateWithLifecycle()
                val liked = com.sonora.player.youtube.YtIds.statsId(state.mediaId) in favorites
                IconButton(onClick = { vm.toggleFavoriteMedia(state.mediaId) }) { com.sonora.player.ui.components.FavoriteHeart(liked, size = 24.dp) }
            }
            SeekBar(position, state.durationMs, state.isPlaying, live, colors) { conn.seekTo(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { conn.toggleShuffle() }) {
                    LIcon(L.drawable.lucide_ic_shuffle, "Shuffle", tint = if (state.shuffle) White else White.copy(alpha = 0.45f), size = 20.dp)
                }
                IconButton(onClick = { conn.previous() }) { LIcon(L.drawable.lucide_ic_skip_back, "Previous", tint = White, size = 28.dp) }
                PlayPauseButton(state.isPlaying, state.isBuffering) { conn.togglePlay() }
                IconButton(onClick = { conn.next() }) { LIcon(L.drawable.lucide_ic_skip_forward, "Next", tint = White, size = 28.dp) }
                IconButton(onClick = { conn.cycleRepeat() }) {
                    LIcon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) L.drawable.lucide_ic_repeat_1 else L.drawable.lucide_ic_repeat,
                        "Repeat", tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) White else White.copy(alpha = 0.45f), size = 20.dp,
                    )
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        // ---- Right: the stage.
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .onGloballyPositioned { stageBounds = it.boundsInRoot() }
                // Watches every touch (without taking it) to bring the buttons back.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (ev.type == androidx.compose.ui.input.pointer.PointerEventType.Press) poke++
                        }
                    }
                },
        ) {
            if (showLyrics) {
                // Lyrics start below the top controls so the first lines aren't hidden behind them.
                Box(Modifier.fillMaxSize().padding(top = 58.dp).padding(horizontal = 20.dp)) {
                    if (song != null) LyricsPanel(vm, song, position, onSearch = { openSheet(Sheet.LYRICS_SEARCH) }, depthBlur = false, live = live)
                    else Text("Lyrics are available for library songs", color = White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
                }
            } else {
                // Style chip at the bottom of the stage, as in portrait.
                androidx.compose.animation.AnimatedVisibility(
                    controls, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    enter = androidx.compose.animation.fadeIn(tween(160)), exit = androidx.compose.animation.fadeOut(tween(320)),
                ) {
                    Text(
                        vizStyle.label, color = White, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.glassPill(RoundedCornerShape(50)).clickable(onClick = nextViz).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                if (!state.isPlaying) {
                    Text(
                        "Play something to see it move", color = White.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            // Top: close, the Visualizer / Lyrics switch, queue and more. Fades away with the chip.
            androidx.compose.animation.AnimatedVisibility(
                controls, Modifier.fillMaxWidth(),
                enter = androidx.compose.animation.fadeIn(tween(160)), exit = androidx.compose.animation.fadeOut(tween(320)),
            ) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassCircle(L.drawable.lucide_ic_chevron_down, "Close", onCollapse)
                Spacer(Modifier.weight(1f))
                // Always-visible switch between the two, so there's an obvious way back either way.
                Row(Modifier.glassPill(RoundedCornerShape(50)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    StageTab(L.drawable.lucide_ic_audio_lines, "Visualizer", selected = !showLyrics) { onShowLyrics(false) }
                    StageTab(L.drawable.lucide_ic_mic_vocal, "Lyrics", selected = showLyrics) { onShowLyrics(true) }
                }
                Spacer(Modifier.weight(1f))
                GlassCircle(L.drawable.lucide_ic_cast, "Cast and output") { openSheet(Sheet.OUTPUT) }
                Spacer(Modifier.width(8.dp))
                GlassCircle(L.drawable.lucide_ic_list_music, "Queue") { openSheet(Sheet.QUEUE) }
                Spacer(Modifier.width(8.dp))
                GlassCircle(L.drawable.lucide_ic_ellipsis, "More") { openSheet(Sheet.MORE) }
            }
            }
        }
    }
}

/** One side of the landscape stage's Visualizer / Lyrics switch. */
@Composable
private fun StageTab(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).then(if (selected) Modifier.background(White) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) Color.Black else White
        LIcon(icon, null, tint = tint, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}
