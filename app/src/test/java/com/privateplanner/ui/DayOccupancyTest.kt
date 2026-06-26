package com.privateplanner.ui

import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DayOccupancyTest {
    @Test
    fun snappedPlacementsMatchOverlapPolicy() {
        val active = block(99, 12 * 60, 60)
        assertPlacementMatchesOverlapPolicy(
            existingBlocks = (1L..6L).map { id -> block(id, 9 * 60, 60) },
            active = active,
            targetStart = 9 * 60,
            targetDuration = 60
        )
        assertPlacementMatchesOverlapPolicy(
            existingBlocks = (1L..7L).map { id -> block(id, 9 * 60, 60) },
            active = active,
            targetStart = 9 * 60,
            targetDuration = 60
        )
        assertPlacementMatchesOverlapPolicy(
            existingBlocks = (1L..8L).map { id -> block(id, 9 * 60, 60) },
            active = active,
            targetStart = 9 * 60,
            targetDuration = 60
        )
    }

    @Test
    fun legacyNonSnappedPlacementsUseExactMinuteCoverage() {
        val active = block(99, 12 * 60, 60)
        assertPlacementMatchesOverlapPolicy(
            existingBlocks = (1L..7L).map { id -> block(id, 9 * 60 + 1, 4) },
            active = active,
            targetStart = 9 * 60,
            targetDuration = 10
        )
        assertPlacementMatchesOverlapPolicy(
            existingBlocks = (1L..7L).map { id -> block(id, 9 * 60 + 10, 1) },
            active = active,
            targetStart = 9 * 60,
            targetDuration = 10
        )
    }

    @Test
    fun invalidCandidatesAreRejectedBeforeOverlapCalculation() {
        val occupancy = DayOccupancy.from(
            blocks = listOf(block(1, 9 * 60, 60)),
            excludedBlockId = 99
        )

        assertEquals(MovePlacement.Invalid, occupancy.placement(9 * 60 + 1, 60))
        assertEquals(MovePlacement.Invalid, occupancy.placement(9 * 60, 11))
        assertEquals(MovePlacement.Invalid, occupancy.placement(9 * 60, 5))
        assertEquals(MovePlacement.Invalid, occupancy.placement(23 * 60 + 55, 60))
        assertFalse(occupancy.canPlace(9 * 60 + 1, 60))
    }

    private fun assertPlacementMatchesOverlapPolicy(
        existingBlocks: List<PlannerBlock>,
        active: PlannerBlock,
        targetStart: Int,
        targetDuration: Int
    ) {
        val occupancy = DayOccupancy.from(existingBlocks + active, active.id)
        val candidate = active.copy(
            startMinutes = targetStart,
            durationMinutes = targetDuration
        )
        val overlap = OverlapPolicy.maxOverlap(
            blocks = existingBlocks + active,
            candidate = candidate
        )
        val expected = when {
            overlap <= OverlapPolicy.MaxSavedOverlap -> MovePlacement.Savable
            overlap <= OverlapPolicy.MaxTransientOverlap -> MovePlacement.TransientOnly
            else -> MovePlacement.Invalid
        }

        assertEquals(expected, occupancy.placement(targetStart, targetDuration))
        assertEquals(
            overlap <= OverlapPolicy.MaxSavedOverlap,
            occupancy.canPlace(targetStart, targetDuration)
        )
        assertEquals(
            overlap <= OverlapPolicy.MaxTransientOverlap,
            occupancy.canPlace(targetStart, targetDuration, OverlapPolicy.MaxTransientOverlap)
        )
    }

    private fun block(id: Long, startMinutes: Int, durationMinutes: Int): PlannerBlock {
        return PlannerBlock(
            id = id,
            date = LocalDate.of(2026, 5, 30),
            title = id.toString(),
            startMinutes = startMinutes,
            durationMinutes = durationMinutes
        )
    }
}
