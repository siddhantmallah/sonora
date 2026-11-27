package com.sonora.player.ui.screens

import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonora.player.R
import com.sonora.player.data.Sky
import com.sonora.player.data.Weather
import com.sonora.player.data.aqiLabel
import com.sonora.player.ui.components.Artwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.composables.icons.lucide.R as L

// ------------------------------------------------------------------ astronomy (good enough for a banner)

private const val DAY = 86_400_000L
private const val SYNODIC = 29.530588853
/** A known new moon: 2000-01-06 18:14 UTC. */
private const val NEW_MOON_EPOCH = 947_182_440_000L

/** The moon right now: age in days, illuminated fraction, and how far it lags the sun (rise/set offset). */
private class MoonState(now: Long) {
    val age = (((now - NEW_MOON_EPOCH) / DAY.toDouble()) % SYNODIC + SYNODIC) % SYNODIC
    val elongation = 2 * PI * age / SYNODIC
    val illumination = (1 - cos(elongation)) / 2
    val phase = when {
        age < 1.0 || age > 28.5 -> "New moon"
        age < 6.4 -> "Waxing crescent"
        age < 8.4 -> "First quarter"
        age < 13.8 -> "Waxing gibbous"
        age < 15.8 -> "Full moon"
        age < 21.1 -> "Waning gibbous"
        age < 23.1 -> "Last quarter"
        else -> "Waning crescent"
    }
    /** The moon rises and sets about this much later than the sun. */
    val lagMs = (age / SYNODIC * DAY).toLong()
}

internal fun todayAt(h: Int, m: Int) = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
}.timeInMillis

/** Progress 0..1 across today's (or the adjacent day's) [rise, set] window, or null when below the horizon. */
private fun arcProgress(now: Long, rise: Long, set: Long): Float? {
    for (k in -1..1) {
        val r = rise + k * DAY
        val s = set + k * DAY
        if (now in r..s) return ((now - r).toFloat() / (s - r)).coerceIn(0f, 1f)
    }
    return null
}

/** Sun altitude as a fraction of 90° (negative below the horizon), with a peak of ~55°. */
internal fun sunAltitude(now: Long, rise: Long, set: Long): Pair<Float, Float> {
    arcProgress(now, rise, set)?.let { p -> return p to (sin(PI * p).toFloat() * 0.62f) }
    // Below the horizon: how long since sunset / until sunrise, ~ -18° (end of twilight) after 80 min.
    val sinceSet = listOf(now - set, now - (set - DAY)).filter { it >= 0 }.minOrNull() ?: 0L
    val toRise = listOf(rise - now, rise + DAY - now).filter { it >= 0 }.minOrNull() ?: 0L
    val nearest = minOf(sinceSet, toRise)
    val p = if (sinceSet < toRise) 1f + nearest / DAY.toFloat() else -nearest / DAY.toFloat()
    return p to -(nearest / 60_000f / 80f * 0.2f).coerceAtMost(1f)
}

private fun cloudiness(sky: Sky?) = when (sky) {
    Sky.CLEAR, null -> 0.04f
    Sky.PARTLY -> 0.38f
    Sky.CLOUDY -> 0.75f
    Sky.FOG -> 0.85f
    Sky.DRIZZLE, Sky.RAIN -> 0.82f
    Sky.SNOW -> 0.8f
    Sky.STORM -> 0.95f
}

// ------------------------------------------------------------------ the sky shader

/**
 * A photographic-looking sky: zenith→horizon gradient driven by the real sun altitude (blue day, warm low sun,
 * twilight, night), a sun disc with glare, a star field at night, soft drifting clouds, and the Moon rendered as a
 * sphere from NASA's LRO colour map, lit for its actual phase (with faint earthshine on the dark side).
 */
