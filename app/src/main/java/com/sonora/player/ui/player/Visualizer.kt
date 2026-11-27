package com.sonora.player.ui.player

import androidx.compose.ui.preferredFrameRate
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.sonora.player.playback.SpectrumAnalyzer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The visualizer's looks. Stored by ordinal in preferences, so only ever add to the end. */
enum class VizStyle(val label: String) { ORB("Neon Orb"), SPHERE("Wireframe"), TERRAIN("Terrain") }

private const val TAU = (2 * PI).toFloat()

/** Neon colours on black, like a club light rig. */
private val NEON = listOf(
    Color(0xFFFF2D95), Color(0xFFFF4A3D), Color(0xFFFFB13D), Color(0xFFFFF04D), Color(0xFF52FF7A),
    Color(0xFF2DF2FF), Color(0xFF3D7BFF), Color(0xFF9B3DFF),
)

private fun neon(f: Float): Color {
    val x = ((f % 1f) + 1f) % 1f * NEON.size
    val i = x.toInt() % NEON.size
    return androidx.compose.ui.graphics.lerp(NEON[i], NEON[(i + 1) % NEON.size], x - x.toInt())
}

/**
 * Turns the spectrum of what you're hearing *right now* into motion. Everything here is driven by the audio: band
 * levels with a fast attack, falling peaks, low/mid/high energy, kick detection (spectral flux) and a short
 * history of spectra for the terrain. The only free-running motion is a slow drift that itself speeds up with
 * the music, so when the song is quiet the picture is quiet too.
 */
@Stable
class VizEngine(private val n: Int = 64) {
    val level = FloatArray(n)
    val peak = FloatArray(n)
    private val prev = FloatArray(n)
    var low = 0f; private set
    var mid = 0f; private set
    var high = 0f; private set
    var energy = 0f; private set
    var kick = 0f; private set
    var drift = 0f; private set
    var spin = 0f; private set
    private var fluxAvg = 0f
    private var sinceKick = 1f
    private var lastNanos = 0L

    // Terrain: recent spectra, newest first.
    val rows = 30
    val cols = 40
    val history = Array(rows) { FloatArray(cols) }
    private var rowClock = 0f
    /** 0..1: how far the terrain has slid toward the next row, so rows glide instead of jumping. */
    var rowPhase = 0f; private set
    /** Seconds since the last frame. */
    var dt = 1f / 60f; private set

    // Wireframe: per-vertex displacement smoothed over time, and projection buffers reused every frame.
    val sLat = 20
    val sLon = 36
    val sphereV = Array(sLat + 1) { FloatArray(sLon) }
    val sx = Array(sLat + 1) { FloatArray(sLon) }
    val sy = Array(sLat + 1) { FloatArray(sLon) }
    val sz = Array(sLat + 1) { FloatArray(sLon) }

    /** Centre of the main shape, as a fraction of the canvas width (0.5 = middle). */
    var focusX = 0.5f
    /** Width of the area the main shape is sized to, as a fraction of the canvas width. */
    var span = 1f
    /** Centre and height of that area, as fractions of the canvas height. */
    var focusY = 0.5f
    var spanY = 1f
    /** False when the canvas is the whole page: no side ribbons boxing it in, and lines fade out toward the player. */
    var edges = true

    // Neon Orb: eased values for each blob point, ray and ribbon point, so shapes swell and settle instead of twitching.
    val orbV = Array(5) { FloatArray(48) }
    val rayV = FloatArray(100)
    val ribV = Array(2) { FloatArray(29) }
    var orbCore = 0f
    var orbLongest = 0f

    /** Moves [cur] toward [target]: quick on the way up (with the beat), gentle on the way down. */
    fun ease(cur: Float, target: Float, up: Float = 22f, down: Float = 7f): Float =
        cur + (target - cur) * (1f - exp(-dt * (if (target > cur) up else down)))

    /** Spectrum level at [f], blended with its neighbours so adjacent points move together. */
    fun soft(f: Float, from: Int, to: Int, spread: Float = 0.04f): Float =
        (at(f - spread, from, to) + 2f * at(f, from, to) + at(f + spread, from, to)) * 0.25f

