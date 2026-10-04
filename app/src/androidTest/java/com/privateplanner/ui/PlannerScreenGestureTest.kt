package com.privateplanner.ui

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.R
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.TimeSnapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerScreenGestureTest : PlannerTestHost() {
    private fun input() = onView(isAssignableFrom(EditText::class.java))

    private fun openCreate() = main {
        assertTrue(screen.descendants().filterIsInstance<DayView>().first()
            .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
    }

    @Test fun tapEmptyTimeCreatesBlockAndOpensActions() {
        launch()
        gesture(screen.width * 0.6f, screen.height * 0.5f, 0f, 0f)
        input().check(matches(hasFocus())).perform(replaceText("Focus"))
        clickLabel("Add")
        awaitTile("Focus")
        main { tile("Focus").performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
        assertTrue(main { screen.descendants().filterIsInstance<Label>().any { it.text == "Delete" } })
    }

    @Test fun timelineAccessibilityActionOpensCreateSheet() {
        launch()
        openCreate()
        input().check(matches(hasFocus()))
    }

    @Test fun horizontalSwipeChangesDayAndBack() {
        launch()
        val today = main { model.selectedDate }
        repeat(7) { index ->
            gesture(screen.width * 0.8f, screen.height * 0.5f, -screen.width * 0.6f, 0f)
            await { main { model.selectedDate } == today.plusDays(index + 1L) }
        }
        repeat(7) { index ->
            gesture(screen.width * 0.2f, screen.height * 0.5f, screen.width * 0.6f, 0f)
            await { main { model.selectedDate } == today.plusDays(6L - index) }
        }
        assertEquals(List(14) { HapticFeedbackConstants.SEGMENT_TICK }, haptics)
    }

    @Test fun tilesFollowMeasuredWidthAndKeepMinimumTouchTargets() {
        launch(hostWidth = 260) { add("Narrow", duration = 10) }
        scrollTo(0)
        awaitTile("Narrow")
        main {
            val tile = tile("Narrow")
            val density = screen.resources.displayMetrics.density
            assertEquals(72f * density, tile.left.toFloat(), 1f)
            assertEquals((260f - 72 - 10 - 4) * density, tile.width.toFloat(), 1f)
            assertEquals(48f * density, tile.height.toFloat(), 1f)
        }
    }

    @Test fun blockDragResizeAndAccessibilityActionsRemainStable() {
        launch { add("Move me") }
        scrollTo(main { screen.context.px(TimelineTopClearance + heightForMinutes(480)) })
        awaitTile("Move me")
        fun bounds() = main { Rect().also { tile("Move me").getDrawingRect(it); screen.offsetDescendantRectToMyCoords(tile("Move me"), it) } }
        val before = stored("Move me")!!
        var area = bounds()
        gesture(area.exactCenterX(), area.exactCenterY(), 0f, screen.context.dp(40f), hold = 450)
        await { stored("Move me")!!.startMinutes != before.startMinutes }
        val moved = stored("Move me")!!
        area = bounds()
        gesture(area.exactCenterX(), area.bottom - screen.context.dp(4f), 0f, screen.context.dp(40f))
        await { stored("Move me")!!.durationMinutes != moved.durationMinutes }
        val resized = stored("Move me")!!
        main { assertTrue(tile("Move me").performAccessibilityAction(R.id.block_lengthen, null)) }
        await { stored("Move me")!!.durationMinutes == resized.durationMinutes + 5 }
    }

    @Test fun cancelledDragDoesNotSaveOrKeepScrolling() {
        launch { add("Keep me") }
        scrollTo(main { screen.context.px(TimelineTopClearance + heightForMinutes(480)) })
        awaitTile("Keep me")
        val before = stored("Keep me")!!
        val area = main { Rect().also { tile("Keep me").getDrawingRect(it); screen.offsetDescendantRectToMyCoords(tile("Keep me"), it) } }
        gesture(area.exactCenterX(), area.exactCenterY(), 0f, screen.context.dp(40f), hold = 450, cancel = true)
        assertEquals(before, stored("Keep me"))
        main {
            assertEquals(0f, tile("Keep me").translationY)
            assertEquals(0L, screen.descendants().filterIsInstance<DayView>().first().activeBlockId)
        }
    }

    @Test fun denseDayKeepsOnlyNearbyTilesAndScrollsToBothEnds() {
        launch {
            withTransaction {
                for (start in 0 until 1440 step 10) repeat(7) { column -> add("Dense $start/$column", start, 10) }
            }
        }
        fun count() = main { screen.descendants().filterIsInstance<TimeBlockView>().count() }
        scrollTo(0)
        awaitTile("Dense 0/0")
        val limit = main { ((screen.height / screen.context.dp(HourHeight) * 60 + 120) / 10 + 1).toInt() * 7 }
        assertTrue("Only nearby tiles should be kept: ${count()}", count() <= limit && count() < 700)
        scrollTo(100_000)
        awaitTile("Dense 1430/6")
        assertTrue(count() <= limit && count() < 700)
        main { tile("Dense 1430/6").performAccessibilityAction(R.id.block_delete, null) }
        await { stored("Dense 1430/6") == null && main { model.snackbar } != null }
        clickLabel("Undo")
        awaitTile("Dense 1430/6")
        assertEquals(1008, storedToday().size)
    }

    @Test fun detachingDuringAHoldCancelsAutoScrollAndKeepsTheSavedBlock() {
        launch { add("Held", duration = 180) }
        awaitTile("Held")
        val saved = stored("Held")
        val held = main { tile("Held") }
        main {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, held.width / 2f, 20f, 0)
            held.onTouchEvent(event)
            event.recycle()
        }
        await { main { screen.descendants().filterIsInstance<DayView>().first().activeBlockId == saved!!.id } }
        main {
            val day = held.parent as DayView
            day.removeView(held)
            assertEquals(0L, day.activeBlockId)
            val autoScroll = TimeBlockView::class.java.getDeclaredField("autoScroll").apply { isAccessible = true }
            assertNull(autoScroll.get(held))
        }
        assertEquals(saved, stored("Held"))
    }

    @Test fun largeFontCreateAndActionFlowRemainReachable() {
        launch(fontScale = 2f)
        openCreate()
        input().perform(replaceText("Large font task"))
        clickLabel("Add")
        awaitTile("Large font task")
        main { tile("Large font task").performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null) }
        assertTrue(main { screen.descendants().filterIsInstance<Label>().any { it.text == "Delete" } })
    }

    @Test fun createTitleInputCapsLongTitleWithoutSplittingEmoji() {
        launch()
        openCreate()
        val title = "A".repeat(MaxTitleLength)
        input().perform(replaceText(title + "overflow")).check(matches(withText(title)))
        val beforeEmoji = title.dropLast(1)
        input().perform(replaceText(beforeEmoji + "\uD83D\uDE00overflow")).check(matches(withText(beforeEmoji)))
        input().perform(replaceText(title), pressImeActionButton())
        awaitTile(title)
    }

    @Test fun completePlanningWorkflowRemainsConsistent() {
        launch()
        main { model.openDateJump() }
        main { screen.descendants().first { it.contentDescription == "Dismiss Choose date" }.performClick() }
        assertNull(main { model.sheet })
        openCreate()
        input().perform(replaceText("Workflow task"))
        clickLabel("Add")
        awaitTile("Workflow task")
        main { tile("Workflow task").performAccessibilityAction(R.id.block_rename, null) }
        input().check { view, _ ->
            view as EditText
            assertEquals("Workflow task", view.text.toString())
            assertEquals(0, view.selectionStart)
            assertEquals(view.length(), view.selectionEnd)
        }
        input().perform(replaceText("Renamed workflow"), pressImeActionButton())
        awaitTile("Renamed workflow")
        main { tile("Renamed workflow").performAccessibilityAction(R.id.block_delete, null) }
        await { stored("Renamed workflow") == null && main { model.snackbar } != null }
        clickLabel("Undo")
        awaitTile("Renamed workflow")
        main { tile("Renamed workflow").performAccessibilityAction(R.id.block_delete, null) }
        await { stored("Renamed workflow") == null }
    }

    // Every write reads its day again; a read that changes nothing must leave the list on
    // screen as it is, so that nothing is laid out or drawn for it.
    @Test fun aWriteThatChangesNothingKeepsTheListOnScreen() {
        launch { add("Same") }
        awaitTile("Same")
        val shown = main { model.blocks }
        main {
            model.openRename(shown.single().id)
            model.renameBlock("Same")
        }
        await { main { model.sheet } == null }
        assertEquals("Same", stored("Same")!!.title)
        assertSame(shown, main { model.blocks })
    }

    // The screen is told it shows some other palette, then its clock ticks back to the real one.
    @Test fun titleSheetStaysThroughAColourStepAndIsRebuiltAroundItsTextWhenLightAndDarkFlip() {
        launch()
        openCreate()
        input().perform(replaceText("Half typed"))
        fun field() = main { screen.descendants().filterIsInstance<EditText>().first() }
        val typedInto = field()
        val shown = main { screen.palette }
        val others = (0 until TimeSnapper.MinutesPerDay step PaletteStepMinutes).map { paletteForMinute(it) }
        fun tickFrom(palette: PlannerPalette) = main {
            PlannerScreen::class.java.getDeclaredField("palette").apply { isAccessible = true }.set(screen, palette)
            (PlannerScreen::class.java.getDeclaredField("clock").apply { isAccessible = true }.get(screen) as Runnable).run()
            assertSame(shown, screen.palette)
        }
        tickFrom(others.first { it !== shown && it.LightBackground == shown.LightBackground })
        assertSame("A step within a ramp must leave the sheet alone", typedInto, field())
        tickFrom(others.first { it.LightBackground != shown.LightBackground })
        val rebuilt = field()
        assertNotSame("The flip between light and dark builds the sheet again", typedInto, rebuilt)
        assertEquals("Half typed", main { rebuilt.text.toString() })
    }

    @Test fun clockSchedulesOnlyWhileStartedAndRepeatedStartsDoNotDuplicateObservers() {
        launch()
        if (Build.VERSION.SDK_INT < 29) return
        main {
            val field = PlannerScreen::class.java.getDeclaredField("clock").apply { isAccessible = true }
            val clock = field.get(screen) as Runnable
            assertTrue(screen.handler.hasCallbacks(clock))
            screen.start()
            assertTrue(screen.handler.hasCallbacks(clock))
            screen.stop()
            assertFalse(screen.handler.hasCallbacks(clock))
            screen.start()
            assertTrue(screen.handler.hasCallbacks(clock))
        }
    }
}
