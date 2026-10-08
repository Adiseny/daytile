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
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val reminders = Reminders(context, database).apply { enabled = true }
        val repository = PlannerRepository(database)
        val today = TimeSnapper.dateOf(TimeSnapper.localNowMillis())
        database.insertBlock(PlannerBlock(1, today, "Running", 0, 1440))
        val manager = context.getSystemService(NotificationManager::class.java)
        reminders.sync(today.toEpochDay() * 1440)
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
                    it.id == 1 && it.notification.extras.getString(Notification.EXTRA_TEXT) == title
                }
                found != null
            }
            return found!!
        }
        val original = posted("Running")
        // What the countdown runs to, above the block's name.
        assertEquals("Until 24:00", original.notification.extras.getString(Notification.EXTRA_TITLE))
        SystemClock.sleep(25)
        repository.createBlock(today.plusYears(20), 540, "Future")
        reminders.sync()
        assertEquals("An unrelated edit must not repost the countdown", original.postTime, posted("Running").postTime)

        reminders.sync(clockChanged = true)
        await("A clock change must repost the countdown") { posted("Running").postTime != original.postTime }

        repository.updateTitle(today, 1, "Renamed")
        reminders.sync()
        val renamed = posted("Renamed")
        assertEquals(original.notification.`when`, renamed.notification.`when`)
        assertNotEquals(original.postTime, renamed.postTime)

        // An earlier-ending overlapping tile changes the current tile's column colour.
        database.insertBlock(PlannerBlock(3, today, "Shorter", 0, 10))
        reminders.sync()
        await("A changed column must recolour the reminder") {
            posted("Renamed").notification.color != renamed.notification.color
        }
        repository.deleteBlock(today, 1)
        reminders.sync()
        await("A deleted block must withdraw its reminder") { manager.activeNotifications.none { it.id == 1 } }
        reminders.enabled = false
        reminders.sync()
    }

    @Test
    fun settledDisabledRemindersCompleteWithoutSchedulingAndCanBeEnabledAgain() {
        val reminders = Reminders(context, database).apply { enabled = false }
        val repository = PlannerRepository(database)
        reminders.sync()
        repeat(20) {
            var finished = false
            reminders.syncSoon { finished = true }
            assertTrue("A settled disabled sync must finish immediately", finished)
        }

        repository.createBlock(LocalDate.now().plusDays(1), 540, "Future")
        reminders.enabled = true
        val finished = CountDownLatch(1)
        reminders.syncSoon { finished.countDown() }
        assertTrue("An enabled sync must finish on the worker thread", finished.await(5, TimeUnit.SECONDS))
        assertNotNull("Enabling must invalidate the disabled shortcut", armedAlarm())
        reminders.enabled = false
        reminders.sync()
        assertNull(armedAlarm())
    }

    @Test
    fun theSwitchArmsAndClearsTheAlarm() {
        val reminders = Reminders(context, database)
        val repository = PlannerRepository(database)
        val tomorrow = LocalDate.now().plusDays(1)
        repository.createBlock(tomorrow, 9 * 60, "Standup")

        reminders.enabled = false
        reminders.sync()
        assertNull("Reminders off must not arm an alarm", armedAlarm())

        reminders.enabled = true
        reminders.sync()
        assertNotNull("Reminders on must arm the next block", armedAlarm())

        reminders.enabled = false
        reminders.sync()
        assertNull("Switching reminders off must clear the alarm", armedAlarm())
    }

    @Test
    fun theReceiverIsEnabledOnlyWhileRemindersAreOn() {
        val receiver = ComponentName(context, ReminderReceiver::class.java)
        fun state() = context.packageManager.getComponentEnabledSetting(receiver)

        Reminders(context, database).apply { enabled = true }.sync()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state())

        // A new process starts without knowing the setting, and restates it.
        val reminders = Reminders(context, database).apply { enabled = false }
        reminders.sync()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state())

        reminders.enabled = true
        reminders.sync()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state())

        reminders.enabled = false
        reminders.sync()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state())
    }

    // Releases up to 1.4.0 and the libraries they shipped kept five small files beside the
    // database, each a block of storage. The reminders switch moves from its settings file
    // to the receiver before anything reads it, and nothing reads the rest any more.
    @Test
    fun theFirstReadBringsAnEarlierSwitchOverAndClearsWhatEarlierReleasesLeft() {
        val data = context.dataDir
        val files = File(data, "files")
        val receiver = ComponentName(context, ReminderReceiver::class.java)
        for (on in listOf(true, false)) {
            files.mkdirs()
            val leftovers = listOf(
                File(data, "shared_prefs/reminders.xml"),
                File(data, "shared_prefs/launch.xml"),
                File(data, "cache/private_planner.db.lck"),
                File(files, "profileInstalled"),
                File(files, "profileinstaller_profileWrittenFor_lastUpdateTime.dat")
            )
            context.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", on).putBoolean("splashLight", true).commit()
            context.getSharedPreferences("launch", Context.MODE_PRIVATE).edit().putBoolean("splashLight", true).commit()
            leftovers.drop(2).forEach { it.writeText("left") }
            leftovers.forEach { assertTrue(it.path, it.exists()) }
            // As releases up to 1.3.3 left the receiver: never switched either way.
            context.packageManager.setComponentEnabledSetting(
                receiver, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
            )
            val reminders = Reminders(context, database)
            assertEquals("The switch an earlier release left", on, reminders.enabled)
            assertEquals(
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                context.packageManager.getComponentEnabledSetting(receiver)
            )
            leftovers.forEach { assertFalse(it.path, it.exists()) }
            assertFalse("The emptied folders go too", files.exists() || File(data, "shared_prefs").exists())
            // A new process reads the same from the receiver alone, and makes no file.
            assertEquals(on, Reminders(context, database).enabled)
            reminders.enabled = false
            reminders.sync()
            assertFalse(File(data, "shared_prefs").exists())
        }
    }

    // Nothing has ever switched a new install's receiver: that reads as off.
    @Test
    fun anUnsetReceiverReadsAsOff() {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, ReminderReceiver::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
        )
        val reminders = Reminders(context, database)
        assertFalse(reminders.enabled)
        reminders.sync()
        assertEquals(
            "The first sync settles it, so no broadcast starts the process",
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(ComponentName(context, ReminderReceiver::class.java))
        )
    }

    @Test
    fun everyWriteKeepsTheAlarmInStep() {
        val reminders = Reminders(context, database).apply { enabled = true }
        val repository = PlannerRepository(database) { reminders.sync() }
        val tomorrow = LocalDate.now().plusDays(1)

        repository.createBlock(tomorrow, 9 * 60, "Standup")
        assertNotNull("Adding a block must arm the alarm", armedAlarm())

        val block = repository.getBlocksForDate(tomorrow).single()
        repository.deleteBlock(tomorrow, block.id)
        assertNull("Deleting the last block must clear the alarm", armedAlarm())
        reminders.enabled = false
        reminders.sync()
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
