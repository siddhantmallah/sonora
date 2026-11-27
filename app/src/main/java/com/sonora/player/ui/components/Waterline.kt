package com.sonora.player.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The little scene a tab's icon plays when you pick it. */
enum class TabFx { HOP, SPIN, WAVE }

data class WaterTab(val icon: Int, val label: String, val fx: TabFx)

/**
 * "Waterline" tabs: the selected tab sits in a drop of water. Pick another tab and the drop stretches toward it,
 * on a long jump its neck breaks into droplets that chase after it, and it lands with a ripple. Each icon plays its
 * own short scene. A native take on the "liquid glass navbar" idea (Nimbus UI · Waterline, guglielmogiannattasio.it).
 *
 * The physics runs only while the water moves; at rest nothing animates.
 */
@Composable
fun WaterlineTabs(
    tabs: List<WaterTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val water = remember { WaterSim() }
    var size by remember { mutableStateOf(Size.Zero) }
    var frame by remember { mutableLongStateOf(0L) }
    val accent = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val fx = remember(tabs.size) { List(tabs.size) { Animatable(1f) } }

    // Geometry from the bar size: one cell per tab, the drop a little inside the cell.
    val padPx = with(density) { 8.dp.toPx() }
    val cell = if (size.width > 0) (size.width - 2 * padPx) / tabs.size else 0f
    fun centerOf(i: Int) = padPx + cell * (i + 0.5f)

    LaunchedEffect(selected, size) {
        if (size.width <= 0f) return@LaunchedEffect
        val r = min(with(density) { 22.dp.toPx() }, size.height / 2 - with(density) { 5.dp.toPx() })
        val q = max(0f, (cell - with(density) { 10.dp.toPx() }) / 2 - r)
        water.setTarget(centerOf(selected), size.height / 2, q, r, size.width)
        var last = 0L
        while (water.busy()) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 1f / 30f)
                last = now
                water.step(dt)
                frame++
            }
        }
        frame++
    }

    Box(modifier.onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame
            water.draw(this, dark, accent)
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            tabs.forEachIndexed { i, tab ->
                val isSel = i == selected
                val tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                val p = fx[i].value
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) {
                        if (i != selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        water.ripple(centerOf(i), size.height / 2, if (i == selected) 0.6f else 0.9f)
                        scope.launch { fx[i].snapTo(0f); fx[i].animateTo(1f, tween(if (tab.fx == TabFx.SPIN) 700 else 650, easing = LinearEasing)) }
                        scope.launch { kickRipples(water) { frame++ } }
                        onSelect(i)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.graphicsLayer {
                            val t = p
                            when (tab.fx) {
                                // A hop with a squash on landing.
                                TabFx.HOP -> {
                                    val up = sin(PI * min(t / 0.55f, 1f)).toFloat()
                                    translationY = -5.dp.toPx() * up
                                    val land = if (t > 0.55f) sin(PI * ((t - 0.55f) / 0.45f)).toFloat() else 0f
                                    scaleX = 1f + 0.12f * land; scaleY = 1f - 0.12f * land
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                                }
                                // A full turn around the vertical axis.
                                TabFx.SPIN -> {
                                    val e = 1f - (1f - t) * (1f - t) * (1f - t)
                                    rotationY = 360f * e
                                    cameraDistance = 12f * this.density
                                }
                                // A friendly wiggle.
                                TabFx.WAVE -> {
                                    rotationZ = 15f * sin(4 * PI * t).toFloat() * (1f - t)
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.9f)
                                }
                            }
                            if (isSel) { scaleX *= 1.06f; scaleY *= 1.06f }
                        },
                    ) { LIcon(tab.icon, tab.label, tint = tint, size = 22.dp) }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label, color = tint, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Keeps frames coming while a tap's ripple fades (when the drop itself isn't moving). */
private suspend fun kickRipples(water: WaterSim, onFrame: () -> Unit) {
    var last = 0L
    while (water.ripplesActive()) {
        withFrameNanos { now ->
            val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 1f / 30f)
            last = now
            water.stepRipples(dt)
            onFrame()
        }
    }
}

