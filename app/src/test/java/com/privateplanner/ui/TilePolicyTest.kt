package com.privateplanner.ui

import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import org.junit.Assert.assertEquals
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

    @Test
    fun readableFloorKeepsShortTaskIdentifiableAndMarkerHonest() {
        val block = block(id = 1, startMinutes = 9 * 60, durationMinutes = 5)

        val item = TilePolicy.renderItems(listOf(block)).single() as TileRenderItem.Single

        assertEquals(SingleTileTreatment.ReadableFloor, item.treatment)
        assertEquals(10f, TilePolicy.proportionalHeightDp(block.durationMinutes), 0.001f)
        assertEquals(9 * 60 + 16, TilePolicy.readableFloorEndMinutes(block.startMinutes))
    }

    @Test
    fun denseShortClusterPreservesTrueSpanWithoutPushingLaterBlocks() {
        val first = block(id = 1, startMinutes = 9 * 60, durationMinutes = 5)
        val second = block(id = 2, startMinutes = 9 * 60 + 5, durationMinutes = 5)
        val later = block(id = 3, startMinutes = 10 * 60, durationMinutes = 60)

        val items = TilePolicy.renderItems(listOf(first, second, later))

        val cluster = items[0] as TileRenderItem.DenseShortCluster
        val normal = items[1] as TileRenderItem.Single
        assertEquals(listOf(first, second), cluster.blocks)
        assertEquals(9 * 60, cluster.startMinutes)
        assertEquals(9 * 60 + 10, cluster.endMinutes)
        assertEquals(later, normal.block)
        assertEquals(SingleTileTreatment.Normal, normal.treatment)
    }

    @Test
    fun shortTaskFlushBeforeNonShortBecomesCompactChip() {
        val short = block(id = 1, startMinutes = 9 * 60, durationMinutes = 5)
        val meeting = block(id = 2, startMinutes = 9 * 60 + 5, durationMinutes = 60)

        val items = TilePolicy.renderItems(listOf(short, meeting))

        val chip = items[0] as TileRenderItem.Single
        val normal = items[1] as TileRenderItem.Single
        assertEquals(SingleTileTreatment.CompactChip, chip.treatment)
        assertEquals(short, chip.block)
        assertEquals(SingleTileTreatment.Normal, normal.treatment)
        assertEquals(meeting, normal.block)
    }

    private fun block(
        id: Long,
        startMinutes: Int,
        durationMinutes: Int
    ): PlannerBlock {
        return PlannerBlock(
            id = id,
            date = LocalDate.of(2026, 6, 9),
            title = "Task $id",
            startMinutes = startMinutes,
            durationMinutes = durationMinutes
        )
    }
}
