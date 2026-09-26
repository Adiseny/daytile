package com.privateplanner.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlapLayoutCalculatorTest {
    @Test
    fun clustersReuseBuffersWithoutCarryingColumnState() {
        val sizes = listOf(2, 7, 3, 12, 1, 4)
        var id = 1000L
        val blocks = sizes.flatMapIndexed { cluster, size ->
            List(size) { block(id++, cluster * 60, 30) }
        }
        val layouts = OverlapLayoutCalculator.calculate(blocks)
        id = 1000L
        for (size in sizes) {
            repeat(size) { column ->
                assertEquals(BlockLayout(column, size), layouts[id++])
            }
        }
    }

    @Test
    fun nonOverlappingBlocksUseFullWidth() {
        val layouts = OverlapLayoutCalculator.calculate(
            listOf(
                block(1, 8 * 60, 60),
                block(2, 9 * 60, 60)
            )
        )

        assertEquals(1, layouts[1]!!.columnCount)
        assertEquals(1, layouts[2]!!.columnCount)
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

        assertEquals(3, layouts[1]!!.columnCount)
        assertEquals(3, layouts[2]!!.columnCount)
        assertEquals(3, layouts[3]!!.columnCount)
    }

    @Test
    fun aFinishedColumnIsReusedWithinTheSameOverlapCluster() {
        val layouts = OverlapLayoutCalculator.calculate(
            listOf(
                block(1, 8 * 60, 30),
                block(2, 8 * 60, 60),
                block(3, 8 * 60 + 30, 30)
            )
        )

        assertEquals(0, layouts[1]!!.columnIndex)
        assertEquals(1, layouts[2]!!.columnIndex)
        assertEquals(0, layouts[3]!!.columnIndex)
        assertEquals(2, layouts[3]!!.columnCount)
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

    @Test
    fun unsortedAndLegacyOvercrowdedDaysKeepTheirColumns() {
        val blocks = (1L..12L).map { id -> block(id, 0, 60) } + block(13, 60, 60)
        val layouts = OverlapLayoutCalculator.calculate(blocks.reversed())
        assertEquals(OverlapLayoutCalculator.calculate(blocks), layouts)
        for (id in 1L..12L) {
            assertEquals((id - 1).toInt(), layouts[id]!!.columnIndex)
            assertEquals(12, layouts[id]!!.columnCount)
        }
        assertEquals(1, layouts[13]!!.columnCount)
    }
}
