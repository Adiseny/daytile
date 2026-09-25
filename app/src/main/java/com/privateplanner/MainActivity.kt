package com.privateplanner

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.ui.PlannerScreen
import com.privateplanner.ui.PlannerTheme
import com.privateplanner.ui.PlannerViewModel
import com.privateplanner.ui.applyPlannerSystemBars
import com.privateplanner.ui.displayedPaletteForMinute
import java.time.LocalTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the window is attached, so the first frame already has the paper and bars.
        applyPlannerSystemBars(displayedPaletteForMinute(TimeSnapper.minuteOfDay(LocalTime.now())))
        super.onCreate(savedInstanceState)

        val app = application as PlannerApp
        // An explicit key: the default one is built from the class's canonical name by
        // reflection on every lookup.
        val plannerViewModel = ViewModelProvider(
            this,
            viewModelFactory { initializer { PlannerViewModel(app.repository, app.reminders) } }
        )["planner", PlannerViewModel::class.java]
        setContent {
            PlannerTheme {
                PlannerScreen(viewModel = plannerViewModel)
            }
        }
        findViewById<View>(android.R.id.content).filterTouchesWhenObscured = true
    }
}
