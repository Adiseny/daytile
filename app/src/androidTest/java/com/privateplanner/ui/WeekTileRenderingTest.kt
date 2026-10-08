package com.privateplanner.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.blockBackgroundArgb
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeekTileRenderingTest : PlannerTestHost() {
    private fun image(tile: View): Bitmap = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(screen.palette.Paper); tile.draw(this) }
    }

    private fun hasInk(bitmap: Bitmap, bottom: Int = bitmap.height): Boolean {
        for (y in 0 until bottom) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (pixel == 0xFF000000.toInt() || pixel == 0xFFFFFFFF.toInt()) return true
        }
        return false
    }

    @Test fun growingTitlesNeverDisappearAndTheirMeasuredTextFits() {
        launch()
        main {
            val week = WeekView(activity, screen)
            val density = activity.resources.displayMetrics.density
            for (widthDp in intArrayOf(32, 36, 48, 64)) {
                for (text in arrayOf("Plan", "gyp", "A title that needs several lines", "Supercalifragilisticexpialidocious", "保持 café mañana gyp", "Plan 👨‍👩‍👧‍👦 🇬🇧")) {
                    val tile = WeekTile(activity, screen, week, PlannerBlock(1, LocalDate.now(), text, 600, 60), BlockLayout(0, 1), 0)
                    var visible = false
                    for (height in Math.round(10 * density)..Math.round(120 * density)) {
                        tile.layout(0, 0, Math.round(widthDp * density), height)
                        val bitmap = image(tile)
                        try {
                            val ink = hasInk(bitmap)
                            assertTrue("Title disappeared at ${height / density}dp, width=$widthDp, text=$text", !visible || ink)
                            visible = visible || ink
                            tile.title?.let { assertTrue("Text exceeds tile at ${height / density}dp: $text (${it.height})", it.height <= height) }
                        } finally { bitmap.recycle() }
                    }
                    assertTrue("A tall tile must name itself: $text at $widthDp", visible)
                }
            }
        }
    }

    @Test fun holdingTheResizeEdgeDoesNotHideOrRelayoutTheTitle() {
        launch { add("A title that wraps gyp", 600, 60) }
        main { model.showWeek(true) }
        await { main { screen.descendants().filterIsInstance<WeekTile>().any { it.block.title == "A title that wraps gyp" && it.width > 0 } } }
        main {
            val tile = screen.descendants().filterIsInstance<WeekTile>().first { it.block.title == "A title that wraps gyp" }
            val original = tile.block
            for (duration in 20..180 step 5) {
                tile.bind(original.copy(durationMinutes = duration), tile.columns, tile.day)
                val before = image(tile)
                val title = tile.title
                val down = SystemClock.uptimeMillis()
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, tile.width / 2f, tile.height - 1f, 0).let {
                    tile.onTouchEvent(it); it.recycle()
                }
                tile.run()
                val held = image(tile)
                try {
                    assertSame("Holding must reuse title at $duration minutes", title, tile.title)
                    val bottom = (tile.height - activity.dp(5f)).toInt().coerceAtLeast(0)
                    assertEquals("Holding must retain visible text at $duration minutes", hasInk(before, bottom), hasInk(held, bottom))
                    for (y in 0 until bottom) for (x in 0 until tile.width) {
                        assertEquals("Holding changed text at $duration minutes ($x,$y)", before.getPixel(x, y), held.getPixel(x, y))
                    }
                } finally {
                    tile.cancelGesture(); before.recycle(); held.recycle()
                }
            }
            tile.bind(original, tile.columns, tile.day)
        }
    }

    @Test fun resizingThroughShortAndLongTitlesMatchesFreshTilesAndCancellationRestoresPixels() {
        launch { add("gyp a title that wraps over several lines 保持", 600, 30) }
        main { model.showWeek(true) }
        await { main { screen.descendants().filterIsInstance<WeekTile>().any { it.block.startMinutes == 600 && it.width > 0 && it.isShown && (it.parent.parent.parent as View).alpha == 1f } } }
        val tile = main { screen.descendants().filterIsInstance<WeekTile>().first { it.block.startMinutes == 600 } }
        val week = main { screen.descendants().filterIsInstance<WeekView>().first() }
        val area = main { android.graphics.Rect().also { tile.getDrawingRect(it); screen.offsetDescendantRectToMyCoords(tile, it) } }
        val down = SystemClock.uptimeMillis()
        val x = floatArrayOf(area.exactCenterX())
        val y = area.bottom - main { activity.dp(2f) }
        val before = main { image(tile) }
        touch(down, MotionEvent.ACTION_DOWN, intArrayOf(0), x, floatArrayOf(y))
        SystemClock.sleep(450)
        main { assertSame("The native hold must start resizing", tile, week.active) }
        try {
            for (duration in intArrayOf(45, 60, 75, 90, 105, 120, 90, 60, 30, 15, 30)) {
                val at = y + main { (duration - 30) / 60f * week.hourPx }
                touch(down, MotionEvent.ACTION_MOVE, intArrayOf(0), x, floatArrayOf(at))
                main {
                    assertEquals(duration, tile.shownDuration)
                    val preview = image(tile)
                    val fresh = WeekTile(activity, screen, week, tile.block.copy(durationMinutes = duration), tile.columns, tile.day)
                    fresh.layout(0, 0, tile.width, tile.height)
                    val expected = image(fresh)
                    try {
                        val bottom = (tile.height - activity.dp(5f)).toInt().coerceAtLeast(0)
                        for (yy in 0 until bottom) for (xx in 0 until tile.width) assertEquals("Preview differs at $duration minutes ($xx,$yy)", expected.getPixel(xx, yy), preview.getPixel(xx, yy))
                    } finally { preview.recycle(); expected.recycle() }
                }
            }
        } finally {
            touch(down, MotionEvent.ACTION_CANCEL, intArrayOf(0), x, floatArrayOf(y))
        }
        main {
            val cancelled = image(tile)
            try { assertTrue("Cancellation must restore every pixel", before.sameAs(cancelled)) }
            finally { cancelled.recycle(); before.recycle() }
        }
        assertEquals(30, stored("gyp a title that wraps over several lines 保持")!!.durationMinutes)
    }

    @Test fun dayAndWeekDrawConsistentInkAndFillAtEveryHourAndPaletteBoundary() {
        launch()
        main {
            val original = screen.palette
            val day = DayView(activity, screen)
            val week = WeekView(activity, screen)
            val parent = android.widget.FrameLayout(activity)
            try {
                for (minute in intArrayOf(0, 404, 419, 420, 570, 780, 990, 1170, 1199, 1200, 1350)) {
                    PlannerScreen::class.java.getDeclaredField("palette").apply { isAccessible = true }.set(screen, paletteForMinute(minute))
                    val ink = if (screen.palette.LightBackground) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                    val opposite = if (screen.palette.LightBackground) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                    val atlas = if (minute == 780 || minute == 1350) Bitmap.createBitmap(activity.px(768f), activity.px(480f), Bitmap.Config.ARGB_8888) else null
                    val atlasCanvas = atlas?.let { Canvas(it).apply { drawColor(screen.palette.Paper) } }
                    val label = activity.textPaint(10f, 500).apply { color = screen.palette.PrimaryText }
                    for (hour in 0..23) for (variant in 0..2) {
                        val block = PlannerBlock(1, LocalDate.now(), "Plan", hour * 60, 60)
                        val columns = BlockLayout(variant, 3)
                        val dayTile = TimeBlockView(activity, screen, day, block, columns).apply {
                            layout(0, 0, activity.px(128f), activity.px(64f))
                            setVisual(128f, 64f, 0, height)
                        }
                        val weekTile = WeekTile(activity, screen, week, block, columns, 0).apply {
                            layout(0, 0, activity.px(48f), activity.px(60f))
                        }
                        parent.addView(weekTile)
                        for (active in booleanArrayOf(false, true)) {
                            if (active) {
                                day.lift(block.id)
                                val down = SystemClock.uptimeMillis()
                                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, weekTile.width / 2f, 0f, 0).let {
                                    weekTile.onTouchEvent(it); it.recycle()
                                }
                                weekTile.run()
                            }
                            val background = compositedTileBackground(blockBackgroundArgb(hour * 60, variant), screen.palette, active)
                            for (tile in arrayOf<View>(dayTile, weekTile)) {
                                val bitmap = image(tile)
                                try {
                                    assertEquals("Fill: minute=$minute hour=$hour variant=$variant active=$active", background, bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4))
                                    val pixels = IntArray(bitmap.width * bitmap.height)
                                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                    assertFalse("Wrong ink: minute=$minute hour=$hour variant=$variant active=$active", opposite in pixels)
                                    assertTrue("Visible title: minute=$minute hour=$hour variant=$variant active=$active", ink in pixels)
                                    if (variant == 0 && !active && atlasCanvas != null) {
                                        val x = (hour % 4) * activity.px(192f)
                                        val y = (hour / 4) * activity.px(80f)
                                        if (tile === dayTile) atlasCanvas.drawText("$hour:00", x.toFloat(), y + activity.dp(12f), label)
                                        atlasCanvas.drawBitmap(bitmap, x + if (tile === dayTile) 0f else activity.dp(136f), y + activity.dp(16f), null)
                                    }
                                } finally { bitmap.recycle() }
                            }
                        }
                        weekTile.cancelGesture()
                        day.putDownAll()
                        parent.removeView(weekTile)
                    }
                    atlas?.let {
                        val directory = File(activity.cacheDir, "tile-rendering").apply { mkdirs() }
                        File(directory, "all-hours-$minute.png").outputStream().use { output -> it.compress(Bitmap.CompressFormat.PNG, 100, output) }
                        it.recycle()
                    }
                }
            } finally {
                day.putDownAll()
                PlannerScreen::class.java.getDeclaredField("palette").apply { isAccessible = true }.set(screen, original)
            }
        }
    }

    @Test fun aReleasedResizeKeepsItsTitleAndSavedTimeWhenReturningFromTheDay() {
        val name = "A longer title gyp café 保持"
        launch { add(name, 600, 30) }
        main { model.showWeek(true) }
        await { main {
            screen.descendants().filterIsInstance<WeekTile>().any { it.block.title == name && it.isShown && (it.parent.parent.parent as View).alpha == 1f }
        } }
        val tile = main { screen.descendants().filterIsInstance<WeekTile>().first { it.block.title == name } }
        val week = main { screen.descendants().filterIsInstance<WeekView>().first() }
        val area = main { android.graphics.Rect().also { tile.getDrawingRect(it); screen.offsetDescendantRectToMyCoords(tile, it) } }
        gesture(area.exactCenterX(), area.bottom - activity.dp(2f), 0f, main { week.hourPx * 1.5f }, hold = 450)
        await { stored(name)?.durationMinutes == 120 }
        await { main { tile.block.durationMinutes == 120 } }
        val after = main { image(tile) }
        main { assertTrue("Saved title must remain drawn", hasInk(after)); model.showWeek(false) }
        await { main { !model.week && this.tile(name).block.durationMinutes == 120 } }
        main { model.showWeek(true) }
        await { main { week.isShown && (week.parent as View).alpha == 1f } }
        main {
            val returned = image(screen.descendants().filterIsInstance<WeekTile>().first { it.block.title == name })
            try { assertTrue("Returning must retain every tile pixel", after.sameAs(returned)) }
            finally { after.recycle(); returned.recycle() }
        }
    }

    @Test fun captureWeekTitleSizesInBothPalettes() {
        launch()
        main {
            val week = WeekView(activity, screen)
            val sizes = intArrayOf(10, 15, 20, 25, 30, 45, 60, 75, 90, 120)
            val texts = arrayOf("Plan", "gyp", "A long title over several lines", "保持 café mañana", "Plan 👨‍👩‍👧‍👦 🇬🇧")
            val directory = File(activity.cacheDir, "tile-rendering").apply { mkdirs() }
            val cellWidth = activity.px(72f)
            val cellHeight = activity.px(94f)
            for (minute in intArrayOf(780, 1350)) {
                PlannerScreen::class.java.getDeclaredField("palette").apply { isAccessible = true }.set(screen, paletteForMinute(minute))
                val atlas = Bitmap.createBitmap(sizes.size * cellWidth, texts.size * cellHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(atlas).apply { drawColor(screen.palette.Paper) }
                val label = activity.textPaint(8f, 400).apply { color = screen.palette.PrimaryText }
                for ((row, text) in texts.withIndex()) for ((column, minutes) in sizes.withIndex()) {
                    val tile = WeekTile(activity, screen, week, PlannerBlock(1, LocalDate.now(), text, 600, minutes), BlockLayout(0, 1), 0)
                    val height = Math.round(minutes / 60f * WeekMinHourHeight * activity.resources.displayMetrics.density) - activity.px(1f)
                    tile.layout(0, 0, activity.px(42f), height)
                    canvas.drawText("${minutes}m", column * cellWidth + activity.dp(4f), row * cellHeight + activity.dp(10f), label)
                    canvas.save()
                    canvas.translate(column * cellWidth + activity.dp(4f), row * cellHeight + activity.dp(14f))
                    tile.draw(canvas)
                    canvas.restore()
                }
                File(directory, "week-title-sizes-$minute.png").outputStream().use { atlas.compress(Bitmap.CompressFormat.PNG, 100, it) }
                atlas.recycle()
            }
        }
    }
}
