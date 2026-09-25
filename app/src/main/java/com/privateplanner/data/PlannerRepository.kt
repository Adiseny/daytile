package com.privateplanner.data

import com.privateplanner.BuildConfig
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class PlannerWriteResult {
    Success,
    NoSpace,
    MissingBlock,
    RejectedOverlap,
    InvalidInput,
    Failed
}

class PlannerRepository private constructor(
    private val dao: PlannerBlockDao,
    private val inTransaction: suspend (suspend () -> PlannerWriteResult) -> PlannerWriteResult,
    // Fired after every successful write so the pending reminder alarm is re-armed
    // from one place; no call site can forget to keep reminders in step.
    private val onWrite: suspend () -> Unit = {}
) {
    constructor(database: PlannerDatabase, onWrite: suspend () -> Unit = {}) : this(
        dao = database.blockDao(),
        inTransaction = { block -> database.withTransaction { block() } },
        onWrite = onWrite
    )

    internal constructor(dao: PlannerBlockDao) : this(
        dao = dao,
        inTransaction = { block -> block() }
    )

    fun observeBlocksForDate(date: LocalDate): Flow<List<PlannerBlock>> {
        // Every write re-reads, even one that only changed another day.
        return dao.observeBlocksForDate(date.toEpochDay())
            .distinctUntilChanged()
            .map { entities -> entities.map { it.toDomain(date) } }
    }

    suspend fun getBlocksForDate(date: LocalDate): List<PlannerBlock> {
        return dao.getBlocksForDate(date.toEpochDay()).map { it.toDomain(date) }
    }

    // Snapping and clamping below always yield a valid time, so only restoring a
    // stored block needs an explicit range check.
    suspend fun createBlock(date: LocalDate, startMinutes: Int, title: String): PlannerWriteResult {
        return writeCatching {
            val normalizedTitle = normalizeTitle(title)
            val start = TimeSnapper.floorToValidStart(startMinutes)
            val dateKey = date.toEpochDay()
            inTransaction transaction@{
                val nextStart = dao.getNextStartMinutes(dateKey, start)
                val previousDuration = dao.getLatestPreviousDurationForTitle(normalizedTitle, dateKey, start)
                val duration = if (previousDuration != null) {
                    TimeSnapper.capDurationAtNextStart(
                        startMinutes = start,
                        durationMinutes = TimeSnapper.clampDuration(start, previousDuration),
                        nextStartMinutes = nextStart
                    )
                } else {
                    TimeSnapper.defaultDurationForStart(start, nextStart)
                }
                val fittedDuration = overlapPolicy(date, start, start + duration, 0)
                    .largestValidDuration(start, duration)
                    ?: return@transaction PlannerWriteResult.NoSpace
                dao.insertBlock(
                    PlannerBlockEntity(
                        dateEpochDay = dateKey,
                        title = normalizedTitle,
                        startMinutes = start,
                        durationMinutes = fittedDuration
                    )
                )
                PlannerWriteResult.Success
            }
        }
    }

    suspend fun updateTitle(id: Long, title: String): PlannerWriteResult {
        return writeCatching {
            rowResult(dao.updateTitle(id, normalizeTitle(title)))
        }
    }

    suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): PlannerWriteResult {
        return writeCatching {
            inTransaction transaction@{
                val current = dao.getBlock(id)
                    ?: return@transaction PlannerWriteResult.MissingBlock
                // The clamped duration always fits after the snapped start.
                val start = TimeSnapper.floorToValidStart(startMinutes)
                val duration = TimeSnapper.clampDuration(
                    startMinutes = start,
                    durationMinutes = TimeSnapper.snapDurationToNearest(durationMinutes)
                )
                if (!overlapPolicy(LocalDate.ofEpochDay(current.dateEpochDay), start, start + duration, id).canPlace(start, duration)) {
                    PlannerWriteResult.RejectedOverlap
                } else {
                    rowResult(dao.updateTime(id, start, duration))
                }
            }
        }
    }

    suspend fun deleteBlock(id: Long): PlannerWriteResult {
        return writeCatching {
            rowResult(dao.deleteBlockById(id))
        }
    }

    suspend fun restoreBlock(block: PlannerBlock): PlannerWriteResult {
        return writeCatching {
            inTransaction transaction@{
                if (dao.getBlock(block.id) != null) {
                    return@transaction PlannerWriteResult.MissingBlock
                }
                val title = normalizeTitle(block.title)
                require(
                    block.startMinutes >= 0 &&
                        block.durationMinutes >= TimeSnapper.MinimumDurationMinutes &&
                        block.endMinutes <= TimeSnapper.MinutesPerDay
                )
                if (!overlapPolicy(block.date, block.startMinutes, block.endMinutes, block.id)
                        .canPlace(block.startMinutes, block.durationMinutes)
                ) {
                    return@transaction PlannerWriteResult.RejectedOverlap
                }
                dao.insertBlock(
                    PlannerBlockEntity(
                        id = block.id,
                        dateEpochDay = block.date.toEpochDay(),
                        title = title,
                        startMinutes = block.startMinutes,
                        durationMinutes = block.durationMinutes
                    )
                )
                PlannerWriteResult.Success
            }
        }
    }

    suspend fun getBlock(id: Long): PlannerBlock? {
        return dao.getBlock(id)?.toDomain()
    }

    suspend fun getNextStart(dateEpochDay: Long, startMinutes: Int): Long? {
        return dao.getNextStart(dateEpochDay, startMinutes)
    }

    suspend fun getBlocksStartingAt(date: LocalDate, startMinutes: Int): List<PlannerBlock> {
        return dao.getBlocksStartingAt(date.toEpochDay(), startMinutes).map { it.toDomain(date) }
    }

    private suspend fun overlapPolicy(
        date: LocalDate,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ): OverlapPolicy = OverlapPolicy.from(
        dao.getPotentiallyOverlappingBlocks(date.toEpochDay(), startMinutes, endMinutes, excludedBlockId)
            .map { it.toDomain(date) },
        excludedBlockId
    )

    private suspend inline fun writeCatching(block: () -> PlannerWriteResult): PlannerWriteResult {
        val result = try {
            block()
        } catch (exception: CancellationException) {
            throw exception
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

// Rows read for one day share that day's date rather than each building their own.
private fun PlannerBlockEntity.toDomain(date: LocalDate = LocalDate.ofEpochDay(dateEpochDay)): PlannerBlock {
    return PlannerBlock(
        id = id,
        date = date,
        title = title,
        startMinutes = startMinutes,
        durationMinutes = durationMinutes
    )
}
