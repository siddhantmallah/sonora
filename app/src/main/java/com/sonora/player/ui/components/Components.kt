package com.sonora.player.ui.components

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.heightIn
import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sonora.player.data.Song
import com.sonora.player.data.db.PlaylistSummary
import com.sonora.player.data.formatDuration
import com.composables.icons.lucide.R as L

@Composable
fun LIcon(
    @DrawableRes res: Int,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 22.dp,
) = Icon(painterResource(res), contentDescription, modifier.size(size), tint)

@Composable
fun Artwork(
    uri: Uri?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    @DrawableRes fallback: Int = L.drawable.lucide_ic_music,
) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        LIcon(fallback, null, tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f))
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A heavily blurred, oversized copy of some artwork, for glowing backdrops behind cards. Blurred at 1/4 size and
 * scaled up, so it looks the same but the GPU blurs 16x fewer pixels whenever the card redraws (scrolling,
 * transitions). Fills its parent; [zoom] enlarges it beyond the edges, [alpha] fades it.
 */
@Composable
fun BlurredArt(uri: Any?, modifier: Modifier = Modifier, blur: Dp = 36.dp, zoom: Float = 1.3f, alpha: Float = 0.75f) {
    if (uri == null || android.os.Build.VERSION.SDK_INT < 31) return
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val k = 4f
        AsyncImage(
            model = uri, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.requiredSize(maxWidth / k, maxHeight / k)
                .graphicsLayer { scaleX = zoom * k; scaleY = zoom * k; this.alpha = alpha }
                .blur(blur / k),
        )
    }
}

/** Whether the player is currently playing; lets list rows animate their "now playing" bars. */
val LocalIsPlaying = androidx.compose.runtime.compositionLocalOf { false }

/** Media id of the current track and the favourite set, shared once instead of collected per row. */
val LocalNowPlayingId = androidx.compose.runtime.compositionLocalOf<String?> { null }
val LocalFavorites = androidx.compose.runtime.compositionLocalOf<Set<Long>> { emptySet() }

/** False for a page that's built but not on screen (e.g. the Library tab next to the one you're on). */
val LocalOnScreen = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.State<Boolean>> { androidx.compose.runtime.mutableStateOf(true) }

/** True while the full-screen player covers the app; screens pause their ambient animations (sky, sun) then. */
val LocalPlayerCovering = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.State<Boolean>> { androidx.compose.runtime.mutableStateOf(false) }

/** Artwork with animated equalizer bars on top, for the row that's currently playing. */
@Composable
fun NowPlayingArtwork(uri: Uri?, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Artwork(uri, Modifier.fillMaxSize(), shape)
        Box(Modifier.fillMaxSize().clip(shape).background(Color.Black.copy(alpha = 0.45f)))
        PlayingBars(Modifier.size(20.dp))
    }
}

@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = Color.White) {
    val playing = LocalIsPlaying.current
    if (!playing || !LocalOnScreen.current.value) {
        // Paused (or on a page you can't see): still bars, no animation ticking away.
        androidx.compose.foundation.Canvas(modifier) {
            val w = size.width / 5
            repeat(3) { i ->
                val hh = size.height * 0.3f
                drawRoundRect(
                    color, androidx.compose.ui.geometry.Offset(i * 2 * w, size.height - hh),
                    androidx.compose.ui.geometry.Size(w, hh), androidx.compose.ui.geometry.CornerRadius(w / 2),
                )
            }
        }
        return
    }
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "bars")
    val specs = listOf(380, 520, 440)
    val heights = specs.mapIndexed { i, ms ->
        t.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(ms, delayMillis = i * 90),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "bar$i",
        )
    }
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width / 5
        heights.forEachIndexed { i, h ->
            val hh = size.height * h.value
            drawRoundRect(
                color, androidx.compose.ui.geometry.Offset(i * 2 * w, size.height - hh),
                androidx.compose.ui.geometry.Size(w, hh), androidx.compose.ui.geometry.CornerRadius(w / 2),
            )
        }
    }
}

/** Actions every song menu offers. Null callbacks hide the entry. */
class SongActions(
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onToggleFavorite: (Song) -> Unit,
    val isFavorite: (Song) -> Boolean,
    val onGoToAlbum: ((Song) -> Unit)? = null,
    val onGoToArtist: ((Song) -> Unit)? = null,
    val onRemove: ((Song) -> Unit)? = null,
    val removeLabel: String = "Remove from playlist",
)

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    subtitle: String = "${song.artist} • ${song.album}",
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            isCurrent -> NowPlayingArtwork(song.artUri, Modifier.size(48.dp))
            else -> Artwork(song.artUri, Modifier.size(48.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (actions.isFavorite(song)) {
                    SmallHeart()
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            formatDuration(song.durationMs), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { menu = true }) { LIcon(L.drawable.lucide_ic_ellipsis_vertical, "More", size = 20.dp) }
            AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem("Play next", L.drawable.lucide_ic_list_start) { menu = false; actions.onPlayNext(song) }
                MenuItem("Add to queue", L.drawable.lucide_ic_list_end) { menu = false; actions.onAddToQueue(song) }
                MenuItem("Add to playlist…", L.drawable.lucide_ic_list_plus) { menu = false; actions.onAddToPlaylist(song) }
                val fav = actions.isFavorite(song)
                MenuItem(if (fav) "Remove from favorites" else "Add to favorites", L.drawable.lucide_ic_heart) {
                    menu = false; actions.onToggleFavorite(song)
                }
                actions.onGoToAlbum?.let { go -> MenuItem("Go to album", L.drawable.lucide_ic_disc_3) { menu = false; go(song) } }
                actions.onGoToArtist?.let { go -> MenuItem("Go to artist", L.drawable.lucide_ic_mic_vocal) { menu = false; go(song) } }
                MenuItem("Share", L.drawable.lucide_ic_share_2) {
                    menu = false
                    val send = Intent(Intent.ACTION_SEND).setType(song.mimeType.ifBlank { "audio/*" })
                        .putExtra(Intent.EXTRA_STREAM, song.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, song.title))
                }
                actions.onRemove?.let { rm ->
                    HorizontalDivider()
                    MenuItem(actions.removeLabel, L.drawable.lucide_ic_trash_2) { menu = false; rm(song) }
                }
            }
        }
    }
}