    // Sparks inside the orb, fired by kicks.
    private val cap = 160
    val px = FloatArray(cap); val py = FloatArray(cap); val vx = FloatArray(cap); val vy = FloatArray(cap)
    val life = FloatArray(cap); val pc = FloatArray(cap)
    private var next = 0
    private var seed = 7

    private fun rand(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }

    fun step(a: SpectrumAnalyzer, frameNanos: Long) {
        val dt = if (lastNanos == 0L) 1f / 60f else ((frameNanos - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = frameNanos
        if (dt <= 0f) return
        this.dt = dt
        var flux = 0f
        var lo = 0f; var mi = 0f; var hi = 0f
        val bc = a.bandCount
        for (i in 0 until n) {
            val v = a.bands[i * bc / n]
            level[i] = if (v > level[i]) level[i] + (v - level[i]) * (1f - exp(-dt * 45f)) else level[i] + (v - level[i]) * (1f - exp(-dt * 12f))
            peak[i] = if (level[i] > peak[i]) level[i] else max(level[i], peak[i] - dt * 0.7f)
            flux += max(0f, level[i] - prev[i])
            prev[i] = level[i]
            when {
                i < n / 8 -> lo += level[i]
                i < n / 2 -> mi += level[i]
                else -> hi += level[i]
            }
        }
        low = lo / (n / 8); mid = mi / (n / 2 - n / 8); high = hi / (n - n / 2)
        energy += ((low * 0.5f + mid * 0.35f + high * 0.15f) - energy) * (1f - exp(-dt * 14f))
        fluxAvg += (flux - fluxAvg) * (1f - exp(-dt * 3f))
        sinceKick += dt
        kick = max(0f, kick - dt * 4f)
        if (flux > fluxAvg * 1.55f + 0.3f && sinceKick > 0.14f && low > 0.15f) {
            sinceKick = 0f
            kick = 1f
            repeat(22) { spawn() }
        }
        // Slow drift that only really moves when the music does.
        drift += dt * (0.02f + energy * 0.35f)
        spin += dt * (0.03f + energy * 0.25f + kick * 0.6f)
        // Terrain: push a new row ~28 times a second.
        rowClock += dt
        if (rowClock >= ROW_SECONDS) {
            rowClock = (rowClock - ROW_SECONDS).coerceAtMost(ROW_SECONDS * 0.5f)
            val last = history[rows - 1]
            for (r in rows - 1 downTo 1) history[r] = history[r - 1]
            history[0] = last
            for (c in 0 until cols) {
                // Mirror: bass in the middle, highs at the edges.
                val m = kotlin.math.abs(c - (cols - 1) / 2f) / ((cols - 1) / 2f)
                last[c] = level[(m * 52).toInt().coerceIn(0, n - 1)]
            }
        }
        rowPhase = (rowClock / ROW_SECONDS).coerceIn(0f, 1f)
        for (i in 0 until cap) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            val drag = exp(-dt * 2.2f)
            vx[i] *= drag; vy[i] *= drag
            px[i] += vx[i] * dt; py[i] += vy[i] * dt
        }
    }

    private fun spawn() {
        val i = next; next = (next + 1) % cap
        val ang = rand() * TAU
        val sp = 0.25f + rand() * 0.9f
        px[i] = 0f; py[i] = 0f
        vx[i] = cos(ang) * sp; vy[i] = sin(ang) * sp
        life[i] = 0.6f + rand() * 0.9f
        pc[i] = rand()
    }

    private companion object { const val ROW_SECONDS = 0.035f }

    /** Smoothly interpolated level at a fraction 0..1 of the spectrum range [from, to). */
    fun at(f: Float, from: Int = 0, to: Int = 56): Float {
        val x = from + f.coerceIn(0f, 1f) * (to - from - 1)
        val i = x.toInt().coerceIn(0, n - 2)
        val t = x - i
        return level[i] * (1 - t) + level[i + 1] * t
    }
}

@Composable
fun Visualizer(
    live: LiveAudio, colors: List<Color>, style: VizStyle, art: Uri?, modifier: Modifier = Modifier,
    focusX: Float = 0.5f, span: Float = 1f, edges: Boolean = true,
    focusY: Float = 0.5f, spanY: Float = 1f,
) {
    val engine = remember { VizEngine() }
    engine.focusX = focusX; engine.span = span; engine.edges = edges
    engine.focusY = focusY; engine.spanY = spanY
    val paths = remember { Array(12) { Path() } }
    // A fixed starfield.
    val stars = remember { FloatArray(240).also { a -> var s = 11; for (i in a.indices) { s = s * 1103515245 + 12345; a[i] = ((s ushr 8) and 0xFFFF) / 65535f } } }
    val albumTint = colors.firstOrNull() ?: Color(0xFF7C5CFF)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().preferredFrameRate(60f)) {
            val frame = live.frame.longValue
            engine.step(live.analyzer, frame)
            drawStars(engine, stars)
            when (style) {
                VizStyle.ORB -> drawOrb(engine, paths, albumTint)
                VizStyle.SPHERE -> drawSphere(engine, paths)
                VizStyle.TERRAIN -> drawTerrain(engine, paths)
            }
        }
    }
}

