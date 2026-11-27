package com.sonora.player.ui.drive

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.sonora.player.ui.components.LIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val Panel = Color.Black.copy(alpha = 0.68f)

/** Your speed from GPS, and the road's limit as a sign; the speed turns red when you're over it. */
@Composable
fun SpeedBadge(info: DriveInfo, modifier: Modifier = Modifier, large: Boolean = false) {
    val speed = info.speedKmh?.toInt()
    val over = speed != null && info.limitKmh != null && speed > info.limitKmh + 4
    val size = if (large) 84.dp else 68.dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.size(size).clip(CircleShape).background(if (over) Color(0xFFE5243B) else Panel),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(speed?.toString() ?: "–", color = Color.White, fontSize = if (large) 34.sp else 28.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
            Text("km/h", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, lineHeight = 12.sp)
        }
        info.limitKmh?.let { lim ->
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(if (large) 64.dp else 52.dp).clip(CircleShape).background(Color.White)
                    .border(if (large) 7.dp else 6.dp, Color(0xFFE5243B), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$lim", color = Color.Black, fontSize = if (large) 24.sp else 20.sp, fontWeight = FontWeight.Black, lineHeight = 22.sp)
                    // No limit on the map for this road: estimated from the road type.
                    if (!info.limitExact) Text("est.", color = Color.Black.copy(alpha = 0.6f), fontSize = 9.sp, lineHeight = 9.sp)
                }
            }
        }
    }
}

