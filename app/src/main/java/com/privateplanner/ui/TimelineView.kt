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
import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// What the day and its blocks need from the screen around them.
internal interface TimelineHost {
    val palette: PlannerPalette
    val scrollPx: Int
    val viewportHeightPx: Int
    // The heading's bottom edge: where pinned titles rest and the top scroll zone begins.
    val headerHeightPx: Int
    // The viewport's bottom edge above the navigation bar.
    val visibleBottomPx: Int
    fun scrollTimelineBy(delta: Float): Boolean
    fun haptic(constant: Int)
    fun overlapPolicy(blockId: Long): OverlapPolicy
    fun onEmptyTimeTap(minutes: Int)
    fun onBlockTap(id: Long)
    fun onBlockRename(id: Long)
    fun onBlockDelete(id: Long)
    fun onBlockMove(id: Long, startMinutes: Int): Boolean
    fun onBlockResize(id: Long, durationMinutes: Int): Boolean
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
// laying them out on the main thread. The day measures its own if the density or font
// scale differ.
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

// The whole day: the grid and its labels, one view per block, and the current time. It is
// as tall as the day and sits in the scroll view; scrolling re-draws nothing.
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
    private val labels = preparedGridLabels?.takeIf { it.fits(context) } ?: GridLabels(context)
    private val tiles = LongSparseArray<TimeBlockView>()
    private val nowBadge = NowBadge(context)
    private var blocks: List<PlannerBlock> = Collections.emptyList()
    private var layouts: BlockLayouts = BlockLayouts.Empty
    private var windowStart = Int.MIN_VALUE
    private var windowHeight = 0
    // Kept through a dense day's windowing while its gesture runs.
    var activeBlockId = 0L

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
    }

    fun setBlocks(value: List<PlannerBlock>) {
        if (blocks === value) return
        blocks = value
        layouts = OverlapLayoutCalculator.calculate(value)
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
        val dense = blocks.size > 64
        windowHeight = viewportHeight()
        windowStart = if (dense) visibleStart() else Int.MIN_VALUE
        val end = if (dense) windowStart + ceil(windowHeight / hourPx * 60).toInt() + 90 else Int.MAX_VALUE
        val stale = tiles.clone()
        for (block in blocks) {
            if (dense && block.id != activeBlockId &&
                (block.endMinutes < windowStart || block.startMinutes > end)
            ) continue
            stale.remove(block.id)
            val columns = layouts[block.id]!!
            val tile = tiles[block.id]
            if (tile != null) {
                tile.bind(block, columns)
            } else {
                TimeBlockView(context, host, this, block, columns).also {
                    tiles.put(block.id, it)
                    addView(it, childCount - 1)
                }
            }
        }
        for (index in 0 until stale.size()) {
            tiles.remove(stale.keyAt(index))
            removeView(stale.valueAt(index))
        }
        requestLayout()
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
        if (event.actionMasked == MotionEvent.ACTION_UP && event.y >= topPx) {
            host.onEmptyTimeTap(TimeSnapper.minutesFromY(event.y - topPx, hourPx))
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
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
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
