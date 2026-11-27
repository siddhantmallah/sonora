package com.sonora.player.ui.screens

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.BuildConfig
import com.sonora.player.data.SettingsRepository.Keys
import com.sonora.player.data.ThemeMode
import com.sonora.player.playback.OutputInfo
import com.sonora.player.playback.OutputType
import com.sonora.player.playback.AudioInfo
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.IconSquare
import com.sonora.player.ui.components.IosColors
import com.sonora.player.ui.components.IosGroup
import com.sonora.player.ui.components.IosRow
import com.sonora.player.ui.components.IosToggleRow
import com.sonora.player.ui.components.LargeTitle
import com.composables.icons.lucide.R as L

@Composable
fun SettingsScreen(vm: MainViewModel, actions: UiActions) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val set = vm.c.settings
    val context = LocalContext.current
    var editingFolders by remember { mutableStateOf(false) }

    if (editingFolders) HiddenFoldersDialog(s.excludedFolders, set) { editingFolders = false }

    val ytSignedIn by vm.ytSignedIn.collectAsStateWithLifecycle()
    val ytAccount by vm.ytAccount.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    if (confirmSignOut) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of YouTube Music?") },
            text = { Text("Your YouTube playlists, likes and history will disappear from Sonora until you sign in again.") },
            confirmButton = {
                com.sonora.player.ui.components.AppButton(small = true, onClick = {
                    confirmSignOut = false
                    vm.yt.signOut()
                    Toast.makeText(context, "Signed out of YouTube Music", Toast.LENGTH_SHORT).show()
                }) { Text("Sign out") }
            },
            dismissButton = { com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }

    val sources by vm.sourceSummary.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        SettingsHero(
            vm, actions, ytSignedIn, ytAccount?.name, ytAccount?.photo?.takeIf { ytSignedIn },
            onSignOut = { confirmSignOut = true },
        )

        // Quick tiles, like Home's.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ShortcutItem(if (sources.isEmpty()) "Connected apps" else "Connected apps · ${sources.size}", null, icon = L.drawable.lucide_ic_link) { actions.connections() },
                ShortcutItem("Equalizer", null, icon = L.drawable.lucide_ic_sliders_horizontal) { actions.equalizer() },
                ShortcutItem("Driving mode", null, icon = L.drawable.lucide_ic_car) { actions.drive() },
                ShortcutItem("Your Replay", null, replay = true) { actions.replay() },
            ).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { ShortcutTile(it, Modifier.weight(1f)) } }
            }
        }

        IosGroup(
            "Accounts", largeTitle = true,
            footer = "YouTube Music uses its unofficial web API, which isn't permitted by YouTube's terms. For personal use only.",
        ) {
            row {
                IosRow(
                    "Connected apps", L.drawable.lucide_ic_link, IosColors.Blue,
                    subtitle = "Spotify, Apple Music, Last.fm and more",
                    detail = if (sources.isEmpty()) null else "${sources.size}",
                ) { actions.connections() }
            }
            row { IosToggleRow("Data saver on mobile data", L.drawable.lucide_ic_signal, IosColors.Green, s.ytDataSaver) { set.set(Keys.ytDataSaver, it) } }
            row { IosToggleRow("Data saver on Wi-Fi", L.drawable.lucide_ic_wifi, IosColors.Blue, s.ytDataSaverWifi) { set.set(Keys.ytDataSaverWifi, it) } }
            row { IosToggleRow("Endless autoplay", L.drawable.lucide_ic_infinity, IosColors.Purple, s.ytAutoplay, subtitle = "Keep the music going with similar songs") { set.set(Keys.ytAutoplay, it) } }
        }

        IosGroup("Appearance", largeTitle = true) {
            row {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(L.drawable.lucide_ic_moon, IosColors.Indigo)
                        Spacer(Modifier.width(14.dp))
                        Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    PillTabs(ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, s.themeMode, sidePadding = 0.dp, idle = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        set.set(Keys.themeMode, it.name)
                    }
                }
            }
            row {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(L.drawable.lucide_ic_type, IosColors.Blue)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Font", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Match system follows your phone's font style",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Each option previews in its own font.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        com.sonora.player.ui.theme.AppFonts.forEach { (key, label) ->
                            val selected = s.fontStyle == key
                            val fam = remember(key) { com.sonora.player.ui.theme.appFontFamily(key) }
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = fam),
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable { set.set(Keys.fontStyle, key) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                row { IosToggleRow("Material You colours", L.drawable.lucide_ic_palette, IosColors.Orange, s.dynamicColor, subtitle = "Use your wallpaper's colours instead of Sonora's") { set.set(Keys.dynamicColor, it) } }
            }
        }

        IosGroup("Playback", largeTitle = true, footer = "Crossfade fades each song out and the next one in, so there are no hard cuts.") {
            row { IosRow("Equalizer & effects", L.drawable.lucide_ic_sliders_horizontal, IosColors.Blue, detail = if (s.eqEnabled) "On" else "Off") { actions.equalizer() } }
            row { IosRow("Driving mode", L.drawable.lucide_ic_car, IosColors.Gray, subtitle = "Big controls, voice and Map mode") { actions.drive() } }
            row {
                var local by remember(s.crossfadeSec) { androidx.compose.runtime.mutableFloatStateOf(s.crossfadeSec.toFloat()) }
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(L.drawable.lucide_ic_blend, IosColors.Purple)
                        Spacer(Modifier.width(14.dp))
                        Text("Crossfade", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(if (local < 0.5f) "Off" else "${local.toInt()} s", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ThinSlider(local.toInt(), 0..12, Modifier.padding(vertical = 6.dp), onChange = { local = it.toFloat() }) {
                        set.set(Keys.crossfadeSec, it)
                    }
                }
            }
            row { IosToggleRow("Skip silence", L.drawable.lucide_ic_scissors, IosColors.Gray, s.skipSilence) { set.set(Keys.skipSilence, it) } }
        }

        IosGroup("Audio quality", largeTitle = true) {
            row { IosToggleRow("Hi-Res output", L.drawable.lucide_ic_audio_waveform, IosColors.Orange, s.hiResOutput, subtitle = "32-bit float keeps 24-bit / 96 kHz+ intact") { set.set(Keys.hiResOutput, it) } }
            if (Build.VERSION.SDK_INT >= 34) {
                row { IosToggleRow("Bit-perfect USB DAC", L.drawable.lucide_ic_usb, IosColors.Teal, s.bitPerfectUsb, subtitle = "No Android resampling or mixing") { set.set(Keys.bitPerfectUsb, it) } }
            }
            row { IosToggleRow("Battery saver", L.drawable.lucide_ic_battery_charging, IosColors.Green, s.audioOffload, subtitle = "Audio offload · disables EQ and visualizer") { set.set(Keys.audioOffload, it) } }
        }

        IosGroup(
            "Sonora Enhance", largeTitle = true,
            footer = if (s.bitPerfectUsb) "Off while Bit-perfect USB DAC is on: nothing touches the samples."
            else "Lossy audio (streams, MP3, AAC, Opus) loses its top end and some sparkle. Enhance regenerates the highs " +
                "the codec cut off, adds a touch of warmth and width, and stops anything from clipping. It can't turn a " +
                "128 kbps stream into true 320 kbps (that detail is gone), but it brings back much of the feel. Auto " +
                "leaves lossless files untouched.",
        ) {
            row {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(L.drawable.lucide_ic_sparkles, IosColors.Pink)
                        Spacer(Modifier.width(14.dp))
                        Text("Enhance", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    PillTabs(listOf(0 to "Off", 1 to "Auto", 2 to "Always"), s.enhanceMode, sidePadding = 0.dp, idle = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        set.set(Keys.enhanceMode, it)
                    }
                }
            }
            if (s.enhanceMode != 0) row {
                var local by remember(s.enhanceAmount) { androidx.compose.runtime.mutableFloatStateOf(s.enhanceAmount.toFloat()) }
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(L.drawable.lucide_ic_sparkles, IosColors.Purple)
                        Spacer(Modifier.width(14.dp))
                        Text("Strength", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            when { local < 34 -> "Subtle"; local < 67 -> "Balanced"; else -> "Rich" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ThinSlider(local.toInt(), 0..100, Modifier.padding(vertical = 6.dp), onChange = { local = it.toFloat() }) {
                        set.set(Keys.enhanceAmount, it)
                    }
                }
            }
        }

        IosGroup(
            "For audiophiles", largeTitle = true,
            footer = "Pre-amp lowers the level before the equalizer and Enhance, so big boosts never clip. Balance and " +
                "Mono help with one-sided hearing or a single earbud.",
        ) {
            row {
                var local by remember(s.preampDb) { androidx.compose.runtime.mutableFloatStateOf(s.preampDb.toFloat()) }
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(L.drawable.lucide_ic_gauge, IosColors.Orange)
                        Spacer(Modifier.width(14.dp))
                        Text("Pre-amp", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(if (local > -0.5f) "0 dB" else "${local.toInt()} dB", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ThinSlider(local.toInt(), -12..0, Modifier.padding(vertical = 6.dp), onChange = { local = it.toFloat() }) {
                        set.set(Keys.preampDb, it)
                    }
                }
            }
            row {
                var local by remember(s.balance) { androidx.compose.runtime.mutableFloatStateOf(s.balance.toFloat()) }
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(L.drawable.lucide_ic_headphones, IosColors.Teal)
                        Spacer(Modifier.width(14.dp))
                        Text("Balance", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            when { local < -2 -> "L ${-local.toInt()}%"; local > 2 -> "R ${local.toInt()}%"; else -> "Centre" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ThinSlider(local.toInt(), -100..100, Modifier.padding(vertical = 6.dp), onChange = { local = it.toFloat() }) {
                        // Snap back to dead centre when close.
                        set.set(Keys.balance, if (kotlin.math.abs(it) <= 4) 0 else it)
                    }
                }
            }
            row { IosToggleRow("Mono audio", L.drawable.lucide_ic_speaker, IosColors.Gray, s.monoAudio, subtitle = "Both ears get the full mix") { set.set(Keys.monoAudio, it) } }
        }

        Text(
            "BLUETOOTH & LDAC", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 7.dp),
        )
        OutputCard(output, null, Modifier.padding(horizontal = 16.dp))

        IosGroup("Library", largeTitle = true) {
            row {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconSquare(L.drawable.lucide_ic_timer, IosColors.Gray)
                        Spacer(Modifier.width(14.dp))
                        Text("Ignore tracks shorter than", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    PillTabs(listOf(0, 15, 30, 60).map { it to if (it == 0) "Off" else "${it} s" }, s.minDurationSec, sidePadding = 0.dp, idle = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        set.set(Keys.minDurationSec, it)
                    }
                }
            }
            row { IosRow("Hidden folders", L.drawable.lucide_ic_eye_off, IosColors.Indigo, detail = "${s.excludedFolders.size}") { editingFolders = true } }
            row {
                IosRow("Rescan library", L.drawable.lucide_ic_refresh_cw, IosColors.Blue) {
                    vm.refreshLibrary()
                    Toast.makeText(context, "Scanning…", Toast.LENGTH_SHORT).show()
                }
            }
        }

        IosGroup("Lyrics & scrobbling", largeTitle = true, footer = "Online lyrics come from LRCLIB and send only title, artist, album and length.") {
            row { IosToggleRow("Find lyrics online", L.drawable.lucide_ic_mic_vocal, IosColors.Pink, s.onlineLyrics) { set.set(Keys.onlineLyrics, it) } }
            row { ListenBrainzRow(vm) }
        }

        IosGroup("Updates", largeTitle = true, footer = "Updates come from Sonora's GitHub releases. Android only installs them when they're signed by Sonora, and asks you to confirm.") {
            row { com.sonora.player.ui.components.UpdatePanel(vm.c.updater) }
        }
        com.sonora.player.ui.components.MadeWithLove()
        // The birds in Home's sky are real photos.
        Text(
            "Bird photos in the sky: Wikimedia Commons (public domain and CC0), by the US National Park Service, " +
                "USFWS, Trikansh Sharma, John Robert McPherson, Jacob W. Frank and others.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 16.dp),
        )
    }
    // Read the scroll position through derivedStateOf so the screen only recomposes when this flips,
    // not on every scrolled frame.
    val scrolled by remember { androidx.compose.runtime.derivedStateOf { scroll.value > 600 } }
    StatusBarScrim(scrolled)
    }
    HeroStatusBar(scroll)
}

