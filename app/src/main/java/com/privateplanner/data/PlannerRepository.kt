package com.privateplanner.data

import androidx.room.withTransaction
import com.privateplanner.BuildConfig
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
    private val inTransaction: suspend (suspend () -> PlannerWriteResult) -> PlannerWriteResult
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
            val normalizedTitle = normalizeTitle(title)
            val snappedStart = TimeSnapper.floorToValidStart(startMinutes)
            val dateKey = date.toEpochDay()
            inTransaction transaction@{
                val nextStartMinutes = dao.getNextStartMinutes(dateKey, snappedStart)
                val previousDuration = dao.getLatestPreviousDurationForTitle(
                    title = normalizedTitle,
                    dateEpochDay = dateKey,
                    startMinutes = snappedStart
                )
                val duration = if (previousDuration != null) {
                    TimeSnapper.capDurationAtNextStart(
                        startMinutes = snappedStart,
                        durationMinutes = TimeSnapper.clampDuration(snappedStart, previousDuration),
                        nextStartMinutes = nextStartMinutes
                    )
                } else {
                    TimeSnapper.defaultDurationForStart(snappedStart, nextStartMinutes)
                }
                val blocks = dao.getPotentiallyOverlappingBlocks(
                    dateEpochDay = dateKey,
                    startMinutes = snappedStart,
                    endMinutes = snappedStart + duration,
                    excludedBlockId = 0
                ).map { it.toDomain() }
                val overlapPolicy = OverlapPolicy.from(blocks, 0)
                val fittedDuration = overlapPolicy.largestValidDuration(
                    startMinutes = snappedStart,
                    preferredDurationMinutes = duration
                ) ?: return@transaction PlannerWriteResult.NoSpace
                validateTime(snappedStart, fittedDuration)
                dao.insertBlock(
                    PlannerBlockEntity(
                        dateEpochDay = dateKey,
                        title = normalizedTitle,
                        startMinutes = snappedStart,
                        durationMinutes = fittedDuration
                    )
                )
                PlannerWriteResult.Success
            }
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
            inTransaction transaction@{
                val current = dao.getBlock(id)
                    ?: return@transaction PlannerWriteResult.MissingBlock
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

                val blocks = dao.getPotentiallyOverlappingBlocks(
                    dateEpochDay = current.dateEpochDay,
                    startMinutes = snappedStart,
                    endMinutes = snappedStart + snappedDuration,
                    excludedBlockId = id
                ).map { it.toDomain() }
                if (!OverlapPolicy.from(blocks, id).canPlace(
                        snappedStart,
                        snappedDuration
                    )
                ) {
                    return@transaction PlannerWriteResult.RejectedOverlap
                }
                val updated = dao.updateTime(id, snappedStart, snappedDuration)
                if (updated == 1) {
                    PlannerWriteResult.Success
                } else {
                    PlannerWriteResult.MissingBlock
                }
            }
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
            inTransaction transaction@{
                if (dao.getBlock(block.id) != null) {
                    return@transaction PlannerWriteResult.MissingBlock
                }
                val restored = block.copy(title = normalizeTitle(block.title))
                validateTime(restored.startMinutes, restored.durationMinutes)
                val overlappingBlocks = dao.getPotentiallyOverlappingBlocks(
                    dateEpochDay = restored.date.toEpochDay(),
                    startMinutes = restored.startMinutes,
                    endMinutes = restored.endMinutes,
                    excludedBlockId = restored.id
                ).map { it.toDomain() }
                if (!OverlapPolicy.from(overlappingBlocks, restored.id).canPlace(
                        restored.startMinutes,
                        restored.durationMinutes
                    )
                ) {
                    return@transaction PlannerWriteResult.RejectedOverlap
                }
                dao.insertBlock(
                    PlannerBlockEntity(
                        id = restored.id,
                        dateEpochDay = restored.date.toEpochDay(),
                        title = restored.title,
                        startMinutes = restored.startMinutes,
                        durationMinutes = restored.durationMinutes
                    )
                )
                PlannerWriteResult.Success
            }
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
    } catch (exception: Exception) {
        when {
            exception is CancellationException -> throw exception
            exception is IllegalArgumentException -> PlannerWriteResult.InvalidInput
            // The app keeps no logs, so debug builds crash on unexpected write
            // failures — otherwise the cause is unrecoverable.
            BuildConfig.DEBUG -> throw exception
            else -> PlannerWriteResult.Failed
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
