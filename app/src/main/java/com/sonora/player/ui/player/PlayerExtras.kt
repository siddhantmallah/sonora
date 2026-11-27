package com.sonora.player.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.playback.AudioInfo
import com.sonora.player.playback.OutputType
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.components.IosColors
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.theme.Brand
import com.composables.icons.lucide.R as L

/** System media-volume slider that stays in sync with the hardware buttons. */
@Composable
fun VolumeSlider(
    track: Color = Color.White.copy(alpha = 0.22f),
    fill: Color = Color.White.copy(alpha = 0.85f),
    iconTint: Color = Color.White.copy(alpha = 0.6f),
) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!dragging) level = am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    fun set(f: Float) {
        level = f.coerceIn(0f, 1f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        LIcon(L.drawable.lucide_ic_volume, "Quieter", tint = iconTint, size = 16.dp)
        Box(
            Modifier.weight(1f).height(28.dp).padding(horizontal = 10.dp)
                .pointerInput(Unit) { detectTapGestures { set(it.x / size.width) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; set(it.x / size.width) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { ch, _ -> ch.consume(); set(ch.position.x / size.width) }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val y = size.height / 2
                val w = (if (dragging) 8.dp else 5.dp).toPx()
                drawLine(track, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
                drawLine(fill, Offset(0f, y), Offset(size.width * level, y), w, StrokeCap.Round)
            }
        }
        LIcon(L.drawable.lucide_ic_volume_2, "Louder", tint = iconTint, size = 16.dp)
    }
}

private fun iconFor(type: OutputType) = when (type) {
    OutputType.BLUETOOTH -> L.drawable.lucide_ic_bluetooth
    OutputType.USB -> L.drawable.lucide_ic_usb
    OutputType.WIRED -> L.drawable.lucide_ic_headphones
    else -> L.drawable.lucide_ic_smartphone
}

/** Shows where audio is playing (e.g. "OnePlus Bullets · AAC"); tapping opens the Connected device sheet. */
@Composable
fun OutputPill(vm: MainViewModel, onClick: () -> Unit) {
    val output by vm.output.collectAsStateWithLifecycle()
    val casting by com.sonora.player.cast.CastState.target.collectAsStateWithLifecycle()
    val label = casting?.name ?: buildString {
        append(if (output.type == OutputType.SPEAKER) "This phone" else output.deviceName ?: output.type.label)
        output.codec?.let { append(" · ").append(it.name) }
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = if (output.type == OutputType.SPEAKER) 0.08f else 0.14f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp).widthIn(max = 200.dp), verticalAlignment = Alignment.CenterVertically) {
            if (casting != null) LIcon(L.drawable.lucide_ic_cast, null, tint = Brand.Gold, size = 15.dp)
            else LIcon(iconFor(output.type), null, tint = if (output.codec?.isHighQuality == true) Brand.Gold else Color.White, size = 15.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Sonora's own "Playing on" sheet (liquid glass, inside the player): what's playing where (with codec / quality), a
 * working volume slider, and a list of outputs that routes Sonora's audio directly (speaker, Bluetooth, wired, USB).
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.OutputSheet(
    vm: MainViewModel,
    audio: AudioInfo,
    haze: dev.chrisbanes.haze.HazeState,
    onAudioDetails: () -> Unit,
    onDismiss: () -> Unit,
) {
    val output by vm.output.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var choices by remember { mutableStateOf(vm.c.output.availableOutputs()) }
    var selected by remember { mutableStateOf(vm.c.output.preferredDeviceId) }
    LaunchedEffect(output) { choices = vm.c.output.availableOutputs() }
    val dim = Color.White.copy(alpha = 0.62f)

    GlassSheet(haze, onDismiss) {
        Text("Playing on", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(14.dp))

        // Current output.
        Row(Modifier.fillMaxWidth().glassGroup().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                LIcon(iconFor(output.type), null, tint = Color.White, size = 22.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (output.type == OutputType.SPEAKER) "This phone" else output.deviceName ?: output.type.label,
                    style = MaterialTheme.typography.titleMedium, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val codec = output.codec
                Text(
                    when {
                        codec != null -> listOfNotNull(codec.name, codec.detail, codec.sampleRateHz.takeIf { it > 0 }?.let { AudioInfo.khz(it) })
                            .joinToString(" · ")
                        output.type == OutputType.BLUETOOTH -> "Bluetooth · codec hidden by your phone"
                        output.type == OutputType.USB -> if (output.bitPerfectActive) "USB DAC · bit-perfect" else "USB DAC"
                        else -> output.type.label
                    },
                    color = dim, style = MaterialTheme.typography.bodySmall,
                )
                audio.summary?.let { Text(it, color = dim, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Volume", style = MaterialTheme.typography.titleSmall, color = dim)
        VolumeSlider(track = Color.White.copy(alpha = 0.16f), fill = Color.White, iconTint = dim)

        Spacer(Modifier.height(12.dp))
        Text("Output", style = MaterialTheme.typography.titleSmall, color = dim)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().glassGroup()) {
            OutputRow("Automatic", "Uses Bluetooth when connected", L.drawable.lucide_ic_smartphone, selected == -1, divider = choices.isNotEmpty()) {
                selected = -1; vm.c.playerConnection.setOutputDevice(-1)
            }
            choices.forEachIndexed { i, c ->
                OutputRow(c.name, c.type.label, iconFor(c.type), selected == c.id, divider = i < choices.lastIndex) {
                    selected = c.id; vm.c.playerConnection.setOutputDevice(c.id)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        CastSection(vm)

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Audio details", Modifier.weight(1f), onAudioDetails)
            GlassButton("Pair a device", Modifier.weight(1f)) {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

/**
 * "Cast to": Chromecast / Google TV devices (found by Android's media router) and DLNA TVs and speakers (found on the
 * Wi-Fi), in one list. While casting, the device shows at the top with a Stop button.
 */
@Composable
private fun CastSection(vm: MainViewModel) {
    val context = LocalContext.current
    val dim = Color.White.copy(alpha = 0.62f)
    val casting by com.sonora.player.cast.CastState.target.collectAsStateWithLifecycle()
    val router = remember { runCatching { androidx.mediarouter.media.MediaRouter.getInstance(context) }.getOrNull() }
    val selector = remember {
        androidx.mediarouter.media.MediaRouteSelector.Builder()
            .addControlCategory(
                com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
                    com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                ),
            ).build()
    }
    var castRoutes by remember { mutableStateOf<List<androidx.mediarouter.media.MediaRouter.RouteInfo>>(emptyList()) }
    DisposableEffect(router) {
        val r = router
        if (r == null) {
            onDispose { }
        } else {
            fun refresh() { castRoutes = r.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) } }
            val cb = object : androidx.mediarouter.media.MediaRouter.Callback() {
                override fun onRouteAdded(router: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(router: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(router: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refresh()
            }
            r.addCallback(selector, cb, androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            refresh()
            onDispose { r.removeCallback(cb) }
        }
    }
    var dlna by remember { mutableStateOf<List<com.sonora.player.cast.DlnaDevice>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            searching = true
            dlna = com.sonora.player.cast.DlnaDiscovery.discover(context, vm.c.http)
            searching = false
            kotlinx.coroutines.delay(15_000)
        }
    }
    // A TV that speaks both shows once, as Google Cast (smoother hand-over).
    val castNames = castRoutes.map { it.name }.toSet()
    val dlnaShown = dlna.filter { it.name !in castNames }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Cast to", style = MaterialTheme.typography.titleSmall, color = dim, modifier = Modifier.weight(1f))
        if (searching) Text("Searching…", style = MaterialTheme.typography.labelSmall, color = dim)
    }
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().glassGroup()) {
        val now = casting
        if (now != null) {
            OutputRow(now.name, "Playing here now · tap to stop casting", L.drawable.lucide_ic_cast, true, divider = castRoutes.isNotEmpty() || dlnaShown.isNotEmpty()) {
                vm.c.playerConnection.stopCasting()
                router?.unselect(androidx.mediarouter.media.MediaRouter.UNSELECT_REASON_STOPPED)
            }
        }
        castRoutes.filter { it.name != now?.name }.forEachIndexed { i, route ->
            OutputRow(route.name, "Google Cast", L.drawable.lucide_ic_cast, false, divider = i < castRoutes.lastIndex || dlnaShown.isNotEmpty()) {
                if (now?.kind == com.sonora.player.cast.CastTarget.Kind.DLNA) vm.c.playerConnection.stopCasting()
                router?.selectRoute(route)
            }
        }
        dlnaShown.filter { it.name != now?.name }.forEachIndexed { i, d ->
            OutputRow(d.name, "DLNA · smart TV / speaker", L.drawable.lucide_ic_tv, false, divider = i < dlnaShown.lastIndex) {
                if (now?.kind == com.sonora.player.cast.CastTarget.Kind.GOOGLE_CAST) router?.unselect(androidx.mediarouter.media.MediaRouter.UNSELECT_REASON_STOPPED)
                vm.c.playerConnection.castToDlna(d)
            }
        }
        if (now == null && castRoutes.isEmpty() && dlnaShown.isEmpty()) {
            Text(
                if (searching) "Looking for TVs and speakers on your Wi-Fi…" else "No TVs found. Make sure the TV is on and on the same Wi-Fi as this phone.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun GlassButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun OutputRow(title: String, subtitle: String, icon: Int, selected: Boolean, divider: Boolean, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LIcon(icon, null, tint = Color.White.copy(alpha = 0.8f), size = 22.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
            }
            if (selected) LIcon(L.drawable.lucide_ic_check, "Selected", tint = Color.White, size = 20.dp)
        }
        if (divider) Box(Modifier.padding(start = 50.dp).fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.10f)))
    }
}
