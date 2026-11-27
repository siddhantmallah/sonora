package com.sonora.player.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume

/** The kind of place you're at, and the music that suits it. */
enum class PlaceKind(val emoji: String) {
    HOME("🏠"), OFFICE("💼"), GYM("🏋️"), BEACH("🏖️"), HILLS("⛰️"), WATER("🌊"), HOTEL("🏨"), CAFE("☕"),
    PARK("🌳"), AIRPORT("✈️"), STATION("🚉"), CAMPUS("📚"), MALL("🛍️"), RESTAURANT("🍽️"), BAR("🍸"), WORSHIP("🕊️"),
}

@Immutable
data class PlaceMood(
    val kind: PlaceKind,
    /** "You're home", "At the beach"… */
    val where: String,
    /** The mix name: "Focus at work", "Beach vibes"… */
    val mix: String,
    /** What to search for (Sonora adds your languages and taste). */
    val query: String,
    /** The place's own name when it has one ("Juhu Beach", "Taj Lands End"). */
    val name: String? = null,
)

/**
 * Where you are, as a mood. Home and Office are the spots you saved in Settings; anywhere else Sonora asks
 * OpenStreetMap what's around you (beach, hotel, gym, café, park, airport, station, campus, mall…) and checks the
 * altitude for hills. Only an approximate position goes out (to Overpass and Open-Meteo); your saved places stay on
 * the phone.
 */
object PlaceSense {
    private var cached: PlaceMood? = null
    private var cachedAt = 0L
    private var cachedWhere: Location? = null

    /** Saves the place you're at now as [key] ("home" / "office"); returns false without a location. */
    suspend fun saveHere(context: Context, settings: SettingsRepository, key: String): Boolean {
        val loc = locate(context) ?: return false
        val value = "%.6f,%.6f".format(Locale.US, loc.latitude, loc.longitude)
        settings.set(if (key == "home") SettingsRepository.Keys.homePlace else SettingsRepository.Keys.officePlace, value)
        // Look again right away, not from the result cached before this place was saved.
        cached = null; cachedAt = 0L
        return true
    }

    suspend fun detect(context: Context, http: OkHttpClient, s: AppSettings, force: Boolean = false): PlaceMood? {
        if (!s.placeMixes) return null
        val loc = locate(context) ?: return cached
        val now = System.currentTimeMillis()
        val moved = cachedWhere?.distanceTo(loc) ?: Float.MAX_VALUE
        if (!force && cachedAt > 0 && now - cachedAt < 10 * 60_000 && moved < 300) return cached
        cachedWhere = loc; cachedAt = now
        cached = saved(loc, s) ?: runCatching { around(http, loc) }.getOrNull()
        android.util.Log.i("SonoraPlace", "place: ${cached?.kind ?: "nothing special"}${cached?.name?.let { " ($it)" } ?: ""}, home saved ${s.homePlace.isNotBlank()}, office saved ${s.officePlace.isNotBlank()}")
        return cached
    }

    private fun parse(v: String): Location? = v.split(",").takeIf { it.size == 2 }?.let { (a, b) ->
        val la = a.toDoubleOrNull() ?: return null; val lo = b.toDoubleOrNull() ?: return null
        Location("saved").apply { latitude = la; longitude = lo }
    }

    private fun saved(loc: Location, s: AppSettings): PlaceMood? {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        parse(s.homePlace)?.takeIf { it.distanceTo(loc) < 200 }?.let {
            val (mix, q) = when (hour) {
                in 5..10 -> "Slow morning" to "lazy morning songs"
                in 11..16 -> "Easy at home" to "chill afternoon songs"
                in 17..21 -> "Evening at home" to "cozy evening songs"
                else -> "Late night in" to "late night calm songs"
            }
            return PlaceMood(PlaceKind.HOME, "You're home", mix, q)
        }
        parse(s.officePlace)?.takeIf { it.distanceTo(loc) < 250 }?.let {
            return if (hour >= 17) PlaceMood(PlaceKind.OFFICE, "At the office", "Wind-down", "relaxing evening songs")
            else PlaceMood(PlaceKind.OFFICE, "At the office", "Deep focus", "focus music for work")
        }
        return null
    }

