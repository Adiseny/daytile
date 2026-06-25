package com.privateplanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannerBlockDao {
    @Query("SELECT * FROM blocks WHERE dateEpochDay = :dateEpochDay ORDER BY startMinutes ASC")
    fun observeBlocksForDate(dateEpochDay: Long): Flow<List<PlannerBlockEntity>>

    @Query("SELECT * FROM blocks WHERE dateEpochDay = :dateEpochDay ORDER BY startMinutes ASC")
    suspend fun getBlocksForDate(dateEpochDay: Long): List<PlannerBlockEntity>

    @Query(
        """
        SELECT * FROM blocks
        WHERE dateEpochDay = :dateEpochDay
            AND id != :excludedBlockId
            AND startMinutes < :endMinutes
            AND startMinutes + durationMinutes > :startMinutes
        ORDER BY startMinutes ASC
        """
    )
    suspend fun getPotentiallyOverlappingBlocks(
        dateEpochDay: Long,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ): List<PlannerBlockEntity>

    @Query(
        """
        SELECT MIN(startMinutes) FROM blocks
        WHERE dateEpochDay = :dateEpochDay AND startMinutes > :startMinutes
        """
    )
    suspend fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int): Int?

    @Query(
        """
        SELECT durationMinutes FROM blocks
        WHERE title = :title
            AND (
                dateEpochDay < :dateEpochDay
                OR (dateEpochDay = :dateEpochDay AND startMinutes < :startMinutes)
            )
        ORDER BY dateEpochDay DESC, startMinutes DESC
        LIMIT 1
        """
    )
    suspend fun getLatestPreviousDurationForTitle(
        title: String,
        dateEpochDay: Long,
        startMinutes: Int
    ): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBlock(block: PlannerBlockEntity): Long

    @Query("UPDATE blocks SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String): Int

    @Query("UPDATE blocks SET startMinutes = :startMinutes, durationMinutes = :durationMinutes WHERE id = :id")
    suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): Int

    @Query("DELETE FROM blocks WHERE id = :id")
    suspend fun deleteBlockById(id: Long): Int

    @Query("SELECT * FROM blocks WHERE id = :id LIMIT 1")
    suspend fun getBlock(id: Long): PlannerBlockEntity?
}
