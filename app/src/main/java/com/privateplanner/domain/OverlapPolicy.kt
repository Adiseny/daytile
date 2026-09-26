package com.privateplanner.domain

enum class MovePlacement {
    Invalid,
    TransientOnly,
    Savable
}

class OverlapPolicy private constructor(
    private val limits: IntArray?,
    private val minutesPerSlot: Int
) {
    fun placement(startMinutes: Int, durationMinutes: Int): MovePlacement {
        if (!isValidCandidate(startMinutes, durationMinutes)) return MovePlacement.Invalid
        val limit = limits?.get(startMinutes / minutesPerSlot) ?: return MovePlacement.Savable
        val endSlot = (startMinutes + durationMinutes) / minutesPerSlot
        return when {
            endSlot > (limit and 0xffff) -> MovePlacement.Invalid
            endSlot > (limit ushr 16) -> MovePlacement.TransientOnly
            else -> MovePlacement.Savable
        }
    }

    fun canPlace(startMinutes: Int, durationMinutes: Int): Boolean =
        placement(startMinutes, durationMinutes) == MovePlacement.Savable

    fun largestValidDuration(startMinutes: Int, preferredDurationMinutes: Int): Int? {
        if (!isValidCandidate(startMinutes, TimeSnapper.MinimumDurationMinutes)) return null
        val duration = TimeSnapper.clampDuration(
            startMinutes,
            TimeSnapper.snapDurationToNearest(preferredDurationMinutes)
        )
        val limit = limits?.get(startMinutes / minutesPerSlot) ?: return duration
        val available = ((limit ushr 16) * minutesPerSlot - startMinutes) /
            TimeSnapper.SnapMinutes * TimeSnapper.SnapMinutes
        return minOf(duration, available).takeIf { it >= TimeSnapper.MinimumDurationMinutes }
    }

    companion object {
        const val MaxSavedOverlap = 7
        const val MaxTransientOverlap = 8
        private val Unrestricted = OverlapPolicy(null, TimeSnapper.SnapMinutes)

        fun from(
            blocks: List<PlannerBlock>,
            excludedBlockId: Long
        ): OverlapPolicy {
            if (blocks.size < MaxSavedOverlap) return Unrestricted
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
            var peak = 0
            for (slot in counts.indices) {
                active += counts[slot]
                peak = maxOf(peak, active)
                counts[slot] = active
            }
            if (peak < MaxSavedOverlap) return Unrestricted
            // Reuse the counts array for the next blocked slot at each threshold. Both
            // indices fit in 16 bits (at most 1,440); each drag/resize check is one lookup,
            // including on legacy days whose boundaries do not fall on five minutes.
            var nextSaved = slotCount
            var nextTransient = slotCount
            for (slot in counts.lastIndex downTo 0) {
                if (counts[slot] >= MaxSavedOverlap) nextSaved = slot
                if (counts[slot] >= MaxTransientOverlap) nextTransient = slot
                counts[slot] = (nextSaved shl 16) or nextTransient
            }
            return OverlapPolicy(counts, minutesPerSlot)
        }

        private fun isValidCandidate(startMinutes: Int, durationMinutes: Int): Boolean {
            return startMinutes >= 0 &&
                durationMinutes >= TimeSnapper.MinimumDurationMinutes &&
                startMinutes <= TimeSnapper.MinutesPerDay - durationMinutes &&
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
