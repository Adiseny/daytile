package com.privateplanner.ui

import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.PlannerBlock

internal data class TimelineBlockBounds(
    val left: Float,
    val right: Float,
    val visualTop: Float,
    val visualHeight: Float,
    val touchTop: Float,
    val touchBottom: Float
)

internal object TimelineGeometry {
    fun blockBounds(
        block: PlannerBlock,
        layout: BlockLayout,
        timelineWidthPx: Float,
        gutterPx: Float,
        hourHeightPx: Float,
        minimumTouchTargetPx: Float
    ): TimelineBlockBounds {
        val blockAreaWidth = (timelineWidthPx - gutterPx - 10f).coerceAtLeast(1f)
        val columnCount = layout.columnCount.coerceAtLeast(1)
        val columnWidth = blockAreaWidth / columnCount
        val left = gutterPx + columnWidth * layout.columnIndex
        val right = left + columnWidth
        val visualTop = block.startMinutes / 60f * hourHeightPx
        val visualHeight = block.durationMinutes / 60f * hourHeightPx
        val touchHeight = maxOf(visualHeight, minimumTouchTargetPx)
        val dayHeight = 24f * hourHeightPx
        val touchTop = (visualTop - (touchHeight - visualHeight) / 2f)
            .coerceIn(0f, (dayHeight - touchHeight).coerceAtLeast(0f))
        return TimelineBlockBounds(
            left = left,
            right = right,
            visualTop = visualTop,
            visualHeight = visualHeight,
            touchTop = touchTop,
            touchBottom = touchTop + touchHeight
        )
    }

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
        return blocks.any { block ->
            val bounds = blockBounds(
                block = block,
                layout = layoutById[block.id] ?: BlockLayout(0, 1),
                timelineWidthPx = timelineWidthPx,
                gutterPx = gutterPx,
                hourHeightPx = hourHeightPx,
                minimumTouchTargetPx = minimumTouchTargetPx
            )
            x in bounds.left..bounds.right && y in bounds.touchTop..bounds.touchBottom
        }
    }

    fun resizeLaneWidth(
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
