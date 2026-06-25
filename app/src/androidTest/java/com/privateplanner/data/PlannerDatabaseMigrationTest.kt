package com.privateplanner.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerDatabaseMigrationTest {
    @Test
    fun migrateFromVersionOnePreservesBlocksInCurrentSchema() {
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
            setVersion(1)
            close()
        }

        val database = Room.databaseBuilder(context, PlannerDatabase::class.java, TestDatabase)
            .addMigrations(*PlannerMigrations)
            .allowMainThreadQueries()
            .build()

        try {
            val block = runBlocking {
                database.blockDao()
                    .getBlocksForDate(LocalDate.of(2026, 5, 29).toEpochDay())
                    .single()
            }

            assertEquals(1L, block.id)
            assertEquals(LocalDate.of(2026, 5, 29).toEpochDay(), block.dateEpochDay)
            assertEquals("Focus", block.title)
            assertEquals(540, block.startMinutes)
            assertEquals(45, block.durationMinutes)

            val reusedDuration = runBlocking {
                database.blockDao().getLatestPreviousDurationForTitle(
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

    private companion object {
        const val TestDatabase = "planner-migration-test"
    }
}
