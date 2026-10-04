package com.privateplanner

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeSnapper
import android.os.SystemClock
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        database = PlannerDatabase(context, name = null)
        clearArmedAlarm()
    }

    @After
    fun tearDown() {
        database.close()
        clearArmedAlarm()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @Test
    fun unchangedRemindersKeepTheirCountdownAndEditsStillRefreshThem() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
        }
        val reminders = Reminders(context).apply { enabled = true }
        val repository = PlannerRepository(database)
        val today = TimeSnapper.dateOf(TimeSnapper.localNowMillis())
        database.insertBlock(PlannerBlock(1, today, "Running", 0, 1440))
        val manager = context.getSystemService(NotificationManager::class.java)
        reminders.sync(repository, today.toEpochDay() * 1440)
        fun await(message: String, check: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!check()) {
                kotlin.check(SystemClock.uptimeMillis() < deadline) { message }
                SystemClock.sleep(10)
            }
        }
        fun posted(title: String): StatusBarNotification {
            var found: StatusBarNotification? = null
            await("Notification missing: $title") {
                found = manager.activeNotifications.firstOrNull {
                    it.id == 1 && it.notification.extras.getString(Notification.EXTRA_TITLE) == title
                }
                found != null
            }
            return found!!
        }
        val original = posted("Running")
        SystemClock.sleep(25)
        repository.createBlock(today.plusYears(20), 540, "Future")
        reminders.sync(repository)
        assertEquals("An unrelated edit must not repost the countdown", original.postTime, posted("Running").postTime)

        reminders.sync(repository, clockChanged = true)
        await("A clock change must repost the countdown") { posted("Running").postTime != original.postTime }

        repository.updateTitle(1, "Renamed")
        reminders.sync(repository)
        val renamed = posted("Renamed")
        assertEquals(original.notification.`when`, renamed.notification.`when`)
        assertNotEquals(original.postTime, renamed.postTime)

        // An earlier-ending overlapping tile changes the current tile's column colour.
        database.insertBlock(PlannerBlock(3, today, "Shorter", 0, 10))
        reminders.sync(repository)
        await("A changed column must recolour the reminder") {
            posted("Renamed").notification.color != renamed.notification.color
        }
        repository.deleteBlock(1)
        reminders.sync(repository)
        await("A deleted block must withdraw its reminder") { manager.activeNotifications.none { it.id == 1 } }
        reminders.enabled = false
        reminders.sync(repository)
    }

    @Test
    fun settledDisabledRemindersCompleteWithoutSchedulingAndCanBeEnabledAgain() {
        val reminders = Reminders(context).apply { enabled = false }
        val repository = PlannerRepository(database)
        reminders.sync(repository)
        repeat(20) {
            var finished = false
            reminders.syncSoon(repository) { finished = true }
            assertTrue("A settled disabled sync must finish immediately", finished)
        }

        repository.createBlock(LocalDate.now().plusDays(1), 540, "Future")
        reminders.enabled = true
        val finished = CountDownLatch(1)
        reminders.syncSoon(repository) { finished.countDown() }
        assertTrue("An enabled sync must finish on the worker thread", finished.await(5, TimeUnit.SECONDS))
        assertNotNull("Enabling must invalidate the disabled shortcut", armedAlarm())
        reminders.enabled = false
        reminders.sync(repository)
        assertNull(armedAlarm())
    }

    @Test
    fun theSwitchArmsAndClearsTheAlarm() {
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
    fun theReceiverIsEnabledOnlyWhileRemindersAreOn() {
        val repository = PlannerRepository(database)
        val receiver = ComponentName(context, ReminderReceiver::class.java)
        fun state() = context.packageManager.getComponentEnabledSetting(receiver)

        Reminders(context).apply { enabled = true }.sync(repository)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state())

        // A new process starts without knowing the setting, and restates it.
        val reminders = Reminders(context).apply { enabled = false }
        reminders.sync(repository)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state())

        reminders.enabled = true
        reminders.sync(repository)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state())

        reminders.enabled = false
        reminders.sync(repository)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state())
    }

    @Test
    fun everyWriteKeepsTheAlarmInStep() {
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
