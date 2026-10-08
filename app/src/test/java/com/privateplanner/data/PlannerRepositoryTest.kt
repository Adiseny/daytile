package com.privateplanner.data

import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import java.util.function.Supplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerRepositoryTest {
    @Test
    fun creationChecksTheRoundedEndAgainstLegacyBlocks() {
        val date = LocalDate.of(2026, 5, 29)
        val dao = FakePlannerBlockDao(
            List(7) { index -> entity(index + 1L, date.toString(), "Legacy", 554, 60) }
        )

        assertEquals(PlannerWriteResult.Success, PlannerRepository(dao).createBlock(date, 540, "New"))
        // The 14-minute gap rounds to 15; that final minute cannot make an eighth overlap.
        assertEquals(10, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockReusesPreviousDurationForSameTitleIgnoringCase() {
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
    fun createBlockUsesMostRecentPreviousMatchingDuration() {
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
    fun matchingHistoryAtTheSameTimeUsesTheMostRecentlyInsertedBlock() {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-05-28", "Focus", 10 * 60, 25),
                entity(2, "2026-05-28", "Focus", 10 * 60, 40)
            )
        )
        val repository = PlannerRepository(dao)

        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 11 * 60,
            title = "Focus"
        )

        assertEquals(40, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockCapsPreviousMatchingDurationAtNextStart() {
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
    fun createBlockKeepsLongPreviousMatchingDurationWhenItFits() {
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
    fun createBlockReusesDurationAfterPreviousBlockWasResized() {
        val dao = FakePlannerBlockDao(emptyList())
        val repository = PlannerRepository(dao)

        repository.createBlock(
            date = LocalDate.of(2026, 5, 28),
            startMinutes = 9 * 60,
            title = "gym"
        )
        repository.updateTime(
            date = LocalDate.of(2026, 5, 28),
            id = 1,
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
    fun createBlockIgnoresFutureMatchingTitles() {
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
    fun createBlockInFinalFiveMinutesUsesLatestValidStart() {
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
    fun deletedOnlyMatchingTitleDoesNotLeaveDurationHistory() {
        val date = LocalDate.of(2026, 5, 28)
        val dao = FakePlannerBlockDao(
            listOf(entity(1, date.toString(), "Focus", 9 * 60, 25))
        )
        val repository = PlannerRepository(dao)

        repository.deleteBlock(date, 1)
        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 10 * 60,
            title = "focus"
        )

        assertEquals(60, dao.inserted.single().durationMinutes)
    }

    @Test
    fun deletingLatestMatchingTitleFallsBackToPreviousDuration() {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-05-27", "Focus", 9 * 60, 25),
                entity(2, "2026-05-28", "Focus", 9 * 60, 40)
            )
        )
        val repository = PlannerRepository(dao)

        repository.deleteBlock(LocalDate.of(2026, 5, 28), 2)
        repository.createBlock(
            date = LocalDate.of(2026, 5, 29),
            startMinutes = 9 * 60,
            title = "focus"
        )

        assertEquals(25, dao.inserted.single().durationMinutes)
    }

    @Test
    fun createBlockReturnsNoSpaceWhenNoValidDurationFits() {
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
    fun createBlockRejectsTooLongTitle() {
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
    fun aBlockMovesToAnotherDayKeepingItsLength() {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-10-05", "Dentist", 15 * 60 + 30, 45),
                entity(2, "2026-10-07", "Lunch", 12 * 60, 60)
            )
        )
        val repository = PlannerRepository(dao)

        val result = repository.moveToDay(LocalDate.of(2026, 10, 5), 1, LocalDate.of(2026, 10, 7), 9 * 60 + 15)

        assertEquals(PlannerWriteResult.Success, result)
        assertTrue(repository.getBlocksForDate(LocalDate.of(2026, 10, 5)).isEmpty())
        assertEquals(
            listOf(entity(1, "2026-10-07", "Dentist", 9 * 60 + 15, 45), entity(2, "2026-10-07", "Lunch", 12 * 60, 60)),
            repository.getBlocksForDate(LocalDate.of(2026, 10, 7))
        )
    }

    @Test
    fun aMoveToADayWithNoRoomIsRefusedAndChangesNothing() {
        val dao = FakePlannerBlockDao(
            List(7) { index -> entity(index + 1L, "2026-10-07", "Full", 9 * 60, 60) } +
                entity(8, "2026-10-05", "Dentist", 9 * 60, 60)
        )
        val repository = PlannerRepository(dao)

        val result = repository.moveToDay(LocalDate.of(2026, 10, 5), 8, LocalDate.of(2026, 10, 7), 9 * 60)

        assertEquals(PlannerWriteResult.RejectedOverlap, result)
        assertEquals(listOf(entity(8, "2026-10-05", "Dentist", 9 * 60, 60)), repository.getBlocksForDate(LocalDate.of(2026, 10, 5)))
        assertEquals(7, repository.getBlocksForDate(LocalDate.of(2026, 10, 7)).size)
    }

    @Test
    fun blocksLiftedTogetherMoveTogetherAndLeaveTheRestOfTheDayAlone() {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-10-05", "Gym", 7 * 60, 60),
                entity(2, "2026-10-05", "Emails", 8 * 60 + 30, 30),
                entity(3, "2026-10-05", "Lunch", 12 * 60, 60)
            )
        )
        val repository = PlannerRepository(dao)

        val result = repository.shiftBlocks(LocalDate.of(2026, 10, 5), longArrayOf(1, 2), 45)

        assertEquals(PlannerWriteResult.Success, result)
        assertEquals(
            listOf(
                entity(1, "2026-10-05", "Gym", 7 * 60 + 45, 60),
                entity(2, "2026-10-05", "Emails", 9 * 60 + 15, 30),
                entity(3, "2026-10-05", "Lunch", 12 * 60, 60)
            ),
            repository.getBlocksForDate(LocalDate.of(2026, 10, 5))
        )
    }

    @Test
    fun blocksLiftedTogetherAllMoveOrNoneDoes() {
        // Seven blocks stand over the hour from 12:00, where one of the two lifted would land.
        val day = List(7) { index -> entity(index + 10L, "2026-10-05", "Full", 12 * 60, 60) } +
            entity(1, "2026-10-05", "Gym", 7 * 60, 60) + entity(2, "2026-10-05", "Emails", 11 * 60, 30)
        val repository = PlannerRepository(FakePlannerBlockDao(day))
        val before = repository.getBlocksForDate(LocalDate.of(2026, 10, 5))

        assertEquals(PlannerWriteResult.RejectedOverlap, repository.shiftBlocks(LocalDate.of(2026, 10, 5), longArrayOf(1, 2), 60))
        assertEquals(before, repository.getBlocksForDate(LocalDate.of(2026, 10, 5)))
        // And none leaves the day at either end.
        assertEquals(PlannerWriteResult.RejectedOverlap, repository.shiftBlocks(LocalDate.of(2026, 10, 5), longArrayOf(1, 2), -8 * 60))
        assertEquals(before, repository.getBlocksForDate(LocalDate.of(2026, 10, 5)))
        // Lifted blocks that overlap one another keep the room they give each other.
        assertEquals(PlannerWriteResult.Success, repository.shiftBlocks(LocalDate.of(2026, 10, 5), longArrayOf(1, 2), -30))
    }

    @Test
    fun aMoveOfABlockThatHasGoneChangesNothing() {
        val dao = FakePlannerBlockDao(listOf(entity(1, "2026-10-05", "Dentist", 9 * 60, 60)))
        val repository = PlannerRepository(dao)

        val result = repository.moveToDay(LocalDate.of(2026, 10, 6), 1, LocalDate.of(2026, 10, 7), 9 * 60)

        assertEquals(PlannerWriteResult.MissingBlock, result)
        assertEquals(1, repository.getBlocksForDate(LocalDate.of(2026, 10, 5)).size)
    }

    @Test
    fun aWeekIsReadInTheOrderOfItsDaysAndStopsAtItsSunday() {
        val dao = FakePlannerBlockDao(
            listOf(
                entity(1, "2026-10-11", "Sunday", 9 * 60, 60),
                entity(2, "2026-10-05", "Monday late", 18 * 60, 60),
                entity(3, "2026-10-05", "Monday early", 7 * 60, 60),
                entity(4, "2026-10-12", "Next week", 9 * 60, 60),
                entity(5, "2026-10-04", "Last week", 9 * 60, 60)
            )
        )

        val week = PlannerRepository(dao).getBlocksForWeek(LocalDate.of(2026, 10, 5))

        assertEquals(listOf(3L, 2L, 1L), week.map { it.id })
    }

    @Test
    fun updateTimeRejectsEighthOverlapWithoutChangingDuration() {
        val date = "2026-05-29"
        val dao = FakePlannerBlockDao(
            (1L..7L).map { id -> entity(id, date, "Busy $id", 9 * 60, 60) } +
                entity(20, date, "Move me", 10 * 60, 60)
        )
        val repository = PlannerRepository(dao)

        val result = repository.updateTime(
            date = LocalDate.of(2026, 5, 29),
            id = 20,
            startMinutes = 9 * 60,
            durationMinutes = 60
        )

        assertEquals(PlannerWriteResult.RejectedOverlap, result)
        val block = dao.getBlock(LocalDate.of(2026, 5, 29).toEpochDay(), 20)!!
        assertEquals(10 * 60, block.startMinutes)
        assertEquals(60, block.durationMinutes)
    }

    @Test
    fun updateTimeRejectsOverlappingResizeWithoutFittingDuration() {
        val date = "2026-05-29"
        val dao = FakePlannerBlockDao(
            (1L..7L).map { id -> entity(id, date, "Busy $id", 9 * 60 + 30, 30) } +
                entity(20, date, "Resize me", 9 * 60, 30)
        )
        val repository = PlannerRepository(dao)

        val result = repository.updateTime(
            date = LocalDate.of(2026, 5, 29),
            id = 20,
            startMinutes = 9 * 60,
            durationMinutes = 60
        )

        assertEquals(PlannerWriteResult.RejectedOverlap, result)
        val block = dao.getBlock(LocalDate.of(2026, 5, 29).toEpochDay(), 20)!!
        assertEquals(9 * 60, block.startMinutes)
        assertEquals(30, block.durationMinutes)
    }

    @Test
    fun updateTimeInFinalFiveMinutesUsesLatestValidStart() {
        val date = "2026-05-29"
        val dao = FakePlannerBlockDao(
            listOf(entity(1, date, "Move me", 22 * 60, 60))
        )
        val repository = PlannerRepository(dao)

        val result = repository.updateTime(
            date = LocalDate.of(2026, 5, 29),
            id = 1,
            startMinutes = 23 * 60 + 55,
            durationMinutes = 60
        )

        assertEquals(PlannerWriteResult.Success, result)
        val block = dao.getBlock(LocalDate.of(2026, 5, 29).toEpochDay(), 1)!!
        assertEquals(23 * 60 + 50, block.startMinutes)
        assertEquals(10, block.durationMinutes)
    }

    @Test
    fun updateTimeQueriesOnlyPotentialOverlaps() {
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
            date = LocalDate.of(2026, 5, 29),
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
    fun updateTimeReturnsMissingBlockWhenNoRowIsUpdated() {
        val dao = FakePlannerBlockDao(
            listOf(entity(1, "2026-05-29", "Move me", 9 * 60, 60))
        )
        dao.nextUpdateTimeRowCount = 0
        val repository = PlannerRepository(dao)

        val result = repository.updateTime(
            date = LocalDate.of(2026, 5, 29),
            id = 1,
            startMinutes = 10 * 60,
            durationMinutes = 60
        )

        assertEquals(PlannerWriteResult.MissingBlock, result)
        val block = dao.getBlock(LocalDate.of(2026, 5, 29).toEpochDay(), 1)!!
        assertEquals(9 * 60, block.startMinutes)
        assertEquals(60, block.durationMinutes)
    }

    @Test
    fun updateTitleReturnsMissingBlockWhenNoRowUpdated() {
        val dao = FakePlannerBlockDao(emptyList())
        val repository = PlannerRepository(dao)

        val result = repository.updateTitle(LocalDate.of(2026, 5, 29), 1, "Rename")

        assertEquals(PlannerWriteResult.MissingBlock, result)
    }

    @Test
    fun updateTitleRejectsTooLongTitle() {
        val dao = FakePlannerBlockDao(
            listOf(entity(1, "2026-05-29", "Original", 9 * 60, 60))
        )
        val repository = PlannerRepository(dao)

        val result = repository.updateTitle(LocalDate.of(2026, 5, 29), 1, "A".repeat(MaxTitleLength + 1))

        assertEquals(PlannerWriteResult.InvalidInput, result)
        assertEquals("Original", dao.getBlock(LocalDate.of(2026, 5, 29).toEpochDay(), 1)!!.title)
    }

    @Test
    fun deleteBlockReturnsMissingBlockWhenNoRowDeleted() {
        val dao = FakePlannerBlockDao(emptyList())
        val repository = PlannerRepository(dao)

        val result = repository.deleteBlock(LocalDate.of(2026, 5, 29), 1)

        assertEquals(PlannerWriteResult.MissingBlock, result)
    }

    @Test
    fun restoreBlockRejectsOverlap() {
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
        assertNull(dao.getBlock(date.toEpochDay(), 99))
    }

    @Test
    fun restoreBlockReturnsMissingBlockWhenIdAlreadyExists() {
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
        assertEquals("Existing", dao.getBlock(date.toEpochDay(), 1)!!.title)
    }

    private class FakePlannerBlockDao(
        initialBlocks: List<PlannerBlock>
    ) : PlannerBlockDao {
        override fun <R> withTransaction(block: Supplier<R>): R = block.get()

        private val blocks = initialBlocks.toMutableList()
        private var lastId = initialBlocks.maxOfOrNull { it.id } ?: 0L
        val inserted = mutableListOf<PlannerBlock>()
        val overlapQueries = mutableListOf<OverlapQuery>()
        var nextUpdateTimeRowCount: Int? = null

        override fun getBlocksForDate(dateEpochDay: Long): List<PlannerBlock> {
            return getSortedBlocksForDate(dateEpochDay)
        }

        override fun getPotentiallyOverlappingBlocks(
            dateEpochDay: Long,
            startMinutes: Int,
            endMinutes: Int,
            excludedBlockId: Long
        ): List<PlannerBlock> {
            val result = blocks
                .filter { block ->
                    block.date.toEpochDay() == dateEpochDay &&
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

        override fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int): Int? {
            return blocks
                .asSequence()
                .filter { block -> block.date.toEpochDay() == dateEpochDay && block.startMinutes > startMinutes }
                .minOfOrNull { block -> block.startMinutes }
        }

        override fun getLatestPreviousDurationForTitle(
            title: String,
            dateEpochDay: Long,
            startMinutes: Int
        ): Int? {
            return blocks
                .asSequence()
                .filter { block -> block.title.equals(title, ignoreCase = true) }
                .filter { block ->
                    block.date.toEpochDay() < dateEpochDay ||
                        (block.date.toEpochDay() == dateEpochDay && block.startMinutes < startMinutes)
                }
                .maxWithOrNull(
                    compareBy<PlannerBlock> { it.date.toEpochDay() }
                        .thenBy { it.startMinutes }
                        .thenBy { it.id }
                )
                ?.durationMinutes
        }

        override fun getNextStart(dateEpochDay: Long, startMinutes: Int) =
            blocks.filter {
                it.date.toEpochDay() > dateEpochDay ||
                    (it.date.toEpochDay() == dateEpochDay && it.startMinutes >= startMinutes)
            }.minOfOrNull { it.date.toEpochDay() * TimeSnapper.MinutesPerDay + it.startMinutes }

        override fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int) =
            blocks.filter { it.date.toEpochDay() == dateEpochDay && it.startMinutes == startMinutes }
                .sortedBy { it.id }

        override fun insertBlock(block: PlannerBlock) {
            val stored = if (block.id == 0L) block.copy(id = ++lastId) else block
            blocks += stored
            inserted += stored
        }

        override fun updateTitle(dateEpochDay: Long, id: Long, title: String): Int {
            var updated = 0
            blocks.replaceAll { existing ->
                if (existing.date.toEpochDay() == dateEpochDay && existing.id == id) {
                    updated = 1
                    PlannerBlock(
                        id = existing.id,
                        date = existing.date,
                        title = title,
                        startMinutes = existing.startMinutes,
                        durationMinutes = existing.durationMinutes
                    )
                } else {
                    existing
                }
            }
            return updated
        }

        override fun updateTime(dateEpochDay: Long, id: Long, startMinutes: Int, durationMinutes: Int): Int {
            nextUpdateTimeRowCount?.let { result ->
                nextUpdateTimeRowCount = null
                return result
            }
            var updated = 0
            blocks.replaceAll { existing ->
                if (existing.date.toEpochDay() == dateEpochDay && existing.id == id) {
                    updated = 1
                    PlannerBlock(
                        id = existing.id,
                        date = existing.date,
                        title = existing.title,
                        startMinutes = startMinutes,
                        durationMinutes = durationMinutes
                    )
                } else {
                    existing
                }
            }
            return updated
        }

        override fun getBlocksForDays(firstEpochDay: Long, endEpochDay: Long): List<PlannerBlock> =
            blocks.filter { it.date.toEpochDay() >= firstEpochDay && it.date.toEpochDay() < endEpochDay }
                .sortedWith(compareBy<PlannerBlock> { it.date.toEpochDay() }.thenBy { it.startMinutes }.thenBy { it.id })

        override fun moveBlock(dateEpochDay: Long, id: Long, toEpochDay: Long, startMinutes: Int, durationMinutes: Int): Int {
            var updated = 0
            blocks.replaceAll { existing ->
                if (existing.date.toEpochDay() == dateEpochDay && existing.id == id) {
                    updated = 1
                    existing.copy(date = LocalDate.ofEpochDay(toEpochDay), startMinutes = startMinutes, durationMinutes = durationMinutes)
                } else {
                    existing
                }
            }
            return updated
        }

        override fun deleteBlock(dateEpochDay: Long, id: Long): Int {
            val before = blocks.size
            blocks.removeAll { it.date.toEpochDay() == dateEpochDay && it.id == id }
            return before - blocks.size
        }

        override fun getBlock(dateEpochDay: Long, id: Long): PlannerBlock? {
            return blocks.firstOrNull { it.date.toEpochDay() == dateEpochDay && it.id == id }
        }

        private fun getSortedBlocksForDate(dateEpochDay: Long): List<PlannerBlock> {
            return blocks
                .filter { it.date.toEpochDay() == dateEpochDay }
                .sortedWith(compareBy<PlannerBlock> { it.startMinutes }.thenBy { it.id })
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
): PlannerBlock {
    return PlannerBlock(
        id = id,
        date = LocalDate.parse(date),
        title = title,
        startMinutes = startMinutes,
        durationMinutes = durationMinutes
    )
}
