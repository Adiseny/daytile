package com.privateplanner.data

import com.privateplanner.domain.PlannerBlock

// The planner's queries, implemented by PlannerDatabase on SQLite and by a fake in the
// unit tests. Every call is made from one thread at a time: the app's worker thread.
// Titles compare case-insensitively (the column is COLLATE NOCASE).
interface PlannerBlockDao {
    fun <R> withTransaction(block: () -> R): R

    // A day's blocks by start then id.
    fun getBlocksForDate(dateEpochDay: Long): List<PlannerBlock>

    // Blocks on the day that overlap [startMinutes, endMinutes), other than the excluded one.
    fun getPotentiallyOverlappingBlocks(
        dateEpochDay: Long,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ): List<PlannerBlock>

    // The earliest start on the day after startMinutes, if any.
    fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int): Int?

    // The duration of the title's latest block before the given day and minute, if any.
    fun getLatestPreviousDurationForTitle(title: String, dateEpochDay: Long, startMinutes: Int): Int?

    // Fails on a clashing id rather than replacing the row.
    fun insertBlock(block: PlannerBlock)

    // The epoch minute of the next block starting at or after the given day and minute.
    fun getNextStart(dateEpochDay: Long, startMinutes: Int): Long?

    fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int): List<PlannerBlock>

    // The update and delete calls return the number of rows changed.
    fun updateTitle(id: Long, title: String): Int

    fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): Int

    fun deleteBlockById(id: Long): Int

    fun getBlock(id: Long): PlannerBlock?
}
