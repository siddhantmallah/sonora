package com.sonora.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

fun indexLetter(s: String): Char {
    val c = s.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

/**
 * A–Z rail on the right edge: tap or drag to jump. [firstIndex] maps a letter to the LazyColumn item index.
 * Call inside a Box that also holds the list.
 */
@Composable
fun BoxScope.LetterRail(letters: List<Char>, state: LazyListState, firstIndex: (Char) -> Int?) {
    if (letters.size < 4) return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var active by remember { mutableStateOf<Char?>(null) }

    fun pick(y: Float, height: Int) {
        val letter = letters[((y / height) * letters.size).toInt().coerceIn(0, letters.lastIndex)]
        if (letter != active) {
            active = letter
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            firstIndex(letter)?.let { scope.launch { state.scrollToItem(it) } }
        }
    }

    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.82f).width(26.dp)
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { pick(it.y, size.height) },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                ) { change, _ -> change.consume(); pick(change.position.y, size.height) }
            }
            .pointerInput(letters) { detectTapGestures(onPress = { pick(it.y, size.height); tryAwaitRelease(); active = null }) },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { l ->
            Text(
                l.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = if (l == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
    active?.let { l ->
        Box(
            Modifier.align(Alignment.Center).size(88.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(l.toString(), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
