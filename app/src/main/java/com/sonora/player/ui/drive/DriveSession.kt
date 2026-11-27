package com.sonora.player.ui.drive

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** One look at the road: what the phone's GPS, sensors and the free map / weather data say right now. */
@Immutable
data class DriveInfo(
    val located: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** km/h from GPS, null until there's a fix. */
    val speedKmh: Float? = null,
    /** Direction of travel (GPS) or where the phone points (compass) when slow. */
    val bearing: Float = 0f,
    val altitudeM: Double? = null,
    /** The road's speed limit from OpenStreetMap, km/h. */
    val limitKmh: Int? = null,
    /** False when the map has no limit for this road and it's estimated from the road type. */
    val limitExact: Boolean = true,
    val roadName: String? = null,
    /** Riders: lean angle (degrees, + right) and g-force from the phone's sensors. */
    val leanDeg: Float? = null,
    val gForce: Float = 0f,
    val weatherHere: String? = null,
    val tempC: Int? = null,
    /** The most important warning ahead (weather, toll, traffic). */
    val alerts: List<String> = emptyList(),
    val tollAhead: String? = null,
    /** How much later than first expected the nav app now says you'll arrive. */
    val trafficDelayMin: Int? = null,
)

/**
 * Everything Driving mode shows besides the music: GPS speed and heading, the speed limit of the road you're on, lean
 * angle and g-force from the phone's sensors, the weather here and on the road ahead, toll plazas coming up and the
 * traffic delay. Runs only while Driving mode (or its Always-On view) is on screen.
 */
object DriveSession {
    private val _info = MutableStateFlow(DriveInfo())
    val info: StateFlow<DriveInfo> = _info.asStateFlow()

    private var users = 0
    private var scope: CoroutineScope? = null
    private var app: Context? = null
    private var http: OkHttpClient? = null
    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private var last: Location? = null
    private var leanZero = 0f
    private var leanRaw = 0f
    private var gPeak = 0f
    private var gPeakAt = 0L

    private var limitAt: Location? = null
    private var limitTime = 0L
    private var weatherAt: Location? = null
    private var weatherTime = 0L
    private var tollAt: Location? = null
    private var tollTime = 0L
    private var firstEta: Pair<String, Long>? = null

