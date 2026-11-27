package com.sonora.player.ui.screens

import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.sonora.player.data.Sky
import com.sonora.player.data.Weather
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Life in Home's sky: flocks of birds that belong to where you are, and the weather that's actually happening.
 *
 *  - Region (from the weather's rounded location, or the phone's time zone) decides which birds live there:
 *    kites, parakeets and egrets over South Asia, geese and starlings in Europe and North America, lorikeets and
 *    flying foxes in Australia, and so on.
 *  - Time of day decides who's out: egrets heading out at dawn, kites circling at midday, starling murmurations and
 *    roosting flights at dusk, bats at night. Seasons matter in temperate places (swallows in summer, geese
 *    migrating in spring and autumn).
 *  - Weather decides how many and how they fly: full flocks on clear days, only a few crows and gulls in rain,
 *    nothing in a storm or fog. Rain, drizzle, snow, fog and lightning are drawn live, slanted by the real wind,
 *    and clear nights get the odd shooting star.
 *
 * Everything is a pure function of the sky clock, so it costs nothing while the sky isn't moving.
 */

enum class DayPart { DAWN, DAY, DUSK, NIGHT }

private enum class Region { SOUTH_ASIA, EAST_ASIA, EUROPE, NORTH_AMERICA, SOUTH_AMERICA, AFRICA, MIDDLE_EAST, OCEANIA, OTHER }

private enum class Season { SUMMER, AUTUMN, WINTER, SPRING, TROPICAL }

private enum class Bird(
    val sizeDp: Float, val flapHz: Float, val crossSec: Float, val count: IntRange, val color: Long,
) {
    SWALLOW(8.5f, 6.5f, 8f, 5..9, 0xE61B2130),
    PARAKEET(9f, 7f, 7f, 5..8, 0xFF3FAE4F),
    LORIKEET(9f, 7f, 7f, 4..7, 0xFF2F9E7A),
    CROW(10f, 3f, 13f, 2..4, 0xF0101216),
    PIGEON(10f, 4.5f, 10f, 7..12, 0xE6444A55),
    EGRET(13f, 1.6f, 15f, 3..6, 0xFFF8F8F3),
    GULL(12f, 1.3f, 17f, 2..4, 0xFFEEF1F4),
    GOOSE(12f, 2.4f, 19f, 7..11, 0xF0262A30),
    KITE(17f, 0.2f, 22f, 1..3, 0xF02A2018),
    STARLING(1.7f, 0f, 20f, 60..90, 0xE0101216),
    BAT(10f, 9f, 9f, 3..6, 0xF5050609),
}

private fun regionOf(lat: Double?, lon: Double?): Region {
    if (lat == null || lon == null) {
        val tz = TimeZone.getDefault().id
        return when {
            tz.startsWith("Asia/Kolkata") || tz.startsWith("Asia/Calcutta") || tz.startsWith("Asia/Karachi") ||
                tz.startsWith("Asia/Dhaka") || tz.startsWith("Asia/Colombo") || tz.startsWith("Asia/Kathmandu") -> Region.SOUTH_ASIA
            tz.startsWith("Asia/Dubai") || tz.startsWith("Asia/Riyadh") || tz.startsWith("Asia/Qatar") || tz.startsWith("Asia/Tehran") ||
                tz.startsWith("Asia/Baghdad") || tz.startsWith("Asia/Kuwait") || tz.startsWith("Asia/Muscat") -> Region.MIDDLE_EAST
            tz.startsWith("Asia/") -> Region.EAST_ASIA
            tz.startsWith("Europe/") -> Region.EUROPE
            tz.startsWith("America/") -> if (tz.contains("Sao_Paulo") || tz.contains("Buenos_Aires") || tz.contains("Lima") ||
                tz.contains("Bogota") || tz.contains("Santiago") || tz.contains("Caracas")) Region.SOUTH_AMERICA else Region.NORTH_AMERICA
            tz.startsWith("Africa/") -> Region.AFRICA
            tz.startsWith("Australia/") || tz.startsWith("Pacific/Auckland") -> Region.OCEANIA
            else -> Region.OTHER
        }
    }
    return when {
        lat in 5.0..37.5 && lon in 60.0..98.0 -> Region.SOUTH_ASIA
        lat in 12.0..42.0 && lon in 34.0..60.0 -> Region.MIDDLE_EAST
        lat in -10.0..60.0 && lon in 98.0..150.0 -> Region.EAST_ASIA
        lat in 35.0..72.0 && lon in -25.0..45.0 -> Region.EUROPE
        lat in 14.0..75.0 && lon in -170.0..-50.0 -> Region.NORTH_AMERICA
        lat in -56.0..14.0 && lon in -92.0..-30.0 -> Region.SOUTH_AMERICA
        lat in -36.0..37.5 && lon in -20.0..52.0 -> Region.AFRICA
        lat in -50.0..-10.0 && lon in 110.0..180.0 -> Region.OCEANIA
        else -> Region.OTHER
    }
}

