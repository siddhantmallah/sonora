package com.sonora.player.ui.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.sonora.player.container
import com.sonora.player.playback.Artwork as ArtLoader
import com.sonora.player.playback.SpectrumAnalyzer
import com.sonora.player.ui.components.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One analyzer + a frame counter shared by everything animated on the player screen. */
@Stable
class LiveAudio(val analyzer: SpectrumAnalyzer, val frame: MutableLongState)

@Composable
fun rememberLiveAudio(playing: Boolean): LiveAudio {
    val context = LocalContext.current
    val live = remember { LiveAudio(SpectrumAnalyzer(context.container.audioTap), mutableLongStateOf(0L)) }
    val conn = context.container.playerConnection
    // Analyse + redraw every frame (up to 120 fps) so the visuals keep up with the music.
    // While paused it runs one more second (so bars and pulses fall to rest) and then stops, instead of waking
    // the screen up every frame for nothing.
    LaunchedEffect(live, playing) {
        var last = 0L
        val stopAt = if (playing) Long.MAX_VALUE else System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < stopAt) {
            withFrameNanos { t ->
                if (t - last >= 7_000_000L) {
                    last = t
                    live.analyzer.update(conn.positionMs)
                    live.frame.longValue = t
                }
            }
        }
    }
    return live
}

/** Four colours pulled from the artwork (vibrant → muted), with sensible fallbacks. */
@Composable
fun rememberArtPalette(uri: Uri?, fallback: List<Color>): State<List<Color>> {
    val context = LocalContext.current
    return produceState(fallback, uri) {
        value = withContext(Dispatchers.Default) {
            val bmp = ArtLoader.load(context, uri, 160) ?: return@withContext fallback
            val p = Palette.from(bmp).maximumColorCount(16).generate()
            val picks = listOfNotNull(
                p.vibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch, p.lightVibrantSwatch, p.darkMutedSwatch, p.dominantSwatch,
            ).map { Color(it.rgb) }.distinct()
            if (picks.isEmpty()) fallback else List(4) { picks[it % picks.size] }
        }
    }
}

/** Slowly drifting colour fields from the artwork that swell with the bass, Apple-Music style. */
@Composable
fun FlowingBackground(colors: List<Color>, live: LiveAudio, modifier: Modifier = Modifier) {
    val animated = List(4) { i -> animateColorAsState(colors.getOrElse(i) { colors.first() }, tween(1200), label = "bg$i") }
    val t = rememberInfiniteTransition(label = "flow")
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(36_000, easing = LinearEasing)), label = "phase")
    // Soft gradients look identical at quarter resolution: render small into an offscreen layer and scale up,
    // which cuts the per-frame fill work ~16x.
    Box(modifier.fillMaxSize().background(Color(0xFF0B0B10))) {
    Canvas(
        Modifier.fillMaxSize(0.25f).graphicsLayer {
            scaleX = 4f; scaleY = 4f
            transformOrigin = TransformOrigin(0f, 0f)
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        live.frame.longValue
        val bass = live.analyzer.bass
        drawRect(Color(0xFF0B0B10))
        val w = size.width
        val h = size.height
        for (i in 0 until 4) {
            val c = animated[i].value
            val sp = 1f + i * 0.35f
            val center = Offset(
                w * (0.5f + 0.42f * cos(phase * sp + i * 1.9f)),
                h * (0.42f + 0.32f * sin(phase * (0.7f + i * 0.2f) + i * 1.3f)),
            )
            val r = max(w, h) * (0.48f + i * 0.06f) * (1f + bass * 0.18f)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.85f), c.copy(alpha = 0.25f), Color.Transparent), center, r), r, center)
        }
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.72f))))
    }
    }
}

