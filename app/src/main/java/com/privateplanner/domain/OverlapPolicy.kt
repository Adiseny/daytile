package com.privateplanner.domain

enum class MovePlacement {
    Invalid,
    TransientOnly,
    Savable
}

class OverlapPolicy private constructor(
    private val counts: IntArray,
    private val minutesPerSlot: Int
) {
    fun placement(startMinutes: Int, durationMinutes: Int): MovePlacement {
        val maxOverlap = maxIncludingCandidate(startMinutes, durationMinutes)
            ?: return MovePlacement.Invalid
        return when {
            maxOverlap <= MaxSavedOverlap -> MovePlacement.Savable
            maxOverlap <= MaxTransientOverlap -> MovePlacement.TransientOnly
            else -> MovePlacement.Invalid
        }
    }

    fun canPlace(startMinutes: Int, durationMinutes: Int): Boolean {
        return placement(startMinutes, durationMinutes) == MovePlacement.Savable
    }

    fun largestValidDuration(startMinutes: Int, preferredDurationMinutes: Int): Int? {
        val duration = TimeSnapper.clampDuration(
            startMinutes,
            TimeSnapper.snapDurationToNearest(preferredDurationMinutes)
        )
        if (!isValidCandidate(startMinutes, duration)) return null

        val startSlot = startMinutes / minutesPerSlot
        val endSlot = (startMinutes + duration) / minutesPerSlot
        for (slot in startSlot until endSlot) {
            if (counts[slot] >= MaxSavedOverlap) {
                val validDuration = (slot * minutesPerSlot - startMinutes) /
                    TimeSnapper.SnapMinutes * TimeSnapper.SnapMinutes
                return validDuration.takeIf { it >= TimeSnapper.MinimumDurationMinutes }
            }
        }
        return duration
    }

    private fun maxIncludingCandidate(startMinutes: Int, durationMinutes: Int): Int? {
        if (!isValidCandidate(startMinutes, durationMinutes)) return null
        val startSlot = startMinutes / minutesPerSlot
        val endSlot = (startMinutes + durationMinutes) / minutesPerSlot
        var maxOverlap = 1
        for (slot in startSlot until endSlot) {
            maxOverlap = maxOf(maxOverlap, counts[slot] + 1)
        }
        return maxOverlap
    }

    companion object {
        const val MaxSavedOverlap = 7
        const val MaxTransientOverlap = 8

        fun from(
            blocks: List<PlannerBlock>,
            excludedBlockId: Long
        ): OverlapPolicy {
            var minutesPerSlot = TimeSnapper.SnapMinutes
            for (block in blocks) {
                if (
                    block.id != excludedBlockId &&
                    !isSnappedValidBlock(block)
                ) {
                    minutesPerSlot = 1
                    break
                }
            }

            val slotCount = TimeSnapper.MinutesPerDay / minutesPerSlot
            val counts = IntArray(slotCount)
            for (block in blocks) {
                if (block.id == excludedBlockId) continue
                val start = block.startMinutes.coerceIn(0, TimeSnapper.MinutesPerDay)
                val end = block.endMinutes.coerceIn(0, TimeSnapper.MinutesPerDay)
                if (start >= end) continue
                counts[start / minutesPerSlot] += 1
                val endSlot = end / minutesPerSlot
                if (endSlot < slotCount) {
                    counts[endSlot] -= 1
                }
            }

            var active = 0
            for (slot in counts.indices) {
                active += counts[slot]
                counts[slot] = active
            }
            return OverlapPolicy(counts, minutesPerSlot)
        }

        private fun isValidCandidate(startMinutes: Int, durationMinutes: Int): Boolean {
            return startMinutes >= 0 &&
                durationMinutes >= TimeSnapper.MinimumDurationMinutes &&
                startMinutes + durationMinutes <= TimeSnapper.MinutesPerDay &&
                startMinutes % TimeSnapper.SnapMinutes == 0 &&
                durationMinutes % TimeSnapper.SnapMinutes == 0
        }

        private fun isSnappedValidBlock(block: PlannerBlock): Boolean {
            return block.startMinutes >= 0 &&
                block.durationMinutes > 0 &&
                block.endMinutes <= TimeSnapper.MinutesPerDay &&
                block.startMinutes % TimeSnapper.SnapMinutes == 0 &&
                block.durationMinutes % TimeSnapper.SnapMinutes == 0
        }
    }
}
