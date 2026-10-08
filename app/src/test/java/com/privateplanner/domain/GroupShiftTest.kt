package com.privateplanner.domain

import java.time.LocalDate
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GroupShiftTest {
    private val date = LocalDate.of(2026, 10, 8)

    private fun block(id: Long, start: Int, duration: Int) = PlannerBlock(
        id = id, date = date, title = "$id", startMinutes = start, durationMinutes = duration
    )

    // The previous implementation is the oracle, including its treatment of legacy
    // boundaries, overcrowding elsewhere in the day, and missing selected IDs.
    private fun previous(blocks: List<PlannerBlock>, ids: LongArray, delta: Int): Boolean {
        val shifted = ArrayList<PlannerBlock>(blocks.size)
        for (block in blocks) {
            if (block.id !in ids) {
                shifted.add(block)
                continue
            }
            val start = block.startMinutes + delta
            if (start < 0 || start + block.durationMinutes > TimeSnapper.MinutesPerDay) return false
            shifted.add(block.copy(startMinutes = start))
        }
        for (block in shifted) {
            if (block.id in ids && !OverlapPolicy.from(shifted, block.id).canPlace(block.startMinutes, block.durationMinutes)) return false
        }
        return true
    }

    @Test fun groupMovesMatchIndividualChecksOnSnappedAndLegacyDays() {
        val random = Random(871023)
        repeat(2_000) { iteration ->
            val blocks = List(random.nextInt(90)) { index ->
                val snapped = iteration % 2 == 0
                block(index.toLong() - 40, if (snapped) random.nextInt(280) * 5 else random.nextInt(1500) - 30,
                    if (snapped) (random.nextInt(36) + 2) * 5 else random.nextInt(200) - 5)
            }
            val ids = blocks.filter { random.nextBoolean() }.map { it.id }.shuffled(random).toLongArray() + 999L
            val delta = if (iteration % 3 == 0) random.nextInt(25) - 12 else (random.nextInt(49) - 24) * 5
            assertEquals("Case $iteration at $delta", previous(blocks, ids, delta), canShift(blocks, ids, delta))
        }
    }

    @Test fun unrelatedCrowdingDoesNotPreventMovingAndTouchingEndsDoNotOverlap() {
        val crowded = List(9) { block(it.toLong(), 600, 60) }
        val moving = listOf(block(20, 60, 30), block(21, 90, 30))
        val blocks = crowded + moving
        for (delta in -60..100 step 5) {
            assertEquals(previous(blocks, longArrayOf(21, 20), delta), canShift(blocks, longArrayOf(21, 20), delta))
        }
        assertEquals(true, canShift(blocks, longArrayOf(20, 21), 30))
        assertEquals(true, canShift(blocks, longArrayOf(999), 10))
        assertEquals(true, canShift(blocks, longArrayOf(), 10))
    }

    @Test fun legacyEndsInsideASnapIntervalAreCheckedWithoutMutatingSelection() {
        val blocks = List(7) { block(it + 1L, 603, 11) } + listOf(block(20, 590, 10), block(21, 680, 10))
        val ids = longArrayOf(21, 20)
        for (delta in -30..80 step 5) {
            assertEquals(previous(blocks, ids, delta), canShift(blocks, ids, delta))
            assertArrayEquals(longArrayOf(21, 20), ids)
        }
        assertEquals(false, canShift(blocks, ids, 20))
        assertEquals(true, canShift(blocks, ids, 25))
    }

    @Test fun measureCrowdedGroupMoves() {
        val blocks = (120 until 1200 step 15).flatMap { start ->
            List(7) { column -> block((start * 7 + column).toLong(), start, 15) }
        }
        val ids = blocks.map { it.id }.toLongArray()
        fun measure(old: Boolean): Long {
            val start = System.nanoTime()
            repeat(30) {
                assertEquals(true, if (old) previous(blocks, ids, 5) else canShift(blocks, ids, 5))
            }
            return (System.nanoTime() - start) / 30
        }
        repeat(3) { measure(true); measure(false) }
        val before = LongArray(5) { measure(true) }.sorted()[2]
        val after = LongArray(5) { measure(false) }.sorted()[2]
        println("504 selected tasks, ns per valid group step: previous=$before current=$after")
    }
}