private const val SKY_SHADER = """
uniform float2 res;
uniform float2 sun;
uniform float sunAlt;
uniform float2 moon;
uniform float moonR;
uniform float3 moonL;
uniform float t;
uniform float cloud;
uniform float boost;
uniform float2 texSize;
uniform shader moonTex;

float hash(float2 p) { p = fract(p * float2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p);
    float a = hash(i); float b = hash(i + float2(1, 0)); float c = hash(i + float2(0, 1)); float d = hash(i + float2(1, 1));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0; float a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * noise(p); p = p * 2.03 + float2(17.1, 9.2); a *= 0.5; }
    return v;
}

half4 main(float2 fc) {
    float2 uv = fc / res;
    float h = 1.0 - uv.y;
    float day = smoothstep(-0.10, 0.12, sunAlt);
    float low = (1.0 - smoothstep(0.0, 0.30, abs(sunAlt - 0.02))) * smoothstep(-0.22, -0.02, sunAlt);

    float3 zen = mix(float3(0.006, 0.010, 0.032), float3(0.09, 0.33, 0.80), day);
    float3 hor = mix(float3(0.025, 0.040, 0.090), float3(0.60, 0.79, 0.97), day);
    zen = mix(zen, float3(0.14, 0.17, 0.38), low * 0.6);
    hor = mix(hor, float3(1.0, 0.50, 0.20), low * 0.85);
    float3 col = mix(hor, zen, pow(clamp(h, 0.0, 1.0), 0.55));

    // Sun glare (persists briefly after sunset as a horizon glow).
    float d = distance(fc, sun) / res.y;
    float3 sunCol = mix(float3(1.0, 0.52, 0.22), float3(1.0, 0.97, 0.90), smoothstep(0.0, 0.35, sunAlt));
    float vis = smoothstep(-0.12, 0.0, sunAlt);
    col += sunCol * exp(-d * mix(2.2, 4.6, day)) * 0.46 * vis * boost;
    col += sunCol * exp(-d * 10.0) * 0.85 * vis * boost;
    // Light rays, turning very slowly.
    float2 dv = fc - sun;
    float ang = atan(dv.y, dv.x);
    float rays = pow(abs(sin(ang * 9.0 + t * 0.05)), 10.0) + 0.6 * pow(abs(sin(ang * 5.0 - t * 0.03 + 1.3)), 14.0);
    col += sunCol * rays * exp(-d * 4.2) * 0.22 * vis * boost * (1.0 - cloud);
    // Sun disc: a big blown-out white core, like in a photo.
    float disc = (1.0 - smoothstep(0.060, 0.076, d)) * smoothstep(-0.03, 0.01, sunAlt);
    col = mix(col, float3(1.0, 0.995, 0.97), disc);

    // Clouds.
    float2 cp = float2(uv.x * res.x / res.y * 2.2 + t * 0.012, uv.y * 3.2);
    float c = fbm(cp) * 0.75 + fbm(cp * 2.7 - t * 0.02) * 0.25;
    float cover = smoothstep(1.02 - cloud * 0.75, 1.25 - cloud * 0.55, c) * smoothstep(0.0, 0.25, h);
    float3 cloudCol = mix(float3(0.07, 0.08, 0.11), float3(0.94, 0.95, 0.97), day);
    cloudCol = mix(cloudCol, float3(1.0, 0.70, 0.50), low * 0.6);
    cloudCol *= 1.0 - cloud * 0.35;

    // Stars.
    float night = 1.0 - smoothstep(-0.20, -0.03, sunAlt);
    if (night > 0.0) {
        float scale = res.y / 140.0;
        float2 g = fc / (3.2 * scale);
        float2 cell = floor(g);
        float r = hash(cell);
        if (r > 0.965) {
            float2 sp = cell + float2(hash(cell + 7.1), hash(cell + 3.3));
            float dd = length(g - sp) * 3.2;
            float b = pow(hash(cell + 11.0), 4.0) * 0.9 + 0.1;
            float tw = 0.75 + 0.25 * sin(t * (1.5 + r * 3.0) + r * 40.0);
            col += float3(0.9, 0.93, 1.0) * b * tw * exp(-dd * dd * 1.6) * night * smoothstep(0.08, 0.35, h) * (1.0 - cover);
        }
    }

    // Moon.
    if (moonR > 0.0) {
        float2 p = (fc - moon) / moonR;
        float rr = dot(p, p);
        float lightAmt = clamp(moonL.z * 0.5 + 0.5, 0.0, 1.0);
        col += float3(0.55, 0.62, 0.78) * exp(-sqrt(rr) * 1.8) * 0.10 * lightAmt * (1.0 - day * 0.8);
        if (rr < 1.0) {
            float z = sqrt(1.0 - rr);
            float3 n = float3(p.x, -p.y, z);
            float lon = atan(n.x, n.z);
            float lat = asin(clamp(n.y, -1.0, 1.0));
            float2 tuv = float2(0.5 + lon / 6.2831853, 0.5 - lat / 3.1415926) * texSize;
            float3 alb = moonTex.eval(tuv).rgb;
            float lum = dot(alb, float3(0.299, 0.587, 0.114));
            alb = pow(mix(float3(lum), alb, 0.3), float3(1.9));
            float ndl = dot(n, moonL);
            float lit = smoothstep(-0.04, 0.10, ndl);
            float3 shaded = alb * (1.30 * lit * (0.78 + 0.22 * clamp(ndl, 0.0, 1.0))) + alb * 0.035;
            float edge = 1.0 - smoothstep(0.965, 1.0, sqrt(rr));
            float3 nightMoon = mix(col, shaded, edge);
            float3 dayMoon = col + alb * lit * 0.55 * edge;
            col = mix(nightMoon, dayMoon, day);
        }
    }

    col = mix(col, cloudCol, cover * 0.88);
    // Slightly darker at the bottom so text stays readable; gentle filmic curve + grain.
    col *= 0.84 + 0.16 * smoothstep(0.0, 0.55, h);
    col = clamp(col, 0.0, 1.0);
    col += (hash(fc + fract(t)) - 0.5) * 0.012;
    return half4(half3(col), 1.0);
}
"""

