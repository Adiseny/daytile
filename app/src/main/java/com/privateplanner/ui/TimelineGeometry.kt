package com.privateplanner.ui

// Shared by the first layout's tile window and the measured launch position.
internal fun initialTimelineScroll(
    targetMinutes: Int,
    viewportHeightPx: Int,
    hourHeightPx: Float,
    topClearancePx: Float,
    isToday: Boolean
): Int {
    val targetPx = Math.round(topClearancePx + targetMinutes / 60f * hourHeightPx)
    val leadPx = if (isToday) Math.round(viewportHeightPx * CurrentTimeViewportFraction) else 0
    val maxScrollPx = Math.round(topClearancePx + 24f * hourHeightPx - viewportHeightPx).coerceAtLeast(0)
    return (targetPx - leadPx).coerceAtLeast(0).coerceAtMost(maxScrollPx)
}

internal object TimelineGeometry {
    fun isInResizeZone(
        x: Float,
        yInVisual: Float,
        blockWidthPx: Float,
        visualHeightPx: Float,
        durationMinutes: Int,
        laneFraction: Float,
        minimumTouchTargetPx: Float,
        quickResizeMaxDurationMinutes: Int
    ): Boolean {
        val laneWidth = maxOf(blockWidthPx * laneFraction, minimumTouchTargetPx)
            .coerceAtMost(blockWidthPx)
        val laneStart = (blockWidthPx - laneWidth) / 2f
        val inLane = x in laneStart..(laneStart + laneWidth)
        return inLane &&
            (durationMinutes <= quickResizeMaxDurationMinutes ||
                yInVisual >= visualHeightPx - minimumTouchTargetPx)
    }

    fun isInResizeHandle(
        x: Float,
        yInVisual: Float,
        blockWidthPx: Float,
        visualHeightPx: Float,
        handleHitWidthPx: Float,
        handleHitHeightPx: Float,
        handleBottomPaddingPx: Float
    ): Boolean {
        val hitWidth = handleHitWidthPx.coerceAtMost(blockWidthPx)
        val left = (blockWidthPx - hitWidth) / 2f
        val right = left + hitWidth
        val bottom = visualHeightPx - handleBottomPaddingPx
        val top = (bottom - handleHitHeightPx).coerceAtLeast(0f)
        return x in left..right && yInVisual in top..bottom
    }

    fun edgeAutoScrollDelta(
        pointerViewportY: Float,
        visibleTopPx: Float,
        visibleBottomPx: Float,
        topReachPx: Float,
        bottomReachPx: Float,
        topMaxStepPx: Float,
        bottomMaxStepPx: Float
    ): Float {
        if (visibleBottomPx <= visibleTopPx) return 0f
        val topStart = visibleTopPx + topReachPx
        val bottomStart = visibleBottomPx - bottomReachPx
        return when {
            pointerViewportY < topStart -> {
                val strength = ((topStart - pointerViewportY) / topReachPx).coerceAtLeast(0f).coerceAtMost(1f)
                -topMaxStepPx * strength * strength
            }
            pointerViewportY > bottomStart -> {
                val strength = ((pointerViewportY - bottomStart) / bottomReachPx).coerceAtLeast(0f).coerceAtMost(1f)
                val easedStrength = strength * strength * (3f - 2f * strength)
                bottomMaxStepPx * easedStrength
            }
            else -> 0f
        }
    }
}
