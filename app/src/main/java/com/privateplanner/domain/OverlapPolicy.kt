package com.privateplanner.domain

object OverlapPolicy {
    const val MaxSavedOverlap = 7
    const val MaxTransientOverlap = 8

    fun canPlace(
        blocks: List<PlannerBlock>,
        candidate: PlannerBlock,
        maxOverlap: Int = MaxSavedOverlap
    ): Boolean {
        return maxOverlapIncluding(blocks, candidate, stopAbove = maxOverlap) <= maxOverlap
    }

    fun maxOverlap(
        blocks: List<PlannerBlock>,
        candidate: PlannerBlock,
        stopAbove: Int = Int.MAX_VALUE
    ): Int {
        return maxOverlapIncluding(blocks, candidate, stopAbove)
    }

    fun largestValidDuration(
        blocks: List<PlannerBlock>,
        candidate: PlannerBlock,
        preferredDurationMinutes: Int,
        maxOverlap: Int = MaxSavedOverlap
    ): Int? {
        val maxDuration = TimeSnapper.clampDuration(
            candidate.startMinutes,
            TimeSnapper.snapDurationToNearest(preferredDurationMinutes)
        )
        val minDuration = TimeSnapper.MinimumDurationMinutes
        if (maxDuration < minDuration) return null

        val invalidOffset = firstInvalidOffset(
            blocks = blocks,
            candidate = candidate.copy(durationMinutes = maxDuration),
            maxOverlap = maxOverlap
        ) ?: return maxDuration
        val validDuration = invalidOffset / TimeSnapper.SnapMinutes * TimeSnapper.SnapMinutes
        return validDuration.takeIf { it >= minDuration }
    }

    private fun maxOverlapIncluding(
        blocks: List<PlannerBlock>,
        candidate: PlannerBlock,
        stopAbove: Int
    ): Int {
        val changes = overlapChanges(blocks, candidate)
        var active = 0
        var max = 0
        for (offset in 0 until candidate.durationMinutes) {
            active += changes[offset]
            if (active > max) {
                max = active
                if (max > stopAbove) return max
            }
        }
        return max
    }

    private fun firstInvalidOffset(
        blocks: List<PlannerBlock>,
        candidate: PlannerBlock,
        maxOverlap: Int
    ): Int? {
        val changes = overlapChanges(blocks, candidate)
        var active = 0
        for (offset in 0 until candidate.durationMinutes) {
            active += changes[offset]
            if (active > maxOverlap) return offset
        }
        return null
    }

    private fun overlapChanges(
        blocks: List<PlannerBlock>,
        candidate: PlannerBlock
    ): IntArray {
        val changes = IntArray(candidate.durationMinutes + 1)

        fun add(startMinutes: Int, endMinutes: Int) {
            val start = maxOf(startMinutes, candidate.startMinutes)
            val end = minOf(endMinutes, candidate.endMinutes)
            if (start < end) {
                changes[start - candidate.startMinutes] += 1
                changes[end - candidate.startMinutes] -= 1
            }
        }

        for (block in blocks) {
            if (
                block.id != candidate.id &&
                block.date == candidate.date &&
                block.startMinutes < candidate.endMinutes &&
                candidate.startMinutes < block.endMinutes
            ) {
                add(block.startMinutes, block.endMinutes)
            }
        }

        add(candidate.startMinutes, candidate.endMinutes)
        return changes
    }
}
