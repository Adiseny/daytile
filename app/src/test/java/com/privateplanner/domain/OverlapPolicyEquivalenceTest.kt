package com.privateplanner.domain

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlapPolicyEquivalenceTest {
    @Test
    fun randomDaysMatchMinuteByMinuteCoverage() {
        val random = Random(581)
        val date = LocalDate.of(2026, 9, 25)
        repeat(80) { day ->
            val snapped = day % 2 == 0
            val blocks = List(random.nextInt(0, 70)) { index ->
                val start = random.nextInt(0, 1430).let { if (snapped) it / 5 * 5 else it }
                val end = random.nextInt(start + 10, 1441).let { if (snapped) it / 5 * 5 else it }
                PlannerBlock(index.toLong(), date, "Task", start, end - start)
            }
            val excluded = random.nextLong(-1, blocks.size.toLong() + 1)
            val coverage = IntArray(1440) { minute ->
                blocks.count { it.id != excluded && minute >= it.startMinutes && minute < it.endMinutes }
            }
            val policy = OverlapPolicy.from(blocks, excluded)
            for (start in 0..1430 step 5) {
                val durations = intArrayOf(10, 15, 60, 480, 1440 - start)
                for (duration in durations) {
                    if (start + duration > 1440) continue
                    val maximum = (start until start + duration).maxOf { coverage[it] }
                    val expected = when {
                        maximum >= 8 -> MovePlacement.Invalid
                        maximum >= 7 -> MovePlacement.TransientOnly
                        else -> MovePlacement.Savable
                    }
                    assertEquals("day=$day start=$start duration=$duration", expected, policy.placement(start, duration))
                    val blocked = (start until start + duration).firstOrNull { coverage[it] >= 7 }
                    val available = if (blocked == null) duration else (blocked - start) / 5 * 5
                    assertEquals(available.takeIf { it >= 10 }, policy.largestValidDuration(start, duration))
                }
            }
        }
    }

    @Test
    fun extremeInputsCannotOverflowIntoValidPlacements() {
        val policy = OverlapPolicy.from(emptyList(), 0)
        for (start in intArrayOf(Int.MIN_VALUE, -5, 1435, 1440, Int.MAX_VALUE)) {
            assertEquals(MovePlacement.Invalid, policy.placement(start, 10))
            assertEquals(null, policy.largestValidDuration(start, 60))
        }
        assertEquals(MovePlacement.Invalid, policy.placement(10, Int.MAX_VALUE))
        assertEquals(1440, TimeSnapper.snapDurationToNearest(Int.MAX_VALUE))
        assertEquals(10, TimeSnapper.snapDurationToNearest(Int.MIN_VALUE))
    }
}
