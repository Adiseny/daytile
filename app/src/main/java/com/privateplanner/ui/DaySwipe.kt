package com.privateplanner.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal fun Modifier.axisLockedDaySwipe(
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
): Modifier {
    if (!enabled) return this
    return pointerInput(enabled, onPrevious, onNext) {
        val swipeThreshold = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchSlop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var horizontal = false
            var vertical = false

            while (true) {
                val event = awaitPointerEvent()
                val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                total += delta

                if (!horizontal && !vertical && total.getDistance() > touchSlop) {
                    if (abs(total.x) > abs(total.y)) {
                        horizontal = true
                    } else {
                        vertical = true
                    }
                }

                if (vertical) {
                    return@awaitEachGesture
                }

                if (horizontal) {
                    change.consume()
                }
            }

            if (horizontal && abs(total.x) >= swipeThreshold) {
                if (total.x > 0f) onPrevious() else onNext()
            }
        }
    }
}
