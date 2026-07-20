package com.privateplanner

import android.app.Application
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository

class PlannerApp : Application() {
    val repository: PlannerRepository by lazy(LazyThreadSafetyMode.NONE) {
        PlannerRepository(PlannerDatabase.create(this))
    }
}
