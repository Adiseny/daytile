package com.privateplanner.ui

import android.os.SystemClock
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.MainActivity
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerActivityStateTest {
    @Test fun unsavedTitleAndSelectedDaySurviveActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val target = LocalDate.now().withDayOfMonth(if (LocalDate.now().dayOfMonth == 10) 11 else 10)
            val spoken = target.format(dateFormatter(FullDate, Locale.getDefault()))
            var heading = ""
            val title = "Unsubmitted draft ${SystemClock.uptimeMillis()}"
            scenario.onActivity { activity ->
                val screen = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PlannerScreen
                screen.descendants().first { it.contentDescription?.startsWith("Jump date") == true }.performClick()
                screen.descendants().first { it.contentDescription == spoken }.performClick()
                heading = screen.descendants().first { it.contentDescription?.startsWith("Jump date") == true }.contentDescription.toString()
                screen.onEmptyTimeTap(540)
            }
            onView(isAssignableFrom(EditText::class.java)).perform(replaceText(title))
            scenario.recreate()
            onView(isAssignableFrom(EditText::class.java)).check(matches(withText(title)))
            scenario.onActivity { activity ->
                val screen = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as PlannerScreen
                assertTrue(screen.backEnabled)
                assertTrue(screen.descendants().any { it.contentDescription?.toString() == heading })
                screen.handleBack()
            }
        }
    }
}
