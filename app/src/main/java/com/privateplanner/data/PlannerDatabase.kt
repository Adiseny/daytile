package com.privateplanner.data

import android.app.ActivityManager
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteStatement
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeSnapper
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val DatabaseName = "private_planner.db"
private const val SchemaVersion = 5

// The planner's one table on the platform's SQLite, in the file, version and schema Room
// used, so existing installs open unchanged. Room reached the same statements through
// generated code, checked the schema on every open, and logged each write with a
// trigger to tell its observers; every write here goes through the DAO below, which
// tells them itself. `name = null` opens an in-memory database.
class PlannerDatabase(context: Context, name: String? = DatabaseName) : PlannerBlockDao {
    // All access runs on one thread, so a transaction's statements always run on the
    // thread that began it, as the platform requires.
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "planner-db") }.asCoroutineDispatcher()

    // Bumped by every write that changed a row; observed queries re-read on each value.
    private val changes = MutableStateFlow(0)
    // These seven statements are always rebound and executed on the database thread.
    // Keep their Java wrappers and bind arrays as well as SQLite's cached native plans.
    private val statements = HashMap<String, SQLiteStatement>(8)

    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, SchemaVersion) {
        override fun onCreate(db: SQLiteDatabase) = createSchema(db)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = migrateToSchemaFive(db)
    }.apply {
        // Room's automatic journal mode: write-ahead logging except on low-RAM devices.
        setWriteAheadLoggingEnabled(!context.getSystemService(ActivityManager::class.java).isLowRamDevice)
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // Opens (creating or migrating) the file ahead of first use.
    fun open() {
        db
    }

    fun close() {
        statements.values.forEach { it.close() }
        helper.close()
        dispatcher.close()
    }

    suspend fun <R> withTransaction(block: suspend () -> R): R = withContext(dispatcher) {
        val database = db
        database.beginTransactionNonExclusive()
        try {
            block().also { database.setTransactionSuccessful() }
        } finally {
            database.endTransaction()
        }
    }

    override fun observeBlocksForDate(dateEpochDay: Long): Flow<List<PlannerBlock>> =
        changes.map { readBlocks(BlocksForDateQuery, arrayOf(dateEpochDay)) }.flowOn(dispatcher)

    override suspend fun getBlocksForDate(dateEpochDay: Long) = blocks(BlocksForDateQuery, dateEpochDay)

    override suspend fun getPotentiallyOverlappingBlocks(
        dateEpochDay: Long,
        startMinutes: Int,
        endMinutes: Int,
        excludedBlockId: Long
    ) = blocks(OverlappingQuery, dateEpochDay, excludedBlockId, endMinutes, startMinutes)

    override suspend fun getNextStartMinutes(dateEpochDay: Long, startMinutes: Int) =
        scalar(NextStartMinutesQuery, dateEpochDay, startMinutes)?.toInt()

    override suspend fun getLatestPreviousDurationForTitle(title: String, dateEpochDay: Long, startMinutes: Int) =
        scalar(PreviousDurationQuery, title, dateEpochDay, startMinutes)?.toInt()

    // Room's insert: an id of 0 is left to AUTOINCREMENT, and a clashing id aborts
    // rather than replacing a row.
    override suspend fun insertBlock(block: PlannerBlock): Unit = withContext(dispatcher) {
        statement("INSERT INTO blocks ($BlockColumns) VALUES (NULLIF(?, 0), ?, ?, ?, ?)").apply {
            bindLong(1, block.id)
            bindLong(2, block.date.toEpochDay())
            bindString(3, block.title)
            bindLong(4, block.startMinutes.toLong())
            bindLong(5, block.durationMinutes.toLong())
            executeInsert()
        }
        changes.value++
        Unit
    }

    override suspend fun getNextStart(dateEpochDay: Long, startMinutes: Int) =
        scalar(NextStartQuery, dateEpochDay, startMinutes)

    override suspend fun getBlocksStartingAt(dateEpochDay: Long, startMinutes: Int) =
        blocks(BlocksStartingAtQuery, dateEpochDay, startMinutes)

    override suspend fun updateTitle(id: Long, title: String) =
        write("UPDATE blocks SET title = ? WHERE id = ?", title, id)

    override suspend fun updateTime(id: Long, startMinutes: Int, durationMinutes: Int) =
        write("UPDATE blocks SET startMinutes = ?, durationMinutes = ? WHERE id = ?", startMinutes, durationMinutes, id)

    override suspend fun deleteBlockById(id: Long) = write("DELETE FROM blocks WHERE id = ?", id)

    override suspend fun getBlock(id: Long) = blocks(BlockByIdQuery, id).firstOrNull()

    private suspend fun blocks(sql: String, vararg args: Any) = withContext(dispatcher) { readBlocks(sql, args) }

    private fun readBlocks(sql: String, args: Array<out Any>): List<PlannerBlock> =
        query(sql, args).use { cursor ->
            if (!cursor.moveToFirst()) return@use emptyList()
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
            rows
        }

    private suspend fun scalar(sql: String, vararg args: Any): Long? = withContext(dispatcher) {
        // Always return a row. Missing titles/alarms are normal, so they must not
        // allocate SQLiteDoneException and its stack trace. No stored time is MIN_VALUE.
        val statement = statement(sql, scalar = true)
        statement.bindAll(args)
        statement.simpleQueryForLong().takeUnless { it == Long.MIN_VALUE }
    }

    private suspend fun write(sql: String, vararg args: Any): Int = withContext(dispatcher) {
        val statement = statement(sql)
        statement.bindAll(args)
        statement.executeUpdateDelete().also { rows -> if (rows > 0) changes.value++ }
    }

    private fun statement(sql: String, scalar: Boolean = false): SQLiteStatement =
        statements.getOrPut(sql) {
            db.compileStatement(if (scalar) "SELECT IFNULL(($sql), ${Long.MIN_VALUE})" else sql)
        }

    // Arguments are bound as their own types: text bound for a number would compare as
    // text against expressions such as startMinutes + durationMinutes.
    private fun query(sql: String, args: Array<out Any>): Cursor =
        db.rawQueryWithFactory(
            { _, driver, table, query ->
                query.bindAll(args)
                SQLiteCursor(driver, table, query)
            },
            sql,
            null,
            "blocks"
        )
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
private const val OverlappingQuery = """
    SELECT $BlockColumns FROM blocks
    WHERE dateEpochDay = ?
        AND id != ?
        AND startMinutes < ?
        AND startMinutes + durationMinutes > ?
"""

private const val NextStartMinutesQuery =
    "SELECT startMinutes FROM blocks WHERE dateEpochDay = ? AND startMinutes > ? ORDER BY startMinutes LIMIT 1"

// Seek to the day AND minute in the existing index, without scanning the rest of the
// day's entries. Row-value comparisons are available on every supported Android version.
private const val PreviousDurationQuery = """
    SELECT durationMinutes FROM blocks
    WHERE title = ?
        AND (dateEpochDay, startMinutes) < (?, ?)
    ORDER BY dateEpochDay DESC, startMinutes DESC, id DESC
    LIMIT 1
"""

// Reminders keep a single pending alarm: the epoch minute of the next block starting at
// or after a given day/minute. Answered from the (dateEpochDay, startMinutes) index
// alone, starting at that day, without touching the table.
private const val NextStartQuery = """
    SELECT dateEpochDay * ${TimeSnapper.MinutesPerDay} + startMinutes FROM blocks
    WHERE (dateEpochDay, startMinutes) >= (?, ?)
    ORDER BY dateEpochDay ASC, startMinutes ASC
    LIMIT 1
"""

private const val BlocksStartingAtQuery =
    "SELECT $BlockColumns FROM blocks WHERE dateEpochDay = ? AND startMinutes = ? ORDER BY id ASC"

// Exactly the schema Room created, so a fresh install matches an upgraded one.
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
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_blocks_title_dateEpochDay_startMinutes_durationMinutes` " +
            "ON `blocks` (`title`, `dateEpochDay`, `startMinutes`, `durationMinutes`)"
    )
}

// Versions 1 to 4 differ only in title collation and indices: each stores the same five
// columns with text ids and ISO dates. So every one migrates straight to 5 in a single
// table rebuild, inside the transaction the platform opens for an upgrade.
private fun migrateToSchemaFive(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE blocks_new (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            dateEpochDay INTEGER NOT NULL,
            title TEXT NOT NULL COLLATE NOCASE,
            startMinutes INTEGER NOT NULL,
            durationMinutes INTEGER NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        INSERT INTO blocks_new (dateEpochDay, title, startMinutes, durationMinutes)
        SELECT
            CAST(julianday(date) - julianday('1970-01-01') AS INTEGER),
            title,
            startMinutes,
            durationMinutes
        FROM blocks
        ORDER BY date, startMinutes
        """.trimIndent()
    )
    db.execSQL("DROP TABLE blocks")
    db.execSQL("ALTER TABLE blocks_new RENAME TO blocks")
    createSchema(db)
}
