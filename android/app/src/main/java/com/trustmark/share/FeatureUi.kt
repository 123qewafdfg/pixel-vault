package com.trustmark.share

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal val LocalReorderInProgress = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
internal fun ReorderableMediaRow(id: String, enabled: Boolean, onMove: (Int) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val move by rememberUpdatedState(onMove)
    val activeCallback = LocalReorderInProgress.current
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var active by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (active) activeCallback(false) } }
    Column(Modifier.fillMaxWidth()
        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .semantics {
            if (enabled) customActions = listOf(
                CustomAccessibilityAction("上移") { move(-1); true },
                CustomAccessibilityAction("下移") { move(1); true },
            )
        }
        .pointerInput(id, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val ended = withTimeoutOrNull(200L) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                    }
                }
                if (ended == null) {
                    active = true; activeCallback(true)
                    var offset = 0f
                    try {
                        drag(down.id) { change ->
                            offset += change.position.y - change.previousPosition.y
                            change.consume()
                            if (kotlin.math.abs(offset) >= threshold) { move(if (offset > 0) 1 else -1); offset = 0f }
                        }
                    } finally { active = false; activeCallback(false) }
                }
            }
        }, content = content)
}
