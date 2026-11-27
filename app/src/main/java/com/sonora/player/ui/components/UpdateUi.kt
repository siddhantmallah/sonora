package com.sonora.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.UpdateInfo
import com.sonora.player.data.UpdateState
import com.sonora.player.data.Updater
import kotlinx.coroutines.launch

/** Release notes, trimmed to their first few meaningful lines. */
private fun shortNotes(notes: String, lines: Int = 5): String =
    notes.lineSequence().map { it.trim().removePrefix("- ").removePrefix("* ").replace("**", "") }
        .filter { it.isNotBlank() && !it.startsWith("#") }.take(lines).joinToString("\n")

/** The update panel for Settings: version, "Check for updates", and download / install with progress. */
@Composable
fun UpdatePanel(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Coming back from Android's "Install unknown apps" switch: carry on if it's now allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val s = updater.state.value
        if (s is UpdateState.NeedsPermission && updater.canInstall()) scope.launch { updater.downloadAndInstall(s.info) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text("Version ${updater.currentVersion}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                when (state) {
                    UpdateState.Checking -> "Checking…"
                    UpdateState.UpToDate -> "Up to date"
                    is UpdateState.Available -> "Update available"
                    is UpdateState.Downloading -> "Downloading…"
                    is UpdateState.Installing -> "Installing…"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UpdateBody(state, updater) { info -> scope.launch { updater.downloadAndInstall(info) } }
        if (state is UpdateState.Idle || state is UpdateState.UpToDate || state is UpdateState.Failed && (state as UpdateState.Failed).info == null) {
            AppButton(small = true, solid = false, onClick = { scope.launch { updater.check(force = true) } }) { Text("Check for updates") }
        }
    }
}

@Composable
private fun UpdateBody(state: UpdateState, updater: Updater, start: (UpdateInfo) -> Unit) {
    when (state) {
        is UpdateState.Available -> {
            Text("Sonora ${state.info.version} is ready to install.", style = MaterialTheme.typography.titleMedium)
            shortNotes(state.info.notes).takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            AppButton(small = true, onClick = { start(state.info) }) {
                Text("Download and install" + if (state.info.sizeBytes > 0) " · ${"%.1f".format(state.info.sizeBytes / 1_048_576f)} MB" else "")
            }
        }
        is UpdateState.Downloading -> {
            Text("Downloading Sonora ${state.info.version}…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is UpdateState.Installing -> Text(
            "Android is installing the update. Confirm on the screen it shows; Sonora restarts on the new version.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is UpdateState.NeedsPermission -> {
            Text(
                "To update itself, Sonora needs \"Install unknown apps\" allowed. Turn it on, then come back here and the update continues.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppButton(small = true, onClick = { updater.openInstallPermission() }) { Text("Allow installs") }
        }
        is UpdateState.Failed -> {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            state.info?.let { info -> AppButton(small = true, onClick = { start(info) }) { Text("Try again") } }
        }
        else -> Unit
    }
}

/** On app start: checks quietly and, if there's a newer release you haven't put off, offers it. */
@Composable
fun UpdatePrompt(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val s = updater.check()
        if (s is UpdateState.Available && !updater.isSnoozed(s.info.version)) open = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val s = updater.state.value
        if (open && s is UpdateState.NeedsPermission && updater.canInstall()) scope.launch { updater.downloadAndInstall(s.info) }
    }
    if (!open) return
    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    } ?: run { open = false; return }
    val busy = state is UpdateState.Downloading
    AlertDialog(
        onDismissRequest = { if (!busy) { updater.snooze(info.version); open = false } },
        title = { Text("Sonora ${info.version} is here") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { UpdateBody(state, updater) { scope.launch { updater.downloadAndInstall(it) } } } },
        confirmButton = {
            if (state is UpdateState.Installing) TextButton(onClick = { open = false }) { Text("OK") }
        },
        dismissButton = {
            if (!busy && state !is UpdateState.Installing) TextButton(onClick = { updater.snooze(info.version); open = false }) { Text("Later") }
        },
    )
}
