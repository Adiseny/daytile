package com.privateplanner.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlapLayoutCalculatorTest {
    @Test
    fun nonOverlappingBlocksUseFullWidth() {
        val layouts = OverlapLayoutCalculator.calculate(
            listOf(
                block(1, 8 * 60, 60),
                block(2, 9 * 60, 60)
            )
        )

        assertEquals(1, layouts.getValue(1).columnCount)
        assertEquals(1, layouts.getValue(2).columnCount)
    }

    @Test
    fun simultaneousOverlapsShareColumns() {
        val layouts = OverlapLayoutCalculator.calculate(
            listOf(
                block(1, 8 * 60, 60),
                block(2, 8 * 60 + 15, 60),
                block(3, 8 * 60 + 30, 60)
            )
        )

        assertEquals(3, layouts.getValue(1).columnCount)
        assertEquals(3, layouts.getValue(2).columnCount)
        assertEquals(3, layouts.getValue(3).columnCount)
    }

    private fun block(id: Long, start: Int, duration: Int): PlannerBlock {
        return PlannerBlock(
            id = id,
            date = LocalDate.of(2026, 5, 26),
            title = id.toString(),
            startMinutes = start,
            durationMinutes = duration
        )
    }
}
