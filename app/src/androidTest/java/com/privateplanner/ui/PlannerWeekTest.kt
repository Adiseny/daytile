package com.privateplanner.ui

import android.graphics.Rect
import android.view.View
import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.data.PlannerDatabase
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerWeekTest : PlannerTestHost() {
    private val monday: LocalDate = LocalDate.now().weekStart()
    // A day of this week that is not today, and the one after it or before it.
    private val other = if (LocalDate.now() == monday.plusDays(2)) 4 else 2

    private fun PlannerDatabase.on(day: Int, title: String, start: Int = 540, duration: Int = 60) =
        insertBlock(PlannerBlock(date = monday.plusDays(day.toLong()), title = title, startMinutes = start, durationMinutes = duration))

    private fun week() = screen.descendants().filterIsInstance<WeekView>().first()

    private fun weekTile(title: String) = screen.descendants().filterIsInstance<WeekTile>().first { it.block.title == title }

    private fun awaitWeekTile(title: String) = await("Missing week tile $title") {
        main { screen.descendants().filterIsInstance<WeekTile>().any { it.block.title == title } }
    }

    private fun weekSettled() = main { model.week && week().isShown && (week().parent as View).alpha == 1f }

    private fun showWeek() {
        main { model.showWeek(true) }
        await("The week did not come in") { weekSettled() }
    }

    private fun stored(day: Int) = worker { database.getBlocksForDate(monday.plusDays(day.toLong()).toEpochDay()) }

    // The dates of the week on show: the row beside it, there while the week changes, is not shown.
    private fun heads() = screen.descendants().filter { it.isShown && it.contentDescription?.startsWith("Open ") == true }.toList()

    private fun bounds(view: View) = main { Rect().also { view.getDrawingRect(it); screen.offsetDescendantRectToMyCoords(view, it) } }

    @Test fun theWeekStandsEachDaysBlocksInItsOwnColumnUnderItsDate() {
        launch { for (day in 0..6) on(day, "Day $day", start = 600 + day * 30) }
        showWeek()
        await { main { screen.descendants().filterIsInstance<WeekTile>().count() == 7 } }
        main {
            val view = week()
            for (day in 0..6) {
                val tile = weekTile("Day $day")
                assertEquals(day, tile.day)
                assertTrue(tile.left >= view.columns.left(day) && tile.right <= view.columns.left(day) + view.columns.column)
                // Centred in its column, to the pixel.
                assertEquals(tile.left - view.columns.left(day), view.columns.left(day) + view.columns.column - tile.right)
                assertEquals(view.topPx + Math.round((600 + day * 30) / 60f * view.hourPx), tile.top)
            }
            // From the week's first hour to midnight fills what the heading and the navigation bar leave.
            val scroll = view.parent as View
            assertEquals(Math.round(WeekFirstHour * view.hourPx), scroll.scrollY)
            assertEquals(7, heads().size)
            assertEquals(1, heads().count { it.contentDescription.startsWith("Open today") })
            val today = LocalDate.now().dayOfWeek.value - 1
            assertEquals(today, view.today)
        }
    }

    @Test fun aTapOnADateOpensThatDay() {
        launch { on(other, "There") }
        showWeek()
        main { assertTrue(heads()[other].performClick()) }
        await { main { !model.week && model.selectedDate == monday.plusDays(other.toLong()) } }
        awaitTile("There")
        await("The day did not come back") { main { tile("There").isShown && (tile("There").parent.parent as View).alpha == 1f } }
    }

    @Test fun aHeldBlockMovesToAnotherDayAndQuarterHourKeepingItsMinutes() {
        launch { on(1, "Move me", start = 9 * 60 + 5) }
        showWeek()
        awaitWeekTile("Move me")
        val area = bounds(weekTile("Move me"))
        val across = main { 2f * (week().columns.column + week().columns.gap) }
        val down = main { week().hourPx }
        gesture(area.exactCenterX(), area.exactCenterY(), across, down, hold = 450)
        // On screen at once, ahead of its save.
        main { assertEquals(3, weekTile("Move me").day) }
        await { stored(3).any { it.title == "Move me" && it.startMinutes == 10 * 60 + 5 && it.durationMinutes == 60 } }
        assertTrue(stored(1).isEmpty())
        main {
            val view = week()
            val tile = weekTile("Move me")
            assertEquals(0f, tile.translationX)
            assertEquals(0f, tile.translationY)
            assertTrue(tile.left >= view.columns.left(3) && tile.right <= view.columns.left(3) + view.columns.column)
            assertNull(view.active)
        }
    }

    @Test fun aHoldLetGoWhereItBeganSavesNothing() {
        launch { on(1, "Stay") }
        showWeek()
        awaitWeekTile("Stay")
        val area = bounds(weekTile("Stay"))
        gesture(area.exactCenterX(), area.exactCenterY(), 0f, 0f, hold = 450)
        assertEquals(540, stored(1).single().startMinutes)
        assertNull(main { model.sheet })
    }

    @Test fun aSidewaysSwipeChangesTheWeekAndBack() {
        launch {
            on(0, "This week's")
            on(7, "Next week's")
        }
        showWeek()
        awaitWeekTile("This week's")
        val day = main { model.selectedDate }
        gesture(screen.width * 0.8f, screen.height * 0.7f, -screen.width * 0.6f, 0f)
        await { main { model.selectedDate == day.plusDays(7) && model.week } }
        awaitWeekTile("Next week's")
        // The week that left has gone with its page, and the one that came is at rest under its own dates.
        await { main { screen.descendants().filterIsInstance<WeekTile>().count() == 1 && (weekTile("Next week's").parent as View).translationX == 0f } }
        main {
            assertEquals(7, heads().size)
            assertTrue(heads().none { it.contentDescription.startsWith("Open today") })
            assertEquals(0f, (heads()[0].parent as View).translationX)
        }
        gesture(screen.width * 0.2f, screen.height * 0.7f, screen.width * 0.6f, 0f)
        await { main { model.selectedDate == day } }
        awaitWeekTile("This week's")
    }

    // The day view's scroller.
    private fun dayScroll() = screen.descendants().filterIsInstance<DayView>().first().parent as View

    @Test fun twoFingersClosingBringInTheWeekAndOpeningBringBackTheDayAsItWasLeft() {
        launch { on(other, "That day's", start = 10 * 60, duration = 60) }
        val from = monday.plusDays(other.toLong())
        main {
            model.jumpTo(from)
            dayScroll().scrollTo(0, Math.round(9.5f * HourHeight * screen.resources.displayMetrics.density))
        }
        awaitTile("That day's")
        val left = main { dayScroll().scrollY }
        val y = screen.height * 0.55f
        pinch(screen.width / 2f, y, screen.context.dp(300f), screen.context.dp(90f))
        await("Closing fingers did not bring in the week") { weekSettled() }
        awaitWeekTile("That day's")
        // Opened over another day's column, and lower down than the day was showing.
        val elsewhere = main { week().columns.left(if (other == 2) 5 else 1) + week().columns.column / 2f }
        pinch(elsewhere, screen.height * 0.8f, screen.context.dp(100f), screen.context.dp(320f))
        await("Opening fingers did not bring back the day") { main { !model.week } }
        awaitTile("That day's")
        main {
            assertEquals(from, model.selectedDate)
            assertEquals(left, dayScroll().scrollY)
        }
    }

    @Test fun leavingTheWeekReturnsToTheDayItWasComeIntoFromWhateverWasTouchedThere() {
        launch {
            on(other, "Home")
            on(other + 1, "Elsewhere", start = 14 * 60)
            on(other + 7, "Next week's")
        }
        val from = monday.plusDays(other.toLong())
        main { model.jumpTo(from) }
        awaitTile("Home")
        showWeek()
        awaitWeekTile("Elsewhere")
        // A tap on another day's block makes that day the one its sheet is about.
        val area = bounds(weekTile("Elsewhere"))
        gesture(area.exactCenterX(), area.exactCenterY(), 0f, 0f)
        await { main { model.sheet?.kind == PlannerSheet.Actions && model.selectedDate == from.plusDays(1) } }
        main { model.dismissSheet() }
        main { screen.handleBack() }
        await { main { !model.week } }
        assertEquals(from, main { model.selectedDate })
        awaitTile("Home")
        // Through another week, it is the same day of that week.
        showWeek()
        main { model.shiftWeek(1) }
        awaitWeekTile("Next week's")
        main { assertTrue(week().performAccessibilityAction(WeekAction, null)) }
        await { main { !model.week } }
        assertEquals(from.plusDays(7), main { model.selectedDate })
        awaitTile("Next week's")
    }

    @Test fun aHoldOnABlocksLowerEdgeLengthensAndShortensItByQuarterHours() {
        launch { on(other, "Stretch", start = 10 * 60, duration = 60) }
        showWeek()
        awaitWeekTile("Stretch")
        val area = bounds(weekTile("Stretch"))
        val hour = main { week().hourPx }
        main { haptics.clear() }
        gesture(area.exactCenterX(), area.bottom - 4f, 0f, hour * 0.8f, hold = 450)
        // A quarter hour at a time from the hour it was: 0.8 of an hour is three of them.
        await { stored(other).any { it.title == "Stretch" && it.startMinutes == 600 && it.durationMinutes == 105 } }
        main {
            // Nothing lifts: no long buzz, only a tick a step.
            assertFalse(haptics.contains(android.view.HapticFeedbackConstants.LONG_PRESS))
            assertEquals(3, haptics.size)
            val tile = weekTile("Stretch")
            assertEquals(105, tile.shownDuration)
            assertNull(week().active)
            assertTrue(tile.contentDescription.contains("10:00 to 11:45"))
            assertTrue(tile.performAccessibilityAction(ShortenAction, null))
        }
        await { stored(other).any { it.title == "Stretch" && it.durationMinutes == 90 } }
        main { assertTrue(weekTile("Stretch").performAccessibilityAction(LengthenAction, null)) }
        await { stored(other).any { it.title == "Stretch" && it.durationMinutes == 105 } }
        // The same hold above the edge lifts the block and moves it.
        val body = bounds(weekTile("Stretch"))
        main { haptics.clear() }
        gesture(body.exactCenterX(), body.top + 8f, 0f, hour, hold = 450)
        await { stored(other).any { it.title == "Stretch" && it.startMinutes == 660 && it.durationMinutes == 105 } }
        main { assertTrue(haptics.contains(android.view.HapticFeedbackConstants.LONG_PRESS)) }
    }

    @Test fun theDayAndTheWeekAreNeverOnShowTogether() {
        launch { add("Here") }
        awaitTile("Here")
        val day = main { tile("Here").parent.parent as View }
        val both = intArrayOf(0, 1)
        val x = screen.width / 2f
        val y = screen.height * 0.55f
        val wide = screen.context.dp(300f)
        fun ys(span: Float) = floatArrayOf(y - span / 2f, y + span / 2f)
        val down = android.os.SystemClock.uptimeMillis()
        val second = 1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT
        touch(down, android.view.MotionEvent.ACTION_DOWN, intArrayOf(0), floatArrayOf(x), floatArrayOf(y - wide / 2f))
        touch(down, android.view.MotionEvent.ACTION_POINTER_DOWN or second, both, floatArrayOf(x, x), ys(wide))
        for (part in 1..40) {
            touch(down, android.view.MotionEvent.ACTION_MOVE, both, floatArrayOf(x, x), ys(wide * (1f - part / 80f)))
            main {
                val week = week().parent as View
                assertFalse("Both on show at step $part", day.isShown && week.isShown)
                // The dates stand over the columns they name all the way.
                val dates = heads().firstOrNull()?.parent?.parent as View?
                if (dates != null && week.isShown) {
                    assertEquals(week.scaleX, dates.scaleX)
                    assertEquals(week.pivotX, dates.pivotX)
                    assertEquals(week.pivotY, dates.pivotY)
                }
            }
        }
        touch(down, android.view.MotionEvent.ACTION_POINTER_UP or second, both, floatArrayOf(x, x), ys(wide / 2f))
        touch(down, android.view.MotionEvent.ACTION_UP, intArrayOf(0), floatArrayOf(x), floatArrayOf(y - wide / 4f))
        await { weekSettled() }
    }

    @Test fun aBlockIsTheSameColourAndInkInTheWeekAsOnTheDayAtEveryHour() {
        // One block in each three hours, on today, seen on the day and then in the week.
        launch { for (part in 0 until 8) add("At ${part * 3}", start = part * 180 + 30, duration = 90) }
        val today = LocalDate.now().dayOfWeek.value - 1
        fun pixel(view: View, x: Int, y: Int): Int = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).let {
            android.graphics.Canvas(it).apply { drawColor(screen.palette.Paper); view.draw(this) }
            it.getPixel(x, y).also { _ -> it.recycle() }
        }
        // The darkest thing in a tile is its title: black ink, or white.
        fun ink(view: View, height: Int): Boolean {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply { drawColor(screen.palette.Paper); view.draw(this) }
            var black = false
            for (x in 0 until view.width) for (y in 0 until height) if (bitmap.getPixel(x, y) == -0x1000000) black = true
            bitmap.recycle()
            return black
        }
        val onDay = IntArray(8)
        val blackOnDay = BooleanArray(8)
        for (part in 0 until 8) {
            scrollTo(main { screen.context.px(TimelineTopClearance + heightForMinutes(part * 180)) })
            awaitTile("At ${part * 3}")
            main {
                val tile = tile("At ${part * 3}")
                onDay[part] = pixel(tile, tile.width / 2, tile.height / 2)
                blackOnDay[part] = ink(tile, tile.height / 2)
            }
        }
        showWeek()
        awaitWeekTile("At 0")
        await { main { weekTile("At 21").title != null } }
        main {
            for (part in 0 until 8) {
                val tile = weekTile("At ${part * 3}")
                assertEquals(today, tile.day)
                // The day blends its tile over the paper as it draws and the week has it
                // blended already: a part in 255 of rounding apart, at most.
                val inWeek = pixel(tile, tile.width / 2, tile.height - tile.height / 4)
                for (shift in intArrayOf(0, 8, 16)) {
                    assertEquals("The fill of the block at ${part * 3}:30", (onDay[part] shr shift and 0xFF).toFloat(), (inWeek shr shift and 0xFF).toFloat(), 1f)
                }
                assertEquals("The ink of the block at ${part * 3}:30", blackOnDay[part], ink(tile, tile.height / 2))
            }
        }
    }

    @Test fun nothingOfTheWeekIsDrawnBehindItsDatesOrItsHeading() {
        launch { on(0, "Night", start = 0, duration = 180) }
        showWeek()
        awaitWeekTile("Night")
        // Scrolled so that the night's band and its block lie where the dates are.
        main { (week().parent as View).scrollTo(0, Math.round(week().hourPx)) }
        main {
            val view = week()
            val dates = heads()[0].parent.parent as WeekDays
            val bitmap = android.graphics.Bitmap.createBitmap(screen.width, screen.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).apply { drawColor(screen.palette.Paper); screen.draw(this) }
            val paper = screen.palette.Paper
            val edge = dates.solidBottom
            val density = screen.resources.displayMetrics.density
            for (day in 0..6) {
                // Beside each date, where there is no text: bare paper down to the dates' foot,
                // and the week from just beneath it.
                val x = view.columns.left(day) + 2
                for (y in dates.topPx until edge step 3) assertEquals("Day $day at $y", paper, bitmap.getPixel(x, y))
                assertNotEquals(paper, bitmap.getPixel(x, edge + Math.round(4f * density)))
            }
            // And nothing in the margin above the label of the hour at the top.
            for (y in 0 until edge - Math.round(8f * density) step 3) assertEquals("Margin at $y", paper, bitmap.getPixel(2, y))
            bitmap.recycle()
        }
    }

    @Test fun aBlockCarriedUpToTheDatesBringsTheNightDownToIt() {
        launch { on(other, "Early", start = 7 * 60, duration = 60) }
        showWeek()
        awaitWeekTile("Early")
        val scroll = main { week().parent as View }
        val before = main { scroll.scrollY }
        val area = bounds(weekTile("Early"))
        val top = main { week().topPx + screen.context.dp(4f) }
        val down = android.os.SystemClock.uptimeMillis()
        val x = floatArrayOf(area.exactCenterX())
        touch(down, android.view.MotionEvent.ACTION_DOWN, intArrayOf(0), x, floatArrayOf(area.exactCenterY()))
        android.os.SystemClock.sleep(450)
        touch(down, android.view.MotionEvent.ACTION_MOVE, intArrayOf(0), x, floatArrayOf(top + 3f))
        touch(down, android.view.MotionEvent.ACTION_MOVE, intArrayOf(0), x, floatArrayOf(top))
        // Held at the top edge, the week scrolls by itself, and the day behind it does not.
        await("The week did not scroll under a block held at its top") { main { scroll.scrollY < before } }
        touch(down, android.view.MotionEvent.ACTION_CANCEL, intArrayOf(0), x, floatArrayOf(top))
        assertEquals(7 * 60, stored(other).single().startMinutes)
    }

    @Test fun aChangeMadeInTheWeekIsOnTheDayWhenTheWeekIsLeft() {
        val today = LocalDate.now().dayOfWeek.value - 1
        launch { on(today, "Mine", start = 600) }
        awaitTile("Mine")
        showWeek()
        awaitWeekTile("Mine")
        main { assertTrue(weekTile("Mine").performAccessibilityAction(LaterAction, null)) }
        await { stored(today).any { it.startMinutes == 615 } }
        // Behind the week the day builds nothing, and is brought up to date as it comes back.
        main { assertEquals(600, tile("Mine").block.startMinutes) }
        main { screen.handleBack() }
        await { main { !model.week && tile("Mine").block.startMinutes == 615 } }
    }

    @Test fun aNarrowScreenStillNamesItsBlocksAndLargeTextLeavesTheGridItsSize() {
        launch(fontScale = 2f, hostWidth = 360) {
            on(1, "Lunch", start = 12 * 60, duration = 60)
            on(2, "Side by side", start = 12 * 60, duration = 60)
            on(2, "With this one", start = 12 * 60 + 30, duration = 60)
            on(3, "Tidy desk", start = 12 * 60, duration = 20)
            on(4, "Sliver", start = 12 * 60, duration = 15)
        }
        showWeek()
        awaitWeekTile("Lunch")
        // Titles are laid out as their tiles are first drawn.
        await { main { weekTile("Lunch").title != null } }
        main {
            val density = screen.resources.displayMetrics.density
            assertEquals(10f * density, week().titlePaint(true).textSize)
            assertEquals(10f * density, week().titlePaint(false).textSize)
            assertEquals(Math.round(WeekGutter * density), week().columns.gutter)
            assertNotNull(weekTile("Tidy desk").title)
            // Too narrow for a few letters, and too short for a line.
            assertNull(weekTile("Side by side").title)
            assertNull(weekTile("Sliver").title)
            // The dates grow with the text, as far as their columns let them.
            val head = heads()[0]
            assertTrue(head.height > Math.round(39f * density))
            assertTrue(head.height <= Math.round(39f * WeekLargestText * density) + 1)
        }
    }

    @Test fun fingersLetGoShortOfHalfWayLeaveTheDayAsItWas() {
        launch { add("Here") }
        awaitTile("Here")
        pinch(screen.width / 2f, screen.height * 0.55f, screen.context.dp(300f), screen.context.dp(262f))
        await { main { !model.week && (tile("Here").parent.parent as View).alpha == 1f } }
        assertNull(main { model.sheet })
    }

    @Test fun aTapOnEmptyTimeAddsToThatDayAtItsQuarterHour() {
        launch()
        showWeek()
        val point = main {
            val view = week()
            val scroll = view.parent as View
            floatArrayOf(
                view.columns.left(other) + view.columns.column / 2f,
                view.topPx + (14 * 60 + 40) / 60f * view.hourPx - scroll.scrollY
            )
        }
        gesture(point[0], point[1], 0f, 0f)
        await { main { model.sheet?.kind == PlannerSheet.Create && model.selectedDate == monday.plusDays(other.toLong()) } }
        assertEquals(14 * 60 + 30L, main { model.sheet!!.value })
        onView(isAssignableFrom(EditText::class.java)).perform(replaceText("Dentist"))
        clickLabel("Add")
        await { stored(other).any { it.title == "Dentist" && it.startMinutes == 14 * 60 + 30 } }
        awaitWeekTile("Dentist")
        main { assertEquals(other, weekTile("Dentist").day) }
    }

    @Test fun backLeavesTheWeekBeforeItLeavesTheDay() {
        launch()
        showWeek()
        main {
            assertTrue(screen.backEnabled)
            screen.handleBack()
        }
        await { main { !model.week } }
        assertFalse(main { screen.backEnabled })
    }

    @Test fun aServiceMovesABlockBetweenDaysAndGoesBetweenDayAndWeek() {
        launch { on(other, "Spoken", start = 600) }
        main { assertTrue(screen.descendants().filterIsInstance<DayView>().first().performAccessibilityAction(WeekAction, null)) }
        await { weekSettled() }
        awaitWeekTile("Spoken")
        main {
            val tile = weekTile("Spoken")
            assertTrue(tile.contentDescription.contains("10:00 to 11:00"))
            assertTrue(tile.performAccessibilityAction(NextDayAction, null))
        }
        await { stored(other + 1).any { it.title == "Spoken" && it.startMinutes == 600 } }
        main { assertTrue(weekTile("Spoken").performAccessibilityAction(LaterAction, null)) }
        await { stored(other + 1).any { it.title == "Spoken" && it.startMinutes == 615 } }
        main { assertTrue(week().performAccessibilityAction(WeekAction, null)) }
        await { main { !model.week } }
    }
}
