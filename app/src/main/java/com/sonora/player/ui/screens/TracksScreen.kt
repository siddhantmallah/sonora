package com.sonora.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.data.Track
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.Artwork
import com.sonora.player.ui.components.EmptyState
import com.sonora.player.ui.components.FavoriteHeart
import com.sonora.player.ui.components.HeartRed
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.LocalNowPlayingId
import com.sonora.player.ui.components.LocalFavorites
import com.sonora.player.ui.components.MenuItem
import com.sonora.player.ui.components.NowPlayingArtwork
import com.sonora.player.ui.components.PlayShuffleRow
import com.composables.icons.lucide.R as L

/** One row for a track from either source. */
@Composable
fun TrackRow(track: Track, vm: MainViewModel, onRemoveDownload: (() -> Unit)? = null, onClick: () -> Unit) {
    val current = LocalNowPlayingId.current == track.key
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current) NowPlayingArtwork(track.art, Modifier.size(48.dp), RoundedCornerShape(8.dp))
        else Artwork(track.art, Modifier.size(48.dp), RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { LIcon(L.drawable.lucide_ic_ellipsis_vertical, "More", size = 20.dp) }
            com.sonora.player.ui.components.AppMenu(menu, { menu = false }) {
                MenuItem("Play next", L.drawable.lucide_ic_list_start) { menu = false; vm.c.playerConnection.playNextItems(listOf(track.mediaItem())) }
                MenuItem("Add to queue", L.drawable.lucide_ic_list_end) { menu = false; vm.c.playerConnection.addItems(listOf(track.mediaItem())) }
                if (track is Track.Online) MenuItem("Start radio", L.drawable.lucide_ic_radio) { menu = false; vm.startRadio(track.song) }
                val liked = com.sonora.player.youtube.YtIds.statsId(track.key) in LocalFavorites.current
                MenuItem(if (liked) "Unlike" else "Like", if (liked) L.drawable.lucide_ic_heart_off else L.drawable.lucide_ic_heart) {
                    menu = false; vm.toggleFavoriteMedia(track.key)
                }
                onRemoveDownload?.let { rm -> MenuItem("Remove download", L.drawable.lucide_ic_trash_2) { menu = false; rm() } }
            }
        }
    }
}
