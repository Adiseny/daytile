package com.privateplanner

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable
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
        val launchPalette = displayedPaletteForMinute(TimeSnapper.minuteOfDay(LocalTime.now()))
        window.setBackgroundDrawable(launchPalette.Paper.toArgb().toDrawable())
        applyPlannerSystemBars(launchPalette)
        super.onCreate(savedInstanceState)

        val app = application as PlannerApp
        val plannerViewModel = ViewModelProvider(
            this,
            viewModelFactory { initializer { PlannerViewModel(app.repository, app.reminders) } }
        )[PlannerViewModel::class.java]
        setContent {
            PlannerTheme {
                PlannerScreen(viewModel = plannerViewModel)
            }
        }
        findViewById<View>(android.R.id.content).filterTouchesWhenObscured = true
    }
}