/** Device tilt in degrees (x = left/right, y = forward/back), low-pass filtered. */
@Composable
fun rememberTilt(enabled: Boolean): State<Offset> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(enabled) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var base: Offset? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val raw = Offset(-e.values[0] / 9.81f * 14f, (e.values[1] / 9.81f) * 14f)
                val b = base ?: raw.also { base = it }
                // Re-centre slowly so holding the phone at any angle feels neutral.
                base = b + (raw - b) * 0.01f
                val target = Offset((raw.x - b.x).coerceIn(-10f, 10f), (raw.y - b.y).coerceIn(-10f, 10f))
                state.value = state.value + (target - state.value) * 0.05f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (enabled && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

/**
 * "Live" cover: the artwork slowly pans and zooms inside its frame, tilts with the phone (parallax + moving
 * glare), and pulses on beats. Shrinks a little while paused.
 */
@Composable
fun LiveArtwork(uri: Uri?, playing: Boolean, live: LiveAudio, active: Boolean, modifier: Modifier = Modifier) {
    val tilt by rememberTilt(active)
    val t = rememberInfiniteTransition(label = "kenburns")
    val dx by t.animateFloat(0f, 1f, infiniteRepeatable(tween(17_000, easing = LinearEasing), RepeatMode.Reverse), label = "dx")
    val dy by t.animateFloat(0f, 1f, infiniteRepeatable(tween(23_000, easing = LinearEasing), RepeatMode.Reverse), label = "dy")
    val zoom by t.animateFloat(0f, 1f, infiniteRepeatable(tween(29_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")
    val playScale by animateFloatAsState(if (playing) 1f else 0.86f, tween(450), label = "playScale")
    val shape = RoundedCornerShape(26.dp)

    Box(
        modifier.graphicsLayer {
            live.frame.longValue
            val s = playScale * (1f + live.analyzer.beat * 0.03f)
            scaleX = s; scaleY = s
            rotationY = tilt.x * 0.9f
            rotationX = -tilt.y * 0.9f
            cameraDistance = 14f * density
            // Fixed shadow: animating elevation re-renders the shadow every frame.
            shadowElevation = 18f * density
            this.shape = shape
            clip = true
        },
    ) {
        Artwork(
            uri,
            Modifier.fillMaxSize().graphicsLayer {
                val k = 1.12f + zoom * 0.06f
                scaleX = k; scaleY = k
                translationX = (dx - 0.5f) * size.width * 0.07f - tilt.x * 2.2f * density
                translationY = (dy - 0.5f) * size.height * 0.07f + tilt.y * 2.2f * density
            },
            RectangleShape,
        )
        // Moving glare that follows the tilt.
        Canvas(Modifier.fillMaxSize()) {
            val gx = size.width * (0.5f + tilt.x / 20f)
            val gy = size.height * (0.15f - tilt.y / 25f)
            val r = size.maxDimension * 0.9f
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), Offset(gx, gy), r))
        }
    }
}

/**
 * Full-screen "live" artwork: the cover runs edge-to-edge from the top of the screen, drifting and slowly zooming
 * (Ken Burns), tilting with the phone and pulsing on the beat. It melts into a blurred copy of itself and the
 * album's base colour, so there's no hard edge anywhere. Motion settles down while paused.
 */
@Composable
fun LiveBackdrop(uri: Uri?, playing: Boolean, live: LiveAudio, tiltActive: Boolean, base: Color, modifier: Modifier = Modifier, showCover: Boolean = true) {
    Box(modifier.fillMaxSize()) {
        // 1) Blurred, oversized copy fills the whole screen with the artwork's colours.
        //    Blurred at 1/8 size and scaled up: it looks the same (it's a blur), but the GPU blurs 64x fewer pixels
        //    whenever the player redraws.
        if (uri != null) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val k = 8f
            coil3.compose.AsyncImage(
                model = uri, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.requiredSize(maxWidth / k, maxHeight / k)
                    .graphicsLayer { scaleX = 1.6f * k; scaleY = 1.6f * k; alpha = 0.55f }
                    .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(70.dp / k) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(base.copy(alpha = 0.35f), base.copy(alpha = 0.75f), base))))

        // 2) The sharp, moving cover, faded out at its bottom edge so it melts into the backdrop.
        if (showCover) Box(
            Modifier.fillMaxWidth().aspectRatio(0.92f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.62f to Color.Black, 1f to Color.Transparent),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                },
        ) {
            // Completely still artwork.
            Artwork(uri, Modifier.fillMaxSize(), RectangleShape)
        }
        // 3) Darken the very top so the status bar and page tabs stay readable.
        Box(Modifier.fillMaxWidth().aspectRatio(2.6f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
    }
}

enum class VisualizerMode(val label: String) { BARS("Bars"), RADIAL("Halo"), WAVE("Waves"), MOUNTAINS("Skyline") }

