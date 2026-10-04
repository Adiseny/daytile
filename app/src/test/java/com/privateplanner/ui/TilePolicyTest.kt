package com.privateplanner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun durationDisplayUsesConcreteWidthThresholdsAndReserveCap() {
        assertFalse(
            durationReserveDp(
                tileWidthDp = 111f,
                durationText = "15m",
                compact = true,
                durationFontSizeSp = 11f,
                fontScale = 1f
            ) > 0f
        )

        val compactReserve = durationReserveDp(
            tileWidthDp = 112f,
            durationText = "15m",
            compact = true,
            durationFontSizeSp = 11f,
            fontScale = 1f
        )
        assertTrue(compactReserve > 0f)
        assertTrue(compactReserve <= 112f * 0.34f)
        assertTrue(112f - compactReserve >= 56f)

        assertFalse(
            durationReserveDp(
                tileWidthDp = 112f,
                durationText = "12h 55m",
                compact = false,
                durationFontSizeSp = 12f,
                fontScale = 1f
            ) > 0f
        )
    }
}
