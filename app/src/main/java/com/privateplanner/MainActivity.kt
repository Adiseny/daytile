package com.privateplanner

import android.os.Bundle
import android.os.Build
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
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as PlannerApp
        app.warmUpInterfaceOnce()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The timeline is already at its final position. Reveal it without the
            // default splash animation translating the content after its first draw.
            splashScreen.setOnExitAnimationListener { it.remove() }
        }

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
        // Still before the window is attached, so the first frame already has the paper and
        // bars; last, so the warm-up thread has built the palettes by now instead of this
        // thread building them while it waits.
        applyPlannerSystemBars(displayedPaletteForMinute(TimeSnapper.minuteOfDay(TimeSnapper.localNowMillis())))
        findViewById<View>(android.R.id.content).filterTouchesWhenObscured = true
    }

    // The system draws the splash before any of the app runs, so by itself it follows the
    // phone's light or dark setting, not the palette: a dark phone opened a light planner
    // through a black splash. Leaving records the polarity on screen for the next launch;
    // only the first launch after 07:00 or 20:00 can still differ. Off the main thread, and
    // the system call, which persists the theme, is made only when the polarity changed.
    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val light = displayedPaletteForMinute(TimeSnapper.minuteOfDay(TimeSnapper.localNowMillis())).LightBackground
        val splash = splashScreen
        val app = applicationContext
        thread(name = "planner-splash") {
            runCatching {
                val launch = app.getSharedPreferences(LaunchStore, MODE_PRIVATE)
                if (!launch.contains(SplashLightKey) || launch.getBoolean(SplashLightKey, true) != light) {
                    splash.setSplashScreenTheme(if (light) R.style.Theme_Daytile_Day else R.style.Theme_Daytile_Night)
                    launch.edit().putBoolean(SplashLightKey, light).apply()
                }
            }
        }
    }
}

private const val LaunchStore = "launch"
private const val SplashLightKey = "splashLight"
