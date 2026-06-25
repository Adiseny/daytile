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

internal val PlannerMigrations = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5)

internal object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE blocks_new (
                id TEXT NOT NULL,
                date TEXT NOT NULL,
                title TEXT NOT NULL,
                startMinutes INTEGER NOT NULL,
                durationMinutes INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO blocks_new (id, date, title, startMinutes, durationMinutes)
            SELECT id, date, title, startMinutes, durationMinutes FROM blocks
            """.trimIndent()
        )
        db.execSQL("DROP TABLE blocks")
        db.execSQL("ALTER TABLE blocks_new RENAME TO blocks")
        db.execSQL("CREATE INDEX index_blocks_date ON blocks(date)")
        db.execSQL("CREATE INDEX index_blocks_date_startMinutes ON blocks(date, startMinutes)")
    }
}

internal object Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE blocks_new (
                id TEXT NOT NULL,
                date TEXT NOT NULL,
                title TEXT NOT NULL COLLATE NOCASE,
                startMinutes INTEGER NOT NULL,
                durationMinutes INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO blocks_new (id, date, title, startMinutes, durationMinutes)
            SELECT id, date, title, startMinutes, durationMinutes FROM blocks
            """.trimIndent()
        )
        db.execSQL("DROP TABLE blocks")
        db.execSQL("ALTER TABLE blocks_new RENAME TO blocks")
        db.execSQL("CREATE INDEX index_blocks_date_startMinutes ON blocks(date, startMinutes)")
        db.execSQL("CREATE INDEX index_blocks_title_date_startMinutes ON blocks(title, date, startMinutes)")
    }
}

internal object Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_blocks_title_date_startMinutes")
        db.execSQL(
            """
            CREATE INDEX index_blocks_title_date_startMinutes_durationMinutes
            ON blocks(title, date, startMinutes, durationMinutes)
            """.trimIndent()
        )
    }
}

internal object Migration4To5 : Migration(4, 5) {
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
