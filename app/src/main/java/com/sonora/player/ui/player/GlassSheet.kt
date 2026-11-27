package com.sonora.player.ui.player

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.launch

/** Frosted dark glass used for sheets on the (always dark) player screen. */
private val GlassTint = Color(0xFF15151B)

/**
 * A "liquid glass" bottom sheet drawn inside the player: it blurs the player behind it (artwork, lyrics…) through a
 * dark tint, with a light rim along the edge and a soft sheen at the top. Slides up on open, follows your finger when
 * dragged down, and closes on a flick, a tap outside, or Back. Content always uses the dark colour scheme.
 *
 * Must be placed inside a full-screen Box that is a sibling of the [haze] source.
 */
@Composable
fun BoxScope.GlassSheet(haze: HazeState, onDismiss: () -> Unit, scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(1f) } // 0 = open, 1 = fully below the screen (fraction of sheet height)
    val heightPx = remember { floatArrayOf(1f) }
    var closing by remember { androidx.compose.runtime.mutableStateOf(false) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            offset.animateTo(1f, tween(170, easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 1f, 1f)))
            onDismiss()
        }
    }
    // Quick and settled: up in about a fifth of a second, no wobble at the end.
    LaunchedEffect(Unit) { offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.002f)) }
    // Only while open: once closing, Back belongs to the screen underneath again.
    BackHandler(enabled = !closing) { close() }

    val style = remember {
        HazeBlurStyle {
            backgroundColor(GlassTint)
            colorEffects(listOf(HazeColorEffect.tint(GlassTint.copy(alpha = 0.58f))))
            blurRadius(36.dp)
            noiseFactor(0.06f)
        }
    }
    val shape = RoundedCornerShape(30.dp)

    // Scrim: dims the player; tap or drag it to dismiss.
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - offset.value }
            .background(Color.Black.copy(alpha = 0.32f))
            .pointerInput(Unit) { detectTapGestures { close() } }
            .pointerInput(Unit) { detectVerticalDragGestures { ch, _ -> ch.consume() } },
    )

    Column(
        Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            .onSizeChanged { heightPx[0] = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer { translationY = offset.value * (heightPx[0] + 48.dp.toPx()) }
            .clip(shape)
            .hazeBlur(HazeInput.Sources(haze), style)
            // Liquid sheen: a little light pooled along the top edge.
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.22f to Color.White.copy(alpha = 0.02f), 1f to Color.Transparent))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.12f))),
                shape,
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (offset.value > 0.22f) close() else offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.002f))
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { ch, dy ->
                    ch.consume()
                    scope.launch { offset.snapTo((offset.value + dy / heightPx[0]).coerceIn(0f, 1f)) }
                }
            },
    ) {
        // Grabber.
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.32f)))
        }
        MaterialTheme(colorScheme = com.sonora.player.ui.theme.DarkScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(
                    Modifier.heightIn(max = 640.dp)
                        .then(if (scrollable) Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier)
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                ) { content() }
            }
        }
    }
}

/** A grouped list surface on glass: a slightly lighter pane with hairline edges. */
fun Modifier.glassGroup(): Modifier = this
    .clip(RoundedCornerShape(18.dp))
    .background(Color.White.copy(alpha = 0.07f))
    .border(0.6.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))

/** Glass for controls sitting on the player: a translucent fill with a light rim, like the app's dock. */
fun Modifier.glassPill(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .clip(shape)
    .background(Color.White.copy(alpha = 0.12f))
    .border(0.8.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))), shape)
