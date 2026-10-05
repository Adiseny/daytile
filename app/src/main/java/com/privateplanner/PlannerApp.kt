package com.privateplanner

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import com.privateplanner.ui.warmUpInterface
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// The planner's one background thread. Every database call and reminder sync runs on it,
// in the order asked: a transaction's statements share a thread, as the platform requires,
// and nothing needs a lock.
internal val Worker: Executor = Executors.newSingleThreadExecutor { Thread(it, "planner-db") }

private val MainHandler = Handler(Looper.getMainLooper())

// Back to the main thread as soon as it is free. Asynchronous, so the message is not held
// behind a frame that is already scheduled: what was read reaches that frame, not the next.
internal fun onMain(task: Runnable) {
    MainHandler.sendMessage(Message.obtain(MainHandler, task).apply { isAsynchronous = true })
}

class PlannerApp : Application() {
    // None of the three touches its context until it is first used.
    private val database = PlannerDatabase(this)

    val reminders = Reminders(this, database)

    val repository = PlannerRepository(database) { reminders.syncSoon() }

    // The splash this process last asked the system for, which MainActivity records.
    var splashLight: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        // Launch's disk and system work, begun while the activity is still being created:
        // SQLite is open (and an earlier release's database brought over) before the first
        // query, which queues behind this and puts the day's blocks in the first frame
        // rather than a later one, and the reminders switch is known before anything on the
        // main thread asks. Only a head start: a failure is left for the real first use to
        // report as before, rather than crashing a process that may have started for a
        // broadcast. Receivers need both too, so this runs for every process start.
        Worker.execute {
            runCatching {
                database.open()
                reminders.enabled
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
        Thread({ runCatching { warmUpInterface() } }, "planner-warm-up-ui").start()
    }
}
