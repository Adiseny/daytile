package com.privateplanner.data

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteStatement
import android.os.Build
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeSnapper
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.function.Supplier

private const val DatabaseName = "private_planner.db"
private const val SchemaVersion = 9

// A quarter of the platform's page, which is the file system's block. A database is three
// pages before it holds anything, so the file took three blocks of storage from the start
// and now takes one until it holds some thirty of the planner's blocks, and grows a
// quarter of a block at a time from there.
private const val PageSize = 1024

// The planner's one table on the platform's SQLite. Android builds SQLite with auto-vacuum
// on, so the file gives back the pages a deletion empties without being asked. `name = null`
// opens an in-memory database. Nothing here touches the context before the first use, and
// that use is from one thread at a time, so a transaction's statements always run on the
// thread that began it, as the platform requires, and its statements can be shared.
class PlannerDatabase(private val context: Context, private val name: String? = DatabaseName) : PlannerBlockDao {
    // These statements are rebound and executed again and again. Keep their Java
    // wrappers and bind arrays as well as SQLite's cached native plans.
    private val statements = HashMap<String, SQLiteStatement>(8)

    private var opened: SQLiteDatabase? = null

    // Whether the file was made or brought over in this opening, in the platform's pages.
    private var repack = false

    private val db: SQLiteDatabase get() = opened ?: connect()

    // Opens (creating or migrating) the file ahead of first use.
    fun open() {
        db
    }

    // The file lies in the planner's own folder: the platform's folder for databases would
    // be one more block of storage. A file an earlier release kept there is brought over
    // where it is and closed, which leaves it whole with nothing beside it, then moved out,
    // and the emptied folder goes.
    private fun connect(): SQLiteDatabase {
        var path = name
        if (name != null) {
            val file = File(context.dataDir, name)
            val folder = File(context.dataDir, "databases")
            val earlier = File(folder, name)
            path = file.path
            if (!file.exists() && earlier.exists()) {
                at(earlier.path).close()
                if (earlier.renameTo(file)) {
                    File(earlier.path + "-journal").delete()
                    folder.delete()
                } else {
                    path = earlier.path
                }
            }
        }
        return at(path).also { opened = it }
    }

    private fun at(path: String?): SQLiteDatabase {
        val database = object : SQLiteOpenHelper(context, path, null, SchemaVersion) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(Schema)
                repack = true
            }

