package com.privateplanner.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePolicyTest {
    @Test
    fun longTitlePinsBelowMeasuredHeader() {
        val density = Density(density = 2f)

        for (headerBottomPx in listOf(180, 260)) {
            val offset = with(density) {
                titleFollowOffsetPx(
                    scrollPx = 1_000,
                    blockTop = 200.dp,
                    visualHeight = 600.dp,
                    headerBottomPx = headerBottomPx
                )
            }
            val titleTopPx = with(density) {
                TimelineTopClearance.toPx() + 200.dp.toPx() - 1_000 + 8.dp.toPx() + offset
            }.toInt()

            assertEquals(headerBottomPx + 12, titleTopPx)
        }
    }

    @Test
    fun longTitlePinWaitsForHeaderMeasurementAndStopsBeforeTileEnd() {
        val density = Density(density = 2f)

        val beforeMeasurement = with(density) {
            titleFollowOffsetPx(
                scrollPx = 1_000,
                blockTop = 200.dp,
                visualHeight = 100.dp,
                headerBottomPx = 0
            )
        }
        val clamped = with(density) {
            titleFollowOffsetPx(
                scrollPx = 2_000,
                blockTop = 200.dp,
                visualHeight = 100.dp,
                headerBottomPx = 260
            )
        }

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
