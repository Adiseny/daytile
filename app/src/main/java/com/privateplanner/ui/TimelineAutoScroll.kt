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
private val AutoScrollTopEdge = 112.dp
private val AutoScrollBottomEdgeMinimum = 240.dp
private val AutoScrollTopMaxStep = 16.dp
private val AutoScrollBottomMaxStep = 42.dp
private const val AutoScrollBottomEdgeFraction = 0.30f

internal fun CoroutineScope.launchEdgeAutoScroll(
    pointerViewportY: () -> Float,
    viewportHeightPx: Int,
    scrollState: ScrollState,
    density: Density,
    enabled: () -> Boolean = { true },
    onScrolled: () -> Unit
): Job {
    return launch {
        val topEdgePx = with(density) { AutoScrollTopEdge.toPx() }
        val bottomEdgeMinPx = with(density) { AutoScrollBottomEdgeMinimum.toPx() }
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
                    viewportHeightPx = viewportHeightPx,
                    topEdgePx = topEdgePx,
                    bottomEdgeMinPx = bottomEdgeMinPx,
                    bottomEdgeFraction = AutoScrollBottomEdgeFraction,
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