            // Every release so far (versions 5 to 7) kept the blocks in the order they were
            // made, with an index by day beside them, SQLite's own record of the last id
            // and, at first, Room's record and an index of titles. The blocks move into the
            // day-ordered table in its own order, and the old table takes its indices with
            // it. So does the platform's record of the locale, which a database opened as
            // below neither needs nor is given again. Version 8, which no release had, is
            // this one in the platform's pages.
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                repack = true
                if (oldVersion == 8) return
                // Nothing, read as 0, where no block was ever added.
                val lastId = db.compileStatement("SELECT MAX(seq) FROM sqlite_sequence").simpleQueryForLong()
                db.execSQL("ALTER TABLE blocks RENAME TO blocks_old")
                db.execSQL(Schema)
                db.execSQL("INSERT INTO blocks SELECT dateEpochDay, startMinutes, id, title, durationMinutes FROM blocks_old ORDER BY 1, 2, 3")
                db.execSQL("DROP TABLE blocks_old")
                db.execSQL("DROP TABLE IF EXISTS room_master_table")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) db.execSQL("DROP TABLE IF EXISTS android_metadata")
                db.execSQL("$LastId = $lastId")
            }
        }.apply {
            // Titles compare with SQLite's own NOCASE, so each open skips loading the locale's
            // collators and a new file carries no table recording the locale.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setOpenParams(
                    SQLiteDatabase.OpenParams.Builder().addOpenFlags(SQLiteDatabase.NO_LOCALIZED_COLLATORS).build()
                )
            }
            // A rollback journal, emptied at every commit, in place of the write-ahead log Room
            // chose (and Android 9 would choose by itself). One thread reads and writes, so
            // the log's concurrent readers were never used, while its two files held some
            // 450 KB beside a database a fraction of that size, and reading took a second
            // connection in every process. A database left in that mode is folded back into
            // its one file the first time it is opened.
            setWriteAheadLoggingEnabled(false)
        }.writableDatabase
        // The platform makes every file in its own page size, which only writing the file
        // out again changes: once, as it is made or brought over. That also fills its pages
        // and leaves out SQLite's emptied record of ids. With no room to do it, the file
        // stays as it is, which works the same.
        if (repack && path != null) {
            try {
                database.execSQL("PRAGMA page_size = $PageSize")
                database.execSQL("VACUUM")
            } catch (_: SQLException) {
            }
        }
        repack = false
        return database
    }

    fun close() {
        statements.values.forEach { it.close() }
        statements.clear()
        opened?.close()
        opened = null
    }

    override fun <R> withTransaction(block: Supplier<R>): R {
        val database = db
        database.beginTransactionNonExclusive()
        try {
            return block.get().also { database.setTransactionSuccessful() }
        } finally {
            database.endTransaction()
        }
    }

    override fun getBlocksForDate(dateEpochDay: Long) = blocks(BlocksForDateQuery, dateEpochDay)

    override fun getPotentiallyOverlappingBlocks(
        dateEpochDay: Long,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ) = blocks(OverlappingQuery, dateEpochDay, excludedBlockId, endMinutes.toLong(), startMinutes.toLong())

    override fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int) =
        scalar(NextStartMinutesQuery, null, dateEpochDay, startMinutes.toLong())?.toInt()

    override fun getLatestPreviousDurationForTitle(title: String, dateEpochDay: Long, startMinutes: Int) =
        scalar(PreviousDurationQuery, title, dateEpochDay, startMinutes.toLong())?.toInt()

    // An id of 0 takes the next one, counted in the file's header: SQLite's application id,
    // which the platform leaves alone, on the page every commit writes anyway. No id is
    // given out twice, as undo and reminders need, and no table of its own keeps the count.
    override fun insertBlock(block: PlannerBlock) {
        var id = block.id
        if (id == 0L) {
            id = statement(LastId).simpleQueryForLong() + 1
            db.execSQL("$LastId = $id")
        }
        write(
            "INSERT INTO blocks (title, id, day, start, duration) VALUES (?, ?, ?, ?, ?)",
            block.title, id, block.date.toEpochDay(), block.startMinutes.toLong(), block.durationMinutes.toLong()
        )
    }

    override fun getNextStart(dateEpochDay: Long, startMinutes: Int) =
        scalar(NextStartQuery, null, dateEpochDay, startMinutes.toLong())

    override fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int) =
        blocks(BlocksStartingAtQuery, dateEpochDay, startMinutes.toLong())

    override fun updateTitle(dateEpochDay: Long, id: Long, title: String) =
        write("UPDATE blocks SET title = ?$OneBlock", title, dateEpochDay, id)

    override fun updateTime(dateEpochDay: Long, id: Long, startMinutes: Int, durationMinutes: Int) =
        write("UPDATE blocks SET start = ?, duration = ?$OneBlock", null, startMinutes.toLong(), durationMinutes.toLong(), dateEpochDay, id)

    override fun deleteBlock(dateEpochDay: Long, id: Long) = write("DELETE FROM blocks$OneBlock", null, dateEpochDay, id)

    override fun getBlock(dateEpochDay: Long, id: Long) =
        blocks("SELECT $BlockColumns FROM blocks$OneBlock", dateEpochDay, id).firstOrNull()

    private fun blocks(sql: String, vararg numbers: Long): List<PlannerBlock> {
        val cursor = db.rawQueryWithFactory(
            { _, driver, table, query ->
                query.bindAll(null, numbers)
                SQLiteCursor(driver, table, query)
            },
            sql,
            null,
            "blocks"
        )
        try {
            if (!cursor.moveToFirst()) return Collections.emptyList()
            // Every block query binds its day first. Share that date across rows instead
            // of copying the same column into SQLite's cursor window for every task.
            val date = LocalDate.ofEpochDay(numbers[0])
            val rows = ArrayList<PlannerBlock>(cursor.count)
            do {
                rows += PlannerBlock(
                    id = cursor.getLong(0),
                    date = date,
                    title = cursor.getString(1),
                    startMinutes = cursor.getInt(2),
                    durationMinutes = cursor.getInt(3)
                )
            } while (cursor.moveToNext())
            return rows
        } finally {
            cursor.close()
        }
    }

    // Always returns a row. Missing titles and alarms are normal, so they must not allocate
    // SQLiteDoneException and its stack trace. No stored time is MIN_VALUE.
    private fun scalar(sql: String, text: String?, vararg numbers: Long): Long? {
        val statement = statement(sql, scalar = true)
        statement.bindAll(text, numbers)
        return statement.simpleQueryForLong().takeUnless { it == Long.MIN_VALUE }
    }

    private fun write(sql: String, text: String?, vararg numbers: Long): Int {
        val statement = statement(sql)
        statement.bindAll(text, numbers)
        return statement.executeUpdateDelete()
    }

    private fun statement(sql: String, scalar: Boolean = false): SQLiteStatement =
        statements.getOrPut(sql) {
            db.compileStatement(if (scalar) "SELECT IFNULL(($sql), ${Long.MIN_VALUE})" else sql)
        }
}

