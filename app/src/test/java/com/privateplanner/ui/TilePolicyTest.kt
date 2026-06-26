package com.privateplanner.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePolicyTest {
    @Test
    fun durationDisplayUsesConcreteWidthThresholdsAndReserveCap() {
        assertFalse(
            TilePolicy.durationDisplayDecision(
                tileWidthDp = DurationVisibleMinWidthDp - 1f,
                durationText = "15m",
                compact = true
            ).show
        )

        val compact = TilePolicy.durationDisplayDecision(
            tileWidthDp = DurationVisibleMinWidthDp,
            durationText = "15m",
            compact = true
        )
        assertTrue(compact.show)
        assertTrue(compact.reserveDp <= DurationVisibleMinWidthDp * DurationMaxReserveFraction)
        assertTrue(DurationVisibleMinWidthDp - compact.reserveDp >= DurationTitleRemainderMinDp)

        assertFalse(
            TilePolicy.durationDisplayDecision(
                tileWidthDp = DurationVisibleMinWidthDp,
                durationText = "12h 55m",
                compact = false
            ).show
        )
    }
}
