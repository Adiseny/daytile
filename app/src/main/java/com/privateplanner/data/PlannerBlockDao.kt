package com.privateplanner.data

import com.privateplanner.domain.PlannerBlock
import java.util.function.Supplier

// The planner's queries, implemented by PlannerDatabase on SQLite and by a fake in the
// unit tests. Every call is made from one thread at a time: the app's worker thread.
// Titles compare case-insensitively (the column is COLLATE NOCASE).
interface PlannerBlockDao {
    fun <R> withTransaction(block: Supplier<R>): R

    // A day's blocks by start then id.
    fun getBlocksForDate(dateEpochDay: Long): List<PlannerBlock>

    // The blocks of the days from the first up to, not including, the end: by day, start then id.
    fun getBlocksForDays(firstEpochDay: Long, endEpochDay: Long): List<PlannerBlock>

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

    // A block with an id of 0 is given the next one, which no block has had before. One
    // with an id keeps it: the caller knows that no block has it now.
    fun insertBlock(block: PlannerBlock)

    // The epoch minute of the next block starting at or after the given day and minute.
    fun getNextStart(dateEpochDay: Long, startMinutes: Int): Long?

    fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int): List<PlannerBlock>

    // A block is named by its day and id, as blocks are stored by day. The update and delete
    // calls return the number of rows changed.
    fun updateTitle(dateEpochDay: Long, id: Long, title: String): Int

    fun updateTime(dateEpochDay: Long, id: Long, startMinutes: Int, durationMinutes: Int): Int

    // To another day, where it is named by that day from then on.
    fun moveBlock(dateEpochDay: Long, id: Long, toEpochDay: Long, startMinutes: Int, durationMinutes: Int): Int

    fun deleteBlock(dateEpochDay: Long, id: Long): Int

    fun getBlock(dateEpochDay: Long, id: Long): PlannerBlock?
}
