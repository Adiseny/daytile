package com.privateplanner.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePolicyTest {
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
