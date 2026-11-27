package com.sonora.player.ui.drive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import kotlin.math.max
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.icons.lucide.R as L
import com.sonora.player.ui.components.LIcon
import org.maplibre.android.MapLibre
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Free OpenStreetMap vector maps (OpenFreeMap): no account, no key. */
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * A live map that follows you like a sat-nav: your position as a navigation puck, the map turned to your heading and
 * tilted ahead. Tap the target button to snap back after panning. Needs location permission (checked by the caller).
 */
@SuppressLint("MissingPermission")
@Composable
fun LiveMap(modifier: Modifier = Modifier, trafficKey: String? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { arrayOfNulls<MapLibreMap>(1) }
    val styleReady = remember { androidx.compose.runtime.mutableStateOf(false) }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                holder[0] = map
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isRotateGesturesEnabled = true
                // Start zoomed in like a sat-nav, not on the whole world.
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder().zoom(16.5).tilt(45.0).build()
                map.setStyle(Style.Builder().fromUri(DARK_STYLE)) { style ->
                    // Above every road (so the roads can't paint over it), under the labels that follow them.
                    val lastRoad = style.layers.lastOrNull { it is LineLayer }?.id
                    // Your route, under the street names.
                    style.addSource(GeoJsonSource(ROUTE_SRC))
                    val casing = LineLayer("sonora-route-casing", ROUTE_SRC).withProperties(
                        PropertyFactory.lineColor("#0B3D91"), PropertyFactory.lineWidth(11f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                    val line = LineLayer("sonora-route", ROUTE_SRC).withProperties(
                        PropertyFactory.lineColor("#4C8DFF"), PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                    style.addSource(GeoJsonSource(DEST_SRC))
                    val dest = CircleLayer("sonora-dest", DEST_SRC).withProperties(
                        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#EFFF1A"),
                        PropertyFactory.circleStrokeColor("#000000"), PropertyFactory.circleStrokeWidth(3f),
                    )
                    if (lastRoad != null) { style.addLayerAbove(casing, lastRoad); style.addLayerAbove(line, casing.id); style.addLayerAbove(dest, line.id) }
                    else { style.addLayer(casing); style.addLayer(line); style.addLayer(dest) }
                    runCatching {
                        val lc = map.locationComponent
                        lc.activateLocationComponent(
                            LocationComponentActivationOptions.builder(context, style)
                                .useDefaultLocationEngine(true)
                                .locationEngineRequest(
                                    org.maplibre.android.location.engine.LocationEngineRequest.Builder(1000)
                                        .setPriority(org.maplibre.android.location.engine.LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                                        .setFastestInterval(500)
                                        .build(),
                                )
                                .build(),
                        )
                        lc.isLocationComponentEnabled = true
                        lc.renderMode = RenderMode.GPS
                        lc.cameraMode = CameraMode.TRACKING_GPS
                        lc.zoomWhileTracking(16.5)
                        lc.tiltWhileTracking(45.0)
                        lc.lastKnownLocation?.let { loc ->
                            map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                                .target(org.maplibre.android.geometry.LatLng(loc.latitude, loc.longitude)).zoom(16.5).tilt(45.0)
                                .bearing(loc.bearing.toDouble()).build()
                        }
                    }.onFailure { android.util.Log.w("SonoraMap", "location layer failed", it) }
                    styleReady.value = true
                }
            }
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            runCatching { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
        }
    }

    // The route ahead of you (redrawn as you move along it) and the destination pin.
    val trip by Trip.state.collectAsState()
    val ready by styleReady
    var drawnIdx by remember { androidx.compose.runtime.mutableIntStateOf(-100) }
    var drawnFor by remember { androidx.compose.runtime.mutableStateOf<Any?>(null) }
    LaunchedEffect(trip, ready) {
        val style = holder[0]?.style ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val t = trip
        val route = style.getSourceAs<GeoJsonSource>(ROUTE_SRC) ?: return@LaunchedEffect
        val dest = style.getSourceAs<GeoJsonSource>(DEST_SRC) ?: return@LaunchedEffect
        if (t == null) {
            route.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
            dest.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
            drawnFor = null; drawnIdx = -100
            return@LaunchedEffect
        }
        if (drawnFor === t.lats && kotlin.math.abs(t.idx - drawnIdx) < 4) return@LaunchedEffect
        drawnFor = t.lats; drawnIdx = t.idx
        val sb = StringBuilder("""{"type":"LineString","coordinates":[""")
        for (i in t.idx until t.lats.size) {
            if (i > t.idx) sb.append(',')
            sb.append('[').append(t.lons[i]).append(',').append(t.lats[i]).append(']')
        }
        sb.append("]}")
        route.setGeoJson(sb.toString())
        dest.setGeoJson("""{"type":"Point","coordinates":[${t.dest.lon},${t.dest.lat}]}""")
    }

    // Live traffic colours from TomTom when you've added a key.
    LaunchedEffect(trafficKey, ready) {
        val style = holder[0]?.style ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        runCatching { style.removeLayer(TRAFFIC_LAYER); style.removeSource(TRAFFIC_SRC) }
        val key = trafficKey?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        runCatching {
            val tiles = TileSet("2.1.0", "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=$key&tileSize=256")
            style.addSource(RasterSource(TRAFFIC_SRC, tiles, 256))
            val layer = RasterLayer(TRAFFIC_LAYER, TRAFFIC_SRC).withProperties(PropertyFactory.rasterOpacity(0.85f))
            style.addLayerBelow(layer, "sonora-route-casing")
        }.onFailure { android.util.Log.w("SonoraMap", "traffic layer failed", it) }
    }

    // Slow or stopped (a rider at lights, a car in a jam): turn the map with the compass instead of GPS.
    val info by DriveSession.info.collectAsState()
    val slow = (info.speedKmh ?: 0f) < 8f
    LaunchedEffect(slow, ready) {
        if (!ready) return@LaunchedEffect
        runCatching {
            holder[0]?.locationComponent?.let { lc ->
                if (lc.cameraMode == CameraMode.NONE) return@let
                lc.renderMode = if (slow) RenderMode.COMPASS else RenderMode.GPS
                lc.cameraMode = if (slow) CameraMode.TRACKING_COMPASS else CameraMode.TRACKING_GPS
            }
        }
    }

    Box(modifier) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        // Back to following you after you pan around (and, with a route, a look at the whole way).
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            MapButton(L.drawable.lucide_ic_navigation, "Follow me") {
                runCatching {
                    holder[0]?.locationComponent?.apply {
                        cameraMode = if (slow) CameraMode.TRACKING_COMPASS else CameraMode.TRACKING_GPS
                        zoomWhileTracking(16.5); tiltWhileTracking(45.0)
                    }
                }
            }
            if (trip != null) MapButton(L.drawable.lucide_ic_route, "Whole route") {
                val t = trip ?: return@MapButton
                runCatching {
                    holder[0]?.let { map ->
                        map.locationComponent.cameraMode = CameraMode.NONE
                        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
                        for (i in t.idx until t.lats.size step max(1, (t.lats.size - t.idx) / 200)) b.include(org.maplibre.android.geometry.LatLng(t.lats[i], t.lons[i]))
                        b.include(org.maplibre.android.geometry.LatLng(t.dest.lat, t.dest.lon))
                        map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b.build(), 80))
                    }
                }
            }
        }
    }
}