/** Where the sun sits inside a banner of size [w]×[h] (same mapping as the shader). */
private fun sunPoint(w: Float, h: Float, p: Float, alt: Float) =
    androidx.compose.ui.geometry.Offset(w * (0.08f + 0.84f * p.coerceIn(-0.2f, 1.2f)), h * 0.88f - alt * h * 1.15f)

/**
 * One steady clock for the whole sky (clouds, stars and the sun's breathing), ticking at ~60 fps. A single even
 * beat looks smoother than separate timers running at different rates. Stands still while the player covers the
 * screen, and carries on from there after.
 */
@Composable
private fun rememberSkyClock(): androidx.compose.runtime.State<Float> {
    val covered = com.sonora.player.ui.components.LocalPlayerCovering.current
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(covered) {
        androidx.compose.runtime.snapshotFlow { covered.value }.collectLatest { hidden ->
            if (hidden) return@collectLatest
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (last == 0L) last = now
                    if (now - last >= 15_000_000L) {
                        time.floatValue += ((now - last) / 1e9f).coerceAtMost(0.1f)
                        last = now
                    }
                }
            }
        }
    }
    return time
}

/** Slow "breathing" of the sun's brightness (0.88..1.18 over ~10 s), shared by the sky and the light it casts. */
private fun sunBoost(t: Float): Float = 0.88f + 0.30f * (0.5f - 0.5f * cos(2.0 * PI * t / 10.4).toFloat())

