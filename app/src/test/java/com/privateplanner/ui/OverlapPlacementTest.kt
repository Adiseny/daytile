package com.privateplanner.ui

import com.privateplanner.domain.MovePlacement
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OverlapPlacementTest {
    @Test
    fun snappedPlacementsMatchExactOverlapCount() {
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
        val policy = OverlapPolicy.from(
            blocks = listOf(block(1, 9 * 60, 60)),
            excludedBlockId = 99
        )

        assertEquals(MovePlacement.Invalid, policy.placement(9 * 60 + 1, 60))
        assertEquals(MovePlacement.Invalid, policy.placement(9 * 60, 11))
        assertEquals(MovePlacement.Invalid, policy.placement(9 * 60, 5))
        assertEquals(MovePlacement.Invalid, policy.placement(23 * 60 + 55, 60))
        assertFalse(policy.canPlace(9 * 60 + 1, 60))
    }

    private fun assertPlacementMatchesOverlapPolicy(
        existingBlocks: List<PlannerBlock>,
        active: PlannerBlock,
        targetStart: Int,
        targetDuration: Int
    ) {
        val policy = OverlapPolicy.from(existingBlocks + active, active.id)
        val candidate = active.copy(
            startMinutes = targetStart,
            durationMinutes = targetDuration
        )
        val maxOverlap = (candidate.startMinutes until candidate.endMinutes).maxOf { minute ->
            existingBlocks.count { block ->
                block.id != candidate.id &&
                    block.date == candidate.date &&
                    minute in block.startMinutes until block.endMinutes
            } + 1
        }
        val expected = when {
            maxOverlap <= OverlapPolicy.MaxSavedOverlap -> MovePlacement.Savable
            maxOverlap <= OverlapPolicy.MaxTransientOverlap -> MovePlacement.TransientOnly
            else -> MovePlacement.Invalid
        }

        assertEquals(expected, policy.placement(targetStart, targetDuration))
        assertEquals(expected == MovePlacement.Savable, policy.canPlace(targetStart, targetDuration))
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
