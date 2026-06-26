package com.privateplanner.data

import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerRepositoryTest {
    @Test
    fun createBlockReusesPreviousDurationForSameTitleIgnoringCase() = runBlocking {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(
                    id = 1,
                    date = "2026-05-28",
                    title = "Morning run",
                    startMinutes = 8 * 60,
                    durationMinutes = 25
                )
            )
        )
        val repository = PlannerRepository(dao)

        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 9 * 60,
            title = "morning RUN"
        )

        assertEquals(25, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockUsesMostRecentPreviousMatchingDuration() = runBlocking {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-05-27", "Focus", 9 * 60, 30),
                entity(2, "2026-05-28", "Focus", 10 * 60, 45)
            )
        )
        val repository = PlannerRepository(dao)

        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 11 * 60,
            title = "Focus"
        )

        assertEquals(45, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockCapsPreviousMatchingDurationAtNextStart() = runBlocking {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-05-28", "Focus", 9 * 60, 75),
                entity(2, "2026-05-29", "Next", 9 * 60 + 30, 45)
            )
        )
        val repository = PlannerRepository(dao)

        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 9 * 60,
            title = "Focus"
        )

        assertEquals(30, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockKeepsLongPreviousMatchingDurationWhenItFits() = runBlocking {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-05-28", "Deep work", 9 * 60, 90)
            )
        )
        val repository = PlannerRepository(dao)

        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 9 * 60,
            title = "deep WORK"
        )

        assertEquals(90, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockReusesDurationAfterPreviousBlockWasResized() = runBlocking {
        val dao = FakePlannerBlockDao(emptyList())
        val repository = PlannerRepository(dao)

        repository.createBlock(
            date = LocalDate.of(2026, 5, 28),
            startMinutes = 9 * 60,
            title = "gym"
        )
        repository.updateTime(
            id = 0,
            startMinutes = 9 * 60,
            durationMinutes = 100
        )
        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 9 * 60,
            title = "gym"
        )

        assertEquals(100, dao.inserted.last().durationMinutes)
    }

    @Test
    fun createBlockIgnoresFutureMatchingTitles() = runBlocking {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-05-30", "Focus", 9 * 60, 45)
            )
        )
        val repository = PlannerRepository(dao)

        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 11 * 60,
            title = "Focus"
        )

        assertEquals(60, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockInFinalFiveMinutesUsesLatestValidStart() = runBlocking {
        val dao = FakePlannerBlockDao(emptyList())
        val repository = PlannerRepository(dao)

        val created = repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 23 * 60 + 55,
            title = "Late note"
        )

        assertEquals(PlannerWriteResult.Success, created)
        assertEquals(23 * 60 + 50, dao.inserted.single().startMinutes)
        assertEquals(10, dao.inserted.single().durationMinutes)
    }

    @Test
    fun deletedOnlyMatchingTitleDoesNotLeaveDurationHistory() = runBlocking {
        val date = LocalDate.of(2026, 5, 28)
        val dao = FakePlannerBlockDao(
            listOf(entity(1, date.toString(), "Focus", 9 * 60, 25))
        )
        val repository = PlannerRepository(dao)

        repository.deleteBlock(1)
        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 10 * 60,
            title = "focus"
        )

        assertEquals(60, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockReturnsNoSpaceWhenNoValidDurationFits() = runBlocking {
        val date = "2026-05-29"
        val dao = FakePlannerBlockDao(
            (1L..7L).map { id -> entity(id, date, "Busy $id", 9 * 60, 60) }
        )
        val repository = PlannerRepository(dao)

        val created = repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 9 * 60,
            title = "Blocked"
        )

        assertEquals(PlannerWriteResult.NoSpace, created)
        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun createBlockRejectsTooLongTitle() = runBlocking {
        val dao = FakePlannerBlockDao(emptyList())
        val repository = PlannerRepository(dao)

        val created = repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 9 * 60,
            title = "A".repeat(MaxTitleLength + 1)
        )

        assertEquals(PlannerWriteResult.InvalidInput, created)
        assertTrue(dao.inserted.isEmpty())
    }

    @Test
    fun updateTimeRejectsEighthOverlapWithoutChangingDuration() = runBlocking {
        val date = "2026-05-29"
        val dao = FakePlannerBlockDao(
            (1L..7L).map { id -> entity(id, date, "Busy $id", 9 * 60, 60) } +
                entity(20, date, "Move me", 10 * 60, 60)
        )
        val repository = PlannerRepository(dao)

        val result = repository.updateTime(
            id = 20,
            startMinutes = 9 * 60,
            durationMinutes = 60
        )

        assertEquals(PlannerWriteResult.RejectedOverlap, result)
        val block = dao.getBlock(20)!!
        assertEquals(10 * 60, block.startMinutes)
        assertEquals(60, block.durationMinutes)
    }

    @Test
    fun updateTimeRejectsOverlappingResizeWithoutFittingDuration() = runBlocking {
        val date = "2026-05-29"
        val dao = FakePlannerBlockDao(
            (1L..7L).map { id -> entity(id, date, "Busy $id", 9 * 60 + 30, 30) } +
                entity(20, date, "Resize me", 9 * 60, 30)
        )
        val repository = PlannerRepository(dao)

        val result = repository.updateTime(
            id = 20,
            startMinutes = 9 * 60,
            durationMinutes = 60
        )

        assertEquals(PlannerWriteResult.RejectedOverlap, result)
        val block = dao.getBlock(20)!!
        assertEquals(9 * 60, block.startMinutes)
        assertEquals(30, block.durationMinutes)
    }

    @Test
    fun updateTimeInFinalFiveMinutesUsesLatestValidStart() = runBlocking {
        val date = "2026-05-29"
        val dao = FakePlannerBlockDao(
            listOf(entity(1, date, "Move me", 22 * 60, 60))
        )
        val repository = PlannerRepository(dao)

        val result = repository.updateTime(
            id = 1,
            startMinutes = 23 * 60 + 55,
            durationMinutes = 60
        )

        assertEquals(PlannerWriteResult.Success, result)
        val block = dao.getBlock(1)!!
        assertEquals(23 * 60 + 50, block.startMinutes)
        assertEquals(10, block.durationMinutes)
    }

    @Test
    fun updateTimeQueriesOnlyPotentialOverlaps() = runBlocking {
        val date = "2026-05-29"
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, date, "Early", 7 * 60, 30),
                entity(2, date, "Overlap", 9 * 60 + 15, 30),
                entity(3, date, "Late", 12 * 60, 30),
                entity(4, date, "Move me", 10 * 60, 30)
            )
        )
        val repository = PlannerRepository(dao)

        repository.updateTime(
            id = 4,
            startMinutes = 9 * 60,
            durationMinutes = 60
        )

        val query = dao.overlapQueries.single()
        assertEquals(9 * 60, query.startMinutes)
        assertEquals(10 * 60, query.endMinutes)
        assertEquals(4, query.excludedBlockId)
        assertEquals(listOf(2L), query.returnedIds)
    }

    @Test
    fun updateTimeReturnsMissingBlockWhenRowCountFailsAfterRead() = runBlocking {
        val dao = FakePlannerBlockDao(
            listOf(entity(1, "2026-05-29", "Move me", 9 * 60, 60))
        )
        dao.nextUpdateTimeRowCount = 0
        val repository = PlannerRepository(dao)

        val result = repository.updateTime(
            id = 1,
            startMinutes = 10 * 60,
            durationMinutes = 60
        )

        assertEquals(PlannerWriteResult.MissingBlock, result)
        val block = dao.getBlock(1)!!
        assertEquals(9 * 60, block.startMinutes)
        assertEquals(60, block.durationMinutes)
    }

    @Test
    fun updateTitleReturnsMissingBlockWhenNoRowUpdated() = runBlocking {
        val dao = FakePlannerBlockDao(emptyList())
        val repository = PlannerRepository(dao)

        val result = repository.updateTitle(1, "Rename")

        assertEquals(PlannerWriteResult.MissingBlock, result)
    }

    @Test
    fun updateTitleRejectsTooLongTitle() = runBlocking {
        val dao = FakePlannerBlockDao(
            listOf(entity(1, "2026-05-29", "Original", 9 * 60, 60))
        )
        val repository = PlannerRepository(dao)

        val result = repository.updateTitle(1, "A".repeat(MaxTitleLength + 1))

        assertEquals(PlannerWriteResult.InvalidInput, result)
        assertEquals("Original", dao.getBlock(1)!!.title)
    }

    @Test
    fun deleteBlockReturnsMissingBlockWhenNoRowDeleted() = runBlocking {
        val dao = FakePlannerBlockDao(emptyList())
        val repository = PlannerRepository(dao)

        val result = repository.deleteBlock(1)

        assertEquals(PlannerWriteResult.MissingBlock, result)
    }

    @Test
    fun restoreBlockRejectsOverlap() = runBlocking {
        val date = LocalDate.of(2026, 5, 29)
        val dao = FakePlannerBlockDao(
            (1L..7L).map { id -> entity(id, date.toString(), "Busy $id", 9 * 60, 60) }
        )
        val repository = PlannerRepository(dao)

        val result = repository.restoreBlock(
            PlannerBlock(
                id = 99,
                date = date,
                title = "Restore me",
                startMinutes = 9 * 60,
                durationMinutes = 60
            )
        )

        assertEquals(PlannerWriteResult.RejectedOverlap, result)
        assertNull(dao.getBlock(99))
    }

    @Test
    fun restoreBlockReturnsMissingBlockWhenIdAlreadyExists() = runBlocking {
        val date = LocalDate.of(2026, 5, 29)
        val dao = FakePlannerBlockDao(
            listOf(entity(1, date.toString(), "Existing", 9 * 60, 60))
        )
        val repository = PlannerRepository(dao)

        val result = repository.restoreBlock(
            PlannerBlock(
                id = 1,
                date = date,
                title = "Restore me",
                startMinutes = 11 * 60,
                durationMinutes = 60
            )
        )

        assertEquals(PlannerWriteResult.MissingBlock, result)
        assertEquals("Existing", dao.getBlock(1)!!.title)
    }

    @Test
    fun restoreBlockReturnsFailedWhenInsertReportsConflict() = runBlocking {
        val date = LocalDate.of(2026, 5, 29)
        val dao = FakePlannerBlockDao(emptyList())
        dao.nextInsertResult = -1L
        val repository = PlannerRepository(dao)

        val result = repository.restoreBlock(
            PlannerBlock(
                id = 99,
                date = date,
                title = "Restore me",
                startMinutes = 9 * 60,
                durationMinutes = 60
            )
        )

        assertTrue(result is PlannerWriteResult.Failed)
        assertNull(dao.getBlock(99))
    }

    private class FakePlannerBlockDao(
        initialBlocks: List<PlannerBlockEntity>
    ) : PlannerBlockDao {
        private val blocks = initialBlocks.toMutableList()
        val inserted = mutableListOf<PlannerBlockEntity>()
        val overlapQueries = mutableListOf<OverlapQuery>()
        var nextInsertResult: Long? = null
        var nextUpdateTimeRowCount: Int? = null

        override fun observeBlocksForDate(dateEpochDay: Long): Flow<List<PlannerBlockEntity>> {
            return flowOf(getSortedBlocksForDate(dateEpochDay))
        }

        override suspend fun getBlocksForDate(dateEpochDay: Long): List<PlannerBlockEntity> {
            return getSortedBlocksForDate(dateEpochDay)
        }

        override suspend fun getPotentiallyOverlappingBlocks(
            dateEpochDay: Long,
            startMinutes: Int,
            endMinutes: Int,
            excludedBlockId: Long
        ): List<PlannerBlockEntity> {
            val result = blocks
                .filter { block ->
                    block.dateEpochDay == dateEpochDay &&
                        block.id != excludedBlockId &&
                        block.startMinutes < endMinutes &&
                        block.startMinutes + block.durationMinutes > startMinutes
                }
                .sortedBy { it.startMinutes }
            overlapQueries += OverlapQuery(
                startMinutes = startMinutes,
                endMinutes = endMinutes,
                excludedBlockId = excludedBlockId,
                returnedIds = result.map { it.id }
            )
            return result
        }

        override suspend fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int): Int? {
            return blocks
                .asSequence()
                .filter { block -> block.dateEpochDay == dateEpochDay && block.startMinutes > startMinutes }
                .minOfOrNull { block -> block.startMinutes }
        }

        override suspend fun getLatestPreviousDurationForTitle(
            title: String,
            dateEpochDay: Long,
            startMinutes: Int
        ): Int? {
            return blocks
                .asSequence()
                .filter { block -> block.title.equals(title, ignoreCase = true) }
                .filter { block ->
                    block.dateEpochDay < dateEpochDay ||
                        (block.dateEpochDay == dateEpochDay && block.startMinutes < startMinutes)
                }
                .maxWithOrNull(compareBy<PlannerBlockEntity> { it.dateEpochDay }.thenBy { it.startMinutes })
                ?.durationMinutes
        }

        override suspend fun insertBlock(block: PlannerBlockEntity): Long {
            nextInsertResult?.let { result ->
                nextInsertResult = null
                if (result != -1L) {
                    blocks += block
                    inserted += block
                }
                return result
            }
            blocks += block
            inserted += block
            return block.id
        }

        override suspend fun updateTitle(id: Long, title: String): Int {
            var updated = 0
            blocks.replaceAll { existing ->
                if (existing.id == id) {
                    updated = 1
                    existing.copy(title = title)
                } else {
                    existing
                }
            }
            return updated
        }

        override suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int): Int {
            nextUpdateTimeRowCount?.let { result ->
                nextUpdateTimeRowCount = null
                return result
            }
            var updated = 0
            blocks.replaceAll { existing ->
                if (existing.id == id) {
                    updated = 1
                    existing.copy(startMinutes = startMinutes, durationMinutes = durationMinutes)
                } else {
                    existing
                }
            }
            return updated
        }

        override suspend fun deleteBlockById(id: Long): Int {
            val before = blocks.size
            blocks.removeAll { it.id == id }
            return before - blocks.size
        }

        override suspend fun getBlock(id: Long): PlannerBlockEntity? {
            return blocks.firstOrNull { it.id == id }
        }

        private fun getSortedBlocksForDate(dateEpochDay: Long): List<PlannerBlockEntity> {
            return blocks
                .filter { it.dateEpochDay == dateEpochDay }
                .sortedBy { it.startMinutes }
        }
    }

    private data class OverlapQuery(
        val startMinutes: Int,
        val endMinutes: Int,
        val excludedBlockId: Long,
        val returnedIds: List<Long>
    )
}

private fun entity(
    id: Long,
    date: String,
    title: String,
    startMinutes: Int,
    durationMinutes: Int
): PlannerBlockEntity {
    return PlannerBlockEntity(
        id = id,
        dateEpochDay = LocalDate.parse(date).toEpochDay(),
        title = title,
        startMinutes = startMinutes,
        durationMinutes = durationMinutes
    )
}
