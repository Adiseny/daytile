package com.privateplanner.data

import androidx.room.withTransaction
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlannerRepository private constructor(
    private val dao: PlannerBlockDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit
) {
    constructor(database: PlannerDatabase) : this(
        dao = database.blockDao(),
        inTransaction = { block -> database.withTransaction { block() } }
    )

    internal constructor(dao: PlannerBlockDao) : this(
        dao = dao,
        inTransaction = { block -> block() }
    )

    fun observeBlocksForDate(date: LocalDate): Flow<List<PlannerBlock>> {
        return dao.observeBlocksForDate(date.toEpochDay()).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getBlocksForDate(date: LocalDate): List<PlannerBlock> {
        return dao.getBlocksForDate(date.toEpochDay()).map { it.toDomain() }
    }

    suspend fun createBlock(date: LocalDate, startMinutes: Int, title: String): PlannerWriteResult {
        return writeCatching {
            var result: PlannerWriteResult = PlannerWriteResult.NoSpace
            inTransaction {
                val normalizedTitle = normalizeTitle(title)
                val snappedStart = TimeSnapper.floorToValidStart(startMinutes)
                val dateKey = date.toEpochDay()
                val nextStartMinutes = dao.getNextStartMinutes(dateKey, snappedStart)
                val previousDuration = dao.getLatestPreviousDurationForTitle(
                    title = normalizedTitle,
                    dateEpochDay = dateKey,
                    startMinutes = snappedStart
                )
                val duration = if (previousDuration != null) {
                    capDurationAtNextStart(
                        startMinutes = snappedStart,
                        durationMinutes = TimeSnapper.clampDuration(snappedStart, previousDuration),
                        nextStartMinutes = nextStartMinutes
                    )
                } else {
                    TimeSnapper.defaultDurationForStart(snappedStart, nextStartMinutes)
                }
                val block = PlannerBlockEntity(
                    dateEpochDay = dateKey,
                    title = normalizedTitle,
                    startMinutes = snappedStart,
                    durationMinutes = duration
                )
                val blocks = dao.getPotentiallyOverlappingBlocks(
                    dateEpochDay = dateKey,
                    startMinutes = block.startMinutes,
                    endMinutes = block.startMinutes + duration,
                    excludedBlockId = block.id
                ).map { it.toDomain() }
                val fittedDuration = OverlapPolicy.largestValidDuration(
                    blocks = blocks,
                    candidate = block.toDomain(),
                    preferredDurationMinutes = duration
                ) ?: return@inTransaction
                val fittedBlock = block.copy(durationMinutes = fittedDuration)
                validateTime(fittedBlock.startMinutes, fittedBlock.durationMinutes)
                dao.insertBlock(fittedBlock)
                result = PlannerWriteResult.Success
            }
            result
        }
    }

    suspend fun updateTitle(id: Long, title: String): PlannerWriteResult {
        return writeCatching {
            val updated = dao.updateTitle(id, normalizeTitle(title))
            if (updated == 1) PlannerWriteResult.Success else PlannerWriteResult.MissingBlock
        }
    }

    suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): PlannerWriteResult {
        return writeCatching {
            var result: PlannerWriteResult = PlannerWriteResult.MissingBlock
            inTransaction {
                val current = dao.getBlock(id) ?: return@inTransaction
                val snappedStartRaw = TimeSnapper.floorToValidStart(startMinutes)
                val snappedDuration = TimeSnapper.clampDuration(
                    startMinutes = snappedStartRaw,
                    durationMinutes = TimeSnapper.snapDurationToNearest(durationMinutes)
                )
                val snappedStart = TimeSnapper.clampStart(
                    startMinutes = snappedStartRaw,
                    durationMinutes = snappedDuration
                )
                validateTime(snappedStart, snappedDuration)

                val candidate = current.copy(
                    startMinutes = snappedStart,
                    durationMinutes = snappedDuration
                )
                val blocks = dao.getPotentiallyOverlappingBlocks(
                    dateEpochDay = current.dateEpochDay,
                    startMinutes = snappedStart,
                    endMinutes = snappedStart + snappedDuration,
                    excludedBlockId = id
                ).map { it.toDomain() }
                if (!OverlapPolicy.canPlace(blocks, candidate.toDomain())) {
                    result = PlannerWriteResult.RejectedOverlap
                    return@inTransaction
                }
                val updated = dao.updateTime(id, snappedStart, snappedDuration)
                result = if (updated == 1) {
                    PlannerWriteResult.Success
                } else {
                    PlannerWriteResult.MissingBlock
                }
            }
            result
        }
    }

    suspend fun deleteBlock(id: Long): PlannerWriteResult {
        return writeCatching {
            val deleted = dao.deleteBlockById(id)
            if (deleted == 1) PlannerWriteResult.Success else PlannerWriteResult.MissingBlock
        }
    }

    suspend fun restoreBlock(block: PlannerBlock): PlannerWriteResult {
        return writeCatching {
            var result: PlannerWriteResult = PlannerWriteResult.Failed(
                IllegalStateException("Restore transaction did not complete")
            )
            inTransaction {
                if (dao.getBlock(block.id) != null) {
                    result = PlannerWriteResult.MissingBlock
                    return@inTransaction
                }
                val restored = block.copy(title = normalizeTitle(block.title))
                validateTime(restored.startMinutes, restored.durationMinutes)
                val overlappingBlocks = dao.getPotentiallyOverlappingBlocks(
                    dateEpochDay = restored.date.toEpochDay(),
                    startMinutes = restored.startMinutes,
                    endMinutes = restored.endMinutes,
                    excludedBlockId = restored.id
                ).map { it.toDomain() }
                if (!OverlapPolicy.canPlace(overlappingBlocks, restored)) {
                    result = PlannerWriteResult.RejectedOverlap
                    return@inTransaction
                }
                val restoredId = dao.insertBlock(restored.toEntity())
                result = if (restoredId == -1L) {
                    PlannerWriteResult.Failed(IllegalStateException("Restore insert was ignored"))
                } else {
                    PlannerWriteResult.Success
                }
            }
            result
        }
    }

    suspend fun getBlock(id: Long): PlannerBlock? {
        return dao.getBlock(id)?.toDomain()
    }

    private fun validateTime(startMinutes: Int, durationMinutes: Int) {
        require(startMinutes >= 0)
        require(startMinutes < TimeSnapper.MinutesPerDay)
        require(durationMinutes >= TimeSnapper.MinimumDurationMinutes)
        require(startMinutes + durationMinutes <= TimeSnapper.MinutesPerDay)
    }

    private fun capDurationAtNextStart(
        startMinutes: Int,
        durationMinutes: Int,
        nextStartMinutes: Int?
    ): Int {
        val gapToNext = nextStartMinutes
            ?.takeIf { it > startMinutes }
            ?.let { it - startMinutes }
            ?: return durationMinutes
        return durationMinutes.coerceAtMost(gapToNext.coerceAtLeast(TimeSnapper.MinimumDurationMinutes))
    }
}

private fun normalizeTitle(title: String): String {
    val normalized = title.trim()
    require(normalized.isNotEmpty())
    require(normalized.length <= MaxTitleLength)
    return normalized
}

private suspend fun writeCatching(block: suspend () -> PlannerWriteResult): PlannerWriteResult {
    return try {
        block()
    } catch (throwable: Throwable) {
        when (throwable) {
            is CancellationException -> throw throwable
            is IllegalArgumentException -> PlannerWriteResult.InvalidInput
            else -> PlannerWriteResult.Failed(throwable)
        }
    }
}

private fun PlannerBlockEntity.toDomain(): PlannerBlock {
    return PlannerBlock(
        id = id,
        date = LocalDate.ofEpochDay(dateEpochDay),
        title = title,
        startMinutes = startMinutes,
        durationMinutes = durationMinutes
    )
}

private fun PlannerBlock.toEntity(): PlannerBlockEntity {
    return PlannerBlockEntity(
        id = id,
        dateEpochDay = date.toEpochDay(),
        title = title,
        startMinutes = startMinutes,
        durationMinutes = durationMinutes
    )
}
