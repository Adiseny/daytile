package com.privateplanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.util.LongSparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.privateplanner.domain.BlockLayouts
import com.privateplanner.domain.OverlapLayoutCalculator
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.holds
import java.util.Arrays
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// What the day and its blocks need from the screen around them.
internal interface TimelineHost : EdgeScrollHost {
    val palette: PlannerPalette
    val scrollPx: Int
    val viewportHeightPx: Int
    // The bottom of the fade beneath the heading, where pinned titles rest: clear of the tint.
    val headerFadeBottomPx: Int
    // When the day last scrolled, by the clock touches are timed by.
    val lastScrollMillis: Long
    fun haptic(constant: Int)
    fun overlapPolicy(blockId: Long): OverlapPolicy
    fun onEmptyTimeTap(minutes: Int)
    fun onBlockTap(id: Long)
    fun onBlockRename(id: Long)
    fun onBlockDelete(id: Long)
    fun onBlockMove(id: Long, startMinutes: Int): Boolean
    fun onBlockResize(id: Long, durationMinutes: Int): Boolean
    // Blocks lifted together: whether they have room that many minutes on, to move them
    // there, and that which are lifted has changed.
    fun canShift(ids: LongArray, deltaMinutes: Int): Boolean
    fun onBlocksShift(ids: LongArray, deltaMinutes: Int)
    fun onLiftedChanged()
    fun onShowWeek()
}

// The 48 label layouts, measured once. Layout depends only on text, size, density and font
// scale, so layouts measured on the launch warm-up thread draw exactly as ones measured
// on the main thread.
private class GridLabels(context: Context) {
    val density = context.resources.displayMetrics.density
    val fontScale = context.resources.configuration.fontScale
    private val hourPaint = context.textPaint(16f, 600)
    private val halfHourPaint = context.textPaint(12f, 600)
    val hours = List(24) { textBlock(TimeFormatter.time(it * 60), hourPaint, 0f, Unbounded) }
    val halfHours = List(24) { textBlock(TimeFormatter.time(it * 60 + 30), halfHourPaint, 0f, Unbounded) }

    fun fits(context: Context): Boolean =
        density == context.resources.displayMetrics.density && fontScale == context.resources.configuration.fontScale
}

@Volatile
private var preparedGridLabels: GridLabels? = null

// Runs on the launch warm-up thread, so the first frame draws the labels instead of
// laying them out on the main thread. The day asks for them only as it first draws, by
// when they are ready, and measures its own if the density or font scale differ.
internal fun Context.prepareGridLabels() {
    preparedGridLabels = GridLabels(this)
}

private const val HourLabelCollisionMinutes = 14
private const val HalfHourLabelCollisionMinutes = 13

internal fun hiddenGridLabelMinutes(currentTimeMinutes: Int?): Int? {
    val current = currentTimeMinutes ?: return null
    val nearestHalfHour = ((current + 15) / 30) * 30
    if (nearestHalfHour !in 0 until TimeSnapper.MinutesPerDay) return null
    val collisionMinutes = if (nearestHalfHour % 60 == 0) {
        HourLabelCollisionMinutes
    } else {
        HalfHourLabelCollisionMinutes
    }
    return nearestHalfHour.takeIf { abs(current - nearestHalfHour) <= collisionMinutes }
}

internal fun heightForMinutes(minutes: Int): Float = HourHeight * (minutes / 60f)

internal fun centredTouchTop(top: Float, contentHeight: Float): Float {
    val touchHeight = max(contentHeight, MinimumTouchTarget)
    return (top - (touchHeight - contentHeight) / 2).coerceAtLeast(0f).coerceAtMost(max(DayHeight - touchHeight, 0f))
}

private val NoBlocks = LongArray(0)