/** ListenBrainz token entry, checked against the server before it's saved. */
@Composable
private fun ListenBrainzRow(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val connected = s.listenBrainzToken.isNotBlank()
    IosRow(
        "ListenBrainz", L.drawable.lucide_ic_radio_tower, IosColors.Orange,
        subtitle = if (connected) "Scrobbling every song you finish" else "Log your listening to your ListenBrainz profile",
        detail = if (connected) "On" else "Off",
    ) { editing = true; token = ""; status = null }
    if (editing) {
        com.sonora.player.ui.components.AppDialog(
            onDismissRequest = { editing = false },
            title = { Text("ListenBrainz") },
            text = {
                Column {
                    Text(
                        "Paste your user token from listenbrainz.org/settings. It stays on this phone and is only sent to ListenBrainz.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("User token") }, modifier = Modifier.fillMaxWidth())
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                }
            },
            confirmButton = {
                com.sonora.player.ui.components.AppButton(small = true, enabled = token.isNotBlank(), onClick = {
                    status = "Checking…"
                    scope.launch {
                        val user = vm.c.scrobbler.validate(token)
                        if (user != null) {
                            vm.c.settings.set(Keys.listenBrainzToken, token.trim())
                            Toast.makeText(context, "Scrobbling as $user", Toast.LENGTH_SHORT).show()
                            editing = false
                        } else status = "That token didn't work. Check it and try again."
                    }
                }) { Text("Connect") }
            },
            dismissButton = {
                com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = {
                    if (connected) vm.c.settings.set(Keys.listenBrainzToken, "")
                    editing = false
                }) { Text(if (connected) "Disconnect" else "Cancel") }
            },
        )
    }
}

