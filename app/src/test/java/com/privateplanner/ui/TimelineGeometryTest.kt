package com.privateplanner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineGeometryTest {
    @Test
    fun launchPositionKeepsNowVisibleAndClampsAtBothDayBoundaries() {
        assertEquals(0, initialTimelineScroll(0, 800, 120f, 100f, true))
        assertEquals(1284, initialTimelineScroll(720, 800, 120f, 100f, true))
        assertEquals(1540, initialTimelineScroll(720, 800, 120f, 100f, false))
        assertEquals(2180, initialTimelineScroll(1439, 800, 120f, 100f, true))
        assertEquals(0, initialTimelineScroll(720, 4000, 120f, 100f, true))
    }

    @Test
    fun currentTimeReplacesOnlyACollidingGridLabel() {
        assertEquals(12 * 60, hiddenGridLabelMinutes(11 * 60 + 53))
        assertEquals(11 * 60 + 30, hiddenGridLabelMinutes(11 * 60 + 39))
        assertNull(hiddenGridLabelMinutes(11 * 60 + 45))
        assertNull(hiddenGridLabelMinutes(null))
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

}