@Composable
fun MusicVisualizer(live: LiveAudio, colors: List<Color>, mode: VisualizerMode, modifier: Modifier = Modifier) {
    val c = List(4) { i -> animateColorAsState(colors.getOrElse(i) { Color.White }, tween(800), label = "vc$i").value }
    val t = rememberInfiniteTransition(label = "vis")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "spin")
    Canvas(modifier.fillMaxSize()) {
        live.frame.longValue
        val a = live.analyzer
        when (mode) {
            VisualizerMode.BARS -> drawBars(a, c)
            VisualizerMode.RADIAL -> drawHalo(a, c, spin)
            VisualizerMode.WAVE -> drawWaves(a, c)
            VisualizerMode.MOUNTAINS -> drawMountains(a, c)
        }
    }
}

private fun light(c: Color) = Color(
    red = min(1f, c.red * 0.5f + 0.5f), green = min(1f, c.green * 0.5f + 0.5f), blue = min(1f, c.blue * 0.5f + 0.5f),
)

private fun DrawScope.drawBars(a: SpectrumAnalyzer, c: List<Color>) {
    val n = 48
    val gap = size.width / n
    val barW = gap * 0.62f
    val mid = size.height / 2
    val brush = Brush.horizontalGradient(listOf(light(c[0]), light(c[3]), light(c[1]), Color.White))
    for (i in 0 until n) {
        val v = a.bands[i * a.bandCount / n]
        val h = max(barW, v * size.height * 0.44f)
        val x = i * gap + (gap - barW) / 2
        drawRoundRect(brush, Offset(x, mid - h), Size(barW, h * 2), CornerRadius(barW / 2))
        // Soft reflection glow.
        drawRoundRect(brush, Offset(x, mid - h * 1.08f), Size(barW, h * 2.16f), CornerRadius(barW / 2), alpha = 0.18f)
    }
}

private fun DrawScope.drawHalo(a: SpectrumAnalyzer, c: List<Color>, spin: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val base = min(size.width, size.height) * 0.24f * (1f + a.bass * 0.12f)
    val n = 120
    drawCircle(Brush.radialGradient(listOf(c[0].copy(alpha = 0.55f), Color.Transparent), center, base * 2.4f), base * 2.4f, center)
    for (i in 0 until n) {
        val band = a.bands[(if (i < n / 2) i else n - 1 - i) * a.bandCount / (n / 2) % a.bandCount]
        val ang = Math.toRadians((i * 360.0 / n) + spin).toFloat()
        val len = 6f + band * base * 1.25f
        val start = Offset(center.x + cos(ang) * base, center.y + sin(ang) * base)
        val end = Offset(center.x + cos(ang) * (base + len), center.y + sin(ang) * (base + len))
        drawLine(if (i % 2 == 0) light(c[0]) else light(c[3]), start, end, strokeWidth = 5f, cap = StrokeCap.Round)
    }
    drawCircle(Brush.linearGradient(listOf(c[1], c[2])), base * 0.92f, center)
    drawCircle(Color.White.copy(alpha = 0.85f), base * 0.92f, center, style = Stroke(width = 3f + a.beat * 8f))
}

private fun DrawScope.drawWaves(a: SpectrumAnalyzer, c: List<Color>) {
    val mid = size.height / 2
    val w = a.wave
    for (layer in 0 until 3) {
        val path = Path()
        val amp = size.height * (0.32f - layer * 0.08f) * (1f + a.bass * 0.6f)
        val offset = layer * 24
        for (i in w.indices) {
            val x = size.width * i / (w.size - 1)
            val y = mid + w[(i + offset) % w.size] * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, light(c[layer]), alpha = 1f - layer * 0.28f, style = Stroke(width = 6f - layer * 1.5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawMountains(a: SpectrumAnalyzer, c: List<Color>) {
    for (layer in 0 until 3) {
        val path = Path()
        val base = size.height * (0.92f - layer * 0.12f)
        val scale = size.height * (0.55f - layer * 0.1f)
        path.moveTo(0f, size.height)
        val n = a.bandCount
        for (i in 0 until n) {
            val idx = (i + layer * 5) % n
            val x = size.width * i / (n - 1)
            val y = base - a.bands[idx] * scale
            path.lineTo(x, y)
        }
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(path, Brush.verticalGradient(listOf(light(c[layer]).copy(alpha = 0.9f), c[layer].copy(alpha = 0.15f))), alpha = 0.55f + layer * 0.15f)
    }
}
