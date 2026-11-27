package com.sonora.player.ui.drive

import android.location.Location
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Immutable
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/** A journey Sonora is drawing on its map: the whole route, how far along you are and what's left. */
@Immutable
data class TripState(
    val dest: Place,
    val bike: Boolean,
    /** Route points as (lat, lon). */
    val lats: DoubleArray,
    val lons: DoubleArray,
    /** Distance from the start to each point, meters. */
    val cum: DoubleArray,
    val totalM: Double,
    val totalS: Double,
    val hasToll: Boolean,
    val hasHighway: Boolean,
    /** Index of the route point just behind you, and how far along the route you are. */
    val idx: Int = 0,
    val alongM: Double = 0.0,
    val offRoute: Boolean = false,
    /** Toll plazas on the route, as distance from the start (meters). */
    val tolls: List<Double> = emptyList(),
    val rerouting: Boolean = false,
) {
    val remainingM: Double get() = (totalM - alongM).coerceAtLeast(0.0)
    val remainingS: Double get() = if (totalM > 0) totalS * remainingM / totalM else 0.0
}

/**
 * "Where to?": find a place (Photon geocoder), get a car or motorbike route (Valhalla on openstreetmap.de), and follow
 * your progress along it, rerouting when you leave it. All free OpenStreetMap services, no account.
 */
object Trip {
    private val _state = MutableStateFlow<TripState?>(null)
    val state: StateFlow<TripState?> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var offCount = 0
    private var lastRoute = 0L

