package com.privateplanner.data

import kotlinx.coroutines.flow.Flow

// One row of the `blocks` table (see PlannerDatabase). An id of 0 asks for a new one.
data class PlannerBlockEntity(
    val id: Long = 0,
    val dateEpochDay: Long,
    val title: String,
    val startMinutes: Int,
    val durationMinutes: Int
)

// The planner's queries, implemented by PlannerDatabase on SQLite and by a fake in the
// unit tests. Titles compare case-insensitively (the column is COLLATE NOCASE).
interface PlannerBlockDao {
    // A day's blocks by start then id, re-read after every write.
    fun observeBlocksForDate(dateEpochDay: Long): Flow<List<PlannerBlockEntity>>

    suspend fun getBlocksForDate(dateEpochDay: Long): List<PlannerBlockEntity>

    // Blocks on the day that overlap [startMinutes, endMinutes), other than the excluded one.
    suspend fun getPotentiallyOverlappingBlocks(
        dateEpochDay: Long,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ): List<PlannerBlockEntity>

    // The earliest start on the day after startMinutes, if any.
    suspend fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int): Int?

    // The duration of the title's latest block before the given day and minute, if any.
    suspend fun getLatestPreviousDurationForTitle(
        title: String,
        dateEpochDay: Long,
        startMinutes: Int
    ): Int?

    // Fails on a clashing id rather than replacing the row.
    suspend fun insertBlock(block: PlannerBlockEntity)

    // The epoch minute of the next block starting at or after the given day and minute.
    suspend fun getNextStart(dateEpochDay: Long, startMinutes: Int): Long?

    suspend fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int): List<PlannerBlockEntity>

    // The update and delete calls return the number of rows changed.
    suspend fun updateTitle(id: Long, title: String): Int

    suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): Int

    suspend fun deleteBlockById(id: Long): Int

    suspend fun getBlock(id: Long): PlannerBlockEntity?
}
