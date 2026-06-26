package com.privateplanner.data

import androidx.room.withTransaction
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
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

    suspend fun createBlock(date: LocalDate, startMinutes: Int, title: String): Boolean {
        var created = false
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
            created = true
        }
        return created
    }

    suspend fun updateTitle(id: Long, title: String) {
        dao.updateTitle(id, normalizeTitle(title))
    }

    suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int) {
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
            if (!OverlapPolicy.canPlace(blocks, candidate.toDomain())) return@inTransaction
            dao.updateTime(id, snappedStart, snappedDuration)
        }
    }

    suspend fun deleteBlock(id: Long) {
        dao.deleteBlockById(id)
    }

    suspend fun restoreBlock(block: PlannerBlock) {
        dao.insertBlock(block.toEntity())
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
    return normalized
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