private fun DrawScope.drawStars(e: VizEngine, s: FloatArray) {
    for (i in 0 until s.size / 3) {
        val x = s[i * 3] * size.width
        val y = s[i * 3 + 1] * size.height
        val tw = 0.25f + 0.75f * (0.5f + 0.5f * sin(e.drift * 6f + s[i * 3 + 2] * 40f))
        drawCircle(Color.White.copy(alpha = (0.15f + e.high * 0.6f) * tw), (0.6f + s[i * 3 + 2] * 1.1f) * density, Offset(x, y))
    }
}

/** A smooth closed curve through radius samples r(θ) around [c]. */
private fun closedCurve(path: Path, c: Offset, rs: FloatArray, rot: Float) {
    path.reset()
    val n = rs.size
    fun pt(i: Int): Offset {
        val k = ((i % n) + n) % n
        val a = k * TAU / n + rot
        return Offset(c.x + cos(a) * rs[k], c.y + sin(a) * rs[k])
    }
    val p0 = pt(0); val p1 = pt(1)
    path.moveTo((p0.x + p1.x) / 2, (p0.y + p1.y) / 2)
    for (i in 1..n) {
        val a = pt(i); val b = pt(i + 1)
        path.quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    path.close()
}

// ---------------------------------------------------------------- Neon Orb

/** Soft ceiling: grows like [x] when small, never past [room]. Keeps loud passages inside the canvas. */
private fun fit(x: Float, room: Float): Float = if (room <= 0f) 0f else room * (1f - exp(-x.coerceAtLeast(0f) / room))

private val orbRs = FloatArray(48)
private val orbBlobs = arrayOf(floatArrayOf(0f, 0f, 14f), floatArrayOf(0.92f, 10f, 30f), floatArrayOf(0.62f, 22f, 44f), floatArrayOf(0.07f, 34f, 60f), floatArrayOf(0.5f, 4f, 24f))

private fun DrawScope.drawOrb(e: VizEngine, paths: Array<Path>, tint: Color) {
    val w = size.width
    val h = size.height
    val c = Offset(w * e.focusX, h * e.focusY)
    val m = min(w * e.span, h * e.spanY * (if (e.edges) 1f else 0.84f))
    e.orbCore = e.ease(e.orbCore, 0.16f + e.low * 0.02f + e.kick * 0.015f, up = 30f, down = 8f)
    val core = m * e.orbCore
    // Everything stays inside this circle, however loud it gets.
    val limit = m * 0.48f
    val room = limit - core

    // Full page: the orb's colour spills softly across the whole screen, so it belongs to the page, not a panel.
    if (!e.edges) {
        val r = w * 0.75f
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = 0.16f + e.energy * 0.14f + e.kick * 0.06f), neon(0.88f).copy(alpha = 0.05f), Color.Transparent), c, r),
            r, c,
        )
    }
    // Layered spectrum blobs, each reading its own slice of the spectrum.
    val n = orbRs.size
    val rs = orbRs
    val inner = 4f * density
    for (b in orbBlobs.indices) {
        val hue = orbBlobs[b][0]; val from = orbBlobs[b][1].toInt(); val to = orbBlobs[b][2].toInt()
        val reach = m * (0.08f + b * 0.02f)
        val eased = e.orbV[b]
        for (i in 0 until n) {
            val f = i.toFloat() / n
            // Mirror around the circle so the shape stays balanced, with a twist per blob.
            val g = if (f < 0.5f) f * 2f else (1f - f) * 2f
            eased[i] = e.ease(eased[i], e.soft(g, from, to))
            val v = eased[i].pow(1.25f)
            rs[i] = core + inner + fit(v * reach * 2.6f + e.kick * reach * 0.25f, room - inner)
        }
        val path = paths[b]
        closedCurve(path, c, rs, e.spin * (if (b % 2 == 0) 1f else -0.7f) + b * 1.3f)
        val col = if (b == 4) androidx.compose.ui.graphics.lerp(tint, Color.White, 0.25f) else neon(hue)
        drawPath(path, Brush.radialGradient(listOf(col.copy(alpha = 0.9f), col.copy(alpha = 0.35f)), c, limit), alpha = 0.4f, blendMode = BlendMode.Plus)
        drawPath(path, col, style = Stroke(1.2f * density), alpha = 0.85f)
    }

    // Light rays: long thin lines whose length follows each band, drawn in one pass with a fading gradient.
    val rayPath = paths[10]
    rayPath.reset()
    val rays = 100
    var longest = core
    for (i in 0 until rays) {
        val f = i.toFloat() / rays
        val g = if (f < 0.5f) f * 2f else (1f - f) * 2f
        e.rayV[i] = e.ease(e.rayV[i], e.soft(g, 0, 56, 0.02f), up = 26f, down = 8f)
        val v = e.rayV[i]
        val a = f * TAU + e.spin * 0.4f
        val r0 = core * 1.02f
        val r1 = core + fit(m * (0.05f + v.pow(1.1f) * 0.42f), room)
        if (r1 > longest) longest = r1
        rayPath.moveTo(c.x + cos(a) * r0, c.y + sin(a) * r0)
        rayPath.lineTo(c.x + cos(a) * r1, c.y + sin(a) * r1)
    }
    e.orbLongest = if (e.orbLongest <= core) longest else e.ease(e.orbLongest, longest, up = 18f, down = 5f)
    val fade = e.orbLongest.coerceAtLeast(core + 1f)
    drawPath(
        rayPath,
        Brush.radialGradient(0f to Color.White.copy(alpha = 0.6f), (core / fade) to Color.White.copy(alpha = 0.5f), 1f to neon(0.62f).copy(alpha = 0f), center = c, radius = fade),
        style = Stroke(1.1f * density), blendMode = BlendMode.Plus,
    )

    // Flowing loop lines that breathe with the mids and highs.
    for (k in 0 until 3) {
        val path = paths[5 + k]
        for (i in 0 until n) {
            val f = i.toFloat() / n
            val a = f * TAU
            val r = m * (0.27f + k * 0.05f) * (1f + 0.14f * sin(a * (2 + k) + e.drift * (2f + k) + k) + (if (k == 2) e.high else e.mid) * 0.3f * sin(a * 3f - e.drift * 4f))
            rs[i] = core + fit(r - core, room)
        }
        closedCurve(path, c, rs, e.spin * 0.2f * (k + 1))
        val col = neon(0.32f + k * 0.08f)
        drawPath(path, Brush.sweepGradient(listOf(col, neon(0.45f + k * 0.1f), col.copy(alpha = 0.1f), col), c),
            style = Stroke((1.4f + e.energy * 1.6f) * density), blendMode = BlendMode.Plus)
    }

    // The orb: a dark core with a white-hot rim.
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f + e.kick * 0.4f), Color.Transparent), c, core * 1.35f), core * 1.35f, c, blendMode = BlendMode.Plus)
    drawCircle(Color(0xFF02030A), core, c)
    drawCircle(Brush.radialGradient(listOf(Color.Transparent, neon(0.6f).copy(alpha = 0.35f)), c, core), core, c)
    drawCircle(Color.White, core, c, style = Stroke((2f + e.kick * 3f) * density))
    drawCircle(neon(0.62f).copy(alpha = 0.8f), core * 1.04f, c, style = Stroke(2.5f * density), blendMode = BlendMode.Plus)
}