    /** What's within a few hundred metres, most telling first. */
    private suspend fun around(http: OkHttpClient, loc: Location): PlaceMood? = withContext(Dispatchers.IO) {
        val la = "%.5f".format(Locale.US, loc.latitude); val lo = "%.5f".format(Locale.US, loc.longitude)
        val q = """[out:json][timeout:10];(
            nwr(around:300,$la,$lo)[natural~"^(beach|coastline)$"];
            nwr(around:120,$la,$lo)[tourism~"^(hotel|resort|hostel|guest_house|motel)$"];
            nwr(around:1500,$la,$lo)[aeroway~"^(aerodrome|terminal)$"];
            nwr(around:150,$la,$lo)[railway=station];
            nwr(around:120,$la,$lo)[leisure~"^(fitness_centre|sports_centre|park|garden|stadium)$"];
            nwr(around:80,$la,$lo)[amenity~"^(cafe|restaurant|bar|pub|nightclub|university|college|library|place_of_worship)$"];
            nwr(around:120,$la,$lo)[shop=mall];
            nwr(around:250,$la,$lo)[natural=water];
        );out tags center 40;"""
        val body = http.newCall(
            Request.Builder().url("https://overpass-api.de/api/interpreter").header("User-Agent", "Sonora/1.5 (Android music player)")
                .post(FormBody.Builder().add("data", q).build()).build(),
        ).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body.string() }
        val tags = JSONObject(body).getJSONArray("elements").let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).optJSONObject("tags") } }
        fun has(key: String, vararg v: String) = tags.firstOrNull { it.optString(key) in v }
        fun name(t: JSONObject?) = t?.optString("name")?.takeIf { it.isNotBlank() }

        has("natural", "beach", "coastline")?.let { return@withContext PlaceMood(PlaceKind.BEACH, "At the beach", "Beach vibes", "beach vibes songs", name(it)) }
        // Hills: high ground, from the free elevation API.
        val elevation = runCatching {
            http.newCall(Request.Builder().url("https://api.open-meteo.com/v1/elevation?latitude=$la&longitude=$lo").build()).execute()
                .use { JSONObject(it.body.string()).getJSONArray("elevation").getDouble(0) }
        }.getOrNull()
        if (elevation != null && elevation > 900) return@withContext PlaceMood(PlaceKind.HILLS, "Up in the hills", "Mountain air", "mountain road trip acoustic songs")
        has("aeroway", "aerodrome", "terminal")?.let { return@withContext PlaceMood(PlaceKind.AIRPORT, "At the airport", "Take-off", "travel songs", name(it)) }
        has("tourism", "hotel", "resort", "hostel", "guest_house", "motel")?.let { return@withContext PlaceMood(PlaceKind.HOTEL, "At your stay", "Lounge", "chill lounge songs", name(it)) }
        has("leisure", "fitness_centre", "sports_centre")?.let { return@withContext PlaceMood(PlaceKind.GYM, "At the gym", "Beast mode", "gym workout songs", name(it)) }
        has("railway", "station")?.let { return@withContext PlaceMood(PlaceKind.STATION, "At the station", "On the move", "travel commute songs", name(it)) }
        has("amenity", "university", "college", "library")?.let { return@withContext PlaceMood(PlaceKind.CAMPUS, "Study time", "Study focus", "study music lofi", name(it)) }
        has("amenity", "cafe")?.let { return@withContext PlaceMood(PlaceKind.CAFE, "At a café", "Coffee house", "coffee shop acoustic songs", name(it)) }
        has("amenity", "bar", "pub", "nightclub")?.let { return@withContext PlaceMood(PlaceKind.BAR, "Night out", "Party starter", "party songs", name(it)) }
        has("amenity", "restaurant")?.let { return@withContext PlaceMood(PlaceKind.RESTAURANT, "Out for a meal", "Dinner table", "dinner jazz songs", name(it)) }
        has("shop", "mall")?.let { return@withContext PlaceMood(PlaceKind.MALL, "Out shopping", "Feel-good hits", "feel good pop hits", name(it)) }
        has("leisure", "park", "garden", "stadium")?.let { return@withContext PlaceMood(PlaceKind.PARK, "At the park", "Walk in the park", "walking songs", name(it)) }
        has("natural", "water")?.let { return@withContext PlaceMood(PlaceKind.WATER, "By the water", "Calm waters", "calm acoustic songs", name(it)) }
        has("amenity", "place_of_worship")?.let { return@withContext PlaceMood(PlaceKind.WORSHIP, "A quiet place", "Peaceful", "peaceful devotional songs", name(it)) }
        null
    }

    @SuppressLint("MissingPermission")
    private suspend fun locate(context: Context): Location? {
        if (!com.sonora.player.ui.drive.hasLocation(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val recent = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { lm.allProviders.contains(it) }
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 5 * 60_000) return recent
        if (Build.VERSION.SDK_INT < 30) return recent
        val provider = if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { cont.resume(it) }
                }.onFailure { cont.resume(null) }
            }
        } ?: recent
    }
}
