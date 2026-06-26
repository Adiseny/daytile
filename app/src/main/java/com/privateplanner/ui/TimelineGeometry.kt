package com.privateplanner.ui

import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.PlannerBlock

internal object TimelineGeometry {
    fun hitTestBlock(
        x: Float,
        y: Float,
        blocks: List<PlannerBlock>,
        layoutById: Map<Long, BlockLayout>,
        timelineWidthPx: Float,
        gutterPx: Float,
        hourHeightPx: Float,
        minimumTouchTargetPx: Float
    ): Boolean {
        val blockAreaWidth = (timelineWidthPx - gutterPx - 10f).coerceAtLeast(1f)
        val dayHeight = 24f * hourHeightPx
        return blocks.any { block ->
            val layout = layoutById[block.id] ?: BlockLayout(0, 1)
            val columnWidth = blockAreaWidth / layout.columnCount.coerceAtLeast(1)
            val left = gutterPx + columnWidth * layout.columnIndex
            val right = left + columnWidth
            if (x !in left..right) return@any false

            val visualTop = block.startMinutes / 60f * hourHeightPx
            val visualHeight = block.durationMinutes / 60f * hourHeightPx
            val touchHeight = maxOf(visualHeight, minimumTouchTargetPx)
            val touchTop = (visualTop - (touchHeight - visualHeight) / 2f)
                .coerceIn(0f, (dayHeight - touchHeight).coerceAtLeast(0f))
            y in touchTop..(touchTop + touchHeight)
        }
    }

    private fun resizeLaneWidth(
        blockWidthPx: Float,
        laneFraction: Float,
        minimumTouchTargetPx: Float
    ): Float {
        return maxOf(blockWidthPx * laneFraction, minimumTouchTargetPx)
            .coerceAtMost(blockWidthPx)
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
        val laneWidth = resizeLaneWidth(blockWidthPx, laneFraction, minimumTouchTargetPx)
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
        viewportHeightPx: Int,
        topEdgePx: Float,
        bottomEdgeMinPx: Float,
        bottomEdgeFraction: Float,
        topMaxStepPx: Float,
        bottomMaxStepPx: Float
    ): Float {
        if (viewportHeightPx <= 0) return 0f
        val bottomEdge = maxOf(bottomEdgeMinPx, viewportHeightPx * bottomEdgeFraction)
        return when {
            pointerViewportY < topEdgePx -> {
                val strength = ((topEdgePx - pointerViewportY) / topEdgePx).coerceIn(0f, 1f)
                -topMaxStepPx * strength * strength
            }
            pointerViewportY > viewportHeightPx - bottomEdge -> {
                val strength = ((pointerViewportY - (viewportHeightPx - bottomEdge)) / bottomEdge)
                    .coerceIn(0f, 1f)
                val easedStrength = strength * strength * (3f - 2f * strength)
                bottomMaxStepPx * easedStrength
            }
            else -> 0f
        }
    }
}