private fun seasonOf(lat: Double?, nowMs: Long): Season {
    if (lat != null && abs(lat) < 23.5) return Season.TROPICAL
    val month = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.MONTH) // 0..11
    val south = (lat ?: 1.0) < 0
    val m = if (south) (month + 6) % 12 else month
    return when (m) {
        in 2..4 -> Season.SPRING
        in 5..7 -> Season.SUMMER
        in 8..10 -> Season.AUTUMN
        else -> Season.WINTER
    }
}

/** Which birds are about, weighted, for this place, season and time of day. */
private fun residents(region: Region, season: Season, part: DayPart): List<Pair<Bird, Int>> {
    val warm = season == Season.SUMMER || season == Season.TROPICAL
    val migrating = season == Season.SPRING || season == Season.AUTUMN
    val list = mutableListOf<Pair<Bird, Int>>()
    fun add(b: Bird, w: Int, ok: Boolean = true) {
        // Parakeets and lorikeets have no photo yet; pigeons fly in their place.
        val bird = if (b == Bird.PARAKEET || b == Bird.LORIKEET) Bird.PIGEON else b
        if (ok && w > 0) list += bird to w
    }
    when (region) {
        Region.SOUTH_ASIA -> when (part) {
            DayPart.DAWN -> { add(Bird.EGRET, 3); add(Bird.PARAKEET, 3); add(Bird.CROW, 2) }
            DayPart.DAY -> { add(Bird.KITE, 4); add(Bird.CROW, 3); add(Bird.PARAKEET, 3); add(Bird.PIGEON, 2) }
            DayPart.DUSK -> { add(Bird.EGRET, 3); add(Bird.CROW, 3); add(Bird.PARAKEET, 2); add(Bird.BAT, 1) }
            DayPart.NIGHT -> add(Bird.BAT, 3)
        }
        Region.EAST_ASIA -> when (part) {
            DayPart.DAWN -> { add(Bird.EGRET, 2); add(Bird.SWALLOW, 2); add(Bird.CROW, 2) }
            DayPart.DAY -> { add(Bird.SWALLOW, 3, warm); add(Bird.PIGEON, 2); add(Bird.KITE, 1); add(Bird.CROW, 2) }
            DayPart.DUSK -> { add(Bird.EGRET, 2); add(Bird.CROW, 2); add(Bird.STARLING, 1, !warm); add(Bird.BAT, 1, warm) }
            DayPart.NIGHT -> add(Bird.BAT, 2, warm)
        }
        Region.EUROPE -> when (part) {
            DayPart.DAWN -> { add(Bird.CROW, 2); add(Bird.SWALLOW, 2, warm); add(Bird.GOOSE, 3, migrating) }
            DayPart.DAY -> { add(Bird.SWALLOW, 3, warm); add(Bird.CROW, 2); add(Bird.PIGEON, 2); add(Bird.GULL, 1); add(Bird.KITE, 1) }
            DayPart.DUSK -> { add(Bird.STARLING, 3, !warm); add(Bird.GOOSE, 2, migrating); add(Bird.CROW, 2); add(Bird.SWALLOW, 1, warm) }
            DayPart.NIGHT -> add(Bird.BAT, 1, warm)
        }
        Region.NORTH_AMERICA -> when (part) {
            DayPart.DAWN -> { add(Bird.GOOSE, 3); add(Bird.CROW, 2) }
            DayPart.DAY -> { add(Bird.CROW, 2); add(Bird.PIGEON, 2); add(Bird.SWALLOW, 2, warm); add(Bird.GULL, 1); add(Bird.KITE, 1) }
            DayPart.DUSK -> { add(Bird.GOOSE, 3, !warm || migrating); add(Bird.STARLING, 1, !warm); add(Bird.CROW, 2); add(Bird.BAT, 1, warm) }
            DayPart.NIGHT -> add(Bird.BAT, 1, warm)
        }
        Region.SOUTH_AMERICA -> when (part) {
            DayPart.DAWN -> { add(Bird.PARAKEET, 3); add(Bird.EGRET, 2) }
            DayPart.DAY -> { add(Bird.PARAKEET, 3); add(Bird.KITE, 2); add(Bird.SWALLOW, 2) }
            DayPart.DUSK -> { add(Bird.PARAKEET, 2); add(Bird.EGRET, 2); add(Bird.BAT, 1) }
            DayPart.NIGHT -> add(Bird.BAT, 3)
        }
        Region.AFRICA -> when (part) {
            DayPart.DAWN -> { add(Bird.EGRET, 2); add(Bird.PARAKEET, 1); add(Bird.CROW, 1) }
            DayPart.DAY -> { add(Bird.KITE, 3); add(Bird.SWALLOW, 2); add(Bird.PIGEON, 1) }
            DayPart.DUSK -> { add(Bird.EGRET, 2); add(Bird.BAT, 2) }
            DayPart.NIGHT -> add(Bird.BAT, 3)
        }
        Region.MIDDLE_EAST -> when (part) {
            DayPart.DAWN -> { add(Bird.PIGEON, 3); add(Bird.SWALLOW, 1, warm) }
            DayPart.DAY -> { add(Bird.PIGEON, 3); add(Bird.KITE, 2) }
            DayPart.DUSK -> { add(Bird.PIGEON, 2); add(Bird.SWALLOW, 2, warm) }
            DayPart.NIGHT -> add(Bird.BAT, 1)
        }
        Region.OCEANIA -> when (part) {
            DayPart.DAWN -> { add(Bird.LORIKEET, 3); add(Bird.GULL, 1) }
            DayPart.DAY -> { add(Bird.GULL, 2); add(Bird.LORIKEET, 2); add(Bird.KITE, 1) }
            DayPart.DUSK -> { add(Bird.LORIKEET, 2); add(Bird.BAT, 2) }
            DayPart.NIGHT -> add(Bird.BAT, 3)
        }
        Region.OTHER -> when (part) {
            DayPart.DAWN -> { add(Bird.CROW, 2); add(Bird.GULL, 1) }
            DayPart.DAY -> { add(Bird.CROW, 2); add(Bird.PIGEON, 2); add(Bird.SWALLOW, 2, warm); add(Bird.GULL, 1) }
            DayPart.DUSK -> { add(Bird.CROW, 2); add(Bird.STARLING, 1, !warm) }
            DayPart.NIGHT -> add(Bird.BAT, 1, warm)
        }
    }
    return list
}