/** Spring physics for the drop: a quick head and a lagging tail, droplets on long jumps, ripples on landing. */
private class WaterSim {
    private class Body(var x: Float = 0f, var v: Float = 0f)
    private class Drop(var x: Float, var y: Float, var vx: Float, var vy: Float, var r: Float, val k: Float)
    private class Ripple(val x: Float, val y: Float, var age: Float, val amp: Float)

    private val a = Body()
    private val b = Body()
    private var sy = 1f
    private var syV = 0f
    private var lead = 0
    private var lagLeft = 0f
    private val drops = ArrayList<Drop>()
    private val ripples = ArrayList<Ripple>()
    private var tx = 0f
    private var cy = 0f
    private var q = 0f
    private var tq = 0f
    private var r = 20f
    private var width = 0f
    private var ready = false
    private var moving = false
    private var split = false
    private val path = Path()
    private val tmp = Path()
    private var seed = 7

    private fun rand(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }

    fun setTarget(x: Float, centerY: Float, halfLen: Float, radius: Float, w: Float) {
        cy = centerY; tq = halfLen; r = radius; width = w
        if (!ready || abs(w - width) > 1f && !moving) {
            a.x = x; b.x = x; a.v = 0f; b.v = 0f; q = halfLen; ready = true; tx = x; return
        }
        if (abs(x - tx) > 0.5f) {
            lead = if (abs(x - a.x) <= abs(x - b.x)) 0 else 1
            lagLeft = 0.07f
            moving = true
            split = false
        }
        tx = x
    }

    fun busy() = moving || drops.isNotEmpty() || ripples.isNotEmpty() || abs(sy - 1f) > 0.002f || abs(a.x - tx) > 0.3f || abs(b.x - tx) > 0.3f
    fun ripplesActive() = ripples.isNotEmpty() && !moving

    fun ripple(x: Float, y: Float, amp: Float) { ripples += Ripple(x, y, 0f, amp); if (ripples.size > 6) ripples.removeAt(0) }

    fun stepRipples(dt: Float) {
        ripples.forEach { it.age += dt }
        ripples.removeAll { it.age > 1.2f }
    }

