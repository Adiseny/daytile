package com.privateplanner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePolicyTest {
    @Test
    fun longTitlePinsBelowMeasuredHeader() {
        val density = 2f

        for (headerBottomPx in listOf(180, 260)) {
            val offset = titleFollowOffsetPx(
                density = density,
                scrollPx = 1_000,
                blockTop = 200f,
                visualHeight = 600f,
                headerBottomPx = headerBottomPx
            )
            val titleTopPx = (TimelineTopClearance * density + 200f * density - 1_000 + 8f * density + offset).toInt()

            assertEquals(headerBottomPx + 12, titleTopPx)
        }
    }

    @Test
    fun longTitlePinWaitsForHeaderMeasurementAndStopsBeforeTileEnd() {
        val density = 2f

        val beforeMeasurement = titleFollowOffsetPx(
            density = density,
            scrollPx = 1_000,
            blockTop = 200f,
            visualHeight = 100f,
            headerBottomPx = 0
        )
        val clamped = titleFollowOffsetPx(
            density = density,
            scrollPx = 2_000,
            blockTop = 200f,
            visualHeight = 100f,
            headerBottomPx = 260
        )

        assertEquals(0, beforeMeasurement)
        assertEquals(88, clamped)
    }

    @Test
    fun durationColumnUsesConcreteWidthThresholdAndKeepsRoomForTheTitle() {
        assertEquals(0f, durationReserveDp(111f, 1f), 0f)
        for (scale in listOf(0.85f, 1f, 1.3f, 2f)) for (width in listOf(112f, 180f, 240f, 320f, 800f)) {
            val reserve = durationReserveDp(width, scale)
            assertTrue(reserve > 0f)
            assertTrue(reserve <= width * 0.34f)
            assertTrue(width - reserve >= 56f)
        }
        assertEquals(durationReserveDp(320f, 1f), durationReserveDp(800f, 1f), 0f)
    }
}
