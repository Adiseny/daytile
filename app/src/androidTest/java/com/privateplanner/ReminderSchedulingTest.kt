package com.privateplanner

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Reminders hold at most one pending alarm, so "is an alarm armed?" is answerable
// by asking whether that single PendingIntent exists.
@RunWith(AndroidJUnit4::class)
class ReminderSchedulingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: PlannerDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, PlannerDatabase::class.java).build()
        clearArmedAlarm()
    }

    @After
    fun tearDown() {
        database.close()
        clearArmedAlarm()
    }

    @Test
    fun theSwitchArmsAndClearsTheAlarm() = runBlocking {
        val reminders = Reminders(context)
        val repository = PlannerRepository(database)
        val tomorrow = LocalDate.now().plusDays(1)
        repository.createBlock(tomorrow, 9 * 60, "Standup")

        reminders.enabled = false
        reminders.sync(repository)
        assertNull("Reminders off must not arm an alarm", armedAlarm())

        reminders.enabled = true
        reminders.sync(repository)
        assertNotNull("Reminders on must arm the next block", armedAlarm())

        reminders.enabled = false
        reminders.sync(repository)
        assertNull("Switching reminders off must clear the alarm", armedAlarm())
    }

    @Test
    fun everyWriteKeepsTheAlarmInStep() = runBlocking {
        val reminders = Reminders(context).apply { enabled = true }
        lateinit var repository: PlannerRepository
        repository = PlannerRepository(database) { reminders.sync(repository) }
        val tomorrow = LocalDate.now().plusDays(1)

        repository.createBlock(tomorrow, 9 * 60, "Standup")
        assertNotNull("Adding a block must arm the alarm", armedAlarm())

        val block = repository.getBlocksForDate(tomorrow).single()
        repository.deleteBlock(block.id)
        assertNull("Deleting the last block must clear the alarm", armedAlarm())
    }

    private fun armedAlarm(): PendingIntent? {
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun clearArmedAlarm() = armedAlarm()?.cancel()
}
