package com.privateplanner

import android.app.Application
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository

class PlannerApp : Application() {
    private val database: PlannerDatabase by lazy {
        PlannerDatabase.create(this)
    }

    val repository: PlannerRepository by lazy {
        PlannerRepository(database)
    }
}
