package com.privateplanner.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerRenderingTest : PlannerTestHost() {
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