// ---------------------------------------------------------------- Wireframe sphere

private fun DrawScope.drawSphere(e: VizEngine, paths: Array<Path>) {
    val w = size.width
    val h = size.height
    val c = Offset(w * e.focusX, h * (e.focusY - (if (e.edges) 0.04f * e.spanY else 0f)))
    val R = min(w * e.span, h * e.spanY * (if (e.edges) 1f else 0.78f)) * 0.30f
    val lat = e.sLat
    val lon = e.sLon
    val yaw = e.spin * 0.6f
    val pitch = 0.42f + sin(e.drift * 0.7f) * 0.12f
    val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
    val camera = R * 3.2f
    // Project every vertex once (into the engine's reused buffers).
    val X = e.sx
    val Y = e.sy
    val Z = e.sz
    val V = e.sphereV
    // Surface follows the music quickly but eases out, so it swells and settles instead of twitching.
    val up = 1f - exp(-e.dt * 22f)
    val down = 1f - exp(-e.dt * 7f)
    for (i in 0..lat) {
        val phi = -PI.toFloat() / 2 + PI.toFloat() * i / lat
        // Each latitude listens to part of the spectrum: bass round the middle, highs at the poles.
        val band = kotlin.math.abs(phi) / (PI.toFloat() / 2)
        for (j in 0 until lon) {
            val theta = TAU * j / lon
            val g = if (j < lon / 2) j * 2f / lon else (lon - j) * 2f / lon
            // Average with the neighbouring bands so adjacent vertices don't fight each other.
            val f = band * 0.7f + g * 0.3f
            val target = (e.at(f - 0.03f, 0, 56) + 2f * e.at(f, 0, 56) + e.at(f + 0.03f, 0, 56)) * 0.25f
            val cur = V[i][j]
            val v = cur + (target - cur) * (if (target > cur) up else down)
            V[i][j] = v
            val r = R * (1f + v.pow(1.3f) * 0.55f * cos(phi).coerceAtLeast(0.25f) + e.kick * 0.05f)
            var x = r * cos(phi) * cos(theta)
            var y = r * sin(phi)
            var z = r * cos(phi) * sin(theta)
            // Rotate (yaw then pitch).
            val x1 = x * cy + z * sy; val z1 = -x * sy + z * cy
            val y2 = y * cp - z1 * sp; val z2 = y * sp + z1 * cp
            x = x1; y = y2; z = z2
            val k = camera / (camera - z)
            X[i][j] = c.x + x * k
            Y[i][j] = c.y + y * k
            Z[i][j] = z
        }
    }
    // Glow behind.
    drawCircle(Brush.radialGradient(listOf(neon(0.85f + e.energy * 0.2f).copy(alpha = 0.25f + e.energy * 0.3f), Color.Transparent), c, R * 2.2f), R * 2.2f, c)
    // Back lines first (dim), then front lines (bright), so it reads as 3D. All meridians share one path and all
    // rings another, per side, coloured by a rainbow across (meridians) or down (rings) the sphere: 4 draws a frame.
    val merid = paths[4]
    val rings = paths[5]
    val left = c.x - R * 1.6f; val right = c.x + R * 1.6f
    val top = c.y - R * 1.6f; val bot = c.y + R * 1.6f
    val shift = e.drift * 0.15f
    val across = Brush.horizontalGradient(List(9) { neon(it / 8f + shift) }, left, right)
    val downward = Brush.verticalGradient(List(9) { neon(it / 8f * 0.9f + 0.05f + shift) }, top, bot)
    for (pass in 0..1) {
        val front = pass == 1
        merid.reset(); rings.reset()
        for (j in 0 until lon) {
            for (i in 0 until lat) {
                if (((Z[i][j] + Z[i + 1][j]) >= 0) != front) continue
                merid.moveTo(X[i][j], Y[i][j]); merid.lineTo(X[i + 1][j], Y[i + 1][j])
            }
        }
        for (i in 1 until lat) {
            for (j in 0 until lon) {
                val j2 = (j + 1) % lon
                if (((Z[i][j] + Z[i][j2]) >= 0) != front) continue
                rings.moveTo(X[i][j], Y[i][j]); rings.lineTo(X[i][j2], Y[i][j2])
            }
        }
        drawPath(merid, across, alpha = if (front) 0.95f else 0.22f, style = Stroke((if (front) 1.4f else 0.9f) * density))
        drawPath(rings, downward, alpha = if (front) 0.85f else 0.2f, style = Stroke((if (front) 1.2f else 0.8f) * density))
    }
}

