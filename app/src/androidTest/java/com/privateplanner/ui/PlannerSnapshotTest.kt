package com.privateplanner.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.domain.PlannerBlock
import java.io.File
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith

// The same tests can run against the preceding APK. Only the planner is captured,
// with a fixed time line, so system icons and the passing clock cannot alter pixels.
@RunWith(AndroidJUnit4::class)
class PlannerSnapshotTest : PlannerTestHost() {
    private val monday = LocalDate.now().plusWeeks(2).weekStart()

    private fun capture(name: String) = main {
        val directory = File(screen.context.cacheDir, "optimisation-snapshots").apply { mkdirs() }
        for (minute in intArrayOf(780, 1350)) {
            PlannerScreen::class.java.getDeclaredField("palette").apply { isAccessible = true }.set(screen, paletteForMinute(minute))
            PlannerScreen::class.java.getDeclaredMethod("paletteChanged", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(screen, true)
            val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(screen.palette.Paper); screen.draw(this) }
            File(directory, "$name-$minute.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun daySettled() = await { main {
        screen.descendants().filterIsInstance<TimeBlockView>().all { it.translationX == 0f }
    } }

    private fun weekAt(y: Int) {
        main { model.showWeek(true) }
        await { main {
            val week = screen.descendants().filterIsInstance<WeekView>().first()
            model.week && week.isShown && (week.parent as View).alpha == 1f
        } }
        main {
            val week = screen.descendants().filterIsInstance<WeekView>().first()
            week.minute = 600
            (week.parent as View).scrollTo(0, y)
        }
    }

    @Test fun normalDayAndWeekSnapshots() {
        launch {
            withTransaction {
                for (day in 0..6) for (index in 0..7) insertBlock(PlannerBlock(
                    date = monday.plusDays(day.toLong()), title = "Plan $day/$index", startMinutes = index * 180,
                    durationMinutes = if (index % 3 == 0) 15 else 90
                ))
            }
        }
        main { model.jumpTo(monday) }
        awaitTile("Plan 0/0")
        daySettled()
        scrollTo(0)
        capture("normal-day-first")
        scrollTo(100_000)
        capture("normal-day-last")
        weekAt(0)
        await { main { screen.descendants().filterIsInstance<WeekTile>().count() == 56 } }
        capture("normal-week-first")
        weekAt(100_000)
        capture("normal-week-last")
    }

    @Test fun crowdedDayAndWeekSnapshots() {
        launch {
            withTransaction {
                for (day in 0..6) {
                    for (start in 0 until 1440 step 10) repeat(7) { column -> insertBlock(PlannerBlock(
                        date = monday.plusDays(day.toLong()), title = "Dense $day/$start/$column", startMinutes = start, durationMinutes = 10
                    )) }
                    insertBlock(PlannerBlock(date = monday.plusDays(day.toLong()), title = "All day $day", startMinutes = 0, durationMinutes = 1440))
                }
            }
        }
        main { model.jumpTo(monday) }
        scrollTo(0)
        awaitTile("Dense 0/0/0")
        daySettled()
        capture("crowded-day-first")
        scrollTo(100_000)
        awaitTile("Dense 0/1430/6")
        capture("crowded-day-last")
        weekAt(0)
        await { main { screen.descendants().filterIsInstance<WeekTile>().any { it.block.title == "Dense 0/0/0" } } }
        capture("crowded-week-first")
        weekAt(100_000)
        await { main { screen.descendants().filterIsInstance<WeekTile>().any { it.block.title == "Dense 6/1430/6" } } }
        capture("crowded-week-last")
    }
}
