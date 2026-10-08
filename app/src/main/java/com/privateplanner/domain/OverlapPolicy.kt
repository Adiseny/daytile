package com.privateplanner.domain

import java.util.Arrays

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
                val start = block.startMinutes.coerceAtLeast(0).coerceAtMost(TimeSnapper.MinutesPerDay)
                val end = block.endMinutes.coerceAtLeast(0).coerceAtMost(TimeSnapper.MinutesPerDay)
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

        internal fun isSnappedValidBlock(block: PlannerBlock): Boolean {
            return block.startMinutes >= 0 &&
                block.durationMinutes > 0 &&
                block.endMinutes <= TimeSnapper.MinutesPerDay &&
                block.startMinutes % TimeSnapper.SnapMinutes == 0 &&
                block.durationMinutes % TimeSnapper.SnapMinutes == 0
        }
    }
}

// Whether some of a day's blocks can move together by the same number of minutes: none
// of them leaves the day, and each has room where it lands with the others moved too. All
// of them go or none does.
fun canShift(blocks: List<PlannerBlock>, ids: LongArray, deltaMinutes: Int): Boolean {
    if (ids.isEmpty() || blocks.isEmpty()) return true
    // Selection order has no meaning. Sort only an unsorted caller's IDs; the timeline
    // already keeps them sorted, so a gesture needs no copied selection or boxed keys.
    val selected = ids.sortedIds()
    var minutesPerSlot = TimeSnapper.SnapMinutes
    if (blocks.size > OverlapPolicy.MaxSavedOverlap) for (block in blocks) {
        if (!OverlapPolicy.isSnappedValidBlock(block)) {
            minutesPerSlot = 1
            break
        }
    }
    // Each difference stores total occupancy in the low word and selected occupancy
    // in the high word. Small days cannot exceed the limit and need no scratch array.
    val counts = if (blocks.size > OverlapPolicy.MaxSavedOverlap) LongArray(TimeSnapper.MinutesPerDay / minutesPerSlot + 1) else null
    for (block in blocks) {
        val held = Arrays.binarySearch(selected, block.id) >= 0
        var start = block.startMinutes
        var end = block.endMinutes
        if (held) {
            start += deltaMinutes
            end += deltaMinutes
            if (start < 0 || block.durationMinutes < TimeSnapper.MinimumDurationMinutes ||
                start > TimeSnapper.MinutesPerDay - block.durationMinutes ||
                start % TimeSnapper.SnapMinutes != 0 || block.durationMinutes % TimeSnapper.SnapMinutes != 0
            ) return false
        }
        if (counts == null) continue
        start = start.coerceAtLeast(0).coerceAtMost(TimeSnapper.MinutesPerDay)
        end = end.coerceAtLeast(0).coerceAtMost(TimeSnapper.MinutesPerDay)
        if (start >= end) continue
        val change = if (held) 0x100000001L else 1L
        counts[start / minutesPerSlot] += change
        counts[end / minutesPerSlot] -= change
    }
    // One sweep checks every moved block together. Crowding outside the selection is
    // irrelevant. Minute slots retain the rules for older, unsnapped blocks too.
    var active = 0L
    for (change in counts ?: return true) {
        active += change
        if (active >= 0x100000000L && active.toInt() > OverlapPolicy.MaxSavedOverlap) return false
    }
    return true
}

internal fun LongArray.sortedIds(): LongArray {
    for (index in 1 until size) if (this[index - 1] > this[index]) {
        return copyOf().also { Arrays.sort(it) }
    }
    return this
}
