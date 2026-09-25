package com.privateplanner.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.privateplanner.domain.TimeSnapper
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "blocks",
    indices = [
        Index(value = ["dateEpochDay", "startMinutes"]),
        Index(value = ["title", "dateEpochDay", "startMinutes", "durationMinutes"])
    ]
)
data class PlannerBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val title: String,
    val startMinutes: Int,
    val durationMinutes: Int
)

private const val BlocksForDateQuery =
    "SELECT * FROM blocks WHERE dateEpochDay = :dateEpochDay ORDER BY startMinutes ASC, id ASC"

@Dao
interface PlannerBlockDao {
    @Query(BlocksForDateQuery)
    fun observeBlocksForDate(dateEpochDay: Long): Flow<List<PlannerBlockEntity>>

    @Query(BlocksForDateQuery)
    suspend fun getBlocksForDate(dateEpochDay: Long): List<PlannerBlockEntity>

    // Only feeds overlap counts, so row order does not matter.
    @Query(
        """
        SELECT * FROM blocks
        WHERE dateEpochDay = :dateEpochDay
            AND id != :excludedBlockId
            AND startMinutes < :endMinutes
            AND startMinutes + durationMinutes > :startMinutes
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

    // The `dateEpochDay <=` bound starts the descending index walk at the given day
    // instead of the title's latest entry.
    @Query(
        """
        SELECT durationMinutes FROM blocks
        WHERE title = :title
            AND dateEpochDay <= :dateEpochDay
            AND (dateEpochDay < :dateEpochDay OR startMinutes < :startMinutes)
        ORDER BY dateEpochDay DESC, startMinutes DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestPreviousDurationForTitle(
        title: String,
        dateEpochDay: Long,
        startMinutes: Int
    ): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBlock(block: PlannerBlockEntity)

    // Reminders keep a single pending alarm: the epoch minute of the next block starting
    // at or after a given day/minute. Answered from the (dateEpochDay, startMinutes)
    // index alone, starting at that day, without touching the table.
    @Query(
        """
        SELECT dateEpochDay * ${TimeSnapper.MinutesPerDay} + startMinutes FROM blocks
        WHERE dateEpochDay >= :dateEpochDay
            AND (dateEpochDay > :dateEpochDay OR startMinutes >= :startMinutes)
        ORDER BY dateEpochDay ASC, startMinutes ASC
        LIMIT 1
        """
    )
    suspend fun getNextStart(dateEpochDay: Long, startMinutes: Int): Long?

    @Query(
        """
        SELECT * FROM blocks
        WHERE dateEpochDay = :dateEpochDay AND startMinutes = :startMinutes
        ORDER BY id ASC
        """
    )
    suspend fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int): List<PlannerBlockEntity>

    @Query("UPDATE blocks SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String): Int

    @Query("UPDATE blocks SET startMinutes = :startMinutes, durationMinutes = :durationMinutes WHERE id = :id")
    suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): Int

    @Query("DELETE FROM blocks WHERE id = :id")
    suspend fun deleteBlockById(id: Long): Int

    @Query("SELECT * FROM blocks WHERE id = :id")
    suspend fun getBlock(id: Long): PlannerBlockEntity?
}
