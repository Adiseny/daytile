package com.privateplanner.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val AutoScrollReferenceFrameMillis = 16.666667f

internal fun CoroutineScope.launchEdgeAutoScroll(
    pointerViewportY: () -> Float,
    viewportHeightPx: Int,
    scrollState: ScrollState,
    enabled: () -> Boolean = { true },
    onScrolled: () -> Unit
): Job {
    return launch {
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
                    topEdgePx = 112f,
                    bottomEdgeMinPx = 240f,
                    bottomEdgeFraction = 0.30f,
                    topMaxStepPx = 16f,
                    bottomMaxStepPx = 42f
                )
                val scrollDelta = baseDelta * (frameMillis / AutoScrollReferenceFrameMillis)
                if (scrollDelta != 0f) {
                    scrollState.dispatchRawDelta(scrollDelta)
                    onScrolled()
                }
            }
        }
    }
}
