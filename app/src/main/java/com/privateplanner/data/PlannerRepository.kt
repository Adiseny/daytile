package com.privateplanner.data

import com.privateplanner.BuildConfig
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate

enum class PlannerWriteResult {
    Success,
    NoSpace,
    MissingBlock,
    RejectedOverlap,
    InvalidInput,
    Failed
}

// The planner's rules over its queries. Like them, called from one thread at a time.
class PlannerRepository(
    private val dao: PlannerBlockDao,
    // Called after every successful write so the pending reminder alarm is re-armed
    // from one place; no call site can forget to keep reminders in step.
    private val onWrite: () -> Unit = {}
) {
    fun getBlocksForDate(date: LocalDate): List<PlannerBlock> {
        return dao.getBlocksForDate(date.toEpochDay())
    }

    // Snapping and clamping below always yield a valid time, so only restoring a
    // stored block needs an explicit range check.
    fun createBlock(date: LocalDate, startMinutes: Int, title: String): PlannerWriteResult {
        return writeCatching {
            val normalizedTitle = normalizeTitle(title)
            val start = TimeSnapper.floorToValidStart(startMinutes)
            val dateKey = date.toEpochDay()
            dao.withTransaction transaction@{
                val nextStart = dao.getNextStartMinutes(dateKey, start)
                val previousDuration = dao.getLatestPreviousDurationForTitle(normalizedTitle, dateKey, start)
                val preferredDuration = if (previousDuration != null) {
                    TimeSnapper.capDurationAtNextStart(
                        startMinutes = start,
                        durationMinutes = TimeSnapper.clampDuration(start, previousDuration),
                        nextStartMinutes = nextStart
                    )
                } else {
                    TimeSnapper.defaultDurationForStart(start, nextStart)
                }
                // A legacy boundary may round up: query the entire interval we will save.
                val duration = TimeSnapper.snapDurationToNearest(preferredDuration)
                val fittedDuration = overlapPolicy(date, start, start + duration, 0)
                    .largestValidDuration(start, duration)
                    ?: return@transaction PlannerWriteResult.NoSpace
                dao.insertBlock(
                    PlannerBlock(
                        date = date,
                        title = normalizedTitle,
                        startMinutes = start,
                        durationMinutes = fittedDuration
                    )
                )
                PlannerWriteResult.Success
            }
        }
    }

    fun updateTitle(id: Long, title: String): PlannerWriteResult {
        return writeCatching {
            rowResult(dao.updateTitle(id, normalizeTitle(title)))
        }
    }

    fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): PlannerWriteResult {
        return writeCatching {
            dao.withTransaction transaction@{
                val current = dao.getBlock(id)
                    ?: return@transaction PlannerWriteResult.MissingBlock
                // The clamped duration always fits after the snapped start.
                val start = TimeSnapper.floorToValidStart(startMinutes)
                val duration = TimeSnapper.clampDuration(
                    startMinutes = start,
                    durationMinutes = TimeSnapper.snapDurationToNearest(durationMinutes)
                )
                if (!overlapPolicy(current.date, start, start + duration, id).canPlace(start, duration)) {
                    PlannerWriteResult.RejectedOverlap
                } else {
                    rowResult(dao.updateTime(id, start, duration))
                }
            }
        }
    }

    fun deleteBlock(id: Long): PlannerWriteResult {
        return writeCatching {
            rowResult(dao.deleteBlockById(id))
        }
    }

    fun restoreBlock(block: PlannerBlock): PlannerWriteResult {
        return writeCatching {
            dao.withTransaction transaction@{
                if (dao.getBlock(block.id) != null) {
                    return@transaction PlannerWriteResult.MissingBlock
                }
                val title = normalizeTitle(block.title)
                require(
                    block.startMinutes >= 0 &&
                        block.durationMinutes >= TimeSnapper.MinimumDurationMinutes &&
                        block.startMinutes <= TimeSnapper.MinutesPerDay - block.durationMinutes
                )
                if (!overlapPolicy(block.date, block.startMinutes, block.endMinutes, block.id)
                        .canPlace(block.startMinutes, block.durationMinutes)
                ) {
                    return@transaction PlannerWriteResult.RejectedOverlap
                }
                dao.insertBlock(block.copy(title = title))
                PlannerWriteResult.Success
            }
        }
    }

    fun getBlock(id: Long): PlannerBlock? {
        return dao.getBlock(id)
    }

    fun getNextStart(dateEpochDay: Long, startMinutes: Int): Long? {
        return dao.getNextStart(dateEpochDay, startMinutes)
    }

    fun getBlocksStartingAt(date: LocalDate, startMinutes: Int): List<PlannerBlock> {
        return dao.getBlocksStartingAt(date.toEpochDay(), startMinutes)
    }

    private fun overlapPolicy(
        date: LocalDate,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ): OverlapPolicy = OverlapPolicy.from(
        dao.getPotentiallyOverlappingBlocks(date.toEpochDay(), startMinutes, endMinutes, excludedBlockId),
        excludedBlockId
    )

    private inline fun writeCatching(block: () -> PlannerWriteResult): PlannerWriteResult {
        val result = try {
            block()
        } catch (_: IllegalArgumentException) {
            PlannerWriteResult.InvalidInput
        } catch (exception: Exception) {
            // The app keeps no logs, so debug builds crash on unexpected write
            // failures — otherwise the cause is unrecoverable.
            if (BuildConfig.DEBUG) throw exception
            PlannerWriteResult.Failed
        }
        if (result == PlannerWriteResult.Success) onWrite()
        return result
    }
}

private fun rowResult(rows: Int): PlannerWriteResult =
    if (rows == 1) PlannerWriteResult.Success else PlannerWriteResult.MissingBlock

private fun normalizeTitle(title: String): String {
    val normalized = title.trim()
    require(normalized.isNotEmpty())
    require(normalized.length <= MaxTitleLength)
    return normalized
}