/** Weather where you are now. */
@Composable
fun WeatherChip(info: DriveInfo, modifier: Modifier = Modifier) {
    val w = info.weatherHere ?: return
    Text(
        w + (info.tempC?.let { " · $it°" } ?: ""), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Panel).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Riders: lean angle and g-force from the phone's sensors (tap with the bike upright to set zero). */
@Composable
fun RiderChip(info: DriveInfo, modifier: Modifier = Modifier) {
    val lean = info.leanDeg ?: return
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Panel).clickable { DriveSession.zeroLean() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Lean ${kotlin.math.abs(lean).toInt()}°${if (lean > 2) " R" else if (lean < -2) " L" else ""}  ·  ${"%.1f".format(info.gForce)} g" +
                (info.altitudeM?.let { "  ·  ${it.toInt()} m" } ?: ""),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The most important thing ahead: weather on your way, a toll plaza, traffic. Cycles when there are several. */
@Composable
fun AlertBanner(alerts: List<String>, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    val i by produceState(0, alerts) { while (true) { delay(5_000); value = (value + 1) % alerts.size } }
    Text(
        alerts[i.coerceIn(0, alerts.lastIndex)], color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFC233)).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** Your trip in Sonora: what's left, when you'll arrive, tolls, and voice guidance from Google Maps. */
@Composable
fun TripBar(t: TripState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val arrive = remember(t.remainingS.toLong() / 60) { arrivalText(t.remainingS) }
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(Panel).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (t.rerouting) "Finding a new route…" else "${DriveSession.km(t.remainingM.toFloat())} · ${duration(t.remainingS)}",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Arrival gets its own line so it's never cut off; the place under it.
            Text("arrive $arrive", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                t.dest.name + (if (t.hasToll) " · tolls" else "") + (if (t.bike) " · bike" else ""),
                color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        SmallAction(L.drawable.lucide_ic_volume_2, "Voice") {
            // Google Maps speaks the turns; Sonora keeps the map and music. ("l" = two-wheeler mode where Maps has it.)
            val uri = Uri.parse("google.navigation:q=${t.dest.lat},${t.dest.lon}&mode=${if (t.bike) "l" else "d"}")
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        SmallAction(L.drawable.lucide_ic_x, "End") { Trip.end() }
    }
}

/** "45 min", "2 h 10 min", "1 d 55 min", "2 d 3 h". */
fun duration(seconds: Double): String {
    val m = (seconds / 60).toInt()
    val d = m / 1440; val h = (m % 1440) / 60; val min = m % 60
    return when {
        d > 0 -> listOfNotNull("$d d", h.takeIf { it > 0 }?.let { "$it h" }, min.takeIf { it > 0 && h == 0 }?.let { "$it min" }).joinToString(" ")
        h > 0 -> "$h h $min min"
        else -> "$min min"
    }
}

/** "18:45", "tomorrow 07:10", or "in 2 days, Sat 19:23" for longer trips. */
fun arrivalText(seconds: Double): String {
    val at = java.util.Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + (seconds * 1000).toLong() }
    val now = java.util.Calendar.getInstance()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(at.time)
    val days = ((at.get(java.util.Calendar.YEAR) - now.get(java.util.Calendar.YEAR)) * 366 +
        at.get(java.util.Calendar.DAY_OF_YEAR) - now.get(java.util.Calendar.DAY_OF_YEAR))
    return when {
        days <= 0 -> time
        days == 1 -> "tomorrow $time"
        else -> "in $days days, " + java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(at.time) + " " + time
    }
}

@Composable
private fun SmallAction(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LIcon(icon, label, tint = Color.White, size = 20.dp)
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}

/** "Where to?": search a place and choose car or bike; Sonora draws the route on its map. */
@Composable
fun WhereToSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var recents by remember { mutableStateOf(Trip.recents(context)) }
    var saved by remember { mutableStateOf(Trip.saved(context)) }
    val info by DriveSession.info.collectAsState()
    var query by remember { mutableStateOf("") }
    var bike by remember { mutableStateOf(Trip.state.value?.bike ?: false) }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        results = Trip.search(query.trim(), if (info.located) info.lat to info.lon else null)
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E0F12)).statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                LIcon(L.drawable.lucide_ic_chevron_left, "Back", tint = Color.White, size = 24.dp)
            }
            Text("Where to?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // Car or motorbike routing.
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.1f)).padding(3.dp)) {
                listOf(false to "Car", true to "Bike").forEach { (b, label) ->
                    Text(
                        label, color = if (bike == b) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (bike == b) Color(0xFFEFFF1A) else Color.Transparent)
                            .clickable { bike = b }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            query, { query = it; error = null }, Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text("Search a place or address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { scope.launch { results = Trip.search(query.trim(), if (info.located) info.lat to info.lon else null) } }),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color(0xFFEFFF1A),
                focusedBorderColor = Color(0xFFEFFF1A), unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                focusedPlaceholderColor = Color.White.copy(alpha = 0.5f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
            ),
        )
        error?.let { Text(it, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 8.dp)) }
        if (busy) Text("Planning your route…", color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 12.dp))
        // Before you type: your saved places, then where you've been going lately.
        val showRecents = query.trim().length < 3
        val starred = saved
        val sections = if (showRecents) listOf("SAVED" to starred, "RECENT" to recents.filterNot { r -> starred.any { Trip.same(it, r) } })
        else listOf("" to results)
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
          sections.filter { it.second.isNotEmpty() }.forEach { (title, places) ->
            if (title.isNotEmpty()) item(key = title) {
                Text(
                    title, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, start = 6.dp, bottom = 2.dp),
                )
            }
            items(places) { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !busy) {
                        if (!info.located) { error = "Waiting for your location…"; return@clickable }
                        busy = true
                        scope.launch {
                            val err = Trip.go(p, bike, info.lat, info.lon)
                            busy = false
                            if (err == null) { Trip.remember(context, p); onDismiss() } else error = err
                        }
                    }.padding(vertical = 12.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LIcon(
                        when (title) { "SAVED" -> L.drawable.lucide_ic_heart; "RECENT" -> L.drawable.lucide_ic_history; else -> L.drawable.lucide_ic_map },
                        null, tint = Color.White.copy(alpha = 0.7f), size = 22.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.detail.isNotBlank()) Text(p.detail, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (info.located) {
                        val d = remember(p, info.lat) { Trip.dist(info.lat, info.lon, p.lat, p.lon) }
                        Text(DriveSession.km(d.toFloat()), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                    // Star it to keep it for later.
                    val isSaved = starred.any { Trip.same(it, p) }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { Trip.toggleSaved(context, p); saved = Trip.saved(context) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (isSaved) "\u2605" else "\u2606", fontSize = 22.sp,
                            color = if (isSaved) Color(0xFFEFFF1A) else Color.White.copy(alpha = 0.6f),
                        )
                    }
                    if (title == "RECENT") Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable { Trip.forget(context, p); recents = Trip.recents(context) },
                        contentAlignment = Alignment.Center,
                    ) { LIcon(L.drawable.lucide_ic_x, "Remove from recent", tint = Color.White.copy(alpha = 0.5f), size = 16.dp) }
                }
            }
          }
        }
        Text(
            "Places: Photon / OpenStreetMap · Routes: Valhalla (openstreetmap.de)",
            color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * The traffic key the map uses: Sonora's own built-in key (nothing for you to set up), or one you pasted yourself,
 * or none (traffic colours off; the delay from your nav app's arrival time still shows).
 */
fun trafficKey(s: com.sonora.player.data.AppSettings): String? =
    if (!s.trafficOn) null else s.tomtomKey.ifBlank { com.sonora.player.BuildConfig.TOMTOM_KEY }.ifBlank { null }

val hasBuiltInTraffic: Boolean get() = com.sonora.player.BuildConfig.TOMTOM_KEY.isNotBlank()
