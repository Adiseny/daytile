package com.privateplanner

import android.app.Application
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import com.privateplanner.ui.warmUpInterface
import kotlin.concurrent.thread

class PlannerApp : Application() {
    // Synchronised, because the warm-up below builds it off the main thread.
    private val database by lazy { PlannerDatabase.create(this) }

    val reminders: Reminders by lazy(LazyThreadSafetyMode.NONE) { Reminders(this) }

    val repository: PlannerRepository by lazy(LazyThreadSafetyMode.NONE) {
        PlannerRepository(database) { reminders.syncSoon(repository) }
    }

    override fun onCreate() {
        super.onCreate()
        // Launch's disk work, begun while the activity is still being created: the
        // reminders switch is in memory when the planner first reads it, and SQLite is
        // open (a few milliseconds with schema validation) in time for the first query to
        // put the day's blocks in the first frame rather than a later one.
        // Only a head start: a failure is left for the real first use to report as before,
        // rather than crashing a process that may have started for a broadcast. Receivers
        // read both too, so this runs for every process start.
        thread(name = "planner-warm-up-disk") {
            runCatching {
                preloadReminderSettings()
                database.openHelper.writableDatabase
            }
        }
    }

    private var interfaceWarmedUp = false

    // The first frame's CPU work, started by the activity rather than here, so a process
    // woken for a reminder or a reboot never lays out text it will not show. Once per
    // process; main thread only.
    fun warmUpInterfaceOnce() {
        if (interfaceWarmedUp) return
        interfaceWarmedUp = true
        thread(name = "planner-warm-up-ui") {
            runCatching { warmUpInterface() }
        }
    }
}
