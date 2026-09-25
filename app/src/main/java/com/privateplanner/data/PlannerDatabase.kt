package com.privateplanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlannerBlockEntity::class],
    version = 5,
    exportSchema = true
)
abstract class PlannerDatabase : RoomDatabase() {
    abstract fun blockDao(): PlannerBlockDao

    companion object {
        fun create(context: Context): PlannerDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PlannerDatabase::class.java,
                "private_planner.db"
            )
                .addMigrations(*PlannerMigrations)
                .build()
        }
    }
}

// Versions 1 to 4 differ only in title collation and indices: each stores the same five
// columns with text ids and ISO dates. So every one migrates straight to 5 in a single
// table rebuild, and Room takes that direct step instead of walking the chain.
internal val PlannerMigrations = Array<Migration>(4) { index ->
    object : Migration(index + 1, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
            db.execSQL("CREATE INDEX index_blocks_dateEpochDay_startMinutes ON blocks(dateEpochDay, startMinutes)")
            db.execSQL(
                """
                CREATE INDEX index_blocks_title_dateEpochDay_startMinutes_durationMinutes
                ON blocks(title, dateEpochDay, startMinutes, durationMinutes)
                """.trimIndent()
            )
        }
    }
}
