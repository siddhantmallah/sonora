package com.sonora.player.ui.screens

import androidx.compose.foundation.border
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.togetherWith
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonora.player.ui.Load
import com.sonora.player.ui.MainViewModel
import com.sonora.player.ui.UiActions
import com.sonora.player.ui.components.EmptyState
import com.sonora.player.ui.components.LIcon
import com.sonora.player.ui.components.SectionHeader
import com.sonora.player.ui.components.SongRow
import com.sonora.player.ui.youtube.YtSearchResults
import com.sonora.player.ui.youtube.shelves
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color as C
import com.sonora.player.youtube.YtPage
import com.sonora.player.youtube.YtSearchFilter
import kotlinx.coroutines.delay
import java.text.Normalizer
import com.composables.icons.lucide.R as L

private val diacritics = Regex("\\p{Mn}+")
private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(diacritics, "")

/** Recent searches, kept on the device (most recent first). */
private class SearchHistory(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("search", android.content.Context.MODE_PRIVATE)
    var items by mutableStateOf(prefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() })
        private set

    fun add(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        save(listOf(t) + items.filterNot { it.equals(t, ignoreCase = true) })
    }
    fun remove(q: String) = save(items - q)
    fun clear() = save(emptyList())
    private fun save(list: List<String>) {
        items = list.take(12)
        prefs.edit().putString("recent", items.joinToString("\n")).apply()
    }
}

@Composable
fun SearchScreen(vm: MainViewModel, actions: UiActions) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val history = remember { SearchHistory(context) }
    var query by rememberSaveable { mutableStateOf("") }
    // What the results show; typing only shows suggestions until you submit or pick one.
    var submitted by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    fun submit(q: String) {
        query = q
        submitted = q.trim()
        history.add(q)
        focusManager.clearFocus()
    }

    Column(Modifier.fillMaxSize()) {
        AmbientHeader(vm, actions, "Search") {
            SearchField(
                query, onChange = { query = it; if (it.isBlank()) submitted = "" },
                onSubmit = { submit(query) }, onFocus = { focused = it },
            )
        }
        val typing = query.isNotBlank() && query.trim() != submitted
        // Back steps out of a search (results → start screen) instead of leaving the Search tab.
        androidx.activity.compose.BackHandler(enabled = query.isNotBlank()) {
            query = ""
            submitted = ""
            focusManager.clearFocus()
        }
        val mode = when {
            query.isBlank() -> 0
            typing -> 1
            else -> 2
        }
        androidx.compose.animation.AnimatedContent(
            mode, label = "search",
            transitionSpec = {
                val forward = targetState > initialState
                // Quick: in ~170ms, out in 90ms.
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150, delayMillis = 20)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(170, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.2f, 1f))) { if (forward) it / 30 else -it / 30 }) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90))
            },
        ) { m ->
            when (m) {
                0 -> SearchHome(vm, actions, history, ::submit)
                1 -> Suggestions(vm, actions, query, ::submit) { query = it }
                else -> SearchResults(vm, actions, submitted)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onSubmit: () -> Unit, onFocus: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
            .height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { runCatching { focus.requestFocus() } }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LIcon(L.drawable.lucide_ic_search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Songs, artists, albums, lyrics", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .onFocusChanged { onFocus(it.isFocused) },
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    .clickable { onChange("") },
                contentAlignment = Alignment.Center,
            ) { LIcon(L.drawable.lucide_ic_x, "Clear", tint = MaterialTheme.colorScheme.surface, size = 13.dp) }
        }
    }
}

// ------------------------------------------------------------------ before you type

/** A mood or genre; [personal] moods are tuned to your top language. */
private data class Mood(val name: String, val query: String, val color: Long, val personal: Boolean = false)

private val moods = listOf(
    Mood("Chill", "chill", 0xFF3E7CB1, true), Mood("Party", "party hits", 0xFFD9480F, true),
    Mood("Romance", "romantic", 0xFFC2255C, true), Mood("Workout", "workout", 0xFFB02A2A, true),
    Mood("Focus", "focus instrumental", 0xFF2B8A8A), Mood("Lo-fi", "lofi", 0xFF5F5AA2, true),
    Mood("Bollywood", "bollywood hits", 0xFFB7791F), Mood("Punjabi", "punjabi hits", 0xFF2F7D46),
    Mood("Indie", "indian indie", 0xFF7A5C3E), Mood("Devotional", "devotional", 0xFFA0522D),
    Mood("Hip-hop", "hip hop hits", 0xFF4A4A4A), Mood("Latin", "latin hits", 0xFFB5338A),
)

