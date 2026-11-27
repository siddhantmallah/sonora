package com.sonora.player.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Minimal drag-to-reorder for fixed-height rows: drag a row's handle and drop it; the list is
 * updated on release. Rows must all be [itemHeight] tall.
 */
@Stable
class DragReorderState(private val itemHeightPx: Float, private val onMove: (Int, Int) -> Unit) {
    var dragging by mutableIntStateOf(-1)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    fun start(index: Int) { dragging = index; offset = 0f }
    fun drag(dy: Float) { offset += dy }
    fun end(count: Int) {
        if (dragging >= 0) {
            val target = (dragging + (offset / itemHeightPx).roundToInt()).coerceIn(0, count - 1)
            if (target != dragging) onMove(dragging, target)
        }
        dragging = -1
        offset = 0f
    }

    fun rowModifier(index: Int): Modifier =
        if (index == dragging) Modifier.zIndex(1f).graphicsLayer { translationY = offset; shadowElevation = 12f }
        else Modifier
}

@Composable
fun rememberDragReorder(itemHeight: Dp, onMove: (from: Int, to: Int) -> Unit): DragReorderState {
    val px = with(LocalDensity.current) { itemHeight.toPx() }
    val latest by rememberUpdatedState(onMove)
    return remember(px) { DragReorderState(px) { a, b -> latest(a, b) } }
}

fun Modifier.dragHandle(state: DragReorderState, index: Int, count: Int): Modifier =
    pointerInput(index, count) {
        detectDragGestures(
            onDragStart = { state.start(index) },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end(count) },
            onDragCancel = { state.end(count) },
        )
    }