@RequiresApi(33)
@Composable
private fun RealSky(now: Long, rise: Long, set: Long, sky: Sky?, time: androidx.compose.runtime.State<Float>, modifier: Modifier) {
    val context = LocalContext.current
    // Compiling the shader and decoding the moon photo take tens of milliseconds, so they're done once per app run
    // and reused; coming back to Home no longer stalls a frame on them.
    val shader = remember { SkyAssets.shader(context) }
    val moonTex = SkyAssets.moonSize
    val moonState = remember(now / 600_000) { MoonState(now) }
    val (sunP, sunAlt) = remember(now / 60_000, rise, set) { sunAltitude(now, rise, set) }
    val moonP = remember(now / 60_000, rise, set) { arcProgress(now, rise + moonState.lagMs, set + moonState.lagMs) }
    val cloud = cloudiness(sky)

    // The sky is soft light and gradients, so it's rendered at half resolution into an offscreen layer and scaled
    // up: a quarter of the pixels for the shader to work out each frame, and it looks the same.
    // The small layer gets a few spare pixels on every side (clipped off), so the scaled-up edges never show a
    // seam where the smoothing blends with the empty space outside the layer.
    Box(modifier.clipToBounds()) {
    androidx.compose.foundation.layout.Spacer(
        Modifier.layout { m, c ->
            val k = 1f / SKY_SCALE
            val p = m.measure(
                androidx.compose.ui.unit.Constraints.fixed(
                    (c.maxWidth * SKY_SCALE).toInt() + 2 * SKY_BLEED, (c.maxHeight * SKY_SCALE).toInt() + 2 * SKY_BLEED,
                ),
            )
            layout(c.maxWidth, c.maxHeight) { p.place((-SKY_BLEED * k).toInt(), (-SKY_BLEED * k).toInt()) }
        }.graphicsLayer {
            scaleX = 1f / SKY_SCALE; scaleY = 1f / SKY_SCALE
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        }.drawWithCache {
            // Sky coordinates start SKY_BLEED pixels into the layer.
            val w = size.width - 2 * SKY_BLEED
            val h = size.height - 2 * SKY_BLEED
            val horizon = h * 0.88f
            fun x(p: Float) = w * (0.08f + 0.84f * p)
            // Altitude fraction (of 90°) -> height above the horizon.
            fun y(alt: Float) = horizon - alt * h * 1.15f
            val brush = ShaderBrush(shader)
            onDrawBehind {
                shader.setFloatUniform("res", w, h)
                shader.setFloatUniform("sun", x(sunP.coerceIn(-0.2f, 1.2f)), y(sunAlt))
                shader.setFloatUniform("sunAlt", sunAlt)
                if (moonP != null) {
                    // Real arc, but kept in the clear band between the greeting and the weather line.
                    val mAlt = (sin(PI * moonP).toFloat() * 0.58f).coerceAtLeast(0.36f)
                    shader.setFloatUniform("moon", x(moonP).coerceIn(w * 0.30f, w * 0.90f), y(mAlt).coerceAtLeast(h * 0.26f))
                    shader.setFloatUniform("moonR", 22.dp.toPx() * SKY_SCALE)
                } else {
                    shader.setFloatUniform("moon", -999f, -999f)
                    shader.setFloatUniform("moonR", 0f)
                }
                val e = moonState.elongation
                shader.setFloatUniform("moonL", sin(e).toFloat(), 0f, -cos(e).toFloat())
                val t = time.value
                shader.setFloatUniform("t", t)
                shader.setFloatUniform("cloud", cloud)
                shader.setFloatUniform("boost", sunBoost(t))
                shader.setFloatUniform("texSize", moonTex[0], moonTex[1])
                translate(SKY_BLEED.toFloat(), SKY_BLEED.toFloat()) {
                    drawRect(brush, topLeft = androidx.compose.ui.geometry.Offset(-SKY_BLEED.toFloat(), -SKY_BLEED.toFloat()), size = this@onDrawBehind.size)
                }
            }
        },
    )
    }
}

/** The sky shader with the moon texture bound, built once and shared (only one Home sky is on screen at a time). */
@RequiresApi(33)
private object SkyAssets {
    private var cached: RuntimeShader? = null
    var moonSize = floatArrayOf(1f, 1f)
        private set

    @Synchronized
    fun shader(context: android.content.Context): RuntimeShader = cached ?: RuntimeShader(SKY_SHADER).also { sh ->
        val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.moon_lroc, BitmapFactory.Options().apply { inSampleSize = 2 })
        sh.setInputShader("moonTex", BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP))
        moonSize = floatArrayOf(bmp.width.toFloat(), bmp.height.toFloat())
        cached = sh
    }

    /** Warm up off the main thread at app start, so even the first Home doesn't wait. */
    fun preload(context: android.content.Context) { runCatching { shader(context) } }
}

/** Builds the sky ahead of time (call from a background thread). */
fun preloadSky(context: android.content.Context) { if (Build.VERSION.SDK_INT >= 33) SkyAssets.preload(context) }

/** Resolution the sky is rendered at (then scaled up to fill the banner). */
private const val SKY_SCALE = 0.5f
private const val SKY_BLEED = 3

/** Older Android: a calm photographic-style gradient for the time of day. */
@Composable
private fun SimpleSky(now: Long, rise: Long, set: Long, modifier: Modifier) {
    val (_, alt) = sunAltitude(now, rise, set)
    val day = ((alt + 0.1f) / 0.22f).coerceIn(0f, 1f)
    val top = androidx.compose.ui.graphics.lerp(Color(0xFF02040C), Color(0xFF1A4FB8), day)
    val bottom = androidx.compose.ui.graphics.lerp(Color(0xFF0A1226), Color(0xFF8DBCEB), day)
    Box(modifier.background(Brush.verticalGradient(listOf(top, bottom))))
}