@Composable
private fun HiddenFoldersDialog(folders: Set<String>, set: com.sonora.player.data.SettingsRepository, onDismiss: () -> Unit) {
    var newFolder by remember { mutableStateOf("") }
    com.sonora.player.ui.components.AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden folders") },
        text = {
            Column {
                Text(
                    "Songs whose path contains any of these are skipped. Files are never deleted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                folders.sorted().forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { set.excludeFolder(f, false) }) { LIcon(L.drawable.lucide_ic_x, "Show again", size = 18.dp) }
                    }
                }
                OutlinedTextField(
                    newFolder, { newFolder = it }, singleLine = true,
                    label = { Text("Add folder name or path") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            com.sonora.player.ui.components.AppButton(small = true, onClick = { set.excludeFolder(newFolder, true); newFolder = "" }, enabled = newFolder.isNotBlank()) { Text("Add") }
        },
        dismissButton = { com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = onDismiss) { Text("Done") } },
    )
}

private fun Context.openSettings(action: String) {
    try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "Not available on this phone", Toast.LENGTH_SHORT).show()
    }
}

/** Where audio goes, which Bluetooth codec is active, and what the player is sending. */
@Composable
fun OutputCard(output: OutputInfo, audio: AudioInfo?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LIcon(
                    when (output.type) {
                        OutputType.BLUETOOTH -> L.drawable.lucide_ic_bluetooth
                        OutputType.USB -> L.drawable.lucide_ic_usb
                        OutputType.WIRED -> L.drawable.lucide_ic_headphones
                        else -> L.drawable.lucide_ic_speaker
                    },
                    null, tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(output.deviceName ?: output.type.label, style = MaterialTheme.typography.titleMedium)
                    Text(output.type.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (audio != null) {
                InfoLine("Source", audio.summary ?: "—")
                InfoLine("Player output", listOfNotNull(audio.outEncoding, audio.outSampleRate.takeIf { it > 0 }?.let { AudioInfo.khz(it) })
                    .joinToString(" · ").ifBlank { "—" } + if (audio.offload) " · offloaded" else "")
            }
            when (output.type) {
                OutputType.BLUETOOTH -> {
                    val codec = output.codec
                    if (codec != null) {
                        InfoLine("Codec", codec.name + (codec.detail?.let { " · $it" } ?: ""))
                        if (codec.sampleRateHz > 0) {
                            InfoLine("Link", "${codec.bitsPerSample}-bit / ${AudioInfo.khz(codec.sampleRateHz)}")
                        }
                    } else {
                        InfoLine("Codec", if (output.needsBluetoothPermission) "Allow Nearby devices permission to read it" else "Hidden by Android on this phone")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "LDAC is negotiated by your phone and headphones; no app can force it. For the best quality, open " +
                            "Developer options → Bluetooth audio LDAC codec: Playback quality → Optimized for audio quality (990 kbps), " +
                            "and set the sample rate to 96 kHz / 24-bit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.sonora.player.ui.components.AppButton(solid = false, small = true, onClick = { context.openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }) {
                            Text("Developer options")
                        }
                        com.sonora.player.ui.components.AppButton(solid = false, small = true, onClick = { context.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }) { Text("Bluetooth") }
                    }
                }
                OutputType.USB -> InfoLine("Bit-perfect", if (output.bitPerfectActive) "Active" else "Off")
                else -> Text(
                    "Connect LDAC / aptX HD headphones or a USB DAC for high-resolution playback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Home-style hero for Settings: today's sky colour with your photo blurred into it, your photo big, your name, and
 * quick actions, melting into the page.
 */
@Composable
private fun SettingsHero(
    vm: MainViewModel, actions: UiActions, signedIn: Boolean, name: String?, photo: String?, onSignOut: () -> Unit,
) {
    val weather by vm.weather.collectAsStateWithLifecycle()
    val page = MaterialTheme.colorScheme.background
    val topInset = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val sky = skyWash(System.currentTimeMillis(), weather)
    val photoUri = photo?.let(android.net.Uri::parse)
    val glass = Color.Black.copy(alpha = 0.28f)
    Box(Modifier.fillMaxWidth().height(topInset + 340.dp).clipToBounds()) {
        Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(androidx.compose.ui.graphics.lerp(sky, Color.Black, 0.25f), androidx.compose.ui.graphics.lerp(sky, Color(0xFF7C3AED), 0.35f)),
                ),
            ),
        )
        com.sonora.player.ui.components.BlurredArt(photoUri, Modifier.matchParentSize(), blur = 60.dp, zoom = 1.4f, alpha = 0.55f)
        Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
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
                ) { LIcon(L.drawable.lucide_ic_chevron_left, "Back", tint = Color.White, size = 22.dp) }
                Spacer(Modifier.weight(1f))
                Text("Settings", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(38.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(horizontal = 20.dp)) {
                com.sonora.player.ui.components.Artwork(
                    photoUri,
                    Modifier.size(84.dp).graphicsLayer { shadowElevation = 14.dp.toPx(); shape = CircleShape; clip = true },
                    CircleShape, L.drawable.lucide_ic_circle_user,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (signedIn) name ?: "Your Google account" else "Welcome to Sonora",
                    color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    if (signedIn) "YouTube Music · signed in" else "Sign in to bring your YouTube Music feed, likes and history",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Color.White)
                            .clickable { if (signedIn) actions.connections() else actions.ytLogin() }.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LIcon(if (signedIn) L.drawable.lucide_ic_link else L.drawable.lucide_ic_log_in, null, tint = Color.Black, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (signedIn) "Connected apps" else "Sign in", color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                    if (signedIn) {
                        Row(
                            Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(glass).clickable(onClick = onSignOut).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LIcon(L.drawable.lucide_ic_log_out, null, tint = Color.White, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Sign out", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(56.dp))
        }
    }
}
