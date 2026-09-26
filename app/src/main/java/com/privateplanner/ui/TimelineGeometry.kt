package com.privateplanner.ui

import androidx.collection.LongObjectMap
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.PlannerBlock

internal object TimelineGeometry {
    fun hitTestBlock(
        x: Float,
        y: Float,
        blocks: List<PlannerBlock>,
        layoutById: LongObjectMap<BlockLayout>,
        timelineWidthPx: Float,
        gutterPx: Float,
        timelineEndPaddingPx: Float,
        blockColumnGapPx: Float,
        hourHeightPx: Float,
        minimumTouchTargetPx: Float
    ): Boolean {
        if (x < gutterPx) return false
        val blockAreaWidth = (timelineWidthPx - gutterPx - timelineEndPaddingPx).coerceAtLeast(1f)
        val dayHeight = 24f * hourHeightPx
        return blocks.any { block ->
            val visualTop = block.startMinutes / 60f * hourHeightPx
            val visualHeight = block.durationMinutes / 60f * hourHeightPx
            val touchHeight = maxOf(visualHeight, minimumTouchTargetPx)
            val touchTop = (visualTop - (touchHeight - visualHeight) / 2f)
                .coerceIn(0f, (dayHeight - touchHeight).coerceAtLeast(0f))
            // Only tiles at the tapped time need a column lookup and horizontal geometry.
            if (y !in touchTop..(touchTop + touchHeight)) return@any false
            val layout = layoutById[block.id]!!
            val columnWidth = blockAreaWidth / layout.columnCount.coerceAtLeast(1)
            val left = gutterPx + columnWidth * layout.columnIndex
            val blockWidth = (columnWidth - blockColumnGapPx).coerceAtLeast(minimumTouchTargetPx)
            x in left..(left + blockWidth)
        }
    }

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
                val strength = ((topStart - pointerViewportY) / topReachPx).coerceIn(0f, 1f)
                -topMaxStepPx * strength * strength
            }
            pointerViewportY > bottomStart -> {
                val strength = ((pointerViewportY - bottomStart) / bottomReachPx).coerceIn(0f, 1f)
                val easedStrength = strength * strength * (3f - 2f * strength)
                bottomMaxStepPx * easedStrength
            }
            else -> 0f
        }
    }
}