    suspend fun search(query: String, near: Pair<Double, Double>?): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            // Strongly prefer places near you.
            val bias = near?.let { "&lat=${"%.4f".format(Locale.US, it.first)}&lon=${"%.4f".format(Locale.US, it.second)}&location_bias_scale=0.6&zoom=11" }.orEmpty()
            val fs = JSONObject(DriveSession.get("https://photon.komoot.io/api/?q=$q&limit=12&lang=en$bias")).getJSONArray("features")
            (0 until fs.length()).mapNotNull { k ->
                val f = fs.getJSONObject(k)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.getJSONObject("properties")
                val name = p.optString("name").ifBlank { listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ") }
                if (name.isBlank()) return@mapNotNull null
                val detail = listOf(p.optString("street"), p.optString("city"), p.optString("state"), p.optString("country"))
                    .filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
                Place(name, detail, c.getDouble(1), c.getDouble(0))
            }.let { places ->
                // Drop matches on other continents when there are ones within driving distance.
                if (near == null) places.take(7)
                else {
                    val d = places.associateWith { dist(near.first, near.second, it.lat, it.lon) }
                    val close = places.filter { d.getValue(it) < 800_000 }
                    (close.ifEmpty { places }).take(7)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Plans the route from where you are; returns an error message or null. */
    suspend fun go(dest: Place, bike: Boolean, fromLat: Double, fromLon: Double): String? {
        val t = route(dest, bike, fromLat, fromLon) ?: return "Couldn't find a route there"
        _state.value = t
        lastRoute = System.currentTimeMillis()
        offCount = 0
        scope.launch { findTolls() }
        return null
    }

    fun end() { _state.value = null }

    // Recent and saved places live on the phone only.
    private fun load(context: android.content.Context, key: String): List<Place> = runCatching {
        val arr = JSONArray(context.getSharedPreferences("drive", 0).getString(key, "[]"))
        (0 until arr.length()).map { k ->
            val o = arr.getJSONObject(k)
            Place(o.getString("name"), o.optString("detail"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }.getOrDefault(emptyList())

    private fun store(context: android.content.Context, key: String, list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("detail", it.detail).put("lat", it.lat).put("lon", it.lon)) }
        context.getSharedPreferences("drive", 0).edit().putString(key, arr.toString()).apply()
    }

    fun same(a: Place, b: Place) = a.name == b.name && dist(a.lat, a.lon, b.lat, b.lon) < 50

    /** Places you've driven to, most recent first. */
    fun recents(context: android.content.Context): List<Place> = load(context, "recent_places")

    fun remember(context: android.content.Context, p: Place) =
        store(context, "recent_places", (listOf(p) + recents(context).filterNot { same(it, p) }).take(10))

    fun forget(context: android.content.Context, p: Place) =
        store(context, "recent_places", recents(context).filterNot { same(it, p) })

    /** Places you've starred for later. */
    fun saved(context: android.content.Context): List<Place> = load(context, "saved_places")

    /** Stars or un-stars a place; returns whether it's saved now. */
    fun toggleSaved(context: android.content.Context, p: Place): Boolean {
        val list = saved(context)
        return if (list.any { same(it, p) }) { store(context, "saved_places", list.filterNot { same(it, p) }); false }
        else { store(context, "saved_places", list + p); true }
    }

    private suspend fun route(dest: Place, bike: Boolean, fromLat: Double, fromLon: Double): TripState? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("locations", JSONArray()
                    .put(JSONObject().put("lat", fromLat).put("lon", fromLon))
                    .put(JSONObject().put("lat", dest.lat).put("lon", dest.lon)))
                .put("costing", if (bike) "motorcycle" else "auto")
                .put("directions_type", "none")
                .put("units", "kilometers")
            val trip = JSONObject(DriveSession.postJson("https://valhalla1.openstreetmap.de/route", body.toString())).getJSONObject("trip")
            val leg = trip.getJSONArray("legs").getJSONObject(0)
            val (la, lo) = decode6(leg.getString("shape"))
            val cum = DoubleArray(la.size)
            for (i in 1 until la.size) cum[i] = cum[i - 1] + dist(la[i - 1], lo[i - 1], la[i], lo[i])
            val sum = trip.getJSONObject("summary")
            TripState(
                dest = dest, bike = bike, lats = la, lons = lo, cum = cum,
                totalM = cum.lastOrNull() ?: 0.0, totalS = sum.optDouble("time", 0.0),
                hasToll = sum.optBoolean("has_toll", false), hasHighway = sum.optBoolean("has_highway", false),
            )
        }.onFailure { android.util.Log.w("SonoraTrip", "route failed", it) }.getOrNull()
    }

    /** Follows you along the route; reroutes after you've been off it for a few seconds. */
    fun onLocation(loc: Location) {
        val t = _state.value ?: return
        val from = max(0, t.idx - 5)
        val to = min(t.lats.size - 2, t.idx + 400)
        var best = Double.MAX_VALUE
        var bestI = t.idx
        var bestAlong = t.alongM
        for (i in from..to) {
            val (d, frac) = toSegment(loc.latitude, loc.longitude, t.lats[i], t.lons[i], t.lats[i + 1], t.lons[i + 1])
            if (d < best) { best = d; bestI = i; bestAlong = t.cum[i] + frac * (t.cum[i + 1] - t.cum[i]) }
        }
        val off = best > 60
        offCount = if (off) offCount + 1 else 0
        _state.value = t.copy(idx = bestI, alongM = max(bestAlong, if (off) t.alongM else 0.0), offRoute = off)
        if (offCount >= 4 && System.currentTimeMillis() - lastRoute > 45_000 && !t.rerouting) {
            lastRoute = System.currentTimeMillis()
            _state.value = _state.value?.copy(rerouting = true)
            scope.launch {
                val again = route(t.dest, t.bike, loc.latitude, loc.longitude)
                _state.value = again ?: _state.value?.copy(rerouting = false)
                offCount = 0
                if (again != null) findTolls()
            }
        }
    }

    /** The next toll plaza on the route, if any. */
    fun nextToll(loc: Location): String? {
        val t = _state.value ?: return null
        val next = t.tolls.firstOrNull { it > t.alongM + 30 } ?: return if (t.hasToll && t.tolls.isEmpty()) "Toll road on this route" else null
        return "Toll plaza in ${DriveSession.km((next - t.alongM).toFloat())}"
    }

    /** Points on the route [kms] ahead of you, as (lat, lon, km). */
    fun pointsAhead(loc: Location, kms: List<Float>): List<Triple<Double, Double, Float>>? {
        val t = _state.value ?: return null
        return kms.mapNotNull { km ->
            val target = t.alongM + km * 1000
            if (target > t.totalM) return@mapNotNull null
            var i = t.idx
            while (i < t.cum.size - 1 && t.cum[i + 1] < target) i++
            Triple(t.lats[i], t.lons[i], km)
        }.ifEmpty { listOf(Triple(t.dest.lat, t.dest.lon, (t.remainingM / 1000).toFloat())) }
    }

    /** Toll booths along the next stretch of the route (OpenStreetMap, via Overpass). */
    private suspend fun findTolls() = withContext(Dispatchers.IO) {
        val t = _state.value ?: return@withContext
        runCatching {
            // Look along the next 120 km at most, so the query stays small.
            val end = t.cum.indexOfFirst { it > t.alongM + 120_000 }.let { if (it < 0) t.lats.size - 1 else it }
            val la = t.lats.slice(t.idx..end); val lo = t.lons.slice(t.idx..end)
            val bbox = "${la.min() - 0.01},${lo.min() - 0.01},${la.max() + 0.01},${lo.max() + 0.01}"
            val els = JSONObject(DriveSession.post("https://overpass-api.de/api/interpreter",
                "[out:json][timeout:15];node[barrier=toll_booth]($bbox);out 200;")).getJSONArray("elements")
            val found = mutableListOf<Double>()
            for (k in 0 until els.length()) {
                val e = els.getJSONObject(k)
                val plat = e.getDouble("lat"); val plon = e.getDouble("lon")
                var bestD = Double.MAX_VALUE; var along = 0.0
                for (i in t.idx until end) {
                    val (d, frac) = toSegment(plat, plon, t.lats[i], t.lons[i], t.lats[i + 1], t.lons[i + 1])
                    if (d < bestD) { bestD = d; along = t.cum[i] + frac * (t.cum[i + 1] - t.cum[i]) }
                }
                if (bestD < 80) found += along
            }
            // Plazas are often several booths side by side: merge those within 300 m.
            val merged = found.sorted().fold(mutableListOf<Double>()) { acc, d -> if (acc.isEmpty() || d - acc.last() > 300) acc.add(d); acc }
            withContext(Dispatchers.Main) { _state.value = _state.value?.copy(tolls = merged) }
        }
    }

    // ------------------------------------------------------------------ geometry

    private fun decode6(s: String): Pair<DoubleArray, DoubleArray> {
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        var i = 0; var lat = 0L; var lon = 0L
        while (i < s.length) {
            var shift = 0; var result = 0L; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            la += lat / 1e6; lo += lon / 1e6
        }
        return la.toDoubleArray() to lo.toDoubleArray()
    }

    fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(la1, lo1, la2, lo2, r)
        return r[0].toDouble()
    }

    /** Distance (m) from a point to a segment, and where along the segment (0..1) the closest point is. */
    private fun toSegment(pla: Double, plo: Double, ala: Double, alo: Double, bla: Double, blo: Double): Pair<Double, Double> {
        val k = Math.cos(Math.toRadians(pla))
        val ax = alo * k; val ay = ala; val bx = blo * k; val by = bla; val px = plo * k; val py = pla
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cla = ala + t * (bla - ala); val clo = alo + t * (blo - alo)
        return dist(pla, plo, cla, clo) to t
    }
}