@Composable
private fun MapButton(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LIcon(icon, label, tint = Color.White, size = 22.dp) }
}

private const val ROUTE_SRC = "sonora-route-src"
private const val DEST_SRC = "sonora-dest-src"
private const val TRAFFIC_SRC = "sonora-traffic-src"
private const val TRAFFIC_LAYER = "sonora-traffic"

/**
 * The next turn from your navigation app, big enough to read at a glance: the app's own arrow, the distance, the
 * instruction and what's left. Tap it to jump back to the navigation app.
 */
@Composable
fun JourneyCard(j: Journey, modifier: Modifier = Modifier, large: Boolean = false) {
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFF0F5132).copy(alpha = 0.94f))
            .clickable { runCatching { j.open?.send() } }
            .padding(horizontal = 16.dp, vertical = if (large) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val arrow = remember(j.arrow) { j.arrow?.asImageBitmap() }
        if (arrow != null) {
            // Nav apps draw the arrow for a light notification; make it white for the card.
            Image(arrow, null, Modifier.size(if (large) 76.dp else 56.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White))
            Spacer(Modifier.width(14.dp))
        } else {
            LIcon(L.drawable.lucide_ic_navigation, null, tint = Color.White, size = if (large) 52.dp else 38.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                j.headline, color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = if (large) 36.sp else 26.sp, lineHeight = if (large) 40.sp else 30.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            j.detail?.let {
                Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = if (large) 22.sp else 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            j.footer?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = if (large) 17.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            j.progress?.let { p ->
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(Color.White))
                }
            }
        }
    }
}

/** Shown in place of the journey until you let Sonora read your navigation app's directions. */
@Composable
fun JourneyAccessChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
            .clickable { NavJourney.requestAccess(context) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LIcon(L.drawable.lucide_ic_route, null, tint = Color.White, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text("Show your Google Maps journey here", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