/** How the weather changes who flies: (chance a flock shows up in a slot, weight changes, count factor). */
private fun weatherFilter(kind: Sky?, birds: List<Pair<Bird, Int>>): Triple<Float, List<Pair<Bird, Int>>, Float> = when (kind) {
    null, Sky.CLEAR -> Triple(0.95f, birds, 1f)
    Sky.PARTLY -> Triple(0.9f, birds, 1f)
    Sky.CLOUDY -> Triple(0.75f, birds.map { (b, w) -> b to if (b == Bird.GULL || b == Bird.CROW || b == Bird.GOOSE) w + 2 else w }, 0.85f)
    Sky.DRIZZLE -> Triple(0.5f, birds.filter { it.first in setOf(Bird.CROW, Bird.GULL, Bird.EGRET, Bird.PIGEON, Bird.GOOSE) }
        .ifEmpty { listOf(Bird.CROW to 1) }, 0.6f)
    Sky.RAIN -> Triple(0.32f, listOf(Bird.CROW to 3, Bird.GULL to 1), 0.5f)
    Sky.SNOW -> Triple(0.3f, listOf(Bird.CROW to 1), 0.6f)
    Sky.FOG -> Triple(0.12f, listOf(Bird.CROW to 1), 0.5f)
    Sky.STORM -> Triple(0f, emptyList(), 0f)
}

/** Photo cut-outs for a species: wing frames to cycle, which way the bird faces in them, and whether it glides. */
private class BirdArt(val frames: List<androidx.compose.ui.graphics.ImageBitmap>, val facing: Float, val glides: Boolean)

/** Real bird photos (public domain / CC0, from Wikimedia Commons), cut out of their sky. Loaded once. */
private object BirdSprites {
    @Volatile private var cache: Map<Bird, BirdArt>? = null

