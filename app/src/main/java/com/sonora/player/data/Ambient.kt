package com.sonora.player.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

/** Current conditions for the Home sky banner. Times are epoch millis. */
@Immutable
data class Weather(
    val tempC: Double,
    val code: Int,
    val isDay: Boolean,
    val sunrise: Long,
    val sunset: Long,
    val aqi: Int?,
    val place: String?,
    /** Rounded location (2 decimals), used to pick the birds that live there. */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Wind at 10 m: speed in km/h and the direction it blows *from*, in degrees (meteorological). */
    val windKmh: Double = 0.0,
    val windFromDeg: Double = 0.0,
) {
    val kind: Sky get() = Sky.of(code)
}

/** WMO weather codes grouped into what the banner can draw. */
enum class Sky(val label: String, val emoji: String) {
    CLEAR("Clear", "☀️"), PARTLY("Partly cloudy", "⛅"), CLOUDY("Cloudy", "☁️"), FOG("Foggy", "🌫️"),
    DRIZZLE("Drizzle", "🌦️"), RAIN("Rain", "🌧️"), SNOW("Snow", "❄️"), STORM("Thunderstorm", "⛈️");

    companion object {
        fun of(code: Int) = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> STORM
            else -> PARTLY
        }
    }
}

/** US AQI bands. */
fun aqiLabel(aqi: Int) = when {
    aqi <= 50 -> "Good"
    aqi <= 100 -> "Moderate"
    aqi <= 150 -> "Unhealthy for some"
    aqi <= 200 -> "Unhealthy"
    aqi <= 300 -> "Very unhealthy"
    else -> "Hazardous"
}

/**
 * Weather, sunrise/sunset and air quality for the device's approximate location, from Open-Meteo (free, no account).
 * Only a rounded latitude/longitude is sent. Results are cached for 20 minutes.
 */
class AmbientRepository(private val context: Context, private val http: OkHttpClient) {
    private var cached: Weather? = null
    private var cachedAt = 0L

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun weather(force: Boolean = false): Weather? = withContext(Dispatchers.IO) {
        if (!force && cached != null && System.currentTimeMillis() - cachedAt < 20 * 60_000) return@withContext cached
        if (!hasPermission()) return@withContext null
        val loc = location() ?: return@withContext cached
        val lat = "%.2f".format(Locale.US, loc.latitude)
        val lon = "%.2f".format(Locale.US, loc.longitude)
        val w = runCatching {
            val f = JSONObject(get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day,wind_speed_10m,wind_direction_10m&daily=sunrise,sunset&timezone=auto&timeformat=unixtime&forecast_days=1",
            ))
            val cur = f.getJSONObject("current")
            val daily = f.getJSONObject("daily")
            val aqi = runCatching {
                JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi"))
                    .getJSONObject("current").optInt("us_aqi", -1).takeIf { it >= 0 }
            }.getOrNull()
            Weather(
                tempC = cur.optDouble("temperature_2m"),
                code = cur.optInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                sunrise = daily.getJSONArray("sunrise").getLong(0) * 1000,
                sunset = daily.getJSONArray("sunset").getLong(0) * 1000,
                aqi = aqi,
                place = placeName(loc),
                lat = lat.toDoubleOrNull(),
                lon = lon.toDoubleOrNull(),
                windKmh = cur.optDouble("wind_speed_10m", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                windFromDeg = cur.optDouble("wind_direction_10m", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            )
        }.getOrNull()
        if (w != null) { cached = w; cachedAt = System.currentTimeMillis() }
        w ?: cached
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", "Sonora/0.1 (Android)").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body.string()
        }

    @SuppressLint("MissingPermission")
    private suspend fun location(): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3_600_000L) return last
        if (Build.VERSION.SDK_INT < 30 || !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return last
        val fresh = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal, context.mainExecutor) { cont.resume(it) }
            }
        }
        return fresh ?: last
    }

    @Suppress("DEPRECATION")
    private fun placeName(loc: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() ?: return null
        a.locality ?: a.subAdminArea ?: a.adminArea
    }.getOrNull()
}
