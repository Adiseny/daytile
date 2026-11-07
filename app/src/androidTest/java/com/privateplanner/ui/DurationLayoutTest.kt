package com.privateplanner.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.text.StaticLayout
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurationLayoutTest : PlannerTestHost() {
    private val name = "A long task title that needs several lines and must not rewrap whenever its duration changes"
    private val lengths = intArrayOf(10, 15, 20, 25, 30, 35, 55, 60, 65, 115, 120, 125, 595, 600, 605, 715, 720, 725, 1430, 1440)

    private fun field(value: Any, name: String): Any? =
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)

    private fun draw(tile: TimeBlockView) {
        val bitmap = Bitmap.createBitmap(tile.width, minOf(tile.height, tile.context.px(160f)), Bitmap.Config.ARGB_8888)
        try { tile.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }

    private fun titleRight(tile: TimeBlockView): Int {
        draw(tile)
        return (field(tile, "titleX") as Int) + (field(tile, "title") as TextBlock).width
    }

    @Test fun titleColumnStaysTheSameWidthAcrossEveryDurationFormatAndStyle() {
        launch()
        main {
            for (scale in floatArrayOf(0.85f, 1f, 1.3f, 2f)) {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = scale })
                val day = DayView(context, screen)
                for (width in intArrayOf(111, 112, 180, 240, 320)) {
                    val block = PlannerBlock(1, LocalDate.now(), name, 0, 10)
                    val columns = BlockLayout(0, 1)
                    val tile = TimeBlockView(context, screen, day, block, columns)
                    var right = -1
                    for (minutes in lengths + lengths.reversedArray()) {
                        val height = heightForMinutes(minutes)
                        tile.bind(block.copy(durationMinutes = minutes), columns)
                        tile.setVisual(width.toFloat(), height, 0, context.px(height))
                        tile.layout(0, 0, context.px(width.toFloat()), context.px(height))
                        val next = titleRight(tile)
                        if (right < 0) right = next
                        assertEquals("Title column moved at ${TimeFormatter.duration(minutes)}, width=$width, font scale=$scale", right, next)
                    }
                }
            }
        }
    }

    @Test fun visibleDurationsAreCompleteRightAlignedAndClearOfTheTitle() {
        launch()
        main {
            for (scale in floatArrayOf(0.85f, 1f, 1.3f, 2f)) {
                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = scale })
                val day = DayView(context, screen)
                for (width in intArrayOf(112, 180, 240, 320)) for (minutes in lengths) {
                    val tile = TimeBlockView(context, screen, day, PlannerBlock(1, LocalDate.now(), name, 0, minutes), BlockLayout(0, 1))
                    val height = heightForMinutes(minutes)
                    tile.setVisual(width.toFloat(), height, 0, context.px(height))
                    tile.layout(0, 0, context.px(width.toFloat()), context.px(height))
                    val right = titleRight(tile)
                    val duration = field(tile, "duration") as? TextBlock ?: continue
                    if (duration.height == 0) continue
                    val x = field(tile, "durationX") as Int
                    val y = field(tile, "durationY") as Int
                    val layout = field(duration, "layout") as StaticLayout
                    val reason = "${TimeFormatter.duration(minutes)}, width=$width, font scale=$scale"
                    assertEquals("Right alignment: $reason", tile.width - context.px(8f), x + duration.width)
                    assertTrue("Title/duration gap: $reason", right + context.px(4f) <= x)
                    assertEquals("One complete line: $reason", 1, layout.lineCount)
                    assertEquals("Complete duration: $reason", TimeFormatter.duration(minutes).length, layout.getLineEnd(0))
                    assertEquals("No truncated duration: $reason", 0, layout.getEllipsisCount(0))
                    assertTrue("Vertical bounds: $reason", y >= 0 && y + duration.height <= tile.height)
                }
            }
        }
    }

    @Test fun previewCancellationAndSavedResizeKeepTheSameTitleColumn() {
        launch { add(name, 540, 55) }
        scrollTo(main { screen.context.px(TimelineTopClearance + heightForMinutes(480)) })
        awaitTile(name)
        SystemClock.sleep(ScrollOwnsTouchMillis + 50)
        val originalRight = main {
            val tile = tile(name)
            val right = titleRight(tile)
            val down = SystemClock.uptimeMillis()
            val y = tile.height - activity.dp(4f)
            fun send(action: Int, at: Float) {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tile.width / 2f, at, 0).let {
                    tile.onTouchEvent(it); it.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN, y)
            tile.run()
            try {
                for (minutes in intArrayOf(60, 65, 115, 120, 125, 65, 60, 55)) {
                    send(MotionEvent.ACTION_MOVE, y + activity.dp(heightForMinutes(minutes - 55)))
                    assertEquals(minutes, tile.displayedDurationMinutes)
                    assertEquals("Resize preview at $minutes minutes", right, titleRight(tile))
                }
            } finally { send(MotionEvent.ACTION_CANCEL, y) }
            assertEquals("Cancellation", right, titleRight(tile))
            right
        }
        assertEquals(55, stored(name)!!.durationMinutes)
        val area = main { Rect().also { tile(name).getDrawingRect(it); screen.offsetDescendantRectToMyCoords(tile(name), it) } }
        gesture(area.exactCenterX(), area.bottom - activity.dp(4f), 0f, activity.dp(heightForMinutes(10)))
        await { stored(name)?.durationMinutes == 65 }
        await { main { tile(name).block.durationMinutes == 65 } }
        main { assertEquals("Saved resize", originalRight, titleRight(tile(name))) }
    }
}