    fun load(res: android.content.res.Resources): Map<Bird, BirdArt> = cache ?: synchronized(this) {
        cache ?: run {
            fun img(id: Int) = androidx.compose.ui.graphics.ImageBitmap.imageResource(res, id)
            val pigeons = listOf(img(com.sonora.player.R.drawable.bird_pigeon_f1), img(com.sonora.player.R.drawable.bird_pigeon_f2), img(com.sonora.player.R.drawable.bird_pigeon_f3), img(com.sonora.player.R.drawable.bird_pigeon_f4))
            val swallows = listOf(
                com.sonora.player.R.drawable.bird_swallow_f1, com.sonora.player.R.drawable.bird_swallow_f2, com.sonora.player.R.drawable.bird_swallow_f3, com.sonora.player.R.drawable.bird_swallow_f4,
                com.sonora.player.R.drawable.bird_swallow_f5, com.sonora.player.R.drawable.bird_swallow_f6, com.sonora.player.R.drawable.bird_swallow_f7, com.sonora.player.R.drawable.bird_swallow_f8,
            ).map(::img)
            val bats = listOf(img(com.sonora.player.R.drawable.bird_bat_f1), img(com.sonora.player.R.drawable.bird_bat_f2), img(com.sonora.player.R.drawable.bird_bat_f3))
            mapOf(
                Bird.EGRET to BirdArt(listOf(img(com.sonora.player.R.drawable.bird_egret)), 1f, true),
                Bird.GULL to BirdArt(listOf(img(com.sonora.player.R.drawable.bird_gull)), 1f, true),
                Bird.KITE to BirdArt(listOf(img(com.sonora.player.R.drawable.bird_kite)), 1f, true),
                Bird.CROW to BirdArt(listOf(img(com.sonora.player.R.drawable.bird_crow)), -1f, false),
                Bird.PIGEON to BirdArt(pigeons + img(com.sonora.player.R.drawable.bird_pigeon), 0f, false),
                Bird.GOOSE to BirdArt(pigeons, 0f, false),
                Bird.SWALLOW to BirdArt(swallows, 0f, false),
                Bird.BAT to BirdArt(bats, 0f, false),
            ).also { cache = it }
        }
    }
}

/** Draws one photo bird centred at (x, y), [width] wide, facing [dir], slightly banked by [tilt] degrees. */
private fun DrawScope.drawSprite(
    art: BirdArt, frame: Int, x: Float, y: Float, width: Float, dir: Float, tilt: Float, squash: Float,
    alpha: Float, filter: androidx.compose.ui.graphics.ColorFilter?,
) {
    val img = art.frames[((frame % art.frames.size) + art.frames.size) % art.frames.size]
    val w = width
    val h = width * img.height / img.width
    val flip = art.facing != 0f && art.facing != dir
    withTransform({
        translate(x, y)
        rotate(tilt, Offset.Zero)
        scale(if (flip) -1f else 1f, squash, Offset.Zero)
    }) {
        drawImage(
            img,
            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
            srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
            dstOffset = androidx.compose.ui.unit.IntOffset((-w / 2).toInt(), (-h / 2).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
            alpha = alpha.coerceIn(0f, 1f),
            colorFilter = filter,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
        )
    }
}

// Small deterministic hash -> [0, 1).
private fun rnd(a: Int, b: Int = 0): Float {
    var h = a * 374761393 + b * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483648f
}

private fun pick(list: List<Pair<Bird, Int>>, r: Float): Bird? {
    val total = list.sumOf { it.second }
    if (total <= 0) return null
    var x = r * total
    for ((b, w) in list) { x -= w; if (x < 0) return b }
    return list.last().first
}

/**
 * Birds + live weather over the sky. [time] is the sky clock (seconds); [night] darkens things for the moon.
 */
@Composable
fun SkyLife(weather: Weather?, part: DayPart, time: State<Float>, nowMs: Long, modifier: Modifier = Modifier) {
    val region = remember(weather?.lat, weather?.lon) { regionOf(weather?.lat, weather?.lon) }
    val season = remember(weather?.lat, nowMs / 86_400_000L) { seasonOf(weather?.lat, nowMs) }
    val kind = weather?.kind
    val (chance, flockList, countK) = remember(region, season, part, kind) { weatherFilter(kind, residents(region, season, part)) }
    // Wind: where it blows *to*, as a horizontal push (-1..1) for rain, snow and the birds' heading.
    val windPush = remember(weather?.windKmh, weather?.windFromDeg) {
        val w = weather ?: return@remember 0f
        val toward = Math.toRadians(w.windFromDeg + 180.0)
        (sin(toward) * min(1.0, w.windKmh / 40.0)).toFloat()
    }
    val path = remember { Path() }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val art = remember { BirdSprites.load(res) }
    // Daylight photos, re-lit for the time of day: warm and darker at dawn/dusk, dim and cool at night.
    val light = remember(part) {
        when (part) {
            DayPart.DAY -> null
            DayPart.DAWN, DayPart.DUSK -> androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFD2C0BC), androidx.compose.ui.graphics.BlendMode.Modulate)
            DayPart.NIGHT -> androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF545A74), androidx.compose.ui.graphics.BlendMode.Modulate)
        }
    }

    Canvas(modifier) {
        val t = time.value
        val d = density
        // ---- Birds: one flock per slot; each slot picks a species, a direction and a height.
        if (chance > 0f && flockList.isNotEmpty()) {
            val slotLen = 21f
            val slot = floor((t + 6f) / slotLen).toInt()
            val inSlot = (t + 6f) - slot * slotLen
            if (rnd(slot, 1) < chance) {
                val bird = pick(flockList, rnd(slot, 2))
                if (bird != null) drawFlock(bird, slot, inSlot, windPush, countK, part, path, d, art, light)
            }
        }
        // ---- Weather.
        when (kind) {
            Sky.RAIN -> drawRain(t, 150, 1.0f, windPush, d)
            Sky.DRIZZLE -> drawRain(t, 70, 0.55f, windPush, d)
            Sky.STORM -> { drawRain(t, 200, 1.25f, windPush, d); drawLightning(t, path, d) }
            Sky.SNOW -> drawSnow(t, 90, windPush, d)
            Sky.FOG -> drawFog(t)
            else -> if (part == DayPart.NIGHT && (kind == null || kind == Sky.CLEAR || kind == Sky.PARTLY)) drawShootingStar(t, d)
        }
    }
}

