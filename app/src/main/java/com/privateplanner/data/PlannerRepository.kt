package com.privateplanner.data

import com.privateplanner.BuildConfig
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate

// How a write ended. Numbers rather than an enum, whose every constant would be an object
// the app carries by name and makes as it starts.
object PlannerWriteResult {
    const val Success = 0
    const val NoSpace = 1
    const val MissingBlock = 2
    const val RejectedOverlap = 3
    const val InvalidInput = 4
    const val Failed = 5
}

// The planner's rules over its queries. Like them, called from one thread at a time.
class PlannerRepository(
    private val dao: PlannerBlockDao,
    // Called after every successful write so the pending reminder alarm is re-armed
    // from one place; no call site can forget to keep reminders in step.
    private val onWrite: Runnable = Runnable {}
) {
    fun getBlocksForDate(date: LocalDate): List<PlannerBlock> {
        return dao.getBlocksForDate(date.toEpochDay())
    }

    // Snapping and clamping below always yield a valid time, so only restoring a
    // stored block needs an explicit range check.
    fun createBlock(date: LocalDate, startMinutes: Int, title: String): Int {
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

    // A block is named by its day and id, as it is stored.
    fun updateTitle(date: LocalDate, id: Long, title: String): Int {
        return writeCatching {
            rowResult(dao.updateTitle(date.toEpochDay(), id, normalizeTitle(title)))
        }
    }

    fun updateTime(date: LocalDate, id: Long, startMinutes: Int, durationMinutes: Int): Int {
        return writeCatching {
            dao.withTransaction {
                // The clamped duration always fits after the snapped start.
                val start = TimeSnapper.floorToValidStart(startMinutes)
                val duration = TimeSnapper.clampDuration(
                    startMinutes = start,
                    durationMinutes = TimeSnapper.snapDurationToNearest(durationMinutes)
                )
                if (!overlapPolicy(date, start, start + duration, id).canPlace(start, duration)) {
                    PlannerWriteResult.RejectedOverlap
                } else {
                    // A block that has gone changes no row.
                    rowResult(dao.updateTime(date.toEpochDay(), id, start, duration))
                }
            }
        }
    }

    fun deleteBlock(date: LocalDate, id: Long): Int {
        return writeCatching {
            rowResult(dao.deleteBlock(date.toEpochDay(), id))
        }
    }

    fun restoreBlock(block: PlannerBlock): Int {
        return writeCatching {
            dao.withTransaction transaction@{
                if (dao.getBlock(block.date.toEpochDay(), block.id) != null) {
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

    private fun overlapPolicy(
        date: LocalDate,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ): OverlapPolicy = OverlapPolicy.from(
        dao.getPotentiallyOverlappingBlocks(date.toEpochDay(), startMinutes, endMinutes, excludedBlockId),
        excludedBlockId
    )

    private inline fun writeCatching(block: () -> Int): Int {
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
        if (result == PlannerWriteResult.Success) onWrite.run()
        return result
    }
}

private fun rowResult(rows: Int): Int =
    if (rows == 1) PlannerWriteResult.Success else PlannerWriteResult.MissingBlock

private fun normalizeTitle(title: String): String {
    val normalized = title.trim()
    require(normalized.isNotEmpty())
    require(normalized.length <= MaxTitleLength)
    return normalized
}
