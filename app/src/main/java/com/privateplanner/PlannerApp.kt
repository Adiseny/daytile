package com.privateplanner

import android.app.Application
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
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
        // rather than crashing a process that may have started for a broadcast.
        thread(name = "planner-warm-up") {
            runCatching {
                preloadReminderSettings()
                database.openHelper.writableDatabase
            }
        }
    }
}
