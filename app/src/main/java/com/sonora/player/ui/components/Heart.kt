package com.sonora.player.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val HeartRed = Color(0xFFFF2D55)

private const val HEART_PATH =
    "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"

/** Heart icon that is outlined when off and solid when on. */
private fun heartVector(filled: Boolean): ImageVector = ImageVector.Builder(
    name = if (filled) "HeartFilled" else "HeartOutline",
    defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).apply {
    addPath(
        pathData = addPathNodes(HEART_PATH),
        fill = if (filled) SolidColor(Color.Black) else null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()

private val HeartFilled = heartVector(true)
private val HeartOutline = heartVector(false)

/** Favourite heart: turns solid red with a little "pop" when liked. */
@Composable
fun FavoriteHeart(liked: Boolean, modifier: Modifier = Modifier, size: Dp = 26.dp, offTint: Color = Color.White) {
    val color by animateColorAsState(if (liked) HeartRed else offTint, label = "heartColor")
    val scale by animateFloatAsState(
        targetValue = if (liked) 1f else 0.92f,
        animationSpec = if (liked) keyframes { durationMillis = 380; 0.92f at 0; 1.35f at 140; 0.95f at 260; 1f at 380 } else spring(),
        label = "heartPop",
    )
    Icon(
        if (liked) HeartFilled else HeartOutline,
        contentDescription = if (liked) "Remove from favorites" else "Add to favorites",
        tint = color,
        modifier = modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale },
    )
}

/** Small solid heart for list rows. */
@Composable
fun SmallHeart(modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Icon(HeartFilled, null, tint = HeartRed, modifier = modifier.size(size))
}

/** "Made with ♥ by Mr.Blanko" credit with a gently beating heart. */
@Composable
fun MadeWithLove(modifier: Modifier = Modifier) {
    // The heart only beats while it's actually on screen; scrolled away, it rests (no redraws for nothing).
    var visible by remember { androidx.compose.runtime.mutableStateOf(false) }
    val beat = if (visible) {
        rememberInfiniteTransition(label = "beat").animateFloat(
            initialValue = 1f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 1200; 1f at 0; 1.25f at 120 using LinearEasing; 1f at 260; 1.18f at 380; 1f at 560 },
                RepeatMode.Restart,
            ),
            label = "beatScale",
        ).value
    } else 1f
    Row(
        modifier.fillMaxWidth()
            .onGloballyPositioned { c -> visible = c.boundsInWindow().height > 0f }
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Made with", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Icon(
            remember { HeartFilled }, contentDescription = "love", tint = HeartRed,
            modifier = Modifier.size(16.dp).graphicsLayer { scaleX = beat; scaleY = beat },
        )
        Spacer(Modifier.width(6.dp))
        Text("by ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Mr.Blanko", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}
