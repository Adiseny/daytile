package com.privateplanner.data

import com.privateplanner.domain.PlannerBlock
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerDatabaseTest {
    @Test
    fun chronologicalQueriesMatchReferenceAcrossDaysTiesAndConcurrentReaders() = runBlocking {
        val database = PlannerDatabase(ApplicationProvider.getApplicationContext(), name = null)
        val blocks = List(600) { index ->
            PlannerBlock(
                id = index + 1L,
                date = LocalDate.ofEpochDay(index % 6 - 2L),
                title = if (index % 3 == 0) "Other" else if (index % 2 == 0) "FOCUS" else "Focus",
                startMinutes = (index / 6 % 24) * 60,
                durationMinutes = 10 + index % 9 * 5
            )
        }
        val order = compareBy<PlannerBlock> { it.date }.thenBy { it.startMinutes }.thenBy { it.id }
        try {
            database.withTransaction { blocks.forEach { database.insertBlock(it) } }
            (-3L..4L).map { day ->
                async(Dispatchers.Default) {
                    for (minute in listOf(-1, 0, 1, 60, 61, 715, 720, 1430, 1440)) {
                        val next = blocks.filter {
                            it.date.toEpochDay() > day || it.date.toEpochDay() == day && it.startMinutes >= minute
                        }.minWithOrNull(order)
                        assertEquals(
                            "Next start at $day/$minute",
                            next?.let { it.date.toEpochDay() * 1440 + it.startMinutes },
                            database.getNextStart(day, minute)
                        )
                        for (title in listOf("focus", "Other", "Missing")) {
                            val previous = blocks.filter {
                                it.title.equals(title, ignoreCase = true) &&
                                    (it.date.toEpochDay() < day || it.date.toEpochDay() == day && it.startMinutes < minute)
                            }.maxWithOrNull(order)
                            assertEquals(
                                "Previous $title at $day/$minute",
                                previous?.durationMinutes,
                                database.getLatestPreviousDurationForTitle(title, day, minute)
                            )
                        }
                    }
                }
            }.awaitAll()
            Unit
        } finally {
            database.close()
        }
    }

    // Room checked every query when it compiled; this checks each against SQLite itself.
    @Test
    fun everyQueryAnswersFromSqlite() = runBlocking {
        val database = PlannerDatabase(ApplicationProvider.getApplicationContext(), name = null)
        val dao = database
        try {
            val day = 20_000L
            dao.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day), title = "Focus", startMinutes = 540, durationMinutes = 60))
            dao.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day), title = "Lunch", startMinutes = 720, durationMinutes = 45))
            dao.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day + 1), title = "Gym", startMinutes = 540, durationMinutes = 30))

            assertEquals(listOf(1L, 2L), dao.getBlocksForDate(day).map { it.id })
            // start + duration is compared as a number: 9:30-9:45 lies inside Focus, and
            // 10:00-12:00 only touches both blocks' ends.
            assertEquals(listOf(1L), dao.getPotentiallyOverlappingBlocks(day, 570, 585, 0).map { it.id })
            assertEquals(emptyList<Long>(), dao.getPotentiallyOverlappingBlocks(day, 570, 585, 1).map { it.id })
            assertEquals(emptyList<Long>(), dao.getPotentiallyOverlappingBlocks(day, 600, 720, 0).map { it.id })
            assertEquals(720, dao.getNextStartMinutes(day, 540))
            assertNull(dao.getNextStartMinutes(day, 720))
            assertEquals(60, dao.getLatestPreviousDurationForTitle("FOCUS", day + 1, 540))
            assertNull(dao.getLatestPreviousDurationForTitle("Focus", day, 540))
            assertEquals((day + 1) * 1440 + 540, dao.getNextStart(day, 721))
            assertNull(dao.getNextStart(day + 1, 541))
            assertEquals(listOf(1L), dao.getBlocksStartingAt(day, 540).map { it.id })
            assertEquals(1, dao.updateTime(2, 750, 30))
            assertEquals(1, dao.updateTitle(2, "Late lunch"))
            assertEquals(0, dao.updateTitle(99, "Missing"))
            assertEquals(PlannerBlock(2, LocalDate.ofEpochDay(day), "Late lunch", 750, 30), dao.getBlock(2))
            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { dao.insertBlock(PlannerBlock(1, LocalDate.ofEpochDay(day), "Clash", 0, 5)) }
            }

            val seen = Channel<List<Long>>(Channel.UNLIMITED)
            val observer = launch(Dispatchers.Default) {
                dao.observeBlocksForDate(day).collect { blocks -> seen.send(blocks.map { it.id }) }
            }
            assertEquals(listOf(1L, 2L), withTimeout(5_000) { seen.receive() })
            assertEquals(1, dao.deleteBlockById(1))
            assertEquals(listOf(2L), withTimeout(5_000) { seen.receive() })
            observer.cancel()
        } finally {
            database.close()
        }
    }

    @Test
    fun everyLegacySchemaPreservesBlocks() {
        for (version in 1..4) migrateFromVersion(version)
    }

    private fun migrateFromVersion(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TestDatabase)

        context.openOrCreateDatabase(TestDatabase, Context.MODE_PRIVATE, null).apply {
            execSQL(
                """
                CREATE TABLE blocks (
                    id TEXT NOT NULL,
                    date TEXT NOT NULL,
                    title TEXT NOT NULL,
                    startMinutes INTEGER NOT NULL,
                    durationMinutes INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO blocks (id, date, title, startMinutes, durationMinutes)
                VALUES ('legacy-id', '2026-05-29', 'Focus', 540, 45)
                """.trimIndent()
            )
            setVersion(version)
            close()
        }

        val database = PlannerDatabase(context, TestDatabase)

        try {
            val block = runBlocking {
                database
                    .getBlocksForDate(LocalDate.of(2026, 5, 29).toEpochDay())
                    .single()
            }

            assertEquals(1L, block.id)
            assertEquals(LocalDate.of(2026, 5, 29).toEpochDay(), block.date.toEpochDay())
            assertEquals("Focus", block.title)
            assertEquals(540, block.startMinutes)
            assertEquals(45, block.durationMinutes)

            val reusedDuration = runBlocking {
                database.getLatestPreviousDurationForTitle(
                    title = "focus",
                    dateEpochDay = LocalDate.of(2026, 5, 30).toEpochDay(),
                    startMinutes = 540
                )
            }
            assertEquals(45, reusedDuration)
        } finally {
            database.close()
            context.deleteDatabase(TestDatabase)
        }
    }

    @Test
    fun scalarQueriesDistinguishMidnightNegativeDatesAndMissingRows() = runBlocking {
        val database = PlannerDatabase(ApplicationProvider.getApplicationContext(), name = null)
        val dao = database
        try {
            dao.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(-1), title = "Past", startMinutes = 1430, durationMinutes = 10))
            dao.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(0), title = "Midnight", startMinutes = 0, durationMinutes = 10))
            assertEquals(-10L, dao.getNextStart(-1, 1430))
            assertEquals(0L, dao.getNextStart(0, 0))
            assertEquals(0, dao.getNextStartMinutes(0, -1))
            assertNull(dao.getNextStartMinutes(0, 0))
            assertNull(dao.getNextStart(0, 1))
            assertNull(dao.getLatestPreviousDurationForTitle("Absent", 0, 0))
        } finally {
            database.close()
        }
    }

    @Test
    fun failedTransactionRollsBackAllWrites() = runBlocking {
        val database = PlannerDatabase(ApplicationProvider.getApplicationContext(), name = null)
        val dao = database
        val original = PlannerBlock(1, LocalDate.ofEpochDay(0), "Original", 0, 60)
        try {
            dao.insertBlock(original)
            try {
                database.withTransaction {
                    dao.updateTitle(1, "Changed")
                    dao.insertBlock(original.copy(title = "Duplicate"))
                }
                throw AssertionError("The conflicting insert must fail")
            } catch (_: SQLiteConstraintException) {
                assertEquals(listOf(original), dao.getBlocksForDate(0))
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TestDatabase = "planner-migration-test"
    }
}