    fun start(context: Context, client: OkHttpClient) {
        users++
        if (users > 1) return
        app = context.applicationContext
        http = client
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        startLocation()
        startSensors()
        scope?.launch { while (isActive) { runCatching { refresh() }; delay(5_000) } }
        scope?.launch { NavJourney.state.collect { j -> trackDelay(j) } }
    }

    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        runCatching { lm?.removeUpdates(locListener) }
        runCatching { sm?.unregisterListener(sensorListener) }
        scope?.let { s -> s.coroutineContext[Job]?.cancel() }
        scope = null
    }

    /** Sets the current lean as upright (riders: tap the lean readout with the bike straight). */
    fun zeroLean() { leanZero = leanRaw }

    // ------------------------------------------------------------------ GPS

    private val locListener = LocationListener { loc -> onLocation(loc) }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val ctx = app ?: return
        if (!hasLocation(ctx)) return
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        lm = m
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && m.allProviders.contains(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
            else { add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER) }
        }
        providers.filter { m.allProviders.contains(it) }.forEach { p ->
            runCatching { m.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper()) }
            runCatching { m.getLastKnownLocation(p) }.getOrNull()?.let { onLocation(it) }
        }
    }

    private fun onLocation(loc: Location) {
        val prev = last
        if (prev != null && loc.time < prev.time) return
        val speed = when {
            loc.hasSpeed() -> loc.speed * 3.6f
            prev != null && loc.time > prev.time -> prev.distanceTo(loc) / ((loc.time - prev.time) / 1000f) * 3.6f
            else -> 0f
        }
        last = loc
        Trip.onLocation(loc)
        _info.update {
            it.copy(
                located = true, lat = loc.latitude, lon = loc.longitude,
                speedKmh = speed?.takeIf { s -> s.isFinite() }?.let { s -> if (s < 2f) 0f else s },
                bearing = if (loc.hasBearing() && (speed ?: 0f) > 8f) loc.bearing else it.bearing,
                altitudeM = if (loc.hasAltitude()) loc.altitude else it.altitudeM,
            )
        }
    }

    // ------------------------------------------------------------------ sensors

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY -> {
                    // Phone upright in a mount: leaning the bike tilts the phone left/right in its screen plane.
                    leanRaw = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                    val lean = -(leanRaw - leanZero)
                    if (abs(lean - (_info.value.leanDeg ?: 0f)) > 0.8f) _info.update { it.copy(leanDeg = lean) }
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
                    val now = System.currentTimeMillis()
                    // Hold the peak for a second so a hard brake is readable.
                    if (g > gPeak || now - gPeakAt > 1000) { gPeak = g; gPeakAt = now }
                    if (abs(gPeak - _info.value.gForce) > 0.03f) _info.update { it.copy(gForce = gPeak) }
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    // Compass heading, used for the direction you face while slow or stopped.
                    if ((_info.value.speedKmh ?: 0f) > 8f) return
                    val r = FloatArray(9); val o = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(r, e.values)
                    SensorManager.getOrientation(r, o)
                    val az = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
                    if (abs(az - _info.value.bearing) > 2f) _info.update { it.copy(bearing = az) }
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startSensors() {
        val s = app?.getSystemService(SensorManager::class.java) ?: return
        sm = s
        listOf(Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ROTATION_VECTOR).forEach { t ->
            s.getDefaultSensor(t)?.let { s.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    // ------------------------------------------------------------------ the road and the weather

    private suspend fun refresh() {
        val loc = last ?: return
        val now = System.currentTimeMillis()
        val speed = _info.value.speedKmh ?: 0f
        // Speed limit: when you've moved 150 m or every 30 s.
        if (limitAt == null || limitAt!!.distanceTo(loc) > 150 || now - limitTime > 30_000) {
            limitAt = loc; limitTime = now
            val r = speedLimit(loc)
            _info.update { it.copy(limitKmh = r?.limit, limitExact = r?.exact ?: true, roadName = r?.name) }
        }
        // Weather here and ahead: every 5 min or 5 km.
        if (weatherAt == null || weatherAt!!.distanceTo(loc) > 5_000 || now - weatherTime > 5 * 60_000) {
            weatherAt = loc; weatherTime = now
            weather(loc, speed)
        }
        // Toll plazas ahead: every 3 min or 3 km.
        if (tollAt == null || tollAt!!.distanceTo(loc) > 3_000 || now - tollTime > 3 * 60_000) {
            tollAt = loc; tollTime = now
            val toll = tollAhead(loc)
            _info.update { it.copy(tollAhead = toll) }
        }
        rebuildAlerts()
    }

    private var weatherAlerts: List<String> = emptyList()

    private fun rebuildAlerts() {
        val i = _info.value
        val list = buildList {
            addAll(weatherAlerts)
            i.tollAhead?.let { add(it) }
            i.trafficDelayMin?.takeIf { it >= 3 }?.let { add("Traffic: about $it min slower than expected") }
        }
        if (list != i.alerts) _info.update { it.copy(alerts = list) }
    }

    private data class Limit(val limit: Int, val exact: Boolean, val name: String?)

    /**
     * The speed limit of the road under you: OpenStreetMap's maxspeed when it's mapped (Overpass API, free), else an
     * estimate from the kind of road (expressway, highway, main road, street), marked as such.
     */
    private suspend fun speedLimit(loc: Location): Limit? = withContext(Dispatchers.IO) {
        runCatching {
            val q = "[out:json][timeout:8];way(around:20,${f(loc.latitude)},${f(loc.longitude)})[highway];out tags 10;"
            val els = JSONObject(post("https://overpass-api.de/api/interpreter", q)).getJSONArray("elements")
            var best: JSONObject? = null
            var bestRank = -1
            for (k in 0 until els.length()) {
                val tags = els.getJSONObject(k).optJSONObject("tags") ?: continue
                val rank = ROAD_RANK.indexOf(tags.optString("highway").removeSuffix("_link")).let { if (it < 0) -1 else ROAD_RANK.size - it }
                val r = rank + if (tags.has("maxspeed")) 100 else 0
                if (r > bestRank) { bestRank = r; best = tags }
            }
            val tags = best ?: return@runCatching null
            val name = tags.optString("name").ifBlank { tags.optString("ref") }.ifBlank { null }
            parseSpeed(tags.optString("maxspeed"))?.let { return@runCatching Limit(it, true, name) }
            val est = when (tags.optString("highway").removeSuffix("_link")) {
                "motorway" -> 100; "trunk" -> 80; "primary" -> 60; "secondary" -> 50; "tertiary" -> 40
                "residential", "living_street", "service", "unclassified" -> 30
                else -> return@runCatching null
            }
            Limit(est, false, name)
        }.getOrNull()
    }

    private val ROAD_RANK = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service")

    private fun parseSpeed(s: String): Int? {
        val n = Regex("""(\d+)""").find(s)?.value?.toIntOrNull() ?: return null
        return if (s.contains("mph")) (n * 1.609).toInt() else n
    }

    /** Toll plazas within ~20 km in the direction you're heading. */
    private suspend fun tollAhead(loc: Location): String? = withContext(Dispatchers.IO) {
        val route = Trip.state.value
        runCatching {
            if (route != null) return@runCatching Trip.nextToll(loc)
            val q = "[out:json][timeout:10];node(around:20000,${f(loc.latitude)},${f(loc.longitude)})[barrier=toll_booth];out 30;"
            val els = JSONObject(post("https://overpass-api.de/api/interpreter", q)).getJSONArray("elements")
            val heading = _info.value.bearing
            var best: Float? = null
            for (k in 0 until els.length()) {
                val e = els.getJSONObject(k)
                val p = Location("").apply { latitude = e.getDouble("lat"); longitude = e.getDouble("lon") }
                val d = loc.distanceTo(p)
                val off = abs(((loc.bearingTo(p) - heading + 540) % 360) - 180)
                if (off < 30 && (best == null || d < best)) best = d
            }
            best?.let { "Toll plaza in ${km(it)}" }
        }.getOrNull()
    }

    /** Weather here, and on the road ahead at the time you'll get there (Open-Meteo, free). */
    private suspend fun weather(loc: Location, speedKmh: Float) = withContext(Dispatchers.IO) {
        runCatching {
            // Points to check: along your route if there is one, else straight ahead at your speed.
            val ahead: List<Triple<Double, Double, Float>> = Trip.pointsAhead(loc, listOf(10f, 25f, 50f))
                ?: run {
                    val v = speedKmh.coerceAtLeast(30f)
                    listOf(15f, 30f, 60f).map { min ->
                        val km = v * min / 60f
                        val p = offset(loc.latitude, loc.longitude, _info.value.bearing.toDouble(), km * 1000.0)
                        Triple(p.first, p.second, km)
                    }
                }
            val lats = (listOf(loc.latitude) + ahead.map { it.first }).joinToString(",") { f(it) }
            val lons = (listOf(loc.longitude) + ahead.map { it.second }).joinToString(",") { f(it) }
            val body = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                    "&current=temperature_2m,weather_code,wind_gusts_10m,visibility,precipitation" +
                    "&hourly=weather_code,precipitation,wind_gusts_10m,visibility&forecast_hours=3&timezone=auto",
            )
            val arr = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
            val here = arr.getJSONObject(0).getJSONObject("current")
            val hereCode = here.optInt("weather_code")
            val alerts = mutableListOf<String>()
            hazard(hereCode, here.optDouble("precipitation", 0.0), here.optDouble("wind_gusts_10m", 0.0), here.optDouble("visibility", 99_999.0))
                ?.let { alerts += "$it here" }
            for (k in ahead.indices) {
                if (k + 1 >= arr.length()) break
                val h = arr.getJSONObject(k + 1).getJSONObject("hourly")
                val km = ahead[k].third
                val etaMin = (km / speedKmh.coerceAtLeast(30f) * 60).toInt()
                val idx = (etaMin / 60).coerceIn(0, h.getJSONArray("weather_code").length() - 1)
                val hz = hazard(
                    h.getJSONArray("weather_code").optInt(idx), h.getJSONArray("precipitation").optDouble(idx, 0.0),
                    h.getJSONArray("wind_gusts_10m").optDouble(idx, 0.0), h.getJSONArray("visibility").optDouble(idx, 99_999.0),
                ) ?: continue
                alerts += "$hz ${km.toInt()} km ahead · in ~${etaMin.coerceAtLeast(1)} min"
                break
            }
            weatherAlerts = alerts
            _info.update {
                it.copy(
                    weatherHere = com.sonora.player.data.Sky.of(hereCode).let { s -> "${s.emoji} ${s.label}" },
                    tempC = here.optDouble("temperature_2m").takeIf { t -> !t.isNaN() }?.toInt(),
                )
            }
        }
    }

    private fun hazard(code: Int, precip: Double, gusts: Double, visibility: Double): String? = when {
        code in 95..99 -> "⛈️ Thunderstorm"
        code in 56..57 || code in 66..67 -> "🧊 Freezing rain"
        code in 71..77 || code == 85 || code == 86 -> "❄️ Snow"
        code == 65 || code == 82 || precip >= 4 -> "🌧️ Heavy rain"
        code == 45 || code == 48 || visibility < 1_000 -> "🌫️ Fog, low visibility"
        gusts >= 60 -> "💨 Strong gusts ${gusts.toInt()} km/h"
        code in 61..64 || code in 80..81 || precip >= 0.5 -> "🌦️ Rain"
        else -> null
    }

    // ------------------------------------------------------------------ traffic delay from the nav app's ETA

    private fun trackDelay(j: Journey?) {
        val eta = j?.footer?.let { Regex("""(\d{1,2}):(\d{2})""").find(it) } ?: run {
            if (j == null) { firstEta = null; _info.update { it.copy(trafficDelayMin = null) } }
            return
        }
        val minutes = eta.groupValues[1].toInt() * 60 + eta.groupValues[2].toInt()
        val first = firstEta
        if (first == null || first.first != j.key) { firstEta = j.key to minutes.toLong(); return }
        var delay = (minutes - first.second).toInt()
        if (delay < -720) delay += 1440
        _info.update { it.copy(trafficDelayMin = delay.coerceAtLeast(0)) }
        rebuildAlerts()
    }

    // ------------------------------------------------------------------ helpers

    private fun f(d: Double) = "%.5f".format(Locale.US, d)

    fun km(m: Float) = if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(Locale.US, m / 1000)

    /** A point [meters] away from (lat, lon) towards [bearing]. */
    fun offset(lat: Double, lon: Double, bearing: Double, meters: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val b = Math.toRadians(bearing)
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        val d = meters / r
        val la2 = Math.asin(Math.sin(la) * cos(d) + cos(la) * Math.sin(d) * cos(b))
        val lo2 = lo + atan2(Math.sin(b) * Math.sin(d) * cos(la), cos(d) - Math.sin(la) * Math.sin(la2))
        return Math.toDegrees(la2) to Math.toDegrees(lo2)
    }

    internal fun get(url: String): String =
        (http ?: OkHttpClient()).newCall(Request.Builder().url(url).header("User-Agent", "Sonora/1.5 (Android music player)").build())
            .execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body.string() }

    internal fun post(url: String, overpass: String): String =
        (http ?: OkHttpClient()).newCall(
            Request.Builder().url(url).header("User-Agent", "Sonora/1.5 (Android music player)")
                .post(okhttp3.FormBody.Builder().add("data", overpass).build()).build(),
        ).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body.string() }

    internal fun postJson(url: String, json: String): String =
        (http ?: OkHttpClient()).newCall(
            Request.Builder().url(url).header("User-Agent", "Sonora/1.5 (Android music player)")
                .post(json.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body.string() }
}
