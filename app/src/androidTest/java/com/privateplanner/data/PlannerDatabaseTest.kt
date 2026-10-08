package com.privateplanner.data

import com.privateplanner.domain.PlannerBlock
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.system.Os
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
            assertEquals(1, dao.updateTime(day, 2, 750, 30))
            assertEquals(1, dao.updateTitle(day, 2, "Late lunch"))
            assertEquals(0, dao.updateTitle(day, 99, "Missing"))
            // A block is its day's: the same id on another day is another block's.
            assertEquals(0, dao.updateTitle(day + 1, 2, "Wrong day"))
            assertEquals(0, dao.updateTime(day + 1, 2, 0, 10))
            assertEquals(0, dao.deleteBlock(day + 1, 2))
            assertNull(dao.getBlock(day + 1, 2))
            assertEquals(PlannerBlock(2, LocalDate.ofEpochDay(day), "Late lunch", 750, 30), dao.getBlock(day, 2))
            assertThrows(SQLiteConstraintException::class.java) {
                dao.insertBlock(PlannerBlock(1, LocalDate.ofEpochDay(day), "Clash", 540, 5))
            }
            assertEquals(1, dao.deleteBlock(day, 1))
            assertEquals(0, dao.deleteBlock(day, 1))
            assertEquals(listOf(2L), dao.getBlocksForDate(day).map { it.id })
            // The newest block's id is not handed out again once it has gone.
            assertEquals(1, dao.deleteBlock(day + 1, 3))
            dao.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day + 1), title = "Swim", startMinutes = 540, durationMinutes = 30))
            assertEquals(listOf(4L), dao.getBlocksForDate(day + 1).map { it.id })
            // A block put back keeps its id and leaves the count alone.
            dao.insertBlock(PlannerBlock(3, LocalDate.ofEpochDay(day + 1), "Gym", 540, 30))
            dao.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day + 1), title = "Run", startMinutes = 540, durationMinutes = 30))
            assertEquals(listOf(3L, 4L, 5L), dao.getBlocksForDate(day + 1).map { it.id })
            assertEquals(listOf(3L, 4L, 5L), dao.getBlocksStartingAt(day + 1, 540).map { it.id })
            // Several days in one read: each row carries its own day, and the end is not included.
            assertEquals(
                listOf(day to 2L, day + 1 to 3L, day + 1 to 4L, day + 1 to 5L),
                dao.getBlocksForDays(day, day + 2).map { it.date.toEpochDay() to it.id }
            )
            assertEquals(listOf(2L), dao.getBlocksForDays(day, day + 1).map { it.id })
            assertEquals(emptyList<Long>(), dao.getBlocksForDays(day + 2, day + 9).map { it.id })
            // To another day: the block is that day's from then on, with its id and title.
            assertEquals(1, dao.moveBlock(day, 2, day + 3, 600, 30))
            assertNull(dao.getBlock(day, 2))
            assertEquals(PlannerBlock(2, LocalDate.ofEpochDay(day + 3), "Late lunch", 600, 30), dao.getBlock(day + 3, 2))
            assertEquals(0, dao.moveBlock(day, 2, day + 4, 600, 30))
        } finally {
            database.close()
        }
    }

    @Test
    fun twentyYearsOfVersionFiveTasksMigrateAndDeletedPagesAreReclaimed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forget(context, TestDatabase)
        val earlier = context.getDatabasePath(TestDatabase)
        val file = File(context.dataDir, TestDatabase)
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
        val oldBytes = earlier.length()
        val database = PlannerDatabase(context, TestDatabase)
        try {
            assertEquals(7, database.getBlocksForDate(firstDay).size)
            assertFalse("The file leaves the platform's folder", earlier.exists() || File(earlier.path + "-journal").exists())
            assertEquals(firstId, database.getBlocksForDate(firstDay).first().id)
            assertEquals(firstId + count - 1, database.getBlocksForDate(lastDay).last().id)
            assertTrue("Dropping both indices must give their pages back", file.length() < oldBytes / 2)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { check ->
                check.rawQuery("SELECT id, day, title, start, duration FROM blocks ORDER BY id", null).use { rows ->
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
                assertEquals(1024L, check.pageSize)
                // The table alone is left and, before Android 9, the platform's record of the
                // locale. SQLite's record of ids, which cannot be dropped, is not written out
                // again with the file.
                check.rawQuery("SELECT name FROM sqlite_master ORDER BY name", null).use {
                    val names = ArrayList<String>()
                    while (it.moveToNext()) names += it.getString(0)
                    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) emptyList() else listOf("android_metadata")
                    assertEquals(locale + listOf("blocks"), names)
                }
                // Every ordered query reads the table in its own order, with nothing to sort.
                for (query in listOf(
                    "SELECT id FROM blocks WHERE day = ? ORDER BY start, id",
                    "SELECT start FROM blocks WHERE day = ? AND start > ? ORDER BY start LIMIT 1",
                    "SELECT duration FROM blocks WHERE title = ? AND (day, start) < (?, ?) ORDER BY day DESC, start DESC, id DESC LIMIT 1",
                    "SELECT day * 1440 + start FROM blocks WHERE (day, start) >= (?, ?) ORDER BY day, start LIMIT 1",
                    "SELECT id FROM blocks WHERE day = ? AND start = ? ORDER BY id",
                    "SELECT id FROM blocks WHERE day = ? AND id = ?",
                    "UPDATE blocks SET start = ?, duration = ? WHERE day = ? AND id = ?",
                    "DELETE FROM blocks WHERE day = ? AND id = ?"
                )) {
                    check.rawQuery("EXPLAIN QUERY PLAN $query", null).use {
                        assertTrue(it.moveToFirst())
                        do {
                            val step = it.getString(3)
                            assertFalse("$query: $step", step.contains("TEMP B-TREE"))
                            assertTrue("$query: $step", step.contains("PRIMARY KEY"))
                        } while (it.moveToNext())
                    }
                }
            }
            database.withTransaction {
                repeat(count - 7) { database.deleteBlock(firstDay + it / 7, firstId + it) }
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
            forget(context, TestDatabase)
        }
    }

    // 1.4.0's database, whose newest block has been deleted: the blocks keep their ids, and
    // the deleted block's id is still not given to the next one.
    @Test
    fun versionsSixAndSevenKeepEveryBlockAndNeverReuseAnId() {
        for (version in 6..7) checkMigratedIds(version)
    }

    private fun checkMigratedIds(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forget(context, TestDatabase)
        val day = 20_000L
        context.openOrCreateDatabase(TestDatabase, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE IF NOT EXISTS `blocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `title` TEXT NOT NULL COLLATE NOCASE, `startMinutes` INTEGER NOT NULL, `durationMinutes` INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_blocks_dateEpochDay_startMinutes` ON `blocks` (`dateEpochDay`, `startMinutes`)")
            if (version == 6) old.execSQL("CREATE INDEX index_blocks_title_dateEpochDay_startMinutes ON blocks(title, dateEpochDay, startMinutes)")
            old.execSQL("INSERT INTO blocks VALUES (3, $day, 'Later', 600, 30)")
            old.execSQL("INSERT INTO blocks VALUES (4, $day, 'Same start', 540, 45)")
            old.execSQL("INSERT INTO blocks VALUES (9, $day, 'Earlier', 540, 60)")
            old.execSQL("INSERT INTO blocks VALUES (2, ${day - 400}, 'Long ago', 0, 1440)")
            old.execSQL("INSERT INTO blocks VALUES (12, ${day + 1}, 'Deleted', 0, 10)")
            old.execSQL("DELETE FROM blocks WHERE id = 12")
            old.version = version
        }
        val database = PlannerDatabase(context, TestDatabase)
        try {
            assertEquals(
                listOf(
                    PlannerBlock(4, LocalDate.ofEpochDay(day), "Same start", 540, 45),
                    PlannerBlock(9, LocalDate.ofEpochDay(day), "Earlier", 540, 60),
                    PlannerBlock(3, LocalDate.ofEpochDay(day), "Later", 600, 30)
                ),
                database.getBlocksForDate(day)
            )
            assertEquals(PlannerBlock(2, LocalDate.ofEpochDay(day - 400), "Long ago", 0, 1440), database.getBlock(day - 400, 2))
            // The moved file is open once: the opening that brought it over was closed
            // before the move.
            val held = File("/proc/self/fd").listFiles()!!.mapNotNull { runCatching { Os.readlink(it.path) }.getOrNull() }
            assertEquals(held.toString(), 1, held.count { it.endsWith("/$TestDatabase") })
            assertEquals(1440, database.getLatestPreviousDurationForTitle("long AGO", day, 0))
            database.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day + 1), title = "New", startMinutes = 0, durationMinutes = 10))
            assertEquals(13L, database.getBlocksForDate(day + 1).single().id)
        } finally {
            database.close()
            forget(context, TestDatabase)
        }
    }

    // Version 8 was this table in the platform's pages and the platform's folder, and went
    // to no release: a copy that opened one keeps its blocks and its count of ids.
    @Test
    fun versionEightIsWrittenOutInSmallPagesAndMoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forget(context, TestDatabase)
        val earlier = context.getDatabasePath(TestDatabase)
        val day = 20_000L
        context.openOrCreateDatabase(TestDatabase, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE blocks (day INTEGER, start INTEGER, id INTEGER, title TEXT NOT NULL COLLATE NOCASE, duration INTEGER NOT NULL, PRIMARY KEY (day, start, id)) WITHOUT ROWID")
            old.execSQL("INSERT INTO blocks VALUES ($day, 540, 3, 'Kept', 45)")
            old.execSQL("PRAGMA application_id = 7")
            old.version = 8
            assertEquals(4096L, old.pageSize)
        }
        val database = PlannerDatabase(context, TestDatabase)
        try {
            assertEquals(listOf(PlannerBlock(3, LocalDate.ofEpochDay(day), "Kept", 540, 45)), database.getBlocksForDate(day))
            database.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day), title = "Next", startMinutes = 600, durationMinutes = 10))
            assertEquals(listOf(3L, 8L), database.getBlocksForDate(day).map { it.id })
            assertFalse(earlier.exists())
            SQLiteDatabase.openDatabase(File(context.dataDir, TestDatabase).path, null, SQLiteDatabase.OPEN_READONLY).use {
                assertEquals(1024L, it.pageSize)
                assertEquals(9, it.version)
            }
        } finally {
            database.close()
            forget(context, TestDatabase)
        }
    }

    // A new database is one block of storage in the planner's own folder: three pages a
    // quarter the size of the platform's, where the platform's folder and pages took four
    // blocks. Before Android 9 the platform adds its record of the locale, a fourth page.
    @Test
    fun aNewDatabaseIsOneBlockOfStorageOutsideThePlatformsFolder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forget(context, TestDatabase)
        val file = File(context.dataDir, TestDatabase)
        val database = PlannerDatabase(context, TestDatabase)
        try {
            database.open()
            assertEquals(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 3072L else 4096L, file.length())
            assertFalse(context.getDatabasePath(TestDatabase).exists())
            database.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(0), title = "First", startMinutes = 0, durationMinutes = 10))
            assertEquals(1L, database.getBlocksForDate(0).single().id)
            assertTrue(file.length() <= 4096)
        } finally {
            database.close()
        }
        val lastWrite = file.lastModified()
        Thread.sleep(10)
        val reopened = PlannerDatabase(context, TestDatabase)
        try {
            assertEquals("First", reopened.getBlocksForDate(0).single().title)
            assertEquals("Reopening must not write the file out again", lastWrite, file.lastModified())
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(1024L, it.pageSize) }
        } finally {
            reopened.close()
            forget(context, TestDatabase)
        }
    }

    // The file in the planner's folder is the planner's. One in the platform's folder
    // beside it is not moved over it.
    @Test
    fun aFileInThePlatformsFolderNeverReplacesThePlanners() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forget(context, TestDatabase)
        PlannerDatabase(context, TestDatabase).apply {
            insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(0), title = "Mine", startMinutes = 0, durationMinutes = 10))
            close()
        }
        val other = context.getDatabasePath(TestDatabase)
        context.openOrCreateDatabase(TestDatabase, Context.MODE_PRIVATE, null).use { it.version = 9 }
        val bytes = other.length()
        val database = PlannerDatabase(context, TestDatabase)
        try {
            assertEquals("Mine", database.getBlocksForDate(0).single().title)
            assertEquals(bytes, other.length())
        } finally {
            database.close()
            forget(context, TestDatabase)
        }
    }

    // A database nothing was ever added to has no id on record.
    @Test
    fun emptyVersionSevenDatabaseStartsCountingAtOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        forget(context, TestDatabase)
        context.openOrCreateDatabase(TestDatabase, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE blocks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dateEpochDay INTEGER NOT NULL, title TEXT NOT NULL COLLATE NOCASE, startMinutes INTEGER NOT NULL, durationMinutes INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX index_blocks_dateEpochDay_startMinutes ON blocks (dateEpochDay, startMinutes)")
            old.version = 7
        }
        val database = PlannerDatabase(context, TestDatabase)
        try {
            database.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(0), title = "First", startMinutes = 0, durationMinutes = 10))
            assertEquals(1L, database.getBlocksForDate(0).single().id)
        } finally {
            database.close()
            forget(context, TestDatabase)
        }
    }

    // Versions up to 1.3.3 kept a write-ahead log, and a process that ended without closing
    // the database, as every one does, left its latest writes there. They are folded into
    // the file before it is moved, so nothing of it stays in the platform's folder.
    @Test
    fun writeAheadLogLeftByAnOlderVersionIsFoldedIntoTheDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = File(context.dataDir, TestDatabase)
        val abandoned = context.getDatabasePath(AbandonedDatabase)
        forget(context, TestDatabase)
        forget(context, AbandonedDatabase)
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
            execSQL("INSERT INTO blocks (id, day, title, start, duration) VALUES (5, $day, 'Logged', 540, 45)")
            // Closing would fold the log in, so the files are taken as a dead process leaves them.
            assertTrue(File(original.path + "-wal").length() > 0)
            abandoned.parentFile!!.mkdirs()
            original.copyTo(abandoned)
            File(original.path + "-wal").copyTo(File(abandoned.path + "-wal"))
            close()
        }

        val database = PlannerDatabase(context, AbandonedDatabase)
        try {
            assertEquals("Logged", database.getBlocksForDate(day).single().title)
            database.insertBlock(PlannerBlock(date = LocalDate.ofEpochDay(day), title = "After", startMinutes = 600, durationMinutes = 30))
            assertEquals(listOf("Logged", "After"), database.getBlocksForDate(day).map { it.title })
            for (left in listOf("", "-wal", "-shm", "-journal")) assertFalse(left, File(abandoned.path + left).exists())
            val moved = File(context.dataDir, AbandonedDatabase)
            assertTrue(moved.exists())
            assertFalse(File(moved.path + "-wal").exists() || File(moved.path + "-shm").exists())
        } finally {
            database.close()
            forget(context, TestDatabase)
            forget(context, AbandonedDatabase)
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
            dao.insertBlock(original.copy(id = 0))
            try {
                database.withTransaction {
                    dao.updateTitle(0, 1, "Changed")
                    dao.insertBlock(original.copy(id = 0, title = "New", startMinutes = 120))
                    assertEquals(listOf(1L, 2L), dao.getBlocksForDate(0).map { it.id })
                    dao.insertBlock(original.copy(title = "Duplicate"))
                }
                throw AssertionError("The conflicting insert must fail")
            } catch (_: SQLiteConstraintException) {
                assertEquals(listOf(original), dao.getBlocksForDate(0))
            }
            // The count of ids went back with the block that took one.
            dao.insertBlock(original.copy(id = 0, title = "Next", startMinutes = 120))
            assertEquals(listOf(1L, 2L), dao.getBlocksForDate(0).map { it.id })
        } finally {
            database.close()
        }
    }

    // Wherever a test left it: the platform's folder, as an earlier release's, or the planner's.
    private fun forget(context: Context, name: String) {
        context.deleteDatabase(name)
        SQLiteDatabase.deleteDatabase(File(context.dataDir, name))
    }

    private companion object {
        const val TestDatabase = "planner-migration-test"
        const val AbandonedDatabase = "planner-abandoned-test"
    }
}
