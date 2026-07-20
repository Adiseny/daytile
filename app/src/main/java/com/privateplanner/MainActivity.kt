package com.privateplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.ViewModelProvider
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.ui.PaperBackgroundDarkArgb
import com.privateplanner.ui.PlannerScreen
import com.privateplanner.ui.PlannerTheme
import com.privateplanner.ui.PlannerViewModel
import com.privateplanner.ui.displayedPaletteForMinute
import java.time.LocalTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val minute = TimeSnapper.minuteOfDay(LocalTime.now())
        val launchPalette = displayedPaletteForMinute(minute)
        val launchBackground = launchPalette.Paper.toArgb()
        window.setBackgroundDrawable(launchBackground.toDrawable())
        val systemBarStyle = if (launchPalette.LightBackground) {
            SystemBarStyle.light(launchBackground, PaperBackgroundDarkArgb)
        } else {
            SystemBarStyle.dark(launchBackground)
        }
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle,
            navigationBarStyle = systemBarStyle
        )
        super.onCreate(savedInstanceState)

        val app = application as PlannerApp
        val plannerViewModel = ViewModelProvider(
            this,
            PlannerViewModel.factory(app.repository)
        )[PlannerViewModel::class.java]
        setContent {
            PlannerTheme {
                PlannerScreen(viewModel = plannerViewModel)
            }
        }
        findViewById<android.view.View>(android.R.id.content).filterTouchesWhenObscured = true
    }
}
