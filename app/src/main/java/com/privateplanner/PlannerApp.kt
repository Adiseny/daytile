package com.privateplanner

import android.app.Application
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository

class PlannerApp : Application() {
    private val database by lazy(LazyThreadSafetyMode.NONE) { PlannerDatabase.create(this) }

    val reminders: Reminders by lazy(LazyThreadSafetyMode.NONE) { Reminders(this) }

    val repository: PlannerRepository by lazy(LazyThreadSafetyMode.NONE) {
        PlannerRepository(database) { reminders.sync(repository) }
    }
}
