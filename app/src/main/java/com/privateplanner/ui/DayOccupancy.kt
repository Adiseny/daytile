package com.privateplanner.ui

import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeSnapper

internal class DayOccupancy private constructor(
    private val counts: IntArray,
    private val mode: Mode
) {
    fun placement(startMinutes: Int, durationMinutes: Int): MovePlacement {
        val maxOverlap = maxIncludingCandidate(startMinutes, durationMinutes)
            ?: return MovePlacement.Invalid
        return when {
            maxOverlap <= OverlapPolicy.MaxSavedOverlap -> MovePlacement.Savable
            maxOverlap <= OverlapPolicy.MaxTransientOverlap -> MovePlacement.TransientOnly
            else -> MovePlacement.Invalid
        }
    }

    fun canPlace(
        startMinutes: Int,
        durationMinutes: Int,
        maxOverlap: Int = OverlapPolicy.MaxSavedOverlap
    ): Boolean {
        return maxIncludingCandidate(startMinutes, durationMinutes)?.let { it <= maxOverlap } == true
    }

    private fun maxIncludingCandidate(startMinutes: Int, durationMinutes: Int): Int? {
        if (!isValidCandidate(startMinutes, durationMinutes)) return null
        var maxOverlap = 0
        when (mode) {
            Mode.SnapSlots -> {
                val startSlot = startMinutes / TimeSnapper.SnapMinutes
                val endSlot = (startMinutes + durationMinutes) / TimeSnapper.SnapMinutes
                for (slot in startSlot until endSlot) {
                    maxOverlap = maxOf(maxOverlap, counts[slot] + 1)
                }
            }
            Mode.MinuteSlots -> {
                val endMinutes = startMinutes + durationMinutes
                for (minute in startMinutes until endMinutes) {
                    maxOverlap = maxOf(maxOverlap, counts[minute] + 1)
                }
            }
        }
        return maxOverlap
    }

    private enum class Mode {
        SnapSlots,
        MinuteSlots
    }

    companion object {
        fun from(blocks: List<PlannerBlock>, excludedBlockId: Long): DayOccupancy {
            val useSnapSlots = blocks
                .asSequence()
                .filter { block -> block.id != excludedBlockId }
                .all(::isSnappedValidBlock)

            return if (useSnapSlots) {
                DayOccupancy(
                    counts = buildSnapSlotCounts(blocks, excludedBlockId),
                    mode = Mode.SnapSlots
                )
            } else {
                DayOccupancy(
                    counts = buildMinuteSlotCounts(blocks, excludedBlockId),
                    mode = Mode.MinuteSlots
                )
            }
        }

        private fun buildSnapSlotCounts(
            blocks: List<PlannerBlock>,
            excludedBlockId: Long
        ): IntArray {
            val counts = IntArray(TimeSnapper.MinutesPerDay / TimeSnapper.SnapMinutes)
            for (block in blocks) {
                if (block.id == excludedBlockId || !isSnappedValidBlock(block)) continue
                val startSlot = block.startMinutes / TimeSnapper.SnapMinutes
                val endSlot = block.endMinutes / TimeSnapper.SnapMinutes
                for (slot in startSlot until endSlot) {
                    counts[slot] += 1
                }
            }
            return counts
        }

        private fun buildMinuteSlotCounts(
            blocks: List<PlannerBlock>,
            excludedBlockId: Long
        ): IntArray {
            val counts = IntArray(TimeSnapper.MinutesPerDay)
            for (block in blocks) {
                if (block.id == excludedBlockId || !isBoundedBlock(block)) continue
                for (minute in block.startMinutes until block.endMinutes) {
                    counts[minute] += 1
                }
            }
            return counts
        }

        private fun isValidCandidate(startMinutes: Int, durationMinutes: Int): Boolean {
            return startMinutes >= 0 &&
                durationMinutes >= TimeSnapper.MinimumDurationMinutes &&
                startMinutes + durationMinutes <= TimeSnapper.MinutesPerDay &&
                startMinutes % TimeSnapper.SnapMinutes == 0 &&
                durationMinutes % TimeSnapper.SnapMinutes == 0
        }

        private fun isSnappedValidBlock(block: PlannerBlock): Boolean {
            return isBoundedBlock(block) &&
                block.startMinutes % TimeSnapper.SnapMinutes == 0 &&
                block.durationMinutes % TimeSnapper.SnapMinutes == 0
        }

        private fun isBoundedBlock(block: PlannerBlock): Boolean {
            return block.startMinutes >= 0 &&
                block.durationMinutes > 0 &&
                block.endMinutes <= TimeSnapper.MinutesPerDay
        }
    }
}
