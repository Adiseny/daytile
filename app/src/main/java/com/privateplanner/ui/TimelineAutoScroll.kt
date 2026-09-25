package com.privateplanner.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val AutoScrollReferenceFrameMillis = 16.666667f
// The zones are measured from what can be seen: the heading's bottom edge and the top of
// the navigation bar. Each reaches full speed at its edge, so the finger never has to
// cover the heading or the bar to get there, and both follow the heading's size (font
// scale, status bar) and the navigation mode.
private val AutoScrollTopReach = 88.dp
private const val AutoScrollBottomFraction = 0.20f
private val AutoScrollTopMaxStep = 16.dp
private val AutoScrollBottomMaxStep = 42.dp

internal fun CoroutineScope.launchEdgeAutoScroll(
    pointerViewportY: () -> Float,
    visibleTopPx: Int,
    visibleBottomPx: Int,
    scrollState: ScrollState,
    density: Density,
    enabled: () -> Boolean = { true },
    onScrolled: () -> Unit
): Job {
    return launch {
        val topReachPx = with(density) { AutoScrollTopReach.toPx() }
        val bottomReachPx = (visibleBottomPx - visibleTopPx) * AutoScrollBottomFraction
        val topMaxStepPx = with(density) { AutoScrollTopMaxStep.toPx() }
        val bottomMaxStepPx = with(density) { AutoScrollBottomMaxStep.toPx() }
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val previousFrameNanos = lastFrameNanos
                lastFrameNanos = frameNanos
                if (previousFrameNanos == 0L || !enabled()) return@withFrameNanos

                val frameMillis = (frameNanos - previousFrameNanos) / 1_000_000f
                val baseDelta = TimelineGeometry.edgeAutoScrollDelta(
                    pointerViewportY = pointerViewportY(),
                    visibleTopPx = visibleTopPx.toFloat(),
                    visibleBottomPx = visibleBottomPx.toFloat(),
                    topReachPx = topReachPx,
                    bottomReachPx = bottomReachPx,
                    topMaxStepPx = topMaxStepPx,
                    bottomMaxStepPx = bottomMaxStepPx
                )
                val scrollDelta = baseDelta * (frameMillis / AutoScrollReferenceFrameMillis)
                if (scrollDelta != 0f) {
                    if (scrollState.dispatchRawDelta(scrollDelta) != 0f) {
                        onScrolled()
                    }
                }
            }
        }
    }
}
