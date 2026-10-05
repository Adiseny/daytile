package com.privateplanner

import android.app.Activity
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.ui.PlannerScreen
import com.privateplanner.ui.PlannerViewModel
import com.privateplanner.ui.displayedPaletteForMinute

class MainActivity : Activity() {
    private var screen: PlannerScreen? = null
    private var back: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as PlannerApp
        app.warmUpInterfaceOnce()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The timeline is already at its final position. Reveal it without the
            // default splash animation translating the content after its first draw.
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        // The view model outlives a change of configuration with its activity.
        @Suppress("DEPRECATION")
        val viewModel = lastNonConfigurationInstance as? PlannerViewModel ?: PlannerViewModel(app.repository, app.reminders)
        // The screen styles the window as it is built, before the window is attached, so the
        // first frame already has the paper and bars.
        screen = PlannerScreen(this, viewModel) { takeBack() }
        setContentView(screen)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any? = screen?.viewModel

    override fun onStart() {
        super.onStart()
        screen?.start()
    }

    // Back is the planner's while a sheet or a message is showing or another day is: it
    // closes them, then returns to today. Otherwise it is the system's, with its own
    // animation out of the app, so from Android 13 the callback is registered only meanwhile.
    private fun takeBack() {
        val enabled = screen?.backEnabled == true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || enabled == (back != null)) return
        if (enabled) {
            back = OnBackInvokedCallback { screen?.handleBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        } else {
            back?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            back = null
        }
    }

    // Before Android 13.
    @SuppressLint("GestureBackNavigation") // Android 13+ uses takeBack and the platform dispatcher above.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (screen?.backEnabled == true) screen?.handleBack() else super.onBackPressed()
    }

    // The answer to the notification permission the date sheet asks for, which is the only
    // permission requested. A dismissed request arrives with no results, and counts as a no.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val viewModel = screen?.viewModel ?: return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            viewModel.setRemindersOn(true)
        } else {
            viewModel.notificationsBlocked()
        }
    }

    // The system draws the splash before any of the app runs, so by itself it follows the
    // phone's light or dark setting, not the palette: a dark phone opened a light planner
    // through a black splash. Leaving tells the system the polarity on screen, which it
    // keeps for the next launch; only the first launch after 07:00 or 20:00 can still
    // differ. The process remembers what it last told, so only the first leave and a
    // changed polarity make the system call, on the worker thread, and nothing is stored
    // for it here.
    override fun onStop() {
        super.onStop()
        screen?.stop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val light = displayedPaletteForMinute(TimeSnapper.minuteOfDay(TimeSnapper.localNowMillis())).LightBackground
        val app = application as PlannerApp
        if (app.splashLight == light) return
        app.splashLight = light
        val splash = splashScreen
        Worker.execute {
            runCatching {
                splash.setSplashScreenTheme(if (light) R.style.Theme_Daytile_Day else R.style.Theme_Daytile_Night)
            }
        }
    }
}