@Composable
private fun SearchHome(vm: MainViewModel, actions: UiActions, history: SearchHistory, onQuery: (String) -> Unit) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val moodCards by vm.moodCards.collectAsStateWithLifecycle()
    val profile = feed.profile
    val artists = profile.topArtists.take(10)
    val quick = remember(profile) {
        buildList {
            profile.topArtists.firstOrNull()?.let { add("${it.name} radio") }
            val lang = profile.languages.firstOrNull()?.name
            profile.vibes.take(2).forEach { v -> add(listOfNotNull(v.name, lang).joinToString(" ") + " songs") }
            if (lang != null) add("New $lang songs")
            profile.topArtists.getOrNull(1)?.let { add("Songs like ${it.name}") }
        }.distinct().take(5)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        if (history.items.isNotEmpty()) {
            item {
                SectionHeader("Recent") { com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = history::clear) { Text("Clear") } }
            }
            items(history.items.take(6), key = { "h-$it" }) { q ->
                Row(
                    Modifier.fillMaxWidth().height(46.dp).clickable { onQuery(q) }.padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LIcon(L.drawable.lucide_ic_history, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { history.remove(q) }, contentAlignment = Alignment.Center) {
                        LIcon(L.drawable.lucide_ic_x, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp)
                    }
                }
            }
        }

        if (artists.isNotEmpty()) {
            item { SectionHeader("Your artists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists, key = { "a-${it.name}" }) { a ->
                        val art = profile.artistSeeds[a.name]?.artUri?.let(android.net.Uri::parse)
                        Column(Modifier.width(76.dp).clickable { onQuery(a.name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            com.sonora.player.ui.components.Artwork(art, Modifier.size(76.dp), CircleShape, L.drawable.lucide_ic_mic_vocal)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (quick.isNotEmpty()) {
            item { SectionHeader("Try") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quick, key = { "q-$it" }) { q ->
                        Text(
                            q, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { onQuery(q) }.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }

        item { SectionHeader("Browse") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowseCard("Charts", "Top songs and artists", L.drawable.lucide_ic_trending_up, Modifier.weight(1f)) {
                    actions.yt(com.sonora.player.youtube.YtBrowse.CHARTS)
                }
                BrowseCard("Explore", "New releases and moods", L.drawable.lucide_ic_compass, Modifier.weight(1f)) {
                    actions.yt(com.sonora.player.youtube.YtBrowse.EXPLORE)
                }
            }
        }

        item {
            SectionHeader("Moods & genres")
            LaunchedEffect(Unit) { vm.loadMoods(moods.map { Triple(it.name, it.query, it.personal) }) }
        }
        items(moods.chunked(2)) { row ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    val card = moodCards[m.name]
                    MoodTile(m, card, Modifier.weight(1f)) {
                        // Open the mood's best playlist; otherwise search for it.
                        card?.playlistId?.let { actions.yt(it) } ?: onQuery(m.query)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BrowseCard(title: String, subtitle: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        LIcon(icon, null, tint = MaterialTheme.colorScheme.primary, size = 22.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * A mood / genre card filled live from YouTube Music: real covers from that mood stacked in the corner, the mood
 * name, and the artists you'll hear. Shows soft placeholders while it loads.
 */
@Composable
private fun MoodTile(m: Mood, card: com.sonora.player.ui.MoodCard?, modifier: Modifier, onClick: () -> Unit) {
    val base = C(m.color)
    Box(
        modifier.height(124.dp).clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(base, androidx.compose.ui.graphics.lerp(base, C.Black, 0.35f))))
            .clickable(onClick = onClick),
    ) {
        // Covers: up to three, overlapping, top-right.
        Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(width = 96.dp, height = 62.dp)) {
            val covers = card?.covers.orEmpty()
            if (covers.isEmpty()) {
                repeat(3) { i ->
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(end = (i * 16).dp, top = (i * 3).dp).size(52.dp)
                            .clip(RoundedCornerShape(6.dp)).background(C.White.copy(alpha = 0.10f + i * 0.04f)),
                    )
                }
            } else {
                covers.take(3).asReversed().forEachIndexed { ri, url ->
                    val i = covers.take(3).size - 1 - ri
                    com.sonora.player.ui.components.Artwork(
                        android.net.Uri.parse(url),
                        // A hairline edge instead of a drop shadow: shadows on every cover made this page heavy
                        // to scroll.
                        Modifier.align(Alignment.TopEnd).padding(end = (i * 18).dp, top = (i * 3).dp)
                            .size((58 - i * 5).dp)
                            .border(0.8.dp, C.Black.copy(alpha = 0.28f), RoundedCornerShape(6.dp)),
                        RoundedCornerShape(6.dp),
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 10.dp, bottom = 10.dp)) {
            Text(m.name, color = C.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                card?.artists?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: " ",
                color = C.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------ while typing

@Composable
private fun Suggestions(vm: MainViewModel, actions: UiActions, query: String, onSubmit: (String) -> Unit, onFill: (String) -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var online by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(query) {
        delay(150)
        online = vm.yt.suggestions(query.trim()).take(8)
    }
    val terms = remember(query) { norm(query).split(' ').filter { it.isNotBlank() } }
    val localArtists = remember(terms, artists) { artists.filter { a -> val hay = norm(a.name); terms.all { it in hay } }.take(2) }
    val localSongs = remember(terms, songs) {
        songs.filter { s -> val hay = norm("${s.title} ${s.artist}"); terms.all { it in hay } }
            .sortedBy { if (norm(it.title).startsWith(terms.firstOrNull().orEmpty())) 0 else 1 }.take(3)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        items(online.ifEmpty { listOf(query.trim()) }, key = { "s-$it" }) { s ->
            Row(
                Modifier.fillMaxWidth().clickable { onSubmit(s) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LIcon(L.drawable.lucide_ic_search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
                Spacer(Modifier.width(14.dp))
                Text(highlight(s, query), style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f).padding(vertical = 13.dp))
                IconButton(onClick = { onFill("$s ") }) {
                    LIcon(L.drawable.lucide_ic_arrow_up_left, "Use this", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
                }
            }
        }
        if (localArtists.isNotEmpty() || localSongs.isNotEmpty()) {
            item { SectionHeader("On this phone") }
            items(localArtists, key = { "la-${it.name}" }) { a ->
                ListRow(a.name, "Artist · ${a.songs.size} songs", a.artUri, CircleShape, L.drawable.lucide_ic_mic_vocal) { actions.artist(a.name) }
            }
            itemsIndexed(localSongs, key = { _, s -> "ls-${s.id}" }) { i, s ->
                SongRow(s, { vm.play(localSongs, i) }, actions.song, isCurrent = player.songId == s.id)
            }
        }
    }
}

/** Bolds the part of a suggestion you haven't typed yet (YouTube Music style). */
private fun highlight(s: String, typed: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val t = typed.trim()
    if (t.isNotEmpty() && s.startsWith(t, ignoreCase = true)) {
        append(s.substring(0, t.length))
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        append(s.substring(t.length))
        pop()
    } else append(s)
}

// ------------------------------------------------------------------ results

@Composable
private fun SearchResults(vm: MainViewModel, actions: UiActions, query: String) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(YtSearchFilter.ALL) }
    var result by remember { mutableStateOf<Load<YtPage>?>(null) }
    LaunchedEffect(query, filter) {
        result = Load.Loading
        result = runCatching { vm.yt.search(query, filter) }.fold({ Load.Ready(it) }, { Load.Error(it.message ?: "Search failed") })
    }
    // When results arrive, build them a section per frame (top result first) instead of the whole page in one go,
    // which used to stall a single frame for ~40 ms. The rest lands within a few frames, below the top result.
    var stage by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(result) {
        stage = 0
        val ready = result as? Load.Ready ?: return@LaunchedEffect
        val total = ready.data.shelves.size + 2
        while (stage < total) {
            stage++
            androidx.compose.runtime.withFrameNanos { }
        }
    }
    val terms = remember(query) { norm(query).split(' ').filter { it.isNotBlank() } }
    val localSongs = remember(terms, songs) {
        songs.filter { s -> val hay = norm("${s.title} ${s.artist} ${s.album}"); terms.all { it in hay } }.take(3)
    }

    Column(Modifier.fillMaxSize()) {
        PillTabs(YtSearchFilter.entries.map { it to it.label }, filter) { filter = it }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
            when (val r = result) {
                null, is Load.Loading -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                is Load.Error -> item { EmptyState(L.drawable.lucide_ic_wifi_off, "Couldn't search online", r.message) }
                is Load.Ready -> {
                    val shelves = r.data.shelves
                    val top = shelves.firstOrNull { it.title.equals("Top result", true) }
                    top?.items?.firstOrNull()?.takeIf { stage >= 1 }?.let { hero -> item(key = "top") { TopResult(hero, vm, actions) } }
                    if (stage >= 2 && filter == YtSearchFilter.ALL && localSongs.isNotEmpty()) {
                        item { SectionHeader("On this phone") }
                        item {
                            androidx.compose.foundation.lazy.LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(localSongs, key = { _, s -> "ls-${s.id}" }) { i, s ->
                                    TrackCard(com.sonora.player.data.Track.Local(s)) { vm.play(localSongs, i) }
                                }
                            }
                        }
                    }
                    val rest = shelves.map { if (it === top) it.copy(title = "", items = it.items.drop(1)) else it }.filter { it.items.isNotEmpty() }
                    if (shelves.isEmpty()) {
                        item { EmptyState(L.drawable.lucide_ic_search_x, "No results", "Nothing on YouTube Music matches “$query”.") }
                    } else {
                        // "All": swipeable song grid and card rows, like Home's shelves. A specific filter: a plain list.
                        shelves(rest.take((stage - 2).coerceAtLeast(0)), vm, actions, homeStyle = filter == YtSearchFilter.ALL)
                    }
                }
            }
        }
    }
}

/** The best match as a Home-style hero card: its artwork blurred behind, the cover, what it is, and Play. */
@Composable
private fun TopResult(item: com.sonora.player.youtube.YtItem, vm: MainViewModel, actions: UiActions) {
    val isArtist = item is com.sonora.player.youtube.YtCollection && item.kind == com.sonora.player.youtube.YtKind.ARTIST
    val cover = com.sonora.player.youtube.hiResThumb(item.thumbnail, 400)?.let(android.net.Uri::parse)
    val colors = heroColors(cover, listOf(androidx.compose.ui.graphics.Color(0xFF3B2F8F), androidx.compose.ui.graphics.Color(0xFFB4235A)))
    val open = {
        when (item) {
            is com.sonora.player.youtube.YtSong -> vm.playYt(listOf(item))
            is com.sonora.player.youtube.YtCollection -> actions.yt(item.browseId)
            else -> Unit
        }
    }
    val white = androidx.compose.ui.graphics.Color.White
    val glass = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(196.dp).clip(RoundedCornerShape(24.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(colors)).clickable(onClick = open),
    ) {
        com.sonora.player.ui.components.BlurredArt(cover, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.15f)),
                ),
            ),
        )
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            com.sonora.player.ui.components.Artwork(
                cover,
                Modifier.size(132.dp).graphicsLayer {
                    shadowElevation = 14.dp.toPx(); shape = if (isArtist) CircleShape else RoundedCornerShape(14.dp); clip = true
                },
                if (isArtist) CircleShape else RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("TOP RESULT", style = MaterialTheme.typography.labelSmall, color = white.copy(alpha = 0.75f))
                Text(
                    item.title, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = white, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    when (item) {
                        is com.sonora.player.youtube.YtSong -> "Song · ${item.artistNames}"
                        is com.sonora.player.youtube.YtCollection -> item.subtitle.ifBlank { item.kind.name.lowercase().replaceFirstChar { it.uppercase() } }
                        else -> item.subtitle
                    },
                    style = MaterialTheme.typography.bodySmall, color = white.copy(alpha = 0.8f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.height(38.dp).clip(RoundedCornerShape(50)).background(white).clickable(onClick = open).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LIcon(if (isArtist) L.drawable.lucide_ic_arrow_right else L.drawable.lucide_ic_play, null, tint = androidx.compose.ui.graphics.Color.Black, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isArtist) "Open" else "Play", color = androidx.compose.ui.graphics.Color.Black, style = MaterialTheme.typography.labelLarge)
                    }
                    if (item is com.sonora.player.youtube.YtSong) {
                        Row(
                            Modifier.height(38.dp).clip(RoundedCornerShape(50)).background(glass).clickable { vm.startRadio(item) }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LIcon(L.drawable.lucide_ic_radio, null, tint = white, size = 16.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Radio", color = white, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YouTubeSearch(vm: MainViewModel, actions: UiActions, query: String, submitted: Int) {
    var filter by rememberSaveable { mutableStateOf(YtSearchFilter.ALL) }
    var result by remember { mutableStateOf<Load<YtPage>?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(query, filter, submitted) {
        val q = query.trim()
        if (q.length < 2) { result = null; suggestions = emptyList(); return@LaunchedEffect }
        delay(if (submitted > 0) 0 else 450)
        result = Load.Loading
        result = runCatching { vm.yt.search(q, filter) }.fold({ Load.Ready(it) }, { Load.Error(it.message ?: "Search failed") })
    }

    PillTabs(YtSearchFilter.entries.map { it to it.label }, filter) { filter = it }
    when (val r = result) {
        null -> Text(
            "Search all of YouTube Music: songs, albums, artists, videos and playlists. Tap a song to start a radio of similar tracks.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        is Load.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Error -> EmptyState(L.drawable.lucide_ic_wifi_off, "Search failed", r.message)
        is Load.Ready -> if (r.data.shelves.isEmpty()) {
            EmptyState(L.drawable.lucide_ic_search_x, "No results", "Nothing on YouTube Music matches “$query”.")
        } else YtSearchResults(vm, actions, r.data)
    }
}

@Composable
private fun LibrarySearch(vm: MainViewModel, actions: UiActions, query: String) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()

    // Every word must match somewhere in title / artist / album (accent-insensitive).
    val terms = remember(query) { norm(query).split(' ').filter { it.isNotBlank() } }
    val songHits = remember(terms, songs) {
        if (terms.isEmpty()) emptyList() else songs.filter { s ->
            val hay = norm("${s.title} ${s.artist} ${s.album}")
            terms.all { it in hay }
        }.sortedBy { if (norm(it.title).startsWith(terms.first())) 0 else 1 }.take(60)
    }
    val albumHits = remember(terms, albums) {
        if (terms.isEmpty()) emptyList() else albums.filter { a -> val hay = norm("${a.title} ${a.artist}"); terms.all { it in hay } }.take(10)
    }
    val artistHits = remember(terms, artists) {
        if (terms.isEmpty()) emptyList() else artists.filter { a -> val hay = norm(a.name); terms.all { it in hay } }.take(10)
    }

    if (terms.isNotEmpty() && songHits.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty()) {
        EmptyState(L.drawable.lucide_ic_search_x, "Not in your library", "Nothing matches “$query” on this phone.") {
            com.sonora.player.ui.components.AppButton(small = true, solid = false, onClick = { vm.searchYouTube.value = true }) { Text("Search YouTube Music instead") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = com.sonora.player.ui.components.bottomBarSpace())) {
        if (artistHits.isNotEmpty()) {
            item { SectionHeader("Artists") }
            items(artistHits, key = { "ar-${it.name}" }) { a ->
                ListRow(a.name, "${a.songs.size} songs", a.artUri, CircleShape, L.drawable.lucide_ic_mic_vocal) { actions.artist(a.name) }
            }
        }
        if (albumHits.isNotEmpty()) {
            item { SectionHeader("Albums") }
            items(albumHits, key = { "al-${it.id}" }) { a ->
                ListRow(a.title, a.artist, a.artUri, fallback = L.drawable.lucide_ic_disc_3) { actions.album(a.id) }
            }
        }
        if (songHits.isNotEmpty()) {
            item { SectionHeader("Songs") }
            itemsIndexed(songHits, key = { _, s -> "s-${s.id}" }) { i, song ->
                SongRow(song, { vm.play(songHits, i) }, actions.song, isCurrent = player.songId == song.id)
            }
        }
        if (terms.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().clickable { vm.searchYouTube.value = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LIcon(L.drawable.lucide_ic_youtube, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Instant, offline search of your music. Switch to YouTube Music above to search everything else.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