// ------------------------------------------------------------------ banner

private enum class Phase(val greeting: String, val emoji: String) {
    NIGHT("Good night", "🌙"), DAWN("Good morning", "🌅"), MORNING("Good morning", "☀️"),
    AFTERNOON("Good afternoon", "😎"), DUSK("Good evening", "🌇"), EVENING("Good evening", "✨"),
}

@Composable
fun SkyBanner(
    weather: Weather?,
    firstName: String?,
    avatar: String?,
    hasLocation: Boolean,
    onAvatar: () -> Unit,
    onEnableWeather: () -> Unit,
    /** Plays music matched to the sky right now: (label, search query). */
    onSkyMix: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val realNow by produceState(System.currentTimeMillis()) {
        while (true) { delay(20_000); value = System.currentTimeMillis() }
    }
    // Long-press previews the sky later in the day (+3 h per press, back to now after a full day).
    var previewSteps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val now = realNow + previewSteps * 3 * 3_600_000L
    val rise = weather?.sunrise ?: todayAt(6, 30)
    val set = weather?.sunset ?: todayAt(18, 15)
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val (_, alt) = sunAltitude(now, rise, set)
    val phase = when {
        alt < -0.05f -> if (hour in 0..4 || hour >= 22) Phase.NIGHT else Phase.EVENING
        now < rise + 60 * 60_000L && now >= rise - 40 * 60_000L -> Phase.DAWN
        now > set - 50 * 60_000L -> Phase.DUSK
        hour < 12 -> Phase.MORNING
        else -> Phase.AFTERNOON
    }
    val moon = remember(now / 600_000) { MoonState(now) }
    val isNight = alt < -0.03f
    val skyTime = rememberSkyClock()
    val (sunP, _) = remember(now / 60_000, rise, set) { sunAltitude(now, rise, set) }
    val cloud = cloudiness(weather?.kind)
    // How strongly the sky lights up the page: full sun at midday, golden at sunset, a faint cool glow at night.
    val sunStrength = (alt / 0.25f).coerceIn(0f, 1f) * (1f - cloud * 0.8f)
    val goldness = (1f - (alt / 0.22f)).coerceIn(0f, 1f)
    val lightColor = androidx.compose.ui.graphics.lerp(Color(0xFFFFF4DA), Color(0xFFFFB066), goldness)

    // Sky Mix: what this sky sounds like.
    val part = when (phase) {
        Phase.DAWN, Phase.MORNING -> "morning"
        Phase.AFTERNOON -> "afternoon"
        Phase.DUSK -> "sunset"
        Phase.EVENING -> "evening"
        Phase.NIGHT -> "night"
    }
    val skyLabel = when (weather?.kind) {
        Sky.RAIN, Sky.DRIZZLE, Sky.STORM -> "Rainy $part"
        Sky.CLOUDY, Sky.FOG -> "Cloudy $part"
        Sky.SNOW -> "Snowy $part"
        else -> if (isNight) (if (moon.illumination > 0.9) "Full-moon night" else "Starry night") else "Clear $part"
    }
    val skyQuery = when (weather?.kind) {
        Sky.RAIN, Sky.DRIZZLE, Sky.STORM -> "rainy day songs"
        Sky.CLOUDY, Sky.FOG -> "mellow cloudy day songs"
        Sky.SNOW -> "cozy winter songs"
        else -> when (phase) {
            Phase.DAWN, Phase.MORNING -> "fresh morning songs"
            Phase.AFTERNOON -> "feel good afternoon songs"
            Phase.DUSK -> "sunset golden hour songs"
            Phase.EVENING -> "evening chill songs"
            Phase.NIGHT -> "late night songs"
        }
    }
    val topInset = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pageColor = MaterialTheme.colorScheme.background

    Box(
        modifier.fillMaxWidth().height(topInset + 296.dp)
            .drawWithContent {
                drawContent()
                if (Build.VERSION.SDK_INT < 33) return@drawWithContent
                val b = sunBoost(skyTime.value)
                if (sunStrength > 0.02f) {
                    val c = sunPoint(size.width, size.height, sunP, alt)
                    val reach = size.width * 1.55f
                    // Sunlight washing over the page under the banner: a warm glow that breathes with the sun.
                    val warm = androidx.compose.ui.graphics.lerp(Color(0xFFFFC46B), Color(0xFFFF8A3D), goldness)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(warm.copy(alpha = 0.26f * sunStrength * b), warm.copy(alpha = 0.10f * sunStrength * b), Color.Transparent),
                            center = c, radius = reach,
                        ),
                        radius = reach, center = c,
                    )
                    // Lens flare: soft ghosts and a ring along the line from the sun through the screen.
                    val mid = androidx.compose.ui.geometry.Offset(size.width * 0.35f, size.height * 2.1f)
                    val dir = mid - c
                    listOf(0.45f to 22f, 0.75f to 10f, 1.05f to 38f, 1.45f to 16f, 1.85f to 56f).forEachIndexed { i, (f, r) ->
                        val ghost = c + dir * f
                        val tint = listOf(Color(0xFFFFB347), Color(0xFF7FC8FF), Color(0xFFFF8FC8), Color(0xFF8CF0B0), Color(0xFFFFD27A))[i]
                        drawCircle(
                            Brush.radialGradient(listOf(tint.copy(alpha = 0.20f * sunStrength * b), tint.copy(alpha = 0.06f * sunStrength * b), Color.Transparent), ghost, r.dp.toPx()),
                            radius = r.dp.toPx(), center = ghost,
                        )
                    }
                    val ring = c + dir * 1.25f
                    drawCircle(
                        Color(0xFFFFC46B).copy(alpha = 0.18f * sunStrength * b), radius = 30.dp.toPx(), center = ring,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
                    )
                } else if (isNight) {
                    // Moonlight: a faint cool glow below the banner. It fades in from nothing (no hard top edge) and out again.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent, 0.35f to Color(0x1A8FB4FF), 1f to Color.Transparent,
                            startY = size.height * 0.5f, endY = size.height * 2.2f,
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.5f),
                        size = androidx.compose.ui.geometry.Size(size.width, size.height * 1.7f),
                        blendMode = androidx.compose.ui.graphics.BlendMode.Screen,
                    )
                }
            }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { previewSteps = (previewSteps + 1) % 8 }) },
    ) {
        if (Build.VERSION.SDK_INT >= 33) RealSky(now, rise, set, weather?.kind, skyTime, Modifier.fillMaxSize())
        else SimpleSky(now, rise, set, Modifier.fillMaxSize())
        // Birds that live here and the weather that's actually happening outside.
        val dayPart = when (phase) {
            Phase.DAWN -> DayPart.DAWN
            Phase.MORNING, Phase.AFTERNOON -> DayPart.DAY
            Phase.DUSK -> DayPart.DUSK
            // Just after sunset the afterglow still has birds heading home; later it is the bats.
            Phase.EVENING -> if (now < set + 75 * 60_000L) DayPart.DUSK else DayPart.NIGHT
            Phase.NIGHT -> DayPart.NIGHT
        }
        // The sky only changes ~60 times a second; asking for 60 Hz here (scrolling still asks for more) gives
        // each frame the full 16 ms instead of racing a 120 Hz deadline it doesn't need.
        SkyLife(weather, dayPart, skyTime, now, Modifier.fillMaxSize().preferredFrameRate(60f))
        // The sky melts into the page instead of ending at a card edge.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(80.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, pageColor.copy(alpha = 0.55f), pageColor))),
        )

        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp, top = topInset + 14.dp, bottom = 52.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${phase.greeting}${firstName?.let { ", $it" } ?: ""}",
                            color = Color.White, style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(10.dp))
                        PhaseGlyph(phase, Modifier.size(26.dp))
                    }
                    Text(
                        (if (previewSteps > 0) "Preview · " else "") +
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)) + " · " +
                            java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(Date(now)),
                        color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onAvatar),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar != null) Artwork(Uri.parse(avatar), Modifier.fillMaxSize(), CircleShape, L.drawable.lucide_ic_circle_user)
                    else com.sonora.player.ui.components.LIcon(L.drawable.lucide_ic_settings, "Settings", tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    if (weather != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${weather.tempC.toInt()}°", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.padding(bottom = 7.dp)) {
                                Text(weather.kind.label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                weather.place?.let { Text(it, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                            }
                        }
                    } else if (!hasLocation) {
                        Text(
                            "Show local weather",
                            color = Color.White, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                                .clickable(onClick = onEnableWeather).padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                    if (isNight) {
                        Text(
                            "${moon.phase} · ${(moon.illumination * 100).toInt()}% lit",
                            color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                weather?.aqi?.let { AqiPill(it, Modifier.padding(bottom = 6.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            // Sky Mix: one tap for music that fits this exact moment.
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f))
                    .clickable { onSkyMix(skyLabel, skyQuery) }.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    com.sonora.player.ui.components.LIcon(L.drawable.lucide_ic_play, null, tint = Color.Black, size = 13.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text("Sky Mix", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(" · $skyLabel", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * A small drawn mark for the time of day, in the sky's own light instead of an emoji: a glowing sun by day, the sun
 * resting on the horizon at dawn and dusk, a crescent moon with a star at night.
 */
@Composable
private fun PhaseGlyph(phase: Phase, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        when (phase) {
            Phase.MORNING, Phase.AFTERNOON -> {
                drawCircle(Brush.radialGradient(listOf(Color(0x66FFE7A3), Color.Transparent), c, r), r, c)
                for (i in 0 until 8) {
                    val a = i * (PI / 4).toFloat()
                    val d = androidx.compose.ui.geometry.Offset(cos(a), sin(a))
                    drawLine(Color(0xFFFFE6A6), c + d * (r * 0.58f), c + d * (r * 0.86f), r * 0.11f, androidx.compose.ui.graphics.StrokeCap.Round)
                }
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFFD970)), c, r * 0.42f), r * 0.40f, c)
            }
            Phase.DAWN, Phase.DUSK -> {
                val hy = size.height * 0.68f
                val sun = androidx.compose.ui.geometry.Offset(c.x, hy)
                drawCircle(Brush.radialGradient(listOf(Color(0x66FFB36B), Color.Transparent), sun, r), r, sun)
                for (i in 0 until 5) {
                    val a = (PI + (i + 1) * PI / 6).toFloat()
                    val d = androidx.compose.ui.geometry.Offset(cos(a), sin(a))
                    drawLine(Color(0xFFFFC98A), sun + d * (r * 0.55f), sun + d * (r * 0.82f), r * 0.10f, androidx.compose.ui.graphics.StrokeCap.Round)
                }
                clipRect(bottom = hy) {
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF1D6), Color(0xFFFF9F5A)), sun, r * 0.42f), r * 0.40f, sun)
                }
                drawLine(Color.White.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(r * 0.12f, hy), androidx.compose.ui.geometry.Offset(size.width - r * 0.12f, hy), r * 0.09f, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Phase.EVENING, Phase.NIGHT -> {
                val moon = androidx.compose.ui.graphics.Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(c.copy(x = c.x - r * 0.12f), r * 0.62f))
                }
                val bite = androidx.compose.ui.graphics.Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(c.x + r * 0.2f, c.y - r * 0.22f), r * 0.55f))
                }
                val crescent = androidx.compose.ui.graphics.Path().apply { op(moon, bite, androidx.compose.ui.graphics.PathOperation.Difference) }
                drawCircle(Brush.radialGradient(listOf(Color(0x40CFE0FF), Color.Transparent), c, r), r, c)
                drawPath(crescent, Brush.linearGradient(listOf(Color.White, Color(0xFFDCE6FF)), c.copy(x = c.x - r), c.copy(x = c.x + r)))
                // A tiny four-point star.
                val st = androidx.compose.ui.geometry.Offset(c.x + r * 0.62f, c.y + r * 0.42f)
                val k = r * 0.2f
                drawLine(Color.White, st.copy(x = st.x - k), st.copy(x = st.x + k), r * 0.07f, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Color.White, st.copy(y = st.y - k), st.copy(y = st.y + k), r * 0.07f, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun AqiPill(aqi: Int, modifier: Modifier) {
    val dot = when {
        aqi <= 50 -> Color(0xFF34C759)
        aqi <= 100 -> Color(0xFFFFCC00)
        aqi <= 150 -> Color(0xFFFF9500)
        aqi <= 200 -> Color(0xFFFF3B30)
        aqi <= 300 -> Color(0xFFAF52DE)
        else -> Color(0xFF8E2C48)
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.25f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text("AQI $aqi · ${aqiLabel(aqi)}", color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
