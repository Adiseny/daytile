package com.privateplanner.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerRenderingTest : PlannerTestHost() {
    @Test fun cancelledResizeRestoresEveryPixelAndItsSpokenTime() {
        launch { add("Resize 保持", start = 540, duration = 60) }
        scrollTo(main { screen.context.px(TimelineTopClearance + heightForMinutes(480)) })
        awaitTile("Resize 保持")
        // A stroke straight after a scroll would be the scroll's.
        SystemClock.sleep(ScrollOwnsTouchMillis + 50)
        main {
            val tile = tile("Resize 保持")
            fun capture(): Bitmap = Bitmap.createBitmap(tile.width, activity.px(240f), Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(screen.palette.Paper); tile.draw(this) }
            }
            val before = capture()
            val spoken = tile.contentDescription
            val down = SystemClock.uptimeMillis()
            val y = tile.height - activity.dp(4f)
            fun send(action: Int, at: Float) {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tile.width / 2f, at, 0).let {
                    tile.onTouchEvent(it)
                    it.recycle()
                }
            }
            try {
                send(MotionEvent.ACTION_DOWN, y)
                send(MotionEvent.ACTION_MOVE, y + activity.dp(80f))
                val preview = capture()
                try {
                    assertFalse("The preview must have resized", before.sameAs(preview))
                    assertNotEquals(spoken, tile.contentDescription)
                    assertEquals(tile.contentDescription, tile.createAccessibilityNodeInfo().contentDescription)
                } finally { preview.recycle() }
                send(MotionEvent.ACTION_CANCEL, y + activity.dp(80f))
                val cancelled = capture()
                try {
                    assertTrue("Cancel must restore text, geometry and ink", before.sameAs(cancelled))
                    assertEquals(spoken, tile.contentDescription)
                    assertEquals(spoken, tile.createAccessibilityNodeInfo().contentDescription)
                } finally { cancelled.recycle() }
            } finally {
                tile.cancelGesture()
                before.recycle()
            }
        }
        assertEquals(60, stored("Resize 保持")!!.durationMinutes)
    }

    @Test fun editedTilesMatchFreshLayoutsInEveryPixel() {
        launch()
        main {
            val day = DayView(activity, screen)
            val width = activity.px(320f)
            fun capture(view: DayView): Bitmap {
                if (view.isLayoutRequested) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), 0)
                    view.layout(0, 0, width, view.measuredHeight)
                }
                return Bitmap.createBitmap(width, activity.px(600f), Bitmap.Config.ARGB_8888).also {
                    val canvas = Canvas(it)
                    canvas.drawColor(screen.palette.Paper)
                    view.draw(canvas)
                }
            }
            var blocks = listOf(
                PlannerBlock(1, LocalDate.now(), "A long title that wraps to another line", 0, 60),
                PlannerBlock(2, LocalDate.now(), "Second", 120, 60)
            )
            for (step in 0..5) {
                blocks = when (step) {
                    1 -> listOf(blocks[0].copy(title = "Renamed 保持"), blocks[1])
                    2 -> listOf(blocks[0].copy(startMinutes = 15), blocks[1])
                    3 -> listOf(blocks[0].copy(durationMinutes = 15), blocks[1])
                    4 -> listOf(blocks[0].copy(durationMinutes = 180), blocks[1])
                    5 -> listOf(blocks[0].copy(durationMinutes = 60), blocks[1].copy(startMinutes = 30))
                    else -> blocks
                }
                day.setBlocks(blocks)
                val fresh = DayView(activity, screen).apply { setBlocks(blocks) }
                val editedImage = capture(day)
                val freshImage = capture(fresh)
                try { assertTrue("Edited geometry and text, step $step", editedImage.sameAs(freshImage)) }
                finally { editedImage.recycle(); freshImage.recycle() }
            }
        }
    }

    // A key press takes the window out of touch mode, and the day's scroll view is what
    // holds the focus once a sheet has closed. The platform would shade all of it.
    @Test fun theDayIsNotShadedWhileItHoldsTheFocusInKeyboardMode() {
        launch()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        try {
            val scroll = main { screen.descendants().filterIsInstance<ScrollView>().first().also { it.requestFocus() } }
            await("The day should hold the focus out of touch mode") { main { scroll.isFocused && !scroll.isInTouchMode } }
            // The platform's highlight fades in over its first frames.
            SystemClock.sleep(400)
            main {
                val image = Bitmap.createBitmap(scroll.width, scroll.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(image).also {
                        it.drawColor(screen.palette.Paper)
                        scroll.draw(it)
                    }
                    // Down the right-hand edge, clear of the labels: paper, but for the grid's lines.
                    val column = IntArray(image.height) { y -> image.getPixel(image.width - 2, y) }
                    val paper = column.count { it == screen.palette.Paper }
                    assertTrue("Paper on $paper of ${column.size} rows", paper > column.size * 3 / 4)
                } finally {
                    image.recycle()
                }
            }
        } finally {
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun currentTimeBadgeStaysWithinTheGutter() {
        launch()
        main {
            val day = screen.descendants().filterIsInstance<DayView>().first()
            day.minute = 60
            val badge = day.descendants().first { it.contentDescription == "Current time, 1:00" }
            val density = screen.resources.displayMetrics.density
            assertEquals("Time badge width", 58f * density, badge.width.toFloat(), 1f)
            assertEquals("Time badge left inset", 6f * density, badge.left.toFloat(), 1f)
        }
    }

    @Test fun backgroundMeasuredGridMatchesTheNormalMeasurementFallback() {
        launch()
        val context = activity.applicationContext
        val differentDensity = Configuration(context.resources.configuration).apply { densityDpi += 80 }
        thread { context.createConfigurationContext(differentDensity).prepareGridLabels() }.join()
        fun capture(): Bitmap = main {
            val width = context.px(300f)
            val height = context.px(360f)
            val day = DayView(context, screen)
            day.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), 0)
            day.layout(0, 0, width, day.measuredHeight)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.drawColor(screen.palette.Paper)
                day.draw(canvas)
            }
        }
        val fallback = capture()
        thread { context.prepareGridLabels() }.join()
        val warmed = capture()
        try { assertTrue("Warm-up must not change any grid pixel", fallback.sameAs(warmed)) }
        finally { fallback.recycle(); warmed.recycle() }
    }

    @Test fun scrollingTileShowsThroughStatusBarAndHeaderAfterSheetDismissal() {
        launch()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100, 5_000)
        val empty = checkNotNull(automation.takeScreenshot())
        val statusHeight = main {
            @Suppress("DEPRECATION")
            activity.window.decorView.rootWindowInsets.systemWindowInsetTop
        }
        assertTrue(statusHeight > 0)
        val x = (empty.width * 0.7f).toInt()
        val paper = empty.getPixel(x, statusHeight / 2)
        empty.recycle()
        // Added through the planner, which reads a day again after its own writes.
        main {
            model.openCreate(0)
            model.createBlock("Glass regression")
        }
        awaitTile("Glass regression")
        main { model.resizeBlock(tile("Glass regression").block.id, 1440) }
        scrollTo(main { screen.context.px(500f) })
        fun assertGlass() {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            automation.waitForIdle(100, 5_000)
            val image = checkNotNull(automation.takeScreenshot())
            try {
                val status = image.getPixel(x, statusHeight / 2)
                val heading = image.getPixel(x, statusHeight + 6)
                val channels = listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)
                assertTrue("Tile should show through system bar: paper=$paper status=$status heading=$heading", channels.sumOf { kotlin.math.abs(it(status) - it(paper)) } > 20)
                channels.forEach { assertTrue("Status and heading tint should be continuous", kotlin.math.abs(it(status) - it(heading)) <= 5) }
            } finally { image.recycle() }
        }
        assertGlass()
        main { model.openDateJump() }
        main { model.dismissSheet() }
        assertGlass()
    }
}
