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

    @Test fun aSwipeLetGoShortSlidesBackAndLeavesTheDayAsItWas() {
        launch { add("Stays") }
        awaitTile("Stays")
        val today = main { model.selectedDate }
        gesture(screen.width * 0.8f, screen.height * 0.5f, -screen.context.dp(40f), 0f)
        await { main { tile("Stays").translationX == 0f && screen.descendants().filterIsInstance<TimeBlockView>().count() == 1 } }
        assertEquals(today, main { model.selectedDate })
        assertTrue(haptics.isEmpty())
    }

    @Test fun aDragCarriesTheNextDaysBlocksInBesideThisDays() {
        launch {
            add("This day's")
            insertBlock(com.privateplanner.domain.PlannerBlock(
                date = java.time.LocalDate.now().plusDays(1), title = "Next day's", startMinutes = 540, durationMinutes = 60
            ))
        }
        awaitTile("This day's")
        val today = main { model.selectedDate }
        val along = screen.context.dp(100f)
        val x = screen.width * 0.8f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, dx: Float) = main {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + dx, screen.height * 0.5f, 0).let {
                screen.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        send(MotionEvent.ACTION_MOVE, -screen.context.dp(30f))
        send(MotionEvent.ACTION_MOVE, -along)
        main {
            val travel = screen.width - screen.context.dp(TimelineGutter)
            assertEquals(-along, tile("This day's").translationX, 1f)
            assertEquals(travel - along, tile("Next day's").translationX, 1f)
            assertEquals(today, model.selectedDate)
        }
        // Past 72dp the finger has felt the tick; back under it and let go, the day stays.
        assertEquals(listOf(HapticFeedbackConstants.SEGMENT_TICK), haptics)
        SystemClock.sleep(120)
        send(MotionEvent.ACTION_MOVE, -screen.context.dp(30f))
        SystemClock.sleep(120)
        send(MotionEvent.ACTION_UP, -screen.context.dp(30f))
        await { main { screen.descendants().filterIsInstance<TimeBlockView>().count() == 1 && tile("This day's").translationX == 0f } }
        assertEquals(today, main { model.selectedDate })

        // Let go past it and the next day's blocks are kept as they are, not built again.
        send(MotionEvent.ACTION_DOWN, 0f)
        send(MotionEvent.ACTION_MOVE, -screen.context.dp(30f))
        send(MotionEvent.ACTION_MOVE, -along)
        val arriving = main { tile("Next day's") }
        SystemClock.sleep(120)
        send(MotionEvent.ACTION_UP, -along)
        assertEquals(today.plusDays(1), main { model.selectedDate })
        await { main { screen.descendants().filterIsInstance<TimeBlockView>().count() == 1 } }
        main {
            assertSame(arriving, tile("Next day's"))
            assertEquals(0f, arriving.translationX)
        }
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
        main { assertTrue(tile("Move me").performAccessibilityAction(LengthenAction, null)) }
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
        main { tile("Dense 1430/6").performAccessibilityAction(DeleteAction, null) }
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
        main { tile("Workflow task").performAccessibilityAction(RenameAction, null) }
        input().check { view, _ ->
            view as EditText
            assertEquals("Workflow task", view.text.toString())
            assertEquals(0, view.selectionStart)
            assertEquals(view.length(), view.selectionEnd)
        }
        input().perform(replaceText("Renamed workflow"), pressImeActionButton())
        awaitTile("Renamed workflow")
        main { tile("Renamed workflow").performAccessibilityAction(DeleteAction, null) }
        await { stored("Renamed workflow") == null && main { model.snackbar } != null }
        clickLabel("Undo")
        awaitTile("Renamed workflow")
        main { tile("Renamed workflow").performAccessibilityAction(DeleteAction, null) }
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

    @Test fun cachedDaysStayCurrentAfterRapidEditsRejectedMovesAndUndo() {
        launch {
            repeat(7) { add("Busy $it", 540, 60) }
            add("Moving", 660, 30)
        }
        awaitTile("Moving")
        val date = main { model.selectedDate }
        val id = stored("Moving")!!.id
        fun settle() {
            worker { }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        settle()
        main {
            // Optimistic movement is rejected by the stored overlap limit while another
            // day is selected. Returning must show the saved position, not the preview.
            model.moveBlock(id, 540)
            model.shiftDay(1)
        }
        settle()
        main {
            model.jumpTo(date)
            assertEquals(660, model.blocks.first { it.id == id }.startMinutes)
            model.moveBlock(id, 720)
            model.moveBlock(id, 725)
            model.resizeBlock(id, 45)
            model.shiftDay(1)
        }
        settle()
        main {
            model.jumpTo(date)
            val moved = model.blocks.first { it.id == id }
            assertEquals(725, moved.startMinutes)
            assertEquals(45, moved.durationMinutes)
            model.deleteBlock(id)
            model.shiftDay(1)
        }
        settle()
        main {
            model.jumpTo(date)
            assertTrue(model.blocks.none { it.id == id })
            model.undoDelete(model.snackbar!!.id)
            // Evict the day while restoring it, then request it again before the write
            // completes. A late prefetch must not replace the restored block.
            model.shiftDay(20)
            model.jumpTo(date)
        }
        settle()
        await { main { model.blocks.any { it.id == id } } }
        assertEquals(storedToday(), main { model.blocks })
        assertEquals(725, stored("Moving")!!.startMinutes)
    }

    @Test fun rapidDaySwipesReadEachMissingDayOnce() {
        launch()
        val reads = java.util.concurrent.ConcurrentHashMap<Long, Int>()
        val dao = object : com.privateplanner.data.PlannerBlockDao by database {
            override fun getBlocksForDate(dateEpochDay: Long): List<com.privateplanner.domain.PlannerBlock> {
                reads[dateEpochDay] = (reads[dateEpochDay] ?: 0) + 1
                return database.getBlocksForDate(dateEpochDay)
            }
        }
        val cached = main {
            PlannerViewModel(com.privateplanner.data.PlannerRepository(dao), com.privateplanner.Reminders(activity, database))
        }
        worker { }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val today = main { cached.selectedDate.toEpochDay() }
        assertEquals(3, reads.size)
        main {
            repeat(100) {
                cached.shiftDay(1)
                cached.shiftDay(-1)
            }
        }
        worker { }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(reads.keys.all { it in today - 1..today + 2 })
        assertEquals("The two visited days stay cached", 1, reads[today])
        assertEquals(1, reads[today + 1])
        // Yesterday is evicted on the first forward swipe, so returning requests it
        // again. The following 99 swipes must reuse that request while it is in flight.
        assertTrue("In-flight days need no duplicate reads: $reads", reads.values.all { it <= 2 })

        val gate = java.util.concurrent.CountDownLatch(1)
        com.privateplanner.Worker.execute { gate.await() }
        try {
            main { repeat(100) { cached.shiftDay(1) } }
        } finally {
            gate.countDown()
        }
        worker { }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("Queued reads for abandoned days must be skipped",
            setOf(today + 99, today + 100, today + 101), reads.keys.filter { it > today + 2 }.toSet())
        assertTrue(reads.values.all { it <= 2 })
    }

    @Test fun aHeldTileStartsAndStopsEdgeScrollingAsThePointerMoves() {
        launch { add("Edge", 660, 120) }
        scrollTo(main { screen.context.px(TimelineTopClearance + heightForMinutes(600)) })
        awaitTile("Edge")
        val tile = main { tile("Edge") }
        val original = stored("Edge")
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) = main {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tile.width / 2f, y, 0)
            tile.onTouchEvent(event)
            event.recycle()
        }
        val y = tile.height * 0.4f
        send(MotionEvent.ACTION_DOWN, y)
        await { main { screen.descendants().filterIsInstance<DayView>().first().activeBlockId != 0L } }
        val before = main { screen.scrollPx }
        SystemClock.sleep(100)
        assertEquals(before, main { screen.scrollPx })
        send(MotionEvent.ACTION_MOVE, y + screen.height)
        await { main { screen.scrollPx > before } }
        send(MotionEvent.ACTION_CANCEL, y + screen.height)
        val cancelled = main { screen.scrollPx }
        SystemClock.sleep(100)
        assertEquals(cancelled, main { screen.scrollPx })
        assertEquals(original, stored("Edge"))
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