    fun step(dt: Float) {
        q += (tq - q) * min(1f, dt * 12f)
        lagLeft -= dt
        var still = true
        listOf(a, b).forEachIndexed { i, body ->
            val isLead = i == lead
            val k = if (isLead) 210f else 72f
            val c = if (isLead) 25f else 13f
            val f = if (!isLead && lagLeft > 0f) 0.08f else 1f
            body.v += (k * (tx - body.x) - c * body.v) * dt * f
            body.x += body.v * dt
            val lo = 8f + q + r - 3f
            val hi = width - 8f - q - r + 3f
            if (body.x < lo || body.x > hi) {
                syV -= min(abs(body.v), 900f) * 0.0016f
                body.x = body.x.coerceIn(lo, hi)
                body.v *= -0.25f
            }
            if (abs(tx - body.x) > 0.3f || abs(body.v) > 3f) still = false
        }
        val leadBody = if (lead == 0) a else b
        val syT = 1f - 0.2f * min(abs(leadBody.v) / 1500f, 1f)
        syV += (280f * (syT - sy) - 9f * syV) * dt
        sy += syV * dt
        if (abs(sy - 1f) > 0.002f || abs(syV) > 0.02f) still = false
        // Long jump: the neck breaks into droplets that chase the drop.
        val sep = abs(a.x - b.x)
        val rr = r * (1f - 0.3f * min(sep / (2 * (tq + r)).coerceAtLeast(1f), 1f))
        if (!split && moving && sep - 2 * tq - 2 * rr > -6f) {
            split = true
            val lo = if (lead == 0) b else a
            val hi = if (lead == 0) a else b
            floatArrayOf(0.3f, 0.5f, 0.72f).forEachIndexed { j, t ->
                drops += Drop(
                    lo.x + (hi.x - lo.x) * t + (rand() - 0.5f) * 6f, cy + (rand() - 0.5f) * 10f,
                    (lo.v + hi.v) * 0.45f, (rand() - 0.5f) * 60f, r * (0.28f + rand() * 0.12f) - j, 70f + rand() * 90f,
                )
            }
        }
        val it = drops.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.vx += (d.k * (tx - d.x) - 9f * d.vx) * dt
            d.vy += (90f * (cy - d.y) - 7f * d.vy) * dt
            d.x += d.vx * dt; d.y += d.vy * dt
            d.y = d.y.coerceIn(cy - r + d.r, cy + r - d.r)
            if (abs(tx - d.x) < tq + r * 0.5f) d.r -= dt * 26f
            if (d.r <= 0.6f) it.remove()
        }
        if (drops.isNotEmpty()) still = false
        if (moving && still) {
            moving = false
            ripples += Ripple(tx, cy, 0f, if (split) 1.2f else 0.7f)
        }
        stepRipples(dt)
    }

    private fun capsule(cx: Float, cy: Float, half: Float, rad: Float, scaleY: Float) = RoundRect(
        cx - half - rad, cy - rad * scaleY, cx + half + rad, cy + rad * scaleY, CornerRadius(rad, rad * scaleY),
    )

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, dark: Boolean, accent: Color) = with(scope) {
        if (!ready) return@with
        val sep = abs(a.x - b.x)
        val thin = min(sep / (2 * (q + r)).coerceAtLeast(1f), 1f)
        val head = if (lead == 0) a else b
        val tail = if (lead == 0) b else a
        // One outline for the whole body of water (head, tail, the neck between, droplets).
        path.reset()
        path.addRoundRect(capsule(head.x, cy, q * (1f - 0.25f * thin), r * (1f - 0.12f * thin), sy))
        if (sep > 1f) {
            tmp.reset(); tmp.addRoundRect(capsule(tail.x, cy, q * (1f - 0.8f * thin), r * (1f - 0.5f * thin), sy))
            path.op(path, tmp, PathOperation.Union)
            val neck = r * (1f - 0.75f * thin) * sy
            if (neck > 2f) {
                tmp.reset()
                tmp.addRoundRect(RoundRect(min(a.x, b.x), cy - neck, max(a.x, b.x), cy + neck, CornerRadius(neck, neck)))
                path.op(path, tmp, PathOperation.Union)
            }
        }
        for (d in drops) { tmp.reset(); tmp.addOval(androidx.compose.ui.geometry.Rect(Offset(d.x, d.y), d.r)); path.op(path, tmp, PathOperation.Union) }

        val top = cy - r
        val bottom = cy + r
        // Soft shadow under the water, then the body, a rim of light, a hint of the accent in the depth.
        translate(0f, 2.5f) { drawPath(path, Color.Black.copy(alpha = if (dark) 0.22f else 0.08f)) }
        drawPath(
            path,
            Brush.verticalGradient(
                if (dark) listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.07f))
                else listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.55f)),
                top, bottom,
            ),
        )
        drawPath(path, Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = if (dark) 0.16f else 0.10f)), cy, bottom))
        drawPath(
            path,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.55f else 0.95f), Color.White.copy(alpha = 0.05f)), top, bottom),
            style = Stroke(1.dp.toPx()),
        )
        // Inside the water only: the glint along its top and the ripples spreading through it.
        clipPath(path) {
            val glintW = (q + r) * 0.9f
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color.White.copy(alpha = if (dark) 0.35f else 0.8f), Color.Transparent), head.x - glintW, head.x + glintW * 0.4f),
                topLeft = Offset(head.x - glintW, top + r * 0.18f), size = Size(glintW * 1.3f, r * 0.22f), cornerRadius = CornerRadius(r * 0.11f),
            )
            for (rp in ripples) {
                val t = rp.age / 1.2f
                val rad = r * (0.4f + 2.2f * t)
                val alpha = (1f - t) * (1f - t) * 0.28f * rp.amp
                if (alpha > 0.01f) drawCircle(
                    Color.White.copy(alpha = alpha.coerceAtMost(1f)), rad, Offset(rp.x, rp.y),
                    style = Stroke(2.dp.toPx() * (1f - t * 0.5f)),
                )
            }
        }
    }
}
