package com.privateplanner.data

import android.content.Context
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteStatement
import android.os.Build
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import java.util.Collections

private const val DatabaseName = "private_planner.db"
private const val SchemaVersion = 7

// The planner's one table on the platform's SQLite, in the file Room used, so existing
// installs retain their data. Android builds SQLite with auto-vacuum on, so the file gives
// back the pages a deletion empties without being asked. `name = null` opens an in-memory
// database. Used from one thread at a time, so a transaction's statements always run on
// the thread that began it, as the platform requires, and its statements can be shared.
class PlannerDatabase(context: Context, name: String? = DatabaseName) : PlannerBlockDao {
    // These seven statements are rebound and executed again and again. Keep their Java
    // wrappers and bind arrays as well as SQLite's cached native plans.
    private val statements = HashMap<String, SQLiteStatement>(8)

    private val helper = object : SQLiteOpenHelper(context, name, null, SchemaVersion) {
        override fun onCreate(db: SQLiteDatabase) = createSchema(db)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 5) migrateLegacyBlocks(db)
            // What versions 5 and 6 kept beside the table and its day index: Room's own
            // record, and an index of titles that was two fifths of the file.
            db.execSQL("DROP INDEX IF EXISTS index_blocks_title_dateEpochDay_startMinutes_durationMinutes")
            db.execSQL("DROP INDEX IF EXISTS index_blocks_title_dateEpochDay_startMinutes")
            db.execSQL("DROP TABLE IF EXISTS room_master_table")
            createSchema(db)
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
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // Opens (creating or migrating) the file ahead of first use.
    fun open() {
        db
    }

    fun close() {
        statements.values.forEach { it.close() }
        helper.close()
    }

    override fun <R> withTransaction(block: () -> R): R {
        val database = db
        database.beginTransactionNonExclusive()
        try {
            return block().also { database.setTransactionSuccessful() }
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
    ) = blocks(OverlappingQuery, dateEpochDay, excludedBlockId, endMinutes, startMinutes)

    override fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int) =
        scalar(NextStartMinutesQuery, dateEpochDay, startMinutes)?.toInt()

    override fun getLatestPreviousDurationForTitle(title: String, dateEpochDay: Long, startMinutes: Int) =
        scalar(PreviousDurationQuery, title, dateEpochDay, startMinutes)?.toInt()

    // Room's insert: an id of 0 is left to AUTOINCREMENT, and a clashing id aborts
    // rather than replacing a row.
    override fun insertBlock(block: PlannerBlock) {
        statement("INSERT INTO blocks ($BlockColumns) VALUES (NULLIF(?, 0), ?, ?, ?, ?)").apply {
            bindLong(1, block.id)
            bindLong(2, block.date.toEpochDay())
            bindString(3, block.title)
            bindLong(4, block.startMinutes.toLong())
            bindLong(5, block.durationMinutes.toLong())
            executeInsert()
        }
    }

    override fun getNextStart(dateEpochDay: Long, startMinutes: Int) =
        scalar(NextStartQuery, dateEpochDay, startMinutes)

    override fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int) =
        blocks(BlocksStartingAtQuery, dateEpochDay, startMinutes)

    override fun updateTitle(id: Long, title: String) =
        write("UPDATE blocks SET title = ? WHERE id = ?", title, id)

    override fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int) =
        write("UPDATE blocks SET startMinutes = ?, durationMinutes = ? WHERE id = ?", startMinutes, durationMinutes, id)

    override fun deleteBlockById(id: Long) = write("DELETE FROM blocks WHERE id = ?", id)

    override fun getBlock(id: Long) = blocks(BlockByIdQuery, id).firstOrNull()

    // Arguments are bound as their own types: text bound for a number would compare as
    // text against expressions such as startMinutes + durationMinutes.
    private fun blocks(sql: String, vararg args: Any): List<PlannerBlock> {
        val cursor = db.rawQueryWithFactory(
            { _, driver, table, query ->
                query.bindAll(args)
                SQLiteCursor(driver, table, query)
            },
            sql,
            null,
            "blocks"
        )
        try {
            if (!cursor.moveToFirst()) return Collections.emptyList()
            // Every block query selects one day (or one id). Share its date across rows.
            val date = LocalDate.ofEpochDay(cursor.getLong(1))
            val rows = ArrayList<PlannerBlock>(cursor.count)
            do {
                rows += PlannerBlock(
                    id = cursor.getLong(0),
                    date = date,
                    title = cursor.getString(2),
                    startMinutes = cursor.getInt(3),
                    durationMinutes = cursor.getInt(4)
                )
            } while (cursor.moveToNext())
            return rows
        } finally {
            cursor.close()
        }
    }

    // Always returns a row. Missing titles and alarms are normal, so they must not allocate
    // SQLiteDoneException and its stack trace. No stored time is MIN_VALUE.
    private fun scalar(sql: String, vararg args: Any): Long? {
        val statement = statement(sql, scalar = true)
        statement.bindAll(args)
        return statement.simpleQueryForLong().takeUnless { it == Long.MIN_VALUE }
    }

    private fun write(sql: String, vararg args: Any): Int {
        val statement = statement(sql)
        statement.bindAll(args)
        return statement.executeUpdateDelete()
    }

    private fun statement(sql: String, scalar: Boolean = false): SQLiteStatement =
        statements.getOrPut(sql) {
            db.compileStatement(if (scalar) "SELECT IFNULL(($sql), ${Long.MIN_VALUE})" else sql)
        }
}

