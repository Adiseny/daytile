package com.privateplanner.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.os.SystemClock
import android.text.StaticLayout
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.PlannerBlock
import java.time.LocalDate
import com.privateplanner.Worker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.max
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerOptimisationTest : PlannerTestHost() {
    private fun image(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(screen.palette.Paper); view.draw(this) }
    }

    private fun field(value: Any, name: String): Any? =
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)

    @Test fun multilingualGlyphsReallyDrawInsideTheirMeasuredHeight() {
        launch()
        main {
            val padding = activity.px(16f)
            for (size in floatArrayOf(10f, 12f, 14f)) for (width in intArrayOf(32, 48, 160)) {
                for (name in arrayOf("First\n保持\ncafé\n👨‍👩‍👧‍👦", "नमस्ते\nWorld\n世界", "café mañana gyp\n保持 👨‍👩‍👧‍👦")) {
                    val text = textBlock(name, activity.tilePaint(size, 600), activity.sp(if (size == 14f) 18f else size * 1.2f),
                        activity.px(width.toFloat()), fill = true)
                    val bitmap = Bitmap.createBitmap(text.width + 2 * padding, text.height + 2 * padding, Bitmap.Config.ARGB_8888)
                    try {
                        text.draw(Canvas(bitmap), padding.toFloat(), padding.toFloat(), 0xFF000000.toInt())
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        for (y in 0 until bitmap.height) if (y < padding || y >= padding + text.height) {
                            for (x in 0 until bitmap.width) assertEquals("Glyph outside measured height: $name, size=$size width=$width, y=$y", 0, pixels[y * bitmap.width + x] ushr 24)
                        }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test fun dayTextStaysInsideTheTileAndClearOfTheHandleAtEveryFontScale() {
        launch()
        main {
            for (scale in floatArrayOf(0.85f, 1f, 1.3f, 2f)) {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = scale })
                val day = DayView(context, screen)
                for (width in intArrayOf(80, 112, 240)) for (height in intArrayOf(20, 30, 40, 50, 60, 70, 90, 120, 240, 360)) {
                    val name = "First line\n保持 café\nThird line\n👨‍👩‍👧‍👦"
                    val tile = TimeBlockView(context, screen, day, PlannerBlock(1, LocalDate.now(), name, 600, height / 2), BlockLayout(0, 1))
                    tile.layout(0, 0, context.px(width.toFloat()), context.px(height.toFloat()))
                    tile.setVisual(width.toFloat(), height.toFloat(), 0, tile.height)
                    image(tile).recycle()
                    val reason = "$width x $height dp, font scale $scale"
                    val follow = field(tile, "followOffset") as Int
                    val title = field(tile, "title") as TextBlock
                    val titleY = (field(tile, "titleY") as Int) + follow
                    if (title.height > 0) {
                        assertTrue("Title above tile: $reason", titleY >= 0)
                        assertTrue("Title below tile: $reason", titleY + title.height <= tile.height)
                    }
                    val meta = field(tile, "meta") as? TextBlock
                    if (meta != null && meta.height > 0) {
                        val metaY = (field(tile, "metaY") as Int) + follow
                        assertTrue("Title/time overlap: $reason", titleY + title.height <= metaY)
                        assertTrue("Time/handle overlap: $reason", metaY + meta.height <= tile.height - context.px(ResizeHandleBottomPadding + ResizeHandleHeight + 2f))
                    }
                    val duration = field(tile, "duration") as? TextBlock
                    if (duration != null && duration.height > 0) {
                        val y = field(tile, "durationY") as Int
                        assertTrue("Duration above tile: $reason", y >= 0)
                        assertTrue("Duration below tile: $reason", y + duration.height <= tile.height)
                        assertTrue("Title/duration overlap: $reason", (field(tile, "titleX") as Int) + title.width <= (field(tile, "durationX") as Int))
                    }
                }
            }
        }
    }

    @Test fun rapidCrossDayMovesNeverReplaceTheLatestPreviewWithAnEarlierSave() {
        val monday = LocalDate.now().weekStart()
        launch { insertBlock(PlannerBlock(date = monday, title = "Rapid moves", startMinutes = 540, durationMinutes = 30)) }
        main { model.showWeek(true) }
        await { main { (0..6).all { model.cachedBlocks(monday.plusDays(it.toLong())) != null } } }
        val gate = CountDownLatch(1)
        Worker.execute { check(gate.await(10, TimeUnit.SECONDS)) }
        val first = monday.plusDays(1)
        val second = monday.plusDays(2)
        val observed = ArrayList<Int>()
        try {
            main {
                model.moveBlock(model.cachedBlocks(monday)!!.single(), first, 600)
                model.moveBlock(model.cachedBlocks(first)!!.single(), second, 720)
                model.moveBlock(model.cachedBlocks(second)!!.single(), first, 840)
                val render = model.onChange
                model.onChange = Runnable {
                    model.cachedBlocks(first)?.firstOrNull()?.let { observed += it.startMinutes }
                    render?.run()
                }
                assertEquals(840, model.cachedBlocks(first)!!.single().startMinutes)
            }
        } finally { gate.countDown() }
        worker { }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        main {
            assertTrue("An earlier completed save replaced the latest preview: $observed", observed.all { it == 840 })
            assertEquals(840, model.cachedBlocks(first)!!.single().startMinutes)
            assertTrue(model.cachedBlocks(monday)!!.isEmpty())
            assertTrue(model.cachedBlocks(second)!!.isEmpty())
        }
        assertEquals(840, worker { database.getBlocksForDate(first.toEpochDay()).single().startMinutes })
    }

    @Test fun shorterDayTilesAlsoShowTwoCompleteLinesWhenThereIsRoom() {
        launch()
        main {
            val day = DayView(activity, screen)
            val name = "First line\nSecond line"
            for (height in intArrayOf(54, 60, 70, 75, 79)) {
                val tile = TimeBlockView(activity, screen, day, PlannerBlock(1, LocalDate.now(), name, 600, 60), BlockLayout(0, 1))
                tile.layout(0, 0, activity.px(240f), activity.px(height.toFloat()))
                tile.setVisual(240f, height.toFloat(), 0, tile.height)
                image(tile).recycle()
                val small = height < 64
                val full = textBlock(name, activity.tilePaint(if (small) 12f else 14f, 600),
                    activity.sp(if (small) 14f else 18f), activity.px(160f))
                val meta = field(tile, "meta") as TextBlock
                val padding = activity.px(if (small) 13f else 16f)
                assertTrue("The full title and time have room at $height dp", full.height + meta.height + padding <= tile.height)
                val title = field(tile, "title") as TextBlock
                val layout = field(title, "layout") as StaticLayout
                assertEquals("Fitting text must not be capped at one line at $height dp", 2, layout.lineCount)
                for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
            }
        }
    }

    @Test fun weekTitleFittingAndPositionMatchThePreviousExhaustiveSearch() {
        launch()
        main {
            val week = WeekView(activity, screen)
            val density = activity.resources.displayMetrics.density
            for (width in intArrayOf(32, 36, 48, 64)) {
                for (name in arrayOf("Plan", "gyp", "A long title across several lines", "保持 café mañana gyp", "Plan 👨‍👩‍👧‍👦 🇬🇧", "नमस्ते 世界")) {
                    val tile = WeekTile(activity, screen, week, PlannerBlock(1, LocalDate.now(), name, 600, 60), BlockLayout(0, 1), 0)
                    for (height in activity.px(10f)..activity.px(120f)) {
                        tile.layout(0, 0, activity.px(width.toFloat()), height)
                        image(tile).recycle()
                        val paint = week.titlePaint(height >= 48f * density)
                        val line = ceil(paint.textSize * 1.2f).toInt()
                        val inset = activity.px(WeekTitleInset)
                        var limit = height / line
                        var expected: TextBlock? = null
                        if (tile.width - 2 * inset >= paint.textSize * 2.4f) while (limit > 0) {
                            val candidate = textBlock(name, paint, line.toFloat(), tile.width - 2 * inset,
                                maxLines = limit, ellipsis = true, fill = true, hyphenate = true)
                            if (candidate.height <= height) { expected = candidate; break }
                            limit--
                        }
                        val reason = "$name at $width dp by ${height / density} dp"
                        assertEquals("Fitted height: $reason", expected?.height, tile.title?.height)
                        if (expected != null) {
                            val expectedY = if (limit == 1) (height - expected.height) / 2
                                else min(inset, max((height - expected.height) / 2, 0))
                            assertEquals("Title position: $reason", expectedY, field(tile, "titleY"))
                        }
                    }
                }
            }
        }
    }

    @Test fun anUnchangedMonthStaysStillThroughoutWeekSwipes() {
        launch()
        main { model.jumpTo(LocalDate.of(2026, 2, 9)); model.showWeek(true) }
        await { main { screen.descendants().filterIsInstance<WeekView>().first().isShown } }
        for (direction in intArrayOf(1, -1)) {
            val header = main { screen.descendants().first { it.contentDescription?.startsWith("Jump date, February") == true } }
            val before = main { image(header) }
            val down = SystemClock.uptimeMillis()
            val x = main { screen.width * 0.65f }
            val y = main { screen.height * 0.5f }
            touch(down, MotionEvent.ACTION_DOWN, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
            try {
                for (travel in intArrayOf(30, 60, 100, 40)) {
                    touch(down, MotionEvent.ACTION_MOVE, intArrayOf(0), floatArrayOf(x - direction * activity.dp(travel.toFloat())), floatArrayOf(y))
                    main {
                        val during = image(header)
                        try { assertTrue("The same month moved during a $travel dp week drag", before.sameAs(during)) }
                        finally { during.recycle() }
                        assertTrue("The week must still follow the finger", screen.descendants().filterIsInstance<WeekDays>().first().descendants().any { it.translationX != 0f })
                    }
                }
            } finally {
                touch(down, MotionEvent.ACTION_CANCEL, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
                before.recycle()
            }
        }
    }

    @Test fun fiveTitleLinesFitAlongsideTheTimeAndStayInsideALongTileWhileScrolling() {
        launch()
        main {
            val day = DayView(activity, screen)
            val name = "First line\nSecond line\nThird line\nFourth line\nFifth line"
            val tile = TimeBlockView(activity, screen, day, PlannerBlock(1, LocalDate.now(), name, 0, 180), BlockLayout(0, 1))
            tile.layout(0, 0, activity.px(240f), activity.px(360f))
            tile.setVisual(240f, 360f, 0, tile.height)
            val bitmap = image(tile)
            bitmap.recycle()
            val title = field(tile, "title") as TextBlock
            val layout = field(title, "layout") as StaticLayout
            assertEquals("All five lines have room", 5, layout.lineCount)
            for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
            val meta = field(tile, "meta") as TextBlock
            val titleY = field(tile, "titleY") as Int
            val metaY = field(tile, "metaY") as Int
            assertTrue(titleY + title.height <= metaY)
            assertTrue(metaY + meta.height <= tile.height - activity.px(8f))
            screen.descendants().filterIsInstance<android.widget.ScrollView>().first().scrollTo(0, activity.px(1_000f))
            tile.scrolled()
            image(tile).recycle()
            val follow = field(tile, "followOffset") as Int
            assertTrue("Pinned text must clear the resize handle", metaY + meta.height + follow <= tile.height - activity.px(8f))
            assertSame("Scrolling must retain the measured title", title, field(tile, "title"))
        }
    }

    @Test fun monthBoundaryWeeksStillMoveAndNameBothMonths() {
        launch()
        main { model.jumpTo(LocalDate.of(2026, 2, 16)); model.showWeek(true) }
        await { main { screen.descendants().filterIsInstance<WeekView>().first().isShown } }
        for (date in arrayOf(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 12, 21))) {
            main { model.jumpTo(date) }
            val header = main { screen.descendants().first { it.contentDescription?.startsWith("Jump date, ") == true } }
            val down = SystemClock.uptimeMillis()
            val x = main { screen.width * 0.7f }
            val y = main { screen.height * 0.5f }
            touch(down, MotionEvent.ACTION_DOWN, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
            val before = main { image(header) }
            try {
                touch(down, MotionEvent.ACTION_MOVE, intArrayOf(0), floatArrayOf(x - activity.dp(40f)), floatArrayOf(y))
                main {
                    val during = image(header)
                    try { assertFalse("A changed month keeps its transition", before.sameAs(during)) }
                    finally { during.recycle() }
                }
                touch(down, MotionEvent.ACTION_MOVE, intArrayOf(0), floatArrayOf(x - activity.dp(110f)), floatArrayOf(y))
                await { main {
                    val title = header.contentDescription.toString()
                    if (date.monthValue == 2) title.contains("February") && title.contains("March")
                    else title.contains("December 2026") && title.contains("January 2027")
                } }
                touch(down, MotionEvent.ACTION_UP, intArrayOf(0), floatArrayOf(x - activity.dp(110f)), floatArrayOf(y))
                assertEquals(date.plusWeeks(1), main { model.selectedDate })
            } finally { before.recycle() }
        }
    }
}
