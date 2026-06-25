package com.privateplanner.domain

import java.time.LocalDate
import kotlin.system.measureNanoTime
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlapPolicyStressTest {
    @Test
    fun denseDayOverlapChecksStayBelowFrameBudget() {
        val blocks = buildList {
            var id = 1L
            for (start in 0 until TimeSnapper.MinutesPerDay step TimeSnapper.SnapMinutes) {
                repeat(7) {
                    add(block(id++, start, TimeSnapper.DefaultDurationMinutes))
                }
            }
        }
        val candidate = block(10_000, 9 * 60, 60)

        repeat(200) {
            OverlapPolicy.canPlace(blocks, candidate, OverlapPolicy.MaxTransientOverlap)
        }

        val elapsedMs = measureNanoTime {
            repeat(2_000) { index ->
                val start = index % (TimeSnapper.MinutesPerDay - TimeSnapper.DefaultDurationMinutes)
                val moved = candidate.copy(startMinutes = TimeSnapper.floorToSnap(start))
                OverlapPolicy.canPlace(blocks, moved, OverlapPolicy.MaxTransientOverlap)
            }
        } / 1_000_000.0

        assertTrue("2,000 dense overlap checks took ${elapsedMs}ms", elapsedMs < 120.0)
    }

    @Test
    fun denseDayLargestValidDurationIsSinglePassFast() {
        val blocks = buildList {
            var id = 1L
            repeat(7) {
                add(block(id++, 9 * 60 + 30, 6 * 60))
            }
        }
        val candidate = block(10_000, 9 * 60, 8 * 60)

        repeat(200) {
            OverlapPolicy.largestValidDuration(blocks, candidate, 8 * 60)
        }

        val elapsedMs = measureNanoTime {
            repeat(2_000) {
                OverlapPolicy.largestValidDuration(blocks, candidate, 8 * 60)
            }
        } / 1_000_000.0

        assertTrue("2,000 dense resize fits took ${elapsedMs}ms", elapsedMs < 80.0)
    }

    private fun block(id: Long, start: Int, duration: Int): PlannerBlock {
        return PlannerBlock(
            id = id,
            date = LocalDate.of(2026, 5, 31),
            title = id.toString(),
            startMinutes = start,
            durationMinutes = duration
        )
    }
}