private fun SQLiteProgram.bindAll(args: Array<out Any>) {
    args.forEachIndexed { index, arg ->
        if (arg is String) bindString(index + 1, arg) else bindLong(index + 1, (arg as Number).toLong())
    }
}

private const val BlockColumns = "id, dateEpochDay, title, startMinutes, durationMinutes"

private const val BlocksForDateQuery =
    "SELECT $BlockColumns FROM blocks WHERE dateEpochDay = ? ORDER BY startMinutes ASC, id ASC"

private const val BlockByIdQuery = "SELECT $BlockColumns FROM blocks WHERE id = ?"

// Only feeds overlap counts, so row order does not matter.
private const val OverlappingQuery = "SELECT $BlockColumns FROM blocks " +
    "WHERE dateEpochDay = ? AND id != ? AND startMinutes < ? AND startMinutes + durationMinutes > ?"

private const val NextStartMinutesQuery =
    "SELECT startMinutes FROM blocks WHERE dateEpochDay = ? AND startMinutes > ? ORDER BY startMinutes LIMIT 1"

// Walks back through the day index from the day and minute, reading each block's title
// until one matches: a handful of rows for a title in regular use, and every earlier block
// for one never used, a few milliseconds off the main thread after ten years of blocks.
// An index of titles made that instant, and was two fifths of the database. Row-value
// comparisons are available on every supported Android version.
private const val PreviousDurationQuery = "SELECT durationMinutes FROM blocks " +
    "WHERE title = ? AND (dateEpochDay, startMinutes) < (?, ?) " +
    "ORDER BY dateEpochDay DESC, startMinutes DESC, id DESC LIMIT 1"

// Reminders keep a single pending alarm: the epoch minute of the next block starting at
// or after a given day/minute. Answered from the (dateEpochDay, startMinutes) index
// alone, starting at that day, without touching the table.
private const val NextStartQuery =
    "SELECT dateEpochDay * ${TimeSnapper.MinutesPerDay} + startMinutes FROM blocks " +
        "WHERE (dateEpochDay, startMinutes) >= (?, ?) ORDER BY dateEpochDay ASC, startMinutes ASC LIMIT 1"

private const val BlocksStartingAtQuery =
    "SELECT $BlockColumns FROM blocks WHERE dateEpochDay = ? AND startMinutes = ? ORDER BY id ASC"

// One index, by day then start. The rowid is its last key, so equal starts come out in
// id order with no temporary sort.
private fun createSchema(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `blocks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`dateEpochDay` INTEGER NOT NULL, `title` TEXT NOT NULL COLLATE NOCASE, " +
            "`startMinutes` INTEGER NOT NULL, `durationMinutes` INTEGER NOT NULL)"
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_blocks_dateEpochDay_startMinutes` " +
            "ON `blocks` (`dateEpochDay`, `startMinutes`)"
    )
}

// Versions 1 to 4 differ only in title collation and indices: each stores the same five
// columns with text ids and ISO dates. So every one migrates straight to 5 in a single
// table rebuild, inside the transaction the platform opens for an upgrade.
private fun migrateLegacyBlocks(db: SQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE blocks_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, dateEpochDay INTEGER NOT NULL, " +
            "title TEXT NOT NULL COLLATE NOCASE, startMinutes INTEGER NOT NULL, durationMinutes INTEGER NOT NULL)"
    )
    db.execSQL(
        "INSERT INTO blocks_new (dateEpochDay, title, startMinutes, durationMinutes) " +
            "SELECT CAST(julianday(date) - julianday('1970-01-01') AS INTEGER), title, startMinutes, durationMinutes " +
            "FROM blocks ORDER BY date, startMinutes"
    )
    db.execSQL("DROP TABLE blocks")
    db.execSQL("ALTER TABLE blocks_new RENAME TO blocks")
}
