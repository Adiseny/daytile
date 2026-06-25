package com.privateplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privateplanner.ui.PaperBackgroundArgb
import com.privateplanner.ui.PaperBackgroundDarkArgb
import com.privateplanner.ui.PlannerScreen
import com.privateplanner.ui.PlannerTheme
import com.privateplanner.ui.PlannerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(PaperBackgroundArgb, PaperBackgroundDarkArgb),
            navigationBarStyle = SystemBarStyle.auto(PaperBackgroundArgb, PaperBackgroundDarkArgb)
        )
        super.onCreate(savedInstanceState)

        val app = application as PlannerApp
        setContent {
            PlannerTheme {
                val viewModel: PlannerViewModel = viewModel(
                    factory = PlannerViewModel.factory(app.repository)
                )
                PlannerScreen(viewModel = viewModel)
            }
        }
        findViewById<android.view.View>(android.R.id.content).filterTouchesWhenObscured = true
    }
}
