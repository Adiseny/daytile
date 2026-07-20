package com.privateplanner.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlapPolicyTest {
    @Test
    fun savedPlacementAllowsSevenButNotEightOverlaps() {
        val blocks = (1L..6L).map { id -> block(id, 9 * 60, 60) }
        val seventh = block(7, 9 * 60, 60)
        val eighth = block(8, 9 * 60, 60)

        assertTrue(OverlapPolicy.from(blocks, seventh.id).canPlace(seventh.startMinutes, seventh.durationMinutes))
        assertFalse(
            OverlapPolicy.from(blocks + seventh, eighth.id)
                .canPlace(eighth.startMinutes, eighth.durationMinutes)
        )
    }

    @Test
    fun createDurationCanShrinkBeforeSaturatedRange() {
        val blocks = (1L..7L).map { id -> block(id, 9 * 60 + 30, 30) }
        val candidate = block(8, 9 * 60, 60)

        assertEquals(
            30,
            OverlapPolicy.from(blocks, candidate.id).largestValidDuration(
                startMinutes = candidate.startMinutes,
                preferredDurationMinutes = 60
            )
        )
    }

    @Test
    fun largestValidDurationAllowsLengtheningThroughOrdinaryOverlaps() {
        val blocks = (1L..5L).map { id -> block(id, 9 * 60 + 30, 30) }
        val candidate = block(8, 9 * 60, 60)

        assertEquals(
            60,
            OverlapPolicy.from(blocks, candidate.id).largestValidDuration(
                startMinutes = candidate.startMinutes,
                preferredDurationMinutes = 60
            )
        )
    }

    @Test
    fun largestValidDurationReturnsNullWhenNoMinimumDurationCanFit() {
        val blocks = (1L..7L).map { id -> block(id, 9 * 60, 60) }
        val candidate = block(8, 9 * 60, 60)

        assertEquals(
            null,
            OverlapPolicy.from(blocks, candidate.id).largestValidDuration(
                startMinutes = candidate.startMinutes,
                preferredDurationMinutes = 60
            )
        )
    }

    private fun block(id: Long, start: Int, duration: Int): PlannerBlock {
        return PlannerBlock(
            id = id,
            date = LocalDate.of(2026, 5, 30),
            title = id.toString(),
            startMinutes = start,
            durationMinutes = duration
        )
    }
}
