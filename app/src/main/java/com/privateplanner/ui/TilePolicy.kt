package com.privateplanner.ui

import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.PlannerBlockOrder
import com.privateplanner.domain.TimeSnapper

internal const val HourHeightDp = 120f
internal const val ShortReadableFloorDp = 32f
internal const val TrueDurationMarkerMinDp = 3f
internal const val DurationVisibleMinWidthDp = 112f
internal const val DurationTitleRemainderMinDp = 56f
internal const val DurationMaxReserveFraction = 0.34f
internal const val LongTitlePinMinHeightDp = 240f

internal enum class SingleTileTreatment {
    Normal,
    ReadableFloor,
    CompactChip
}

internal sealed interface TileRenderItem {
    val startMinutes: Int

    data class Single(
        val block: PlannerBlock,
        val treatment: SingleTileTreatment
    ) : TileRenderItem {
        override val startMinutes: Int = block.startMinutes
    }

    data class DenseShortCluster(
        val blocks: List<PlannerBlock>
    ) : TileRenderItem {
        override val startMinutes: Int = blocks.minOf { it.startMinutes }
        val endMinutes: Int = blocks.maxOf { it.endMinutes }
    }
}

internal data class DurationDisplayDecision(
    val show: Boolean,
    val reserveDp: Float
)

internal object TilePolicy {
    fun renderItems(blocks: List<PlannerBlock>): List<TileRenderItem> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedWith(PlannerBlockOrder)
        val decisions = mutableListOf<TileRenderItem>()
        val consumed = mutableSetOf<Long>()

        sorted.forEachIndexed { index, block ->
            if (block.id in consumed) return@forEachIndexed
            if (!isBelowShortFloor(block.durationMinutes)) {
                decisions += TileRenderItem.Single(block, SingleTileTreatment.Normal)
                return@forEachIndexed
            }

            val cluster = denseShortRunFrom(index, sorted, consumed)
            if (cluster.size > 1) {
                consumed += cluster.map { it.id }
                decisions += TileRenderItem.DenseShortCluster(cluster)
                return@forEachIndexed
            }

            val treatment = if (isBlockedByFollowingNonShort(block, sorted.drop(index + 1))) {
                SingleTileTreatment.CompactChip
            } else {
                SingleTileTreatment.ReadableFloor
            }
            decisions += TileRenderItem.Single(block, treatment)
        }

        return decisions.sortedWith(compareBy<TileRenderItem> { it.startMinutes }.thenBy {
            when (it) {
                is TileRenderItem.Single -> it.block.id
                is TileRenderItem.DenseShortCluster -> it.blocks.minOf { block -> block.id }
            }
        })
    }

    fun proportionalHeightDp(durationMinutes: Int): Float {
        return durationMinutes / TimeSnapper.MinutesPerHour.toFloat() * HourHeightDp
    }

    fun isBelowShortFloor(durationMinutes: Int): Boolean {
        return proportionalHeightDp(durationMinutes) < ShortReadableFloorDp
    }

    fun readableFloorEndMinutes(startMinutes: Int): Int {
        val floorMinutes = ShortReadableFloorDp / HourHeightDp * TimeSnapper.MinutesPerHour
        return startMinutes + kotlin.math.ceil(floorMinutes).toInt()
    }

    fun durationDisplayDecision(
        tileWidthDp: Float,
        durationText: String,
        compact: Boolean,
        durationFontSizeSp: Float = if (compact) 11f else 12f
    ): DurationDisplayDecision {
        if (tileWidthDp < DurationVisibleMinWidthDp) {
            return DurationDisplayDecision(show = false, reserveDp = 0f)
        }
        val estimatedTextWidthDp = durationText.length * durationFontSizeSp * 0.58f
        val desiredReserveDp = estimatedTextWidthDp + if (compact) 10f else 12f
        val maxReserveDp = tileWidthDp * DurationMaxReserveFraction
        if (desiredReserveDp > maxReserveDp) {
            return DurationDisplayDecision(show = false, reserveDp = 0f)
        }
        val reserveDp = desiredReserveDp.coerceAtMost(maxReserveDp)
        return DurationDisplayDecision(
            show = tileWidthDp - reserveDp >= DurationTitleRemainderMinDp,
            reserveDp = reserveDp
        )
    }

    private fun denseShortRunFrom(
        startIndex: Int,
        blocks: List<PlannerBlock>,
        consumed: Set<Long>
    ): List<PlannerBlock> {
        val first = blocks[startIndex]
        val cluster = mutableListOf(first)
        var floorEnd = readableFloorEndMinutes(first.startMinutes)
        var index = startIndex + 1

        while (index < blocks.size) {
            val candidate = blocks[index]
            if (candidate.id in consumed) {
                index += 1
                continue
            }
            if (!isBelowShortFloor(candidate.durationMinutes)) {
                break
            }
            if (candidate.startMinutes >= floorEnd) {
                break
            }
            cluster += candidate
            floorEnd = maxOf(floorEnd, readableFloorEndMinutes(candidate.startMinutes))
            index += 1
        }

        return cluster
    }

    private fun isBlockedByFollowingNonShort(
        block: PlannerBlock,
        followingBlocks: List<PlannerBlock>
    ): Boolean {
        val floorEnd = readableFloorEndMinutes(block.startMinutes)
        return followingBlocks.any { following ->
            following.startMinutes < floorEnd &&
                !isBelowShortFloor(following.durationMinutes)
        }
    }
}