private fun DrawScope.drawFlock(bird: Bird, slot: Int, inSlot: Float, wind: Float, countK: Float, part: DayPart, path: Path, d: Float, art: Map<Bird, BirdArt>, light: androidx.compose.ui.graphics.ColorFilter?) {
    val w = size.width
    val h = size.height
    val dir = if (rnd(slot, 3) + wind * 0.35f > 0.5f) 1f else -1f
    val cross = bird.crossSec * (0.85f + rnd(slot, 4) * 0.3f)
    val p = inSlot / cross
    if (p > 1.15f) return
    val baseY = h * (0.16f + rnd(slot, 5) * 0.34f)
    val n = max(1, ((bird.count.first + (rnd(slot, 6) * (bird.count.last - bird.count.first + 1)).toInt()) * countK).toInt())
    val s = bird.sizeDp * d
    val color = Color(bird.color).let {
        // Dark silhouettes go a touch warmer against sunsets; at night everything is dimmer.
        when (part) {
            DayPart.NIGHT -> it.copy(alpha = it.alpha * 0.85f)
            else -> it
        }
    }
    // Fade in/out at the very ends so nothing pops.
    val edge = (min(p, 1.15f - p) / 0.08f).coerceIn(0f, 1f)
    val travel = w + 260f * d
    val leadX = if (dir > 0) -130f * d + p * travel else w + 130f * d - p * travel

    when (bird) {
        Bird.KITE -> {
            // Soaring in slow circles while drifting across.
            repeat(n) { i ->
                val r = (38f + i * 22f) * d
                val a = inSlot * (0.5f + i * 0.08f) + i * 2.1f
                val cx = (if (dir > 0) -60f * d + p * (w + 120f * d) else w + 60f * d - p * (w + 120f * d)) + i * 30f * d
                val x = cx + cos(a) * r
                val y = baseY + sin(a) * r * 0.32f + i * 14f * d
                val heading = if (-sin(a) * dir >= 0) 1f else -1f
                val kiteArt = art[Bird.KITE]
                if (kiteArt != null) drawSprite(kiteArt, 0, x, y, s * 2.5f * (0.9f + i * 0.06f), heading, -sin(a) * 12f, 1f, edge, light)
                else drawKite(x, y, s * (0.9f + i * 0.06f), heading, inSlot + i, color.copy(alpha = color.alpha * edge), path)
            }
        }
        Bird.STARLING -> {
            // Murmuration: a swirling, breathing cloud of tiny birds.
            val cx = leadX
            val cy = baseY + sin(inSlot * 0.5f) * 20f * d
            repeat(n) { i ->
                val a = rnd(i, 11) * 2f * PI.toFloat()
                val rr = (0.25f + rnd(i, 12) * 0.75f)
                val stretch = 1f + 0.45f * sin(inSlot * 0.9f + i * 0.05f)
                val x = cx + cos(a + inSlot * (0.6f + rnd(i, 13))) * 70f * d * rr * stretch
                val y = cy + sin(a * 1.3f + inSlot * (0.8f + rnd(i, 14))) * 26f * d * rr / stretch
                drawCircle(color.copy(alpha = color.alpha * edge), s * (0.7f + rnd(i, 15) * 0.6f), Offset(x, y))
            }
        }
        Bird.GOOSE -> {
            // V formation: a leader and two trailing lines.
            repeat(n) { i ->
                val k = (i + 1) / 2
                val side = if (i % 2 == 0) 1f else -1f
                val x = leadX - dir * k * s * 1.7f
                val y = baseY + (if (i == 0) 0f else side * k * s * 0.95f) + sin(inSlot * 0.8f + i) * 2f * d
                val flap = sin(inSlot * bird.flapHz * 2f * PI.toFloat() + i * 0.6f)
                val a = art[bird]
                if (a != null) {
                    val fr = ((inSlot * bird.flapHz * a.frames.size).toInt() + i * 2)
                    drawSprite(a, fr, x, y, s * 2.4f, dir, flap * 3f, 1f, edge, androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF3A3632), androidx.compose.ui.graphics.BlendMode.Modulate))
                } else drawFlapper(bird, x, y, s, flap, dir, color.copy(alpha = color.alpha * edge), path)
            }
        }
        Bird.BAT -> {
            repeat(n) { i ->
                val off = rnd(slot * 31 + i, 21)
                val x = leadX - dir * (i * 34f * d + off * 40f * d) + sin(inSlot * 2.3f + i * 4f) * 22f * d
                val y = baseY + (rnd(slot * 31 + i, 22) - 0.5f) * 60f * d + sin(inSlot * 7f + i * 2f) * 9f * d + sin(inSlot * 3.1f + i) * 14f * d
                val flap = sin(inSlot * bird.flapHz * 2f * PI.toFloat() + i * 1.7f)
                val a = art[Bird.BAT]
                if (a != null) {
                    val fr = (inSlot * 6f).toInt() + i
                    drawSprite(a, fr, x, y, s * 2.6f * (0.85f + off * 0.3f), dir, flap * 8f, 1f, edge, light)
                } else drawBat(x, y, s * (0.85f + off * 0.3f), flap, color.copy(alpha = color.alpha * edge), path)
            }
        }
        else -> {
            // A loose flock: everyone keeps their own place, flap rhythm and a little bob.
            repeat(n) { i ->
                val ox = (rnd(slot * 31 + i, 31) - 0.2f) * 150f * d
                val oy = (rnd(slot * 31 + i, 32) - 0.5f) * 70f * d
                val depth = 0.75f + rnd(slot * 31 + i, 33) * 0.4f
                val swoop = if (bird == Bird.SWALLOW) sin(inSlot * 2.2f + i * 1.3f) * 16f * d else 0f
                val x = leadX - dir * ox
                val y = baseY + oy + sin(inSlot * 1.4f + i * 0.9f) * 3f * d + swoop
                // Gulls and egrets glide between flaps.
                val phase = inSlot * bird.flapHz * 2f * PI.toFloat() + i * 1.1f
                val glide = bird == Bird.GULL || bird == Bird.EGRET
                val flap = if (glide && sin(phase * 0.25f) < -0.2f) 0.15f else sin(phase)
                val a = art[bird]
                if (a != null) {
                    // Many wing frames: cycle them to flap. A single glider photo: flex it a touch instead.
                    val fr = if (a.frames.size > 1) (phase / (2f * PI.toFloat()) * a.frames.size).toInt() + i * 3 else 0
                    val squash = if (a.frames.size > 1) 1f else 1f - (if (a.glides) 0.05f else 0.18f) * (0.5f + 0.5f * flap)
                    drawSprite(a, fr, x, y, s * 2.4f * depth, dir, sin(inSlot * 1.4f + i * 0.9f) * 6f, squash, edge * (0.75f + 0.25f * depth), light)
                } else drawFlapper(bird, x, y, s * depth, flap, dir, color.copy(alpha = color.alpha * edge * (0.7f + 0.3f * depth)), path)
            }
        }
    }
}

