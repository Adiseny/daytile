package com.privateplanner.data

import com.privateplanner.domain.PlannerBlock
import kotlinx.coroutines.flow.Flow

// The planner's queries, implemented by PlannerDatabase on SQLite and by a fake in the
// unit tests. Titles compare case-insensitively (the column is COLLATE NOCASE).
interface PlannerBlockDao {
    // A day's blocks by start then id, re-read after every write.
    fun observeBlocksForDate(dateEpochDay: Long): Flow<List<PlannerBlock>>

    suspend fun getBlocksForDate(dateEpochDay: Long): List<PlannerBlock>

    // Blocks on the day that overlap [startMinutes, endMinutes), other than the excluded one.
    suspend fun getPotentiallyOverlappingBlocks(
        dateEpochDay: Long,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ): List<PlannerBlock>

    // The earliest start on the day after startMinutes, if any.
    suspend fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int): Int?

    // The duration of the title's latest block before the given day and minute, if any.
    suspend fun getLatestPreviousDurationForTitle(
        title: String,
        dateEpochDay: Long,
        startMinutes: Int
    ): Int?

    // Fails on a clashing id rather than replacing the row.
    suspend fun insertBlock(block: PlannerBlock)

    // The epoch minute of the next block starting at or after the given day and minute.
    suspend fun getNextStart(dateEpochDay: Long, startMinutes: Int): Long?

    suspend fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int): List<PlannerBlock>

    // The update and delete calls return the number of rows changed.
    suspend fun updateTitle(id: Long, title: String): Int

    suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): Int

    suspend fun deleteBlockById(id: Long): Int

    suspend fun getBlock(id: Long): PlannerBlock?
}