// ---------------------------------------------------------------- Terrain

private val TERRAIN_COLORS = listOf(neon(0.62f), neon(0.88f), neon(0.0f), neon(0.15f), neon(0.0f), neon(0.88f), neon(0.62f))
private val TERRAIN_FILL = Color(0xF203040A)
/** Same, fading out at both ends (full-page mode, so ridges don't stop at a hard edge). */
private val TERRAIN_FADED = listOf(Color.Transparent) + TERRAIN_COLORS.drop(1).dropLast(1) + Color.Transparent

private fun DrawScope.drawTerrain(e: VizEngine, paths: Array<Path>) {
    val w = size.width
    val h = size.height
    val rows = e.rows
    val cols = e.cols
    val areaTop = h * (e.focusY - e.spanY / 2f)
    val areaH = h * e.spanY
    val horizon = areaTop + areaH * 0.30f
    val bottom = areaTop + areaH * 0.98f
    val cx = w * e.focusX
    val stageW = w * e.span
    // Back to front, so nearer rows hide the ones behind (filled black underneath).
    for (r in rows - 1 downTo 0) {
        // 0 = newest (front), 1 = oldest (back). The phase slides every row back a little each frame.
        val t = (r + e.rowPhase) / (rows - 1)
        if (t > 1f) continue
        val depth = 1f / (1f + t * 3.2f)          // perspective scale
        val y0 = horizon + (bottom - horizon) * depth.pow(1.4f)
        val half = stageW * 0.62f * depth
        val amp = h * 0.42f * depth
        val row = e.history[r]
        val p = paths[0]
        val outline = paths[1]
        p.reset(); outline.reset()
        // Smooth ridge for the visible line (curve through the midpoints); a plain polygon for the hiding fill.
        var px0 = 0f; var py0 = 0f
        for (cIdx in 0 until cols) {
            val x = cx - half + 2 * half * cIdx / (cols - 1)
            val y = y0 - row[cIdx].pow(1.15f) * amp
            if (cIdx == 0) p.moveTo(x, y) else p.lineTo(x, y)
            when (cIdx) {
                0 -> outline.moveTo(x, y)
                cols - 1 -> outline.quadraticTo(px0, py0, x, y)
                else -> {
                    val mx = (px0 + x) / 2; val my = (py0 + y) / 2
                    if (cIdx == 1) outline.lineTo(mx, my) else outline.quadraticTo(px0, py0, mx, my)
                }
            }
            px0 = x; py0 = y
        }
        val base = y0 + 2f * density
        p.lineTo(cx + half, base); p.lineTo(cx - half, base); p.close()
        drawPath(
            p,
            Brush.verticalGradient(
                0f to TERRAIN_FILL, 0.65f to TERRAIN_FILL, 1f to Color.Transparent,
                startY = y0 - amp, endY = base,
            ),
        )
        // Fade in at the front as a row arrives, fade out at the back as it leaves.
        val a = (1f - t * 0.75f) * (t / 0.03f).coerceAtMost(1f) * ((1f - t) / 0.08f).coerceIn(0f, 1f)
        drawPath(
            outline,
            if (e.edges) Brush.horizontalGradient(TERRAIN_COLORS, cx - half, cx + half)
            // Full page: the ridge's player-side end fades out instead of stopping at a panel edge.
            else Brush.horizontalGradient(TERRAIN_FADED, cx - half, cx + half),
            alpha = a, style = Stroke((0.8f + (1f - t) * 1.4f) * density, join = StrokeJoin.Round), blendMode = BlendMode.Plus,
        )
    }
    // A wash of light from the front on every kick.
    if (e.kick > 0f) {
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, neon(0.92f).copy(alpha = 0.18f * e.kick), Color.Transparent), horizon, areaTop + areaH * 1.15f))
    }
}