// A statement's text, where it has any, is its first argument, and the rest are numbers,
// bound as numbers: text bound for a number would compare as text against expressions such
// as start + duration.
private fun SQLiteProgram.bindAll(text: String?, numbers: LongArray) {
    var index = 1
    if (text != null) bindString(index++, text)
    for (number in numbers) bindLong(index++, number)
}

private const val BlockColumns = "id, title, start, duration"

// The blocks themselves in the order the planner reads them, by day, start and id, with no
// row ids and so no index beside them: a day is one run of neighbouring rows, and a block
// takes about two thirds of what it took as a row plus an index entry. The key's columns
// cannot be null. A block is found by its day and id, among that day's few rows.
private const val Schema = "CREATE TABLE blocks (day INTEGER, start INTEGER, id INTEGER, " +
    "title TEXT NOT NULL COLLATE NOCASE, duration INTEGER NOT NULL, PRIMARY KEY (day, start, id)) WITHOUT ROWID"

private const val OneBlock = " WHERE day = ? AND id = ?"

private const val LastId = "PRAGMA application_id"

// The orders asked for below are the table's own, so none of them sorts.
private const val BlocksForDateQuery = "SELECT $BlockColumns FROM blocks WHERE day = ? ORDER BY start, id"

// Only feeds overlap counts, so row order does not matter.
private const val OverlappingQuery =
    "SELECT $BlockColumns FROM blocks WHERE day = ? AND id != ? AND start < ? AND start + duration > ?"

private const val NextStartMinutesQuery = "SELECT start FROM blocks WHERE day = ? AND start > ? ORDER BY start LIMIT 1"

// Walks back through the table from the day and minute until a title matches: a handful
// of rows for a title in regular use, and every earlier block for one never used, under a
// millisecond off the main thread after ten years of blocks. An index of titles made that
// instant, and was two fifths of the database. Row-value comparisons are available on
// every supported Android version.
private const val PreviousDurationQuery = "SELECT duration FROM blocks " +
    "WHERE title = ? AND (day, start) < (?, ?) ORDER BY day DESC, start DESC, id DESC LIMIT 1"

// Reminders keep a single pending alarm: the epoch minute of the next block starting at
// or after a given day/minute, which is the first row from there on.
private const val NextStartQuery =
    "SELECT day * ${TimeSnapper.MinutesPerDay} + start FROM blocks WHERE (day, start) >= (?, ?) ORDER BY day, start LIMIT 1"

private const val BlocksStartingAtQuery = "SELECT $BlockColumns FROM blocks WHERE day = ? AND start = ? ORDER BY id"
