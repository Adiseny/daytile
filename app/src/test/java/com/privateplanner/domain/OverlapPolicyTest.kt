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

        assertTrue(OverlapPolicy.canPlace(blocks, seventh))
        assertFalse(OverlapPolicy.canPlace(blocks + seventh, eighth))
    }

    @Test
    fun transientPlacementAllowsPassingOverAsEighthOverlap() {
        val blocks = (1L..7L).map { id -> block(id, 9 * 60, 60) }
        val moving = block(8, 9 * 60, 60)

        assertFalse(OverlapPolicy.canPlace(blocks, moving, OverlapPolicy.MaxSavedOverlap))
        assertTrue(OverlapPolicy.canPlace(blocks, moving, OverlapPolicy.MaxTransientOverlap))
    }

    @Test
    fun createDurationCanShrinkBeforeSaturatedRange() {
        val blocks = (1L..7L).map { id -> block(id, 9 * 60 + 30, 30) }
        val candidate = block(8, 9 * 60, 60)

        assertEquals(
            30,
            OverlapPolicy.largestValidDuration(
                blocks = blocks,
                candidate = candidate,
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
            OverlapPolicy.largestValidDuration(
                blocks = blocks,
                candidate = candidate,
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
            OverlapPolicy.largestValidDuration(
                blocks = blocks,
                candidate = candidate,
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
