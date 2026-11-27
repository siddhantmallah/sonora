package com.sonora.player.ui.screens

import com.sonora.player.ui.components.IosColors
import com.sonora.player.ui.components.IosGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.ConnectionsRepository
import com.sonora.player.data.Service
import com.sonora.player.data.Services
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.LIcon
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.composables.icons.lucide.R as L

/** Connect Spotify, Apple Music, Last.fm and other apps so stats and recommendations cover all your listening. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(vm: MainViewModel, actions: UiActions) {
    val summary by vm.sourceSummary.collectAsStateWithLifecycle()
    val status by vm.c.connections.status.collectAsStateWithLifecycle()
    val ytSignedIn by vm.ytSignedIn.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Service?>(null) }
    val connected = summary.map { it.source }.toSet() + Services.filter { vm.c.connections.isSignedIn(it.id) }.map { it.id }
    val total = connected.size + if (ytSignedIn) 1 else 0
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 120 } }

    // One row per app: what it shows and what tapping it does.
    data class Entry(val name: String, val color: Long, val line: String, val on: Boolean, val tap: () -> Unit)
    val entries = buildList {
        add(Entry("YouTube Music", 0xFFFF0033, if (ytSignedIn) "Signed in · history used live" else "Sign in to use your history", ytSignedIn) {
            if (!ytSignedIn) actions.ytLogin()
        })
        Services.forEach { s ->
            val sum = summary.firstOrNull { it.source == s.id }
            val signed = vm.c.connections.isSignedIn(s.id)
            val line = when {
                sum != null -> "%,d listens".format(sum.plays) + (if (signed) " · synced " + ago(vm.c.connections.lastSync(s.id)) else " · imported")
                signed -> "Signed in · synced " + ago(vm.c.connections.lastSync(s.id))
                else -> if (s.signIn) "Sign in or import your history" else "Import your history"
            }
            add(Entry(s.name, s.color, line, sum != null || signed) { open = s })
        }
    }
    val on = entries.filter { it.on }
    val off = entries.filterNot { it.on }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace() + 16.dp)) {
            item {
                AmbientHeader(vm, actions, "Connected apps", onBack = { actions.back() }) {
                    Text(
                        "Bring in what you play on other apps, so Insights and your mixes cover all of your listening.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
            }
            item {
                // Progress toward cross-app recommendations, with the logos of what's connected.
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (total >= 2) "Cross-app picks are on" else "Connect ${2 - total} more app" + (if (2 - total > 1) "s" else ""),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (total >= 2) "Home's \"Across your apps\" mixes what you love everywhere."
                                else "Two or more unlock \"Across your apps\" on Home.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (on.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            // Overlapping logo stack.
                            Box(Modifier.width((24 + 16 * (on.size.coerceAtMost(4) - 1)).dp).height(28.dp)) {
                                on.take(4).forEachIndexed { i, e ->
                                    Box(
                                        Modifier.padding(start = (16 * i).dp).size(28.dp).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainer).padding(2.dp)
                                            .clip(CircleShape).background(Color(e.color)),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(e.name.first().toString(), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(2) { i ->
                            Box(
                                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                                    .background(if (i < total) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                        }
                    }
                }
            }
            if (on.isNotEmpty()) item {
                IosGroup("Connected") { on.forEach { e -> row { ServiceRow(e.name, e.color, e.line, true, e.tap) } } }
            }
            item {
                IosGroup(
                    if (on.isEmpty()) "Apps" else "Add more",
                    footer = status ?: "Sign-ins stay on this phone. Imports read only your listening history.",
                ) { off.forEach { e -> row { ServiceRow(e.name, e.color, e.line, false, e.tap) } } }
            }
        }
        StatusBarScrim(scrolled)
    }

    open?.let { s -> ServiceSheet(vm, s) { open = null } }
}

private fun ago(t: Long): String {
    if (t <= 0) return "never"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 1440 -> "${m / 60} h ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    }
}

/** The app's logo tile: its brand colour with its initial. */
@Composable
private fun Logo(name: String, color: Long, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.27f).dp)).background(Color(color)), contentAlignment = Alignment.Center) {
        Text(
            name.first().toString(), color = Color.White, fontWeight = FontWeight.Bold,
            style = if (size >= 40) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
    }
}