@Composable
fun MenuItem(label: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { LIcon(icon, null, size = 18.dp) }, onClick = onClick)
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, shuffleLabel: String = "Shuffle") {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppButton(onClick = onPlay, modifier = Modifier.weight(1f).height(50.dp)) {
            LIcon(L.drawable.lucide_ic_play, null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("Play")
        }
        AppButton(onClick = onShuffle, modifier = Modifier.weight(1f).height(50.dp), solid = false) {
            LIcon(L.drawable.lucide_ic_shuffle, null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(shuffleLabel)
        }
    }
}

/** Height of the floating glass bars (mini player + navigation), so lists can scroll underneath them. */
val LocalBottomBarPadding = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** Bottom content padding for scrolling screens: clears the glass bars plus [extra]. */
@Composable
fun bottomBarSpace(extra: Dp = 24.dp): Dp = LocalBottomBarPadding.current + extra

@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            LIcon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 30.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("New playlist name") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                )
                if (playlists.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(Modifier.height((playlists.size.coerceAtMost(5) * 52).dp)) {
                        items(playlists, key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(p.id) }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LIcon(L.drawable.lucide_ic_list_music, null, size = 20.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.songCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            AppButton(small = true, enabled = name.isNotBlank(), onClick = { onCreate(name) }) { Text("Create") }
        },
        dismissButton = { AppButton(small = true, solid = false, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) },
        confirmButton = { AppButton(small = true, enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { AppButton(small = true, solid = false, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The app's one button: a pill, solid (main action) or soft. Used everywhere instead of Material buttons. */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    solid: Boolean = true,
    small: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val bg = if (solid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (solid) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Row(
        modifier.heightIn(min = if (small) 36.dp else 48.dp).clip(RoundedCornerShape(50))
            .background(bg.copy(alpha = if (enabled) 1f else 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides fg.copy(alpha = if (enabled) 1f else 0.6f)) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) { content() }
        }
    }
}

/** Dialogs in the app's style: rounded, on the page colour, with pill buttons. */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    )
}

/**
 * Pop-up menus in the app's style: rounded card on the grouped-list colour. Placed like a dropdown (below the
 * anchor, flipped above when there's no room), but with our own quick pop: in ~120ms, out ~80ms.
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    state.targetState = expanded
    if (!state.currentState && !state.targetState && state.isIdle) return

    // Where the menu grows from (the corner nearest the anchor); set by the position provider.
    var origin by remember { mutableStateOf(androidx.compose.ui.graphics.TransformOrigin(0f, 0f)) }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    val provider = remember(margin) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val w = popupContentSize.width
                val h = popupContentSize.height
                val fromRight = anchorBounds.left + w > windowSize.width - margin
                val x = (if (fromRight) anchorBounds.right - w else anchorBounds.left).coerceIn(margin, (windowSize.width - w - margin).coerceAtLeast(margin))
                val above = anchorBounds.bottom + h > windowSize.height - margin && anchorBounds.top - h >= margin
                val y = (if (above) anchorBounds.top - h else anchorBounds.bottom).coerceIn(margin, (windowSize.height - h - margin).coerceAtLeast(margin))
                origin = androidx.compose.ui.graphics.TransformOrigin(if (fromRight) 1f else 0f, if (above) 1f else 0f)
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        val t = androidx.compose.animation.core.rememberTransition(state, label = "menu")
        val scale by t.animateFloat({ androidx.compose.animation.core.tween(if (targetState) 120 else 80, easing = androidx.compose.animation.core.FastOutSlowInEasing) }, label = "s") { if (it) 1f else 0.85f }
        val alpha by t.animateFloat({ androidx.compose.animation.core.tween(if (targetState) 90 else 80) }, label = "a") { if (it) 1f else 0f }
        Surface(
            modifier = Modifier.padding(4.dp).graphicsLayer {
                scaleX = scale; scaleY = scale; this.alpha = alpha; transformOrigin = origin
            },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier.padding(vertical = 8.dp)
                    .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                content = content,
            )
        }
    }
}

/** Round button for page top bars (same as the back button on Settings). */
@Composable
fun TopCircle(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LIcon(icon, label, size = 20.dp) }
}