/** A bird seen from the side/below: two curved wings that beat, plus a little body, neck or tail by species. */
private fun DrawScope.drawFlapper(bird: Bird, x: Float, y: Float, s: Float, flap: Float, dir: Float, c: Color, path: Path) {
    val tipY = flap * s * 0.55f
    val sweep = if (bird == Bird.SWALLOW) -dir * s * 0.35f else 0f
    path.reset()
    path.moveTo(x - s + sweep, y - tipY)
    path.quadraticTo(x - s * 0.45f, y - s * 0.28f - tipY * 0.35f, x, y)
    path.quadraticTo(x + s * 0.45f, y - s * 0.28f - tipY * 0.35f, x + s + sweep, y - tipY)
    val stroke = max(1.3f, s * if (bird == Bird.EGRET || bird == Bird.GOOSE || bird == Bird.GULL) 0.15f else 0.19f)
    drawPath(path, c, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(c, s * 0.14f, Offset(x, y + s * 0.04f))
    when (bird) {
        Bird.GOOSE, Bird.EGRET -> drawLine(c, Offset(x, y + s * 0.04f), Offset(x + dir * s * 0.62f, y + s * 0.1f), stroke * 0.9f, StrokeCap.Round)
        Bird.PARAKEET, Bird.LORIKEET -> drawLine(c, Offset(x, y + s * 0.05f), Offset(x - dir * s * 0.95f, y + s * 0.2f), stroke * 0.8f, StrokeCap.Round)
        Bird.SWALLOW -> {
            drawLine(c, Offset(x, y), Offset(x - dir * s * 0.55f, y + s * 0.22f), stroke * 0.6f, StrokeCap.Round)
            drawLine(c, Offset(x, y), Offset(x - dir * s * 0.6f, y - s * 0.02f), stroke * 0.6f, StrokeCap.Round)
        }
        else -> Unit
    }
}

/** A soaring raptor: broad flat wings with fingered tips and a notched tail; barely flaps. */
private fun DrawScope.drawKite(x: Float, y: Float, s: Float, heading: Float, t: Float, c: Color, path: Path) {
    val flex = sin(t * 1.3f) * s * 0.05f
    path.reset()
    path.moveTo(x - s, y - flex)
    path.quadraticTo(x - s * 0.5f, y - s * 0.16f, x - s * 0.08f, y - s * 0.06f)
    path.lineTo(x + heading * s * 0.22f, y - s * 0.03f)
    path.lineTo(x + s * 0.08f, y - s * 0.06f)
    path.quadraticTo(x + s * 0.5f, y - s * 0.16f, x + s, y - flex)
    path.quadraticTo(x + s * 0.55f, y + s * 0.06f, x + s * 0.12f, y + s * 0.08f)
    // Notched tail.
    path.lineTo(x - heading * s * 0.1f + s * 0.06f, y + s * 0.34f)
    path.lineTo(x - heading * s * 0.1f, y + s * 0.27f)
    path.lineTo(x - heading * s * 0.1f - s * 0.06f, y + s * 0.34f)
    path.lineTo(x - s * 0.12f, y + s * 0.08f)
    path.quadraticTo(x - s * 0.55f, y + s * 0.06f, x - s, y - flex)
    path.close()
    drawPath(path, c)
}

/** A bat: scalloped wings, fast jerky beats. */
private fun DrawScope.drawBat(x: Float, y: Float, s: Float, flap: Float, c: Color, path: Path) {
    val tip = flap * s * 0.6f
    path.reset()
    path.moveTo(x, y - s * 0.12f)
    path.quadraticTo(x - s * 0.5f, y - s * 0.3f - tip * 0.6f, x - s, y - tip)
    path.quadraticTo(x - s * 0.8f, y + s * 0.02f - tip * 0.3f, x - s * 0.62f, y + s * 0.12f - tip * 0.2f)
    path.quadraticTo(x - s * 0.45f, y + s * 0.02f, x - s * 0.3f, y + s * 0.16f)
    path.quadraticTo(x - s * 0.15f, y + s * 0.05f, x, y + s * 0.14f)
    path.quadraticTo(x + s * 0.15f, y + s * 0.05f, x + s * 0.3f, y + s * 0.16f)
    path.quadraticTo(x + s * 0.45f, y + s * 0.02f, x + s * 0.62f, y + s * 0.12f - tip * 0.2f)
    path.quadraticTo(x + s * 0.8f, y + s * 0.02f - tip * 0.3f, x + s, y - tip)
    path.quadraticTo(x + s * 0.5f, y - s * 0.3f - tip * 0.6f, x, y - s * 0.12f)
    path.close()
    drawPath(path, c)
}

/** Rain: thin streaks falling fast, slanted by the wind. [heavy] scales speed, length and opacity. */
private fun DrawScope.drawRain(t: Float, count: Int, heavy: Float, wind: Float, d: Float) {
    val w = size.width
    val h = size.height
    val slant = 0.18f + wind * 0.45f
    val len = 16f * d * heavy
    for (i in 0 until count) {
        val depth = 0.5f + rnd(i, 41) * 0.5f
        val speed = (520f + 380f * depth) * d * heavy
        val y0 = rnd(i, 42) * h
        val y = ((y0 + t * speed) % (h + len)) - len
        val x = ((rnd(i, 43) * (w + 200f * d) - 100f * d) + (y + len) * slant + w * 4f) % (w + 120f * d) - 60f * d
        drawLine(
            Color(0xFFDCE7F5).copy(alpha = (0.16f + 0.22f * depth) * min(1f, heavy)),
            Offset(x, y), Offset(x + len * slant * depth, y + len * depth), max(1f, 1.1f * d * depth), StrokeCap.Round,
        )
    }
}

/** Snow: soft flakes drifting down and swaying. */
private fun DrawScope.drawSnow(t: Float, count: Int, wind: Float, d: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until count) {
        val depth = 0.4f + rnd(i, 51) * 0.6f
        val speed = (18f + 34f * depth) * d
        val y = (rnd(i, 52) * h + t * speed) % (h + 10f * d) - 5f * d
        val sway = sin(t * (0.6f + rnd(i, 53)) + i) * 14f * d
        val x = ((rnd(i, 54) * w + sway + t * wind * 30f * d) % w + w) % w
        drawCircle(Color.White.copy(alpha = 0.45f + 0.45f * depth), (1.1f + 2.2f * depth) * d, Offset(x, y))
    }
}

