package com.privateplanner.ui

import androidx.collection.longObjectMapOf
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGeometryTest {
    @Test
    fun currentTimeReplacesOnlyACollidingGridLabel() {
        assertEquals(12 * 60, hiddenGridLabelMinutes(11 * 60 + 53))
        assertEquals(11 * 60 + 30, hiddenGridLabelMinutes(11 * 60 + 39))
        assertNull(hiddenGridLabelMinutes(11 * 60 + 45))
        assertNull(hiddenGridLabelMinutes(null))
    }

    @Test
    fun hitTestExpandsShortBlocksToMinimumTouchTarget() {
        val shortBlock = block(startMinutes = 9 * 60, durationMinutes = 5)
        val blocks = listOf(shortBlock)
        val layouts = longObjectMapOf(1L, BlockLayout(columnIndex = 0, columnCount = 1))
        fun hit(y: Float): Boolean {
            return TimelineGeometry.hitTestBlock(
                x = 100f,
                y = y,
                blocks = blocks,
                layoutById = layouts,
                timelineWidthPx = 400f,
                gutterPx = 72f,
                timelineEndPaddingPx = 10f,
                blockColumnGapPx = 4f,
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
        val layouts = longObjectMapOf(
            1L, BlockLayout(columnIndex = 0, columnCount = 2),
            2L, BlockLayout(columnIndex = 1, columnCount = 2)
        )

        assertTrue(
            TimelineGeometry.hitTestBlock(
                x = 300f,
                y = 9 * 120f,
                blocks = blocks,
                layoutById = layouts,
                timelineWidthPx = 400f,
                gutterPx = 72f,
                timelineEndPaddingPx = 10f,
                blockColumnGapPx = 4f,
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
                timelineEndPaddingPx = 10f,
                blockColumnGapPx = 4f,
                hourHeightPx = 120f,
                minimumTouchTargetPx = 48f
            )
        )
    }

    @Test
    fun hitTestLeavesTheVisualGapBetweenOverlapColumnsEmpty() {
        val blocks = listOf(
            block(id = 1, startMinutes = 9 * 60, durationMinutes = 30),
            block(id = 2, startMinutes = 9 * 60, durationMinutes = 30)
        )
        val layouts = longObjectMapOf(
            1L, BlockLayout(columnIndex = 0, columnCount = 2),
            2L, BlockLayout(columnIndex = 1, columnCount = 2)
        )

        assertFalse(
            TimelineGeometry.hitTestBlock(
                x = 229f,
                y = 9 * 120f,
                blocks = blocks,
                layoutById = layouts,
                timelineWidthPx = 400f,
                gutterPx = 72f,
                timelineEndPaddingPx = 10f,
                blockColumnGapPx = 4f,
                hourHeightPx = 120f,
                minimumTouchTargetPx = 48f
            )
        )
    }

    @Test
    fun hitTestMatchesTheMinimumWidthUsedByCrowdedTiles() {
        val target = block(startMinutes = 9 * 60, durationMinutes = 30)

        assertTrue(
            TimelineGeometry.hitTestBlock(
                x = 118f,
                y = 9 * 120f,
                blocks = listOf(target),
                layoutById = longObjectMapOf(1L, BlockLayout(columnIndex = 0, columnCount = 7)),
                timelineWidthPx = 400f,
                gutterPx = 72f,
                timelineEndPaddingPx = 10f,
                blockColumnGapPx = 4f,
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
        fun delta(y: Float) = TimelineGeometry.edgeAutoScrollDelta(
            pointerViewportY = y,
            visibleTopPx = 100f,
            visibleBottomPx = 800f,
            topReachPx = 88f,
            bottomReachPx = 140f,
            topMaxStepPx = 16f,
            bottomMaxStepPx = 42f
        )
        assertEquals(0f, delta(400f), 0.001f)
        assertEquals(0f, delta(188f), 0.001f)
        assertEquals(0f, delta(660f), 0.001f)
        assertTrue(delta(150f) < 0f)
        assertTrue(delta(700f) > 0f)
    }

    @Test
    fun edgeAutoScrollReachesFullSpeedAtTheVisibleEdges() {
        fun delta(y: Float) = TimelineGeometry.edgeAutoScrollDelta(
            pointerViewportY = y,
            visibleTopPx = 100f,
            visibleBottomPx = 800f,
            topReachPx = 88f,
            bottomReachPx = 140f,
            topMaxStepPx = 16f,
            bottomMaxStepPx = 42f
        )
        assertEquals(-16f, delta(100f), 0.001f)
        assertEquals(-16f, delta(20f), 0.001f)
        assertEquals(42f, delta(800f), 0.001f)
        assertEquals(42f, delta(900f), 0.001f)
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
