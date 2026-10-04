package com.privateplanner.data

import com.privateplanner.domain.PlannerBlock
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerDatabaseTest {
    @Test
    fun chronologicalQueriesMatchReferenceAcrossDaysAndTies() {
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
            for (day in -3L..4L) {
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
        } finally {
            database.close()
        }
    }

    // Room checked every query when it compiled; this checks each against SQLite itself.
    @Test
    fun everyQueryAnswersFromSqlite() {
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
                dao.insertBlock(PlannerBlock(1, LocalDate.ofEpochDay(day), "Clash", 0, 5))
            }
            assertEquals(1, dao.deleteBlockById(1))
            assertEquals(0, dao.deleteBlockById(1))
            assertEquals(listOf(2L), dao.getBlocksForDate(day).map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun everyLegacySchemaPreservesBlocks() {
        for (version in 1..4) migrateFromVersion(version)
    }

    @Test
    fun twentyYearsOfVersionFiveTasksMigrateAndDeletedPagesAreReclaimed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TestDatabase)
        val file = context.getDatabasePath(TestDatabase)
        val count = 50_400
        val firstId = 200_000L
        val firstDay = 20_000L
        val lastDay = firstDay + (count - 1) / 7
        // Created as Room created it: by the platform, which builds SQLite with auto-vacuum
        // on. The planner relies on that to give back the pages a deletion empties.
        context.openOrCreateDatabase(TestDatabase, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE blocks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dateEpochDay INTEGER NOT NULL, title TEXT NOT NULL COLLATE NOCASE, startMinutes INTEGER NOT NULL, durationMinutes INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX index_blocks_dateEpochDay_startMinutes ON blocks(dateEpochDay, startMinutes)")
            old.execSQL("CREATE INDEX index_blocks_title_dateEpochDay_startMinutes_durationMinutes ON blocks(title, dateEpochDay, startMinutes, durationMinutes)")
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.beginTransaction()
            try {
                old.compileStatement("INSERT INTO blocks VALUES (?, ?, ?, ?, ?)").use { insert ->
                    repeat(count) { i ->
                        insert.bindLong(1, firstId + i)
                        insert.bindLong(2, firstDay + i / 7)
                        insert.bindString(3, "Task ${i % 30} – 保持")
                        insert.bindLong(4, 480L + i % 7 * 60)
                        insert.bindLong(5, 10L + i % 9 * 5)
                        insert.executeInsert()
                    }
                }
                old.setTransactionSuccessful()
            } finally {
                old.endTransaction()
            }
            old.version = 5
        }
        val oldBytes = file.length()
        val database = PlannerDatabase(context, TestDatabase)
        try {
            assertEquals(7, database.getBlocksForDate(firstDay).size)
            assertEquals(firstId, database.getBlocksForDate(firstDay).first().id)
            assertEquals(firstId + count - 1, database.getBlocksForDate(lastDay).last().id)
            assertTrue("Dropping the title index must give its pages back", file.length() < oldBytes * 2 / 3)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { check ->
                check.rawQuery("SELECT * FROM blocks ORDER BY id", null).use { rows ->
                    repeat(count) { i ->
                        assertTrue(rows.moveToNext())
                        assertEquals(firstId + i, rows.getLong(0))
                        assertEquals(firstDay + i / 7, rows.getLong(1))
                        assertEquals("Task ${i % 30} – 保持", rows.getString(2))
                        assertEquals(480 + i % 7 * 60, rows.getInt(3))
                        assertEquals(10 + i % 9 * 5, rows.getInt(4))
                    }
                    assertFalse(rows.moveToNext())
                }
                check.rawQuery("PRAGMA auto_vacuum", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
                check.rawQuery("SELECT name FROM sqlite_master WHERE name = 'room_master_table' OR name LIKE '%title%'", null).use {
                    assertFalse(it.moveToFirst())
                }
                check.rawQuery(
                    "EXPLAIN QUERY PLAN SELECT durationMinutes FROM blocks WHERE title = ? AND (dateEpochDay, startMinutes) < (?, ?) ORDER BY dateEpochDay DESC, startMinutes DESC, id DESC LIMIT 1",
                    arrayOf("Task 1 – 保持", lastDay.toString(), "900")
                ).use {
                    while (it.moveToNext()) assertFalse(it.getString(3).contains("TEMP B-TREE"))
                }
            }
            database.withTransaction {
                repeat(count - 7) { database.deleteBlockById(firstId + it) }
            }
            assertTrue("Deleting history must return unused pages", file.length() < oldBytes / 20)
            assertEquals(7, database.getBlocksForDate(lastDay).size)
            database.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(lastDay + 1), title = "After", startMinutes = 0, durationMinutes = 10))
            assertEquals(firstId + count, database.getBlocksForDate(lastDay + 1).single().id)
        } finally {
            database.close()
        }
        val lastWrite = file.lastModified()
        Thread.sleep(10)
        val reopened = PlannerDatabase(context, TestDatabase)
        try {
            assertEquals(7, reopened.getBlocksForDate(lastDay).size)
            assertEquals("After", reopened.getBlocksForDate(lastDay + 1).single().title)
            assertEquals("Reopening must not rewrite the database header", lastWrite, file.lastModified())
        } finally {
            reopened.close()
            context.deleteDatabase(TestDatabase)
        }
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
            val block = database.getBlocksForDate(LocalDate.of(2026, 5, 29).toEpochDay()).single()

            assertEquals(1L, block.id)
            assertEquals(LocalDate.of(2026, 5, 29).toEpochDay(), block.date.toEpochDay())
            assertEquals("Focus", block.title)
            assertEquals(540, block.startMinutes)
            assertEquals(45, block.durationMinutes)

            val reusedDuration = database.getLatestPreviousDurationForTitle(
                title = "focus",
                dateEpochDay = LocalDate.of(2026, 5, 30).toEpochDay(),
                startMinutes = 540
            )
            assertEquals(45, reusedDuration)
        } finally {
            database.close()
            context.deleteDatabase(TestDatabase)
        }
    }

    // Versions up to 1.3.3 kept a write-ahead log, and a process that ended without closing
    // the database, as every one does, left its latest writes there.
    @Test
    fun writeAheadLogLeftByAnOlderVersionIsFoldedIntoTheDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = context.getDatabasePath(TestDatabase)
        val abandoned = context.getDatabasePath(AbandonedDatabase)
        context.deleteDatabase(TestDatabase)
        context.deleteDatabase(AbandonedDatabase)
        val day = 20_000L
        PlannerDatabase(context, TestDatabase).apply {
            open()
            close()
        }
        SQLiteDatabase.openDatabase(
            original.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        ).apply {
            execSQL("INSERT INTO blocks (dateEpochDay, title, startMinutes, durationMinutes) VALUES ($day, 'Logged', 540, 45)")
            // Closing would fold the log in, so the files are taken as a dead process leaves them.
            assertTrue(File(original.path + "-wal").length() > 0)
            original.copyTo(abandoned)
            File(original.path + "-wal").copyTo(File(abandoned.path + "-wal"))
            close()
        }

        val database = PlannerDatabase(context, AbandonedDatabase)
        try {
            assertEquals("Logged", database.getBlocksForDate(day).single().title)
            database.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day), title = "After", startMinutes = 600, durationMinutes = 30))
            assertEquals(listOf("Logged", "After"), database.getBlocksForDate(day).map { it.title })
            assertFalse(File(abandoned.path + "-wal").exists())
            assertFalse(File(abandoned.path + "-shm").exists())
        } finally {
            database.close()
            context.deleteDatabase(TestDatabase)
            context.deleteDatabase(AbandonedDatabase)
        }
    }

    @Test
    fun scalarQueriesDistinguishMidnightNegativeDatesAndMissingRows() {
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
    fun failedTransactionRollsBackAllWrites() {
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
        const val AbandonedDatabase = "planner-abandoned-test"
    }
}