/** A grouped-list row (same geometry as Settings' rows, so separators line up). */
@Composable
private fun ServiceRow(name: String, color: Long, line: String, connected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(name, color, 30)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (connected) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(IosColors.Green), contentAlignment = Alignment.Center) {
                LIcon(L.drawable.lucide_ic_check, "Connected", tint = Color.White, size = 14.dp)
            }
            Spacer(Modifier.width(6.dp))
        }
        LIcon(L.drawable.lucide_ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), size = 18.dp)
    }
}

/** A full-width pill button: solid (primary action) or soft (secondary). */
@Composable
private fun PillButton(text: String, modifier: Modifier = Modifier, solid: Boolean = true, enabled: Boolean = true, color: Color = Color.Unspecified, icon: Int? = null, onClick: () -> Unit) {
    val bg = if (solid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (color != Color.Unspecified) color else if (solid) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(50)).background(bg.copy(alpha = if (enabled) 1f else 0.35f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { LIcon(icon, null, tint = fg, size = 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceSheet(vm: MainViewModel, s: Service, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                vm.c.connections.import(s.id, uri)
                busy = false
                vm.loadFeed(force = true)
            }
        }
    }
    val connected = vm.c.connections.isSignedIn(s.id) || vm.sourceSummary.value.any { it.source == s.id }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(s.name, s.color, 48)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (connected) "Connected" else "Not connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connected) IosColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            if (s.id == "Spotify") {
                var clientId by remember { mutableStateOf(vm.c.connections.spotifyClientId) }
                Text("SIGN IN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Spotify needs a free Client ID for apps like Sonora: open developer.spotify.com/dashboard → Create app → " +
                        "add the Redirect URI below → tick \"Web API\" → copy the Client ID here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { clipboard.setText(AnnotatedString(ConnectionsRepository.SPOTIFY_REDIRECT)) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ConnectionsRepository.SPOTIFY_REDIRECT, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("Copy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(clientId, { clientId = it }, label = { Text("Client ID") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PillButton(
                    if (vm.c.connections.isSignedIn("Spotify")) "Sign in again" else "Sign in with Spotify",
                    Modifier.fillMaxWidth(), enabled = clientId.length >= 20,
                ) {
                    vm.c.connections.spotifyClientId = clientId
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.c.connections.spotifyAuthUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    onDismiss()
                }
                Spacer(Modifier.height(18.dp))
            }

            if (s.id == "Last.fm") {
                var user by remember { mutableStateOf("") }
                var key by remember { mutableStateOf("") }
                Text("SIGN IN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Enter your Last.fm username and an API key (free at last.fm/api/account/create). Sonora reads your public scrobbles.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PillButton(
                    if (busy) "Connecting…" else "Connect Last.fm", Modifier.fillMaxWidth(),
                    enabled = !busy && user.isNotBlank() && key.length >= 20,
                ) {
                    busy = true
                    scope.launch {
                        val ok = vm.c.connections.connectLastFm(user, key)
                        busy = false
                        if (ok) { vm.loadFeed(force = true); onDismiss() }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            Text("IMPORT YOUR HISTORY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.howTo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            PillButton(
                if (busy) "Importing…" else "Choose file (.zip, .json or .csv)", Modifier.fillMaxWidth(),
                solid = s.id != "Spotify" && s.id != "Last.fm", enabled = !busy, icon = L.drawable.lucide_ic_file_up,
            ) { picker.launch(arrayOf("*/*")) }
            if (connected) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.signIn && vm.c.connections.isSignedIn(s.id)) {
                        PillButton("Sync now", Modifier.weight(1f), solid = false) { vm.syncConnections(); onDismiss() }
                    }
                    PillButton("Disconnect", Modifier.weight(1f), solid = false, color = MaterialTheme.colorScheme.error) {
                        scope.launch { vm.c.connections.remove(s.id); vm.loadFeed(force = true); onDismiss() }
                    }
                }
            }
        }
    }
}