/** Fog: wide soft bands drifting slowly across. */
private fun DrawScope.drawFog(t: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 4) {
        val y = h * (0.25f + i * 0.17f)
        val drift = ((t * (6f + i * 3f) + i * 300f) % (w * 2f)) - w * 0.5f
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(drift, y), radius = w * 0.75f,
            ),
            topLeft = Offset(0f, y - h * 0.22f), size = androidx.compose.ui.geometry.Size(w, h * 0.44f),
        )
    }
}

/** Thunderstorm: a bright flicker over the whole sky and a jagged bolt, every several seconds. */
private fun DrawScope.drawLightning(t: Float, path: Path, d: Float) {
    val slotLen = 9f
    val slot = floor(t / slotLen).toInt()
    val at = rnd(slot, 61) * (slotLen - 1.5f)
    val k = t - slot * slotLen - at
    if (k < 0f || k > 0.6f) return
    // Two quick flickers, then a fade.
    val flash = when {
        k < 0.06f -> 1f
        k < 0.12f -> 0.25f
        k < 0.18f -> 0.85f
        else -> (1f - (k - 0.18f) / 0.42f).coerceIn(0f, 1f) * 0.5f
    }
    drawRect(Color(0xFFE8ECFF).copy(alpha = 0.32f * flash))
    if (k < 0.22f) {
        val w = size.width
        val h = size.height
        var x = w * (0.2f + rnd(slot, 62) * 0.6f)
        var y = 0f
        path.reset()
        path.moveTo(x, y)
        var seg = 0
        while (y < h * 0.7f && seg < 14) {
            x += (rnd(slot * 17 + seg, 63) - 0.5f) * 46f * d
            y += (18f + rnd(slot * 17 + seg, 64) * 26f) * d
            path.lineTo(x, y)
            seg++
        }
        drawPath(path, Color.White.copy(alpha = 0.9f * flash), style = Stroke(2.2f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Color(0xFFB9C6FF).copy(alpha = 0.35f * flash), style = Stroke(7f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Clear nights: now and then a shooting star streaks across. */
private fun DrawScope.drawShootingStar(t: Float, d: Float) {
    val slotLen = 13f
    val slot = floor(t / slotLen).toInt()
    if (rnd(slot, 71) > 0.55f) return
    val at = rnd(slot, 72) * (slotLen - 1.2f)
    val k = (t - slot * slotLen - at) / 0.9f
    if (k < 0f || k > 1f) return
    val w = size.width
    val h = size.height
    val sx = w * (0.15f + rnd(slot, 73) * 0.7f)
    val sy = h * (0.08f + rnd(slot, 74) * 0.25f)
    val dx = (if (rnd(slot, 75) > 0.5f) 1f else -1f) * 170f * d
    val dy = 70f * d
    val head = Offset(sx + dx * k, sy + dy * k)
    val tail = Offset(sx + dx * max(0f, k - 0.35f), sy + dy * max(0f, k - 0.35f))
    val a = sin(k * PI.toFloat())
    drawLine(
        Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f * a)), start = tail, end = head),
        tail, head, 1.6f * d, StrokeCap.Round,
    )
    drawCircle(Color.White.copy(alpha = a), 1.6f * d, head)
}
