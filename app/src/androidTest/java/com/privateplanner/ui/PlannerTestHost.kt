package com.privateplanner.ui

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.privateplanner.Reminders
import com.privateplanner.TestHostActivity
import com.privateplanner.Worker
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.data.PlannerRepository
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import java.util.concurrent.FutureTask
import org.junit.After

internal fun View.descendants(): Sequence<View> = sequence {
    yield(this@descendants)
    if (this@descendants is ViewGroup) {
        for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
    }
}

open class PlannerTestHost {
    private lateinit var scenario: ActivityScenario<TestHostActivity>
    internal lateinit var activity: TestHostActivity
    internal lateinit var screen: PlannerScreen
    internal lateinit var model: PlannerViewModel
    internal lateinit var database: PlannerDatabase
    internal val haptics = mutableListOf<Int>()

    internal fun <T> main(action: () -> T): T {
        var answer: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { answer = runCatching(action) }
        return answer!!.getOrThrow()
    }

    internal fun await(message: String = "UI did not settle", check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!check()) {
            kotlin.check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(16)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // On the thread the planner reads and writes from, behind whatever it has queued.
    internal fun <T> worker(action: () -> T): T = FutureTask(action).also(Worker::execute).get()

    internal fun launch(fontScale: Float = 1f, hostWidth: Int? = null, seed: PlannerDatabase.() -> Unit = {}) {
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
        scenario.onActivity { activity = it }
        database = PlannerDatabase(activity.applicationContext, name = null)
        worker { database.seed() }
        main {
            val context: Context = if (fontScale == activity.resources.configuration.fontScale) activity else {
                activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { this.fontScale = fontScale })
            }
            model = PlannerViewModel(PlannerRepository(database), Reminders(activity.applicationContext, database))
            screen = object : PlannerScreen(context, model) {
                override fun haptic(constant: Int) {
                    haptics += constant
                    super.haptic(constant)
                }
            }
            activity.applyPlannerSystemBars(screen.palette)
            activity.setContentView(FrameLayout(activity).apply {
                addView(screen, FrameLayout.LayoutParams(hostWidth?.let { context.px(it.toFloat()) } ?: -1, -1))
            })
            screen.start()
        }
        await { main { screen.width > 0 && screen.descendants().filterIsInstance<DayView>().first().height > 0 } }
    }

    @After
    fun closeHost() {
        if (this::screen.isInitialized) main { screen.stop() }
        if (this::scenario.isInitialized) scenario.close()
        if (this::database.isInitialized) worker { database.close() }
    }

    internal fun scrollTo(y: Int) = main {
        screen.descendants().filterIsInstance<ScrollView>().first().scrollTo(0, y)
    }

    internal fun tile(title: String): TimeBlockView =
        screen.descendants().filterIsInstance<TimeBlockView>().first { it.block.title == title }

    internal fun awaitTile(title: String) = await("Missing tile $title") {
        main { screen.descendants().filterIsInstance<TimeBlockView>().any { it.block.title == title } }
    }

    internal fun clickLabel(text: String) = main {
        var view = screen.descendants().filterIsInstance<Label>().first { it.text == text } as View
        while (!view.isClickable) view = view.parent as View
        check(view.performClick())
    }

    internal fun storedToday(): List<PlannerBlock> {
        val day = main { model.selectedDate }.toEpochDay()
        return worker { database.getBlocksForDate(day) }
    }

    internal fun stored(title: String): PlannerBlock? = storedToday().firstOrNull { it.title == title }

    internal fun PlannerDatabase.add(title: String, start: Int = 540, duration: Int = 60) =
        insertBlock(PlannerBlock(date = LocalDate.now(), title = title, startMinutes = start, durationMinutes = duration))

    internal fun gesture(x: Float, y: Float, dx: Float, dy: Float, hold: Long = 0, cancel: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, px: Float, py: Float) = main {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0).let {
                screen.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, x, y)
        if (hold > 0) SystemClock.sleep(hold)
        for (step in 1..12) {
            SystemClock.sleep(12)
            event(MotionEvent.ACTION_MOVE, x + dx * step / 12f, y + dy * step / 12f)
        }
        event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x + dx, y + dy)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
