package com.privateplanner.ui

import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGeometryTest {
    @Test
    fun hitTestExpandsShortBlocksToMinimumTouchTarget() {
        val shortBlock = block(startMinutes = 9 * 60, durationMinutes = 5)
        val blocks = listOf(shortBlock)
        val layouts = mapOf(1L to BlockLayout(columnIndex = 0, columnCount = 1))
        fun hit(y: Float): Boolean {
            return TimelineGeometry.hitTestBlock(
                x = 100f,
                y = y,
                blocks = blocks,
                layoutById = layouts,
                timelineWidthPx = 400f,
                gutterPx = 72f,
                hourHeightPx = 120f,
                minimumTouchTargetPx = 48f
            )
        }

        assertFalse(hit(9 * 120f - 20f))
        assertTrue(hit(9 * 120f - 18f))
        assertTrue(hit(9 * 120f + 28f))
        assertFalse(hit(9 * 120f + 30f))
    }

    @Test
    fun hitTestUsesOverlapColumnsAndMinimumTouchArea() {
        val target = block(id = 2, startMinutes = 9 * 60, durationMinutes = 5)
        val blocks = listOf(
            block(id = 1, startMinutes = 9 * 60, durationMinutes = 30),
            target
        )
        val layouts = mapOf(
            1L to BlockLayout(columnIndex = 0, columnCount = 2),
            2L to BlockLayout(columnIndex = 1, columnCount = 2)
        )

        assertTrue(
            TimelineGeometry.hitTestBlock(
                x = 300f,
                y = 9 * 120f,
                blocks = blocks,
                layoutById = layouts,
                timelineWidthPx = 400f,
                gutterPx = 72f,
                hourHeightPx = 120f,
                minimumTouchTargetPx = 48f
            )
        )
        assertFalse(
            TimelineGeometry.hitTestBlock(
                x = 50f,
                y = 9 * 120f,
                blocks = blocks,
                layoutById = layouts,
                timelineWidthPx = 400f,
                gutterPx = 72f,
                hourHeightPx = 120f,
                minimumTouchTargetPx = 48f
            )
        )
    }

    @Test
    fun resizeZoneUsesCentredLaneAndBottomAreaForLongBlocks() {
        assertTrue(
            TimelineGeometry.isInResizeZone(
                x = 50f,
                yInVisual = 105f,
                blockWidthPx = 100f,
                visualHeightPx = 120f,
                durationMinutes = 60,
                laneFraction = 0.24f,
                minimumTouchTargetPx = 48f,
                quickResizeMaxDurationMinutes = 30
            )
        )
        assertFalse(
            TimelineGeometry.isInResizeZone(
                x = 10f,
                yInVisual = 105f,
                blockWidthPx = 100f,
                visualHeightPx = 120f,
                durationMinutes = 60,
                laneFraction = 0.24f,
                minimumTouchTargetPx = 48f,
                quickResizeMaxDurationMinutes = 30
            )
        )
        assertFalse(
            TimelineGeometry.isInResizeZone(
                x = 50f,
                yInVisual = 20f,
                blockWidthPx = 100f,
                visualHeightPx = 120f,
                durationMinutes = 60,
                laneFraction = 0.24f,
                minimumTouchTargetPx = 48f,
                quickResizeMaxDurationMinutes = 30
            )
        )
    }

    @Test
    fun resizeHandleHitAreaIsSmallCentredAndNearVisibleBar() {
        assertTrue(
            TimelineGeometry.isInResizeHandle(
                x = 50f,
                yInVisual = 115f,
                blockWidthPx = 100f,
                visualHeightPx = 120f,
                handleHitWidthPx = 44f,
                handleHitHeightPx = 18f,
                handleBottomPaddingPx = 3f
            )
        )
        assertFalse(
            TimelineGeometry.isInResizeHandle(
                x = 20f,
                yInVisual = 115f,
                blockWidthPx = 100f,
                visualHeightPx = 120f,
                handleHitWidthPx = 44f,
                handleHitHeightPx = 18f,
                handleBottomPaddingPx = 3f
            )
        )
        assertFalse(
            TimelineGeometry.isInResizeHandle(
                x = 50f,
                yInVisual = 95f,
                blockWidthPx = 100f,
                visualHeightPx = 120f,
                handleHitWidthPx = 44f,
                handleHitHeightPx = 18f,
                handleBottomPaddingPx = 3f
            )
        )
    }

    @Test
    fun edgeAutoScrollIsZeroInMiddleAndDirectionalNearEdges() {
        assertEquals(
            0f,
            TimelineGeometry.edgeAutoScrollDelta(
                pointerViewportY = 300f,
                viewportHeightPx = 800,
                topEdgePx = 112f,
                bottomEdgeMinPx = 240f,
                bottomEdgeFraction = 0.30f,
                topMaxStepPx = 16f,
                bottomMaxStepPx = 42f
            ),
            0.001f
        )
        assertTrue(
            TimelineGeometry.edgeAutoScrollDelta(
                pointerViewportY = 20f,
                viewportHeightPx = 800,
                topEdgePx = 112f,
                bottomEdgeMinPx = 240f,
                bottomEdgeFraction = 0.30f,
                topMaxStepPx = 16f,
                bottomMaxStepPx = 42f
            ) < 0f
        )
        assertTrue(
            TimelineGeometry.edgeAutoScrollDelta(
                pointerViewportY = 780f,
                viewportHeightPx = 800,
                topEdgePx = 112f,
                bottomEdgeMinPx = 240f,
                bottomEdgeFraction = 0.30f,
                topMaxStepPx = 16f,
                bottomMaxStepPx = 42f
            ) > 0f
        )
    }

    private fun block(
        id: Long = 1,
        startMinutes: Int,
        durationMinutes: Int
    ): PlannerBlock {
        return PlannerBlock(
            id = id,
            date = LocalDate.of(2026, 5, 29),
            title = id.toString(),
            startMinutes = startMinutes,
            durationMinutes = durationMinutes
        )
    }
}