// The whole day: the grid and its labels, one view per block, and the current time. It is
// as tall as the day and sits in the scroll view; scrolling re-draws nothing. While the
// day is changing a second day's blocks stand beside the selected day's: the day arriving
// or, once that is the selected one, the day leaving.
internal class DayView(context: Context, private val host: TimelineHost) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val hourPx = HourHeight * density
    private val topPx = Math.round(TimelineTopClearance * density)
    private val gutterPx = TimelineGutter * density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GridStrokeWidth * density
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hourPath = Path()
    private val halfHourPath = Path()
    private val quarterPath = Path()
    private val fiveMinutePath = Path()
    private var tiles = LongSparseArray<TimeBlockView>()
    private val nowBadge = NowBadge(context)
    private var blocks: List<PlannerBlock> = Collections.emptyList()
    private var layouts: BlockLayouts = BlockLayouts.Empty
    private var otherTiles = LongSparseArray<TimeBlockView>()
    private var otherBlocks: List<PlannerBlock> = Collections.emptyList()
    private var otherLayouts: BlockLayouts = BlockLayouts.Empty
    private var paired = false
    // How far each day's blocks stand from their place.
    private var shift = 0f
    private var otherShift = 0f
    private var windowStart = Int.MIN_VALUE
    private var windowHeight = 0
    // Kept through a dense day's windowing while its gesture runs.
    var activeBlockId = 0L

    // Blocks lifted to be moved together. Two held at once are lifted and stay so when let
    // go; then a tap lifts another or puts one down, a hold on any carries them all, and a
    // tap on empty time puts them down where they were.
    var lifted = NoBlocks
        private set
    // How many minutes the lifted blocks have been carried, while one of them is held.
    var carried = 0
        private set
    // A touch that has had two fingers: nothing in it is a tap.
    var manyFingers = false

    val hasLifted: Boolean get() = lifted.size != 0

    fun isLifted(id: Long): Boolean = lifted.holds(id)

    private fun setLifted(ids: LongArray) {
        val before = lifted
        lifted = ids
        for (id in before) tiles[id]?.invalidate()
        for (id in ids) tiles[id]?.invalidate()
        host.onLiftedChanged()
    }

    fun lift(id: Long) {
        if (isLifted(id)) return
        val ids = Arrays.copyOf(lifted, lifted.size + 1)
        ids[ids.size - 1] = id
        setLifted(ids)
    }

    fun toggle(id: Long) {
        if (!isLifted(id)) return lift(id)
        val ids = LongArray(lifted.size - 1)
        var index = 0
        for (other in lifted) if (other != id) ids[index++] = other
        setLifted(ids)
    }

    fun putDownAll() {
        if (hasLifted) setLifted(NoBlocks)
    }

    // The block being held, lifted with another held after it: unless the first is already
    // on its way somewhere, which the second then leaves alone.
    fun liftWith(id: Long): Boolean {
        val first = tiles[activeBlockId] ?: return false
        if (!first.heldStill) return false
        first.cancelGesture()
        lift(first.block.id)
        lift(id)
        return true
    }

    // As far as the lifted blocks can go that way with all of them still in the day.
    fun clampCarry(deltaMinutes: Int): Int {
        var earliest = TimeSnapper.MinutesPerDay
        var latest = 0
        for (block in blocks) {
            if (!isLifted(block.id)) continue
            earliest = min(earliest, block.startMinutes)
            latest = max(latest, block.endMinutes)
        }
        return deltaMinutes.coerceAtLeast(-earliest).coerceAtMost(TimeSnapper.MinutesPerDay - latest)
    }

    // The lifted blocks follow the one that is held.
    fun carry(minutes: Int) {
        carried = minutes
        for (id in lifted) {
            val tile = tiles[id] ?: continue
            tile.translationY = minutes / 60f * hourPx
            tile.translationZ = if (minutes != 0) 2f else 0f
            tile.invalidate()
        }
    }

    var showsNow = false
        set(value) {
            if (field == value) return
            field = value
            nowBadge.visibility = if (value) VISIBLE else GONE
            placeNow()
            invalidate()
        }

    // The grid draws again with the line: once a minute, and only on today.
    var minute = -1
        set(value) {
            if (field == value) return
            field = value
            nowBadge.time = TimeFormatter.time(value)
            placeNow()
            if (showsNow) invalidate()
        }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        nowBadge.visibility = GONE
        nowBadge.translationZ = 4f
        addView(nowBadge)
    }

    fun paletteChanged() {
        invalidate()
        nowBadge.invalidate()
        for (index in 0 until tiles.size()) tiles.valueAt(index).paletteChanged()
        for (index in 0 until otherTiles.size()) otherTiles.valueAt(index).paletteChanged()
    }

    fun setBlocks(value: List<PlannerBlock>) {
        if (blocks === value) return
        // A title edit leaves every column in place.
        var moved = blocks.size != value.size
        if (!moved) for (index in value.indices) {
            val before = blocks[index]
            val after = value[index]
            if (before.id != after.id || before.startMinutes != after.startMinutes || before.durationMinutes != after.durationMinutes) {
                moved = true
                break
            }
        }
        if (moved) layouts = OverlapLayoutCalculator.calculate(value)
        blocks = value
        // A lifted block that is no longer of this day is no longer lifted.
        if (hasLifted) {
            var kept = 0
            for (block in value) if (isLifted(block.id)) kept++
            if (kept != lifted.size) {
                val ids = LongArray(kept)
                kept = 0
                for (block in value) if (isLifted(block.id)) ids[kept++] = block.id
                setLifted(ids)
            }
        }
        syncTiles()
    }

    fun cancelGesture() {
        for (index in 0 until tiles.size()) tiles.valueAt(index).cancelGesture()
    }

    // Small days keep every tile. Dense days keep only those near the viewport, including
    // long tiles crossing it and any tile with a gesture in progress.
    private fun viewportHeight(): Int = host.viewportHeightPx.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

    // Half an hour of lead on either side, updated at half-hour boundaries. Ordinary
    // scroll frames calculate one integer instead of allocating a new range each time.
    private fun visibleStart(): Int = (max(host.scrollPx - topPx, 0) / hourPx * 2).toInt() * 30 - 30

    private fun syncTiles() {
        windowHeight = viewportHeight()
        windowStart = sync(blocks, layouts, tiles, shift)
    }

    // One day's tiles made to match its blocks. Answers where a dense day's window starts.
    private fun sync(blocks: List<PlannerBlock>, layouts: BlockLayouts, tiles: LongSparseArray<TimeBlockView>, shift: Float): Int {
        val dense = blocks.size > 64
        val windowStart = if (dense) visibleStart() else Int.MIN_VALUE
        val end = if (dense) windowStart + ceil(viewportHeight() / hourPx * 60).toInt() + 90 else Int.MAX_VALUE
        for (block in blocks) {
            if (dense && block.startMinutes > end && activeBlockId == 0L) break
            if (dense && block.id != activeBlockId &&
                (block.endMinutes < windowStart || block.startMinutes > end)
            ) continue
            val columns = layouts[block.id]!!
            val tile = tiles[block.id]
            if (tile != null) {
                tile.retained = true
                tile.bind(block, columns)
            } else {
                TimeBlockView(context, host, this, block, columns).also {
                    it.retained = true
                    it.translationX = shift
                    tiles.put(block.id, it)
                    addView(it, childCount - 1)
                }
            }
        }
        // Do not clone both sparse-array buffers or search the clone for every kept
        // tile. Collect removals first: valueAt() compacts after a remove, so deleting
        // during this scan would copy the remaining tiles once per removed child.
        val stale = ArrayList<TimeBlockView>()
        for (index in 0 until tiles.size()) {
            val tile = tiles.valueAt(index)
            if (tile.retained) tile.retained = false else stale.add(tile)
        }
        for (tile in stale) {
            tiles.remove(tile.block.id)
            removeView(tile)
        }
        // Adding/removing a child already requests layout. Existing tiles place only
        // themselves when their geometry changes; scrolling needs no whole-screen layout.
        return windowStart
    }

    // --- A second day beside the selected one, while the day changes ------------------

    fun openOther(value: List<PlannerBlock>) {
        otherBlocks = value
        otherLayouts = OverlapLayoutCalculator.calculate(value)
        paired = true
        sync(value, otherLayouts, otherTiles, otherShift)
        // Drawn again once, for the clip below.
        invalidate()
    }

    // The arriving day is the selected one from here, and the day that was is the one leaving.
    fun swapDays() {
        val held = tiles
        tiles = otherTiles
        otherTiles = held
        val were = blocks
        blocks = otherBlocks
        otherBlocks = were
        val columns = layouts
        layouts = otherLayouts
        otherLayouts = columns
        val place = shift
        shift = otherShift
        otherShift = place
        syncTiles()
    }

    // Only moves what is drawn already: nothing is laid out or drawn again.
    fun slide(selected: Float, other: Float) {
        shift = selected
        otherShift = other
        for (index in 0 until tiles.size()) tiles.valueAt(index).translationX = selected
        for (index in 0 until otherTiles.size()) otherTiles.valueAt(index).translationX = other
    }

    fun closeOther() {
        for (index in 0 until otherTiles.size()) removeView(otherTiles.valueAt(index))
        otherTiles.clear()
        otherBlocks = Collections.emptyList()
        otherLayouts = BlockLayouts.Empty
        paired = false
        slide(0f, 0f)
        invalidate()
    }

    // Blocks on their way in or out pass behind the hour column, not over it.
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (!paired || child === nowBadge) return super.drawChild(canvas, child, drawingTime)
        canvas.save()
        canvas.clipRect(gutterPx, 0f, width.toFloat(), height.toFloat())
        val more = super.drawChild(canvas, child, drawingTime)
        canvas.restore()
        return more
    }

    // Called as the day scrolls: only a dense day's window and pinned titles depend on it.
    fun scrolled() {
        if (windowStart != Int.MIN_VALUE && (windowStart != visibleStart() || windowHeight != viewportHeight())) syncTiles()
        for (index in 0 until tiles.size()) tiles.valueAt(index).scrolled()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), topPx + Math.round(DayHeight * density))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        hourPath.rewind()
        halfHourPath.rewind()
        quarterPath.rewind()
        fiveMinutePath.rewind()
        fun Path.line(y: Float, endX: Float) {
            moveTo(gutterPx, y)
            lineTo(endX, y)
        }
        for (hour in 0..24) {
            val hourY = Math.round(hour * hourPx).toFloat()
            hourPath.line(hourY, width.toFloat())
            if (hour == 24) break
            for (tick in 1 until 12) {
                val tickY = Math.round(hourY + hourPx * tick / 12f).toFloat()
                when {
                    tick == 6 -> halfHourPath.line(tickY, width.toFloat())
                    tick % 3 == 0 -> quarterPath.line(tickY, gutterPx + QuarterHourTickLength * density)
                    else -> fiveMinutePath.line(tickY, gutterPx + FiveMinuteTickLength * density)
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until tiles.size()) place(tiles.valueAt(index))
        for (index in 0 until otherTiles.size()) place(otherTiles.valueAt(index))
        placeNow()
    }

    // The touch target, at least 48dp tall and centred on a shorter tile, and the visual
    // tile within it. Every length is rounded to pixels on its own, as it always was.
    fun place(tile: TimeBlockView) {
        fun px(dp: Float) = Math.round(dp * density)
        val block = tile.block
        val columnWidth = max(width / density - TimelineGutter - TimelineEndPadding, 1f) / max(tile.columns.columnCount, 1)
        val left = TimelineGutter + columnWidth * tile.columns.columnIndex
        val tileWidth = max(columnWidth - BlockColumnGap, MinimumTouchTarget)
        val baseTop = heightForMinutes(block.startMinutes)
        val baseHeight = heightForMinutes(block.durationMinutes)
        val touchTop = centredTouchTop(baseTop, baseHeight)
        val visualHeight = heightForMinutes(tile.displayedDurationMinutes)
        val visualOffset = baseTop - touchTop
        val touchHeight = min(
            max(max(baseHeight, MinimumTouchTarget), visualOffset + visualHeight),
            max(DayHeight - touchTop, MinimumTouchTarget)
        )
        tile.setVisual(tileWidth, visualHeight, px(visualOffset), px(visualHeight))
        val x = px(left)
        val y = topPx + px(touchTop)
        tile.layout(x, y, x + px(tileWidth), y + px(touchHeight))
    }

    private fun nowY(): Float = HourHeight * (minute / 60f)

    private fun placeNow() {
        if (!showsNow) return
        val y = topPx + Math.round(max(nowY() - HourLabelHeight / 2, 0f) * density)
        val x = Math.round(6f * density)
        nowBadge.layout(x, y, x + Math.round(58f * density), y + Math.round(HourLabelHeight * density))
    }

    override fun onDraw(canvas: Canvas) {
        val colours = host.palette
        val hidden = if (showsNow) hiddenGridLabelMinutes(minute) else null
        val labels = preparedGridLabels?.takeIf { it.fits(context) } ?: GridLabels(context).also { preparedGridLabels = it }
        canvas.save()
        canvas.translate(0f, topPx.toFloat())
        canvas.drawPath(hourPath, stroke.apply { color = colours.HourLine })
        canvas.drawPath(halfHourPath, stroke.apply { color = colours.HalfHourLine })
        canvas.drawPath(quarterPath, stroke.apply { color = colours.QuarterTick })
        canvas.drawPath(fiveMinutePath, stroke.apply { color = withAlpha(colours.QuarterTick, FiveMinuteTickAlpha) })
        val labelWidth = (TimelineGutter - 8f) * density
        val hourLabelHeight = HourLabelHeight * density
        val halfHourLabelHeight = HalfHourLabelHeight * density
        for (hour in 0 until 24) {
            if (hidden != hour * 60) {
                val label = labels.hours[hour]
                label.draw(
                    canvas,
                    labelWidth - label.width,
                    max(hourPx * hour - hourLabelHeight / 2f, 0f) + (hourLabelHeight - label.height) / 2f,
                    colours.TimeText
                )
            }
            if (hidden != hour * 60 + 30) {
                val label = labels.halfHours[hour]
                label.draw(
                    canvas,
                    labelWidth - label.width,
                    hourPx * hour + hourPx / 2f - halfHourLabelHeight / 2f + (halfHourLabelHeight - label.height) / 2f,
                    colours.MutedText
                )
            }
        }
        canvas.restore()
    }

    // The current time's line and dot, over the blocks; its badge is a child above them too.
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!showsNow) return
        val lineY = topPx + Math.round(nowY() * density).toFloat()
        nowPaint.color = host.palette.Delete
        nowPaint.style = Paint.Style.STROKE
        nowPaint.strokeWidth = 2f * density
        canvas.drawLine(gutterPx, lineY, width.toFloat(), lineY, nowPaint)
        nowPaint.style = Paint.Style.FILL
        canvas.drawCircle(gutterPx, lineY, 4f * density, nowPaint)
    }

    // A tap that no block took is on empty time. A scroll or a day swipe takes the gesture
    // away before it lifts.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && event.y >= topPx && !manyFingers) {
            // With blocks lifted, it puts them down.
            if (hasLifted) putDownAll() else host.onEmptyTimeTap(TimeSnapper.minutesFromY(event.y - topPx, hourPx))
        }
        return true
    }

    // The time a screen reader's "add" lands on: where the current time sits at launch.
    private fun focusMinutes(): Int = TimeSnapper.minutesFromY(
        max(host.scrollPx + host.viewportHeightPx * CurrentTimeViewportFraction - topPx, 0f),
        hourPx
    )

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val time = TimeFormatter.time(focusMinutes())
        info.contentDescription = "Day timeline, $time"
        info.isClickable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Add block at $time"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(WeekAction, "Show week"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == WeekAction) {
            host.onShowWeek()
            return true
        }
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return super.performAccessibilityAction(action, arguments)
        host.onEmptyTimeTap(focusMinutes())
        return true
    }

    // The time badge in the gutter beside the line.
    private inner class NowBadge(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = context.textPaint(13f, 600)
        private var block: TextBlock? = null

        var time = ""
            set(value) {
                if (field == value) return
                field = value
                block = null
                contentDescription = "Current time, $value"
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val colours = host.palette
            val radius = 7f * density
            paint.style = Paint.Style.FILL
            paint.color = colours.Sheet
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            // A whole-pixel stroke inset by half its width, its corners smaller by as much.
            val strokeWidth = min(ceil(density), ceil(min(width, height) / 2f))
            val half = strokeWidth / 2
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = withAlpha(colours.Delete, 0.35f)
            canvas.drawRoundRect(half, half, width - half, height - half, max(radius - half, 0f), max(radius - half, 0f), paint)
            val text = block ?: textBlock(time, textPaint, context.sp(15f), width).also { block = it }
            text.draw(
                canvas,
                Math.round((width - text.width) / 2f).toFloat(),
                Math.round((height - text.height) / 2f).toFloat(),
                colours.Delete
            )
        }
    }
}
