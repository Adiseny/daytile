package com.privateplanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.TextPaint
import android.util.LongSparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.MovePlacement
import com.privateplanner.domain.OverlapLayoutCalculator
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.blockBackgroundArgb
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// What the week and its blocks need from the screen around them. A day is named by its
// place in the week on show, Monday 0.
internal interface WeekHost : EdgeScrollHost {
    val palette: PlannerPalette
    val weekScrollPx: Int
    fun haptic(constant: Int)
    fun dayName(day: Int): String
    fun overlapPolicy(day: Int, blockId: Long): OverlapPolicy
    fun onDayOpen(day: Int)
    fun onShowDay()
    fun onEmptyTimeTap(day: Int, minutes: Int)
    fun onBlockTap(block: PlannerBlock)
    fun onBlockRename(block: PlannerBlock)
    fun onBlockDelete(block: PlannerBlock)
    fun onBlockMove(block: PlannerBlock, day: Int, startMinutes: Int): Boolean
    fun onBlockResize(block: PlannerBlock, durationMinutes: Int): Boolean
}

// The colour of the band a minute of the day lies in: the colour a block starting then
// takes, as a wash over the paper.
internal fun bandColour(minutes: Int, paper: Int): Int =
    compositeOver(withAlpha(blockBackgroundArgb(minutes, 0), WeekBandAlpha), paper)

// Where the seven days stand across a width, in pixels: the margin for the hours, then
// columns of one width with one gap between each.
internal class WeekColumns(private val density: Float, private val labelWidth: Int) {
    var gutter = 0
        private set
    var column = 0
        private set
    var gap = 0
        private set

    fun fit(width: Int) {
        gap = Math.round(WeekDayGap * density)
        // Room for the widest hour label at any font scale.
        gutter = max(Math.round(WeekGutter * density), labelWidth + Math.round(10f * density))
        column = max((width - gutter - Math.round(WeekEndPadding * density) - 6 * gap) / 7, 1)
    }

    fun left(day: Int): Int = gutter + day * (column + gap)

    // The day whose column, or the gap before it, a point is in; -1 in the margin.
    fun dayAt(x: Float): Int = if (x < gutter - gap) -1 else
        ((x - gutter + gap / 2f) / (column + gap)).toInt().coerceAtLeast(0).coerceAtMost(6)
}

// The week: seven days side by side down one day's hours. Each day is a stack of bands
// three hours tall, in the colour blocks take in those hours, with its blocks as views
// over them and no line anywhere. It is as tall as the day and sits in a scroll view, as
// the day does; scrolling draws nothing again. A week is a page of its own: its bands,
// its blocks and the current time's line move as one thing when the week changes, with
// the next week's page beside it, and only the margin of hours stays where it is.
internal class WeekView(context: Context, private val host: WeekHost) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val labelPaint = gridPaint(11f, 600)
    private val labels = Array(24 / WeekBandHours) {
        textBlock(TimeFormatter.time(it * WeekBandHours * 60), labelPaint, 0f, Unbounded)
    }
    val columns: WeekColumns
    // The titles' text, lighter on a short tile.
    private val titles = arrayOf(gridPaint(10f, 500), gridPaint(10f, 600))
    private val bands = IntArray(24 / WeekBandHours)
    private var bandPaper = 0
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private var page = Page(context)
    private var other = Page(context)
    private var paired = false

    // Where the day begins beneath the heading and how tall an hour is, both given by the
    // screen: from the first hour to midnight fills what the heading leaves, where an
    // hour can then be tall enough.
    var topPx = 0
        private set
    var hourPx = WeekMinHourHeight * density
        private set
    private var bottomPx = 0

    // The tile being moved, if one is.
    var active: WeekTile? = null

    // Today's place in the week on show, or -1 on another week.
    var today: Int
        get() = page.today
        set(value) {
            page.today = value
        }

    var minute = 0
        set(value) {
            if (field == value) return
            field = value
            page.invalidate()
            other.invalidate()
        }

    init {
        var widest = 0
        for (label in labels) widest = max(widest, label.width)
        columns = WeekColumns(density, widest)
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Week timeline"
        other.visibility = GONE
        addView(page)
        addView(other)
    }

    fun setFrame(top: Int, bottom: Int, viewport: Int) {
        val hour = max(WeekMinHourHeight * density, (viewport - top - bottom - 3f * density) / (24 - WeekFirstHour))
        if (top == topPx && bottom == bottomPx && hour == hourPx) return
        topPx = top
        bottomPx = bottom
        hourPx = hour
        requestLayout()
        invalidate()
    }

    // Text that stands in the grid, an hour's label or a block's title, follows the
    // system's text size down and not up. Seven columns on a screen hold a short word to
    // a line at the size given and less at any larger, and wider labels would only narrow
    // them: the day, a pinch away, is where a title is as large as is asked for.
    private fun gridPaint(size: Float, weight: Int): TextPaint =
        context.textPaint(size, weight).also { it.textSize = min(it.textSize, size * density) }

    fun titlePaint(tall: Boolean): TextPaint = titles[if (tall) 1 else 0]

    fun paletteChanged() {
        invalidate()
        page.repaint()
        other.repaint()
    }

    // One day of the week on show. A day whose list is the one already shown costs nothing.
    fun setDay(day: Int, blocks: List<PlannerBlock>) {
        if (page.shown[day] === blocks) return
        page.shown[day] = blocks
        sync(day, blocks, page)
    }

    private fun sync(day: Int, blocks: List<PlannerBlock>, into: Page) {
        val tiles = into.tiles
        val layouts = OverlapLayoutCalculator.calculate(blocks)
        for (block in blocks) {
            val columns = layouts[block.id]!!
            val tile = tiles[block.id]
            if (tile != null) {
                tile.retained = true
                tile.bind(block, columns, day)
            } else {
                WeekTile(context, host, this, block, columns, day).also {
                    it.retained = true
                    tiles.put(block.id, it)
                    into.addView(it)
                }
            }
        }
        // As the day does: collect first, since removing compacts the array under the scan.
        var stale: ArrayList<WeekTile>? = null
        for (index in 0 until tiles.size()) {
            val tile = tiles.valueAt(index)
            if (tile.retained) tile.retained = false
            else if (tile.day == day) (stale ?: ArrayList<WeekTile>().also { stale = it }).add(tile)
        }
        stale?.let {
            for (tile in it) {
                tiles.remove(tile.block.id)
                into.removeView(tile)
            }
        }
    }

    // A hold in waiting as well as a move under way.
    fun cancelGesture() {
        for (index in 0 until page.tiles.size()) page.tiles.valueAt(index).cancelGesture()
    }

    // --- A second week beside the one on show, while the week changes ------------------

    fun openOther(days: Array<List<PlannerBlock>>, today: Int) {
        paired = true
        other.visibility = VISIBLE
        other.today = today
        for (day in 0..6) {
            other.shown[day] = days[day]
            sync(day, days[day], other)
        }
    }

    // The arriving week is the one on show from here, and the week that was is the one leaving.
    fun swapPages() {
        val held = page
        page = other
        other = held
    }

    // Only moves the two pages: nothing is laid out or drawn again.
    fun slide(selected: Float, leaving: Float) {
        page.translationX = selected
        other.translationX = leaving
    }

    fun closeOther() {
        other.clear()
        other.visibility = GONE
        paired = false
        slide(0f, 0f)
    }

    // A week on its way in or out passes behind the hours' margin, not over it.
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (!paired) return super.drawChild(canvas, child, drawingTime)
        canvas.save()
        canvas.clipRect((columns.gutter - columns.gap).toFloat(), 0f, width.toFloat(), height.toFloat())
        val more = super.drawChild(canvas, child, drawingTime)
        canvas.restore()
        return more
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        val height = topPx + Math.round(24 * hourPx) + bottomPx + Math.round(3f * density)
        setMeasuredDimension(width, height)
        val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        page.measure(exactWidth, exactHeight)
        other.measure(exactWidth, exactHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        columns.fit(right - left)
        page.layout(0, 0, right - left, bottom - top)
        other.layout(0, 0, right - left, bottom - top)
    }

    // A tile within its day's column, beside whatever overlaps it, a pixel short of the
    // block that follows it. Every length is rounded to pixels on its own.
    fun place(tile: WeekTile) {
        val block = tile.block
        val count = max(tile.columns.columnCount, 1)
        val inset = Math.round(WeekTileInset * density)
        val between = Math.round(WeekTileGap * density)
        val width = max((columns.column - 2 * inset - (count - 1) * between) / count, 1)
        val x = columns.left(tile.day) + inset + tile.columns.columnIndex * (width + between)
        val y = topPx + Math.round(block.startMinutes / 60f * hourPx)
        val height = max(Math.round(tile.shownDuration / 60f * hourPx) - Math.round(density), Math.round(3f * density))
        tile.layout(x, y, x + width, y + height)
    }

    // The margin alone: a label where the colour of the bands changes, and a tick for each
    // hour between.
    override fun onDraw(canvas: Canvas) {
        val colours = host.palette
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = GridStrokeWidth * density
        stroke.color = colours.HourLine
        val labelRight = columns.gutter - 8f * density
        for (hour in 0 until 24) {
            val y = Math.round(topPx + hour * hourPx).toFloat()
            if (hour % WeekBandHours != 0) {
                canvas.drawLine(labelRight, y, columns.gutter - 3f * density, y, stroke)
            } else {
                val label = labels[hour / WeekBandHours]
                label.draw(canvas, labelRight - label.width, max(y - label.height / 2f, topPx.toFloat()), colours.TimeText)
            }
        }
    }

    // The quarter hour a height lies in.
    private fun minutesAt(y: Float): Int =
        ((y - topPx) / hourPx * 60).toInt().coerceAtLeast(0).coerceAtMost(TimeSnapper.MinutesPerDay - 1) /
            WeekSnapMinutes * WeekSnapMinutes

    // A tap that no block took is on empty time. A scroll or a week swipe takes the
    // gesture away before it lifts.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && event.y >= topPx) {
            val day = columns.dayAt(event.x)
            if (day >= 0) host.onEmptyTimeTap(day, minutesAt(event.y))
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityAction(WeekAction, "Show day"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action != WeekAction) return super.performAccessibilityAction(action, arguments)
        host.onShowDay()
        return true
    }

    // One week: its seven columns of bands, its blocks over them and, where today is in
    // it, the current time's line.
    private inner class Page(context: Context) : ViewGroup(context) {
        val tiles = LongSparseArray<WeekTile>()
        val shown = arrayOfNulls<List<PlannerBlock>>(7)

        // Today's place in this week, or -1.
        var today = -1
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        init {
            setWillNotDraw(false)
        }

        fun repaint() {
            invalidate()
            for (index in 0 until tiles.size()) tiles.valueAt(index).invalidate()
        }

        fun clear() {
            removeAllViews()
            tiles.clear()
            for (day in 0..6) shown[day] = null
            today = -1
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (index in 0 until tiles.size()) place(tiles.valueAt(index))
        }

        // The current time's line and the dot it starts from, across today's column and over
        // its blocks, carried ones too.
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (today < 0) return
            val x = columns.left(today).toFloat()
            val y = topPx + Math.round(minute / 60f * hourPx).toFloat()
            stroke.color = host.palette.Delete
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 2f * density
            canvas.drawLine(x, y, x + columns.column, y, stroke)
            stroke.style = Paint.Style.FILL
            canvas.drawCircle(x, y, 3.5f * density, stroke)
        }

        // A pixel of paper above and below each band keeps one from the next.
        override fun onDraw(canvas: Canvas) {
            val paper = host.palette.Paper
            if (bandPaper != paper) {
                bandPaper = paper
                for (band in bands.indices) bands[band] = bandColour(band * WeekBandHours * 60, paper)
            }
            val radius = WeekBandRadius * density
            val bandHeight = WeekBandHours * hourPx
            TilePaint.style = Paint.Style.FILL
            for (day in 0..6) {
                val left = columns.left(day).toFloat()
                for (band in bands.indices) {
                    TilePaint.color = bands[band]
                    canvas.drawRoundRect(
                        left, Math.round(topPx + band * bandHeight) + density, left + columns.column,
                        Math.round(topPx + (band + 1) * bandHeight) - density, radius, radius, TilePaint
                    )
                }
            }
        }
    }

}

private const val Idle = 0
private const val Pending = 1
private const val Moving = 2
private const val Resizing = 3

// One block of the week: its tile, which is all of it there is to touch. A tap opens its
// actions, a hold moves it, to another time or to another day, and a hold on its lower
// edge lengthens or shortens it.
internal class WeekTile(
    context: Context,
    private val host: WeekHost,
    private val week: WeekView,
    block: PlannerBlock,
    columns: BlockLayout,
    day: Int
) : View(context), EdgeDragged, Runnable {
    private val density = resources.displayMetrics.density

    var block = block
        private set
    var columns = columns
        private set
    var day = day
        private set

    // Set while a day's tiles are synchronised, then cleared in the same pass.
    var retained = false

    // As long as the tile stands: the length its edge has been taken to, while it is held.
    val shownDuration: Int get() = if (phase == Resizing) lastDuration else block.durationMinutes

    var title: TextBlock? = null
        private set
    private var titleY = 0
    private var stale = true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        describe()
    }

    fun bind(block: PlannerBlock, columns: BlockLayout, day: Int) {
        val changed = this.block != block || this.day != day
        if (!changed && this.columns == columns) return
        if (this.block.title != block.title) stale = true
        this.block = block
        this.columns = columns
        this.day = day
        week.place(this)
        invalidate()
        if (changed) describe()
    }

    private fun describe() {
        contentDescription = "${block.title}, ${host.dayName(day)}, " +
            "${TimeFormatter.range(block.startMinutes, shownDuration, " to ")}, " +
            "${TimeFormatter.duration(shownDuration)}. Actions: Rename, Delete."
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        stale = true
    }

    // The title alone, in as many lines as the tile is tall: the time is read from the
    // margin. A tile too short for a line, or too narrow for a few letters as one of two
    // side by side is, is its colour.
    private fun layOut() {
        stale = false
        val paint = week.titlePaint(height >= 48f * density)
        val line = paint.textSize * 1.2f
        val inset = Math.round(WeekTitleInset * density)
        var lines = ((height - inset - density) / line).toInt()
        if (lines == 0 && height >= line) lines = 1
        val text = if (lines == 0 || width - 2 * inset < paint.textSize * 2.4f) null else textBlock(
            block.title, paint, line, width - 2 * inset, maxLines = lines, ellipsis = true, fill = true, hyphenate = true
        )
        title = text
        // One line sits in the middle of its tile; more start from the top.
        titleY = if (text != null && lines == 1) Math.round((height - text.height) / 2f) else inset
    }

    // In one pass and in this order: fill, title, handle, border, as the day's tiles are
    // drawn. The handle is there only while the lower edge is held: at this size one on
    // every tile would be half the week's ink.
    override fun onDraw(canvas: Canvas) {
        if (stale) layOut()
        val moving = phase == Moving
        val background = blockBackgroundArgb(block.startMinutes, columns.columnIndex)
        // The colour and the ink the day gives the same block, to the shade: what its tile
        // shows over the paper. Here it is painted solid, since a tile may reach into the
        // next band, whose edge must not show through it, and one being carried must hide
        // what it passes over.
        val paper = host.palette.Paper
        val ink = tileInkFor(background, paper, moving)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = min(WeekTileRadius * density, min(w, h) / 2f)
        TilePaint.style = Paint.Style.FILL
        TilePaint.color = compositedTileBackground(background, paper, moving)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, TilePaint)
        val handleHeight = min(2f * density, h / 3f)
        val handleTop = if (phase == Resizing) h - min(2f * density, h / 4f) - handleHeight else h
        // On a tile too short for both, the handle is what is being looked at.
        title?.let { if (titleY + it.height <= handleTop) it.draw(canvas, Math.round(WeekTitleInset * density).toFloat(), titleY.toFloat(), ink) }
        if (phase == Resizing) {
            val handleWidth = min(WeekHandleWidth * density, w / 2f)
            val handleLeft = (w - handleWidth) / 2f
            TilePaint.color = withAlpha(ink, ResizeHandleHeldAlpha)
            canvas.drawRoundRect(
                handleLeft, handleTop, handleLeft + handleWidth, handleTop + handleHeight, handleHeight / 2f, handleHeight / 2f, TilePaint
            )
        }
        val stroke = min(ceil((if (moving) 1.5f else 1f) * density), ceil(min(w, h) / 2))
        val half = stroke / 2
        TilePaint.style = Paint.Style.STROKE
        TilePaint.strokeWidth = stroke
        TilePaint.color = withAlpha(ink, 0.30f)
        canvas.drawRoundRect(half, half, w - half, h - half, max(radius - half, 0f), max(radius - half, 0f), TilePaint)
    }

    // --- Gestures -------------------------------------------------------------------

    private var phase = Idle
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downY = 0f
    private var preHoldX = 0f
    private var preHoldY = 0f
    private var totalDx = 0f
    private var totalDy = 0f
    private var initialScroll = 0
    private var initialPointerViewportY = 0f
    private var hasDragged = false
    private var lastDay = 0
    private var lastStart = 0
    private var savableDay = 0
    private var savableStart = 0
    private var lastDuration = 0
    private var savableDuration = 0
    private var autoScroll: EdgeAutoScroll? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                phase = Pending
                downY = event.y
                lastRawX = event.rawX
                lastRawY = event.rawY
                preHoldX = 0f
                preHoldY = 0f
                postDelayed(this, BlockMoveHoldMillis)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> finish(cancelled = true)
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (phase == Pending) {
                    // Movement past the tolerance leaves the gesture to scrolling or the week swipe.
                    preHoldX += dx
                    preHoldY += dy
                    if (!heldStill()) finish(cancelled = true)
                } else if (phase != Idle) {
                    totalDx += dx
                    totalDy += dy
                    if (!hasDragged) {
                        val slop = ViewConfiguration.get(context).scaledTouchSlop
                        hasDragged = abs(totalDx) > slop || abs(totalDy) > slop
                    }
                    edgeScrolled()
                    autoScroll?.update()
                }
            }
            MotionEvent.ACTION_UP -> if (phase == Pending) {
                finish(cancelled = true)
                host.onBlockTap(block)
            } else {
                finish(cancelled = false)
            }
        }
        return true
    }

    private fun heldStill(): Boolean {
        val still = max(ViewConfiguration.get(context).scaledTouchSlop * 1.25f, HoldStillTolerance * density)
        return preHoldX * preHoldX + preHoldY * preHoldY <= still * still
    }

    // The hold has lasted: the tile is its own timer for it.
    override fun run() {
        if (phase != Pending) return
        if (!heldStill()) return finish(cancelled = true)
        parent.requestDisallowInterceptTouchEvent(true)
        initialScroll = host.weekScrollPx
        initialPointerViewportY = top - initialScroll + downY
        totalDx = preHoldX
        totalDy = preHoldY
        hasDragged = false
        week.active = this
        translationZ = 2f
        // The week's hours are shorter than the day's, so its edges scroll as much slower.
        var speed = week.hourPx / (HourHeight * density)
        if (downY >= height - min(WeekResizeEdge * density, height * 0.4f)) {
            // By its lower edge: nothing lifts and nothing buzzes, as on the day.
            phase = Resizing
            lastDuration = block.durationMinutes
            savableDuration = lastDuration
            speed *= ResizeScrollSpeed
        } else {
            host.haptic(HapticFeedbackConstants.LONG_PRESS)
            phase = Moving
            lastDay = day
            lastStart = block.startMinutes
            savableDay = day
            savableStart = block.startMinutes
        }
        invalidate()
        autoScroll = EdgeAutoScroll(host, density, this, speed)
    }

    override val pointerViewportY: Float get() = initialPointerViewportY + totalDy

    override val scrollsAtEdges: Boolean get() = hasDragged

    override fun edgeScrolled() = if (phase == Moving) moveTo() else resizeTo()

    // The tile stands on the day its middle is over and at the quarter hour its drag has
    // reached, keeping the minutes it began with. Every step ticks unless the spot is
    // unusable; only a savable one may be dropped on.
    private fun moveTo() {
        val hourPx = week.hourPx
        val steps = Math.round((totalDy + host.weekScrollPx - initialScroll) / hourPx * 60 / WeekSnapMinutes)
        val start = TimeSnapper.clampStart(block.startMinutes + steps * WeekSnapMinutes, block.durationMinutes)
        val to = week.columns.dayAt(left + width / 2f + totalDx).coerceAtLeast(0)
        translationX = ((to - day) * (week.columns.column + week.columns.gap)).toFloat()
        translationY = (start - block.startMinutes) / 60f * hourPx
        if (to == lastDay && start == lastStart) return
        lastDay = to
        lastStart = start
        invalidate()
        val placement = host.overlapPolicy(to, block.id).placement(start, block.durationMinutes)
        if (placement != MovePlacement.Invalid) host.haptic(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        if (placement == MovePlacement.Savable) {
            savableDay = to
            savableStart = start
        }
    }

    // The edge follows the drag a quarter hour at a time from the length the block began
    // with, ticking and savable as a move is.
    private fun resizeTo() {
        val steps = Math.round((totalDy + host.weekScrollPx - initialScroll) / week.hourPx * 60 / WeekSnapMinutes)
        val duration = TimeSnapper.clampDuration(block.startMinutes, block.durationMinutes + steps * WeekSnapMinutes)
        if (duration == lastDuration) return
        lastDuration = duration
        week.place(this)
        describe()
        val placement = host.overlapPolicy(day, block.id).placement(block.startMinutes, duration)
        if (placement != MovePlacement.Invalid) host.haptic(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        if (placement == MovePlacement.Savable) savableDuration = duration
    }

    private fun finish(cancelled: Boolean) {
        removeCallbacks(this)
        val was = phase
        phase = Idle
        if (was != Moving && was != Resizing) return
        parent?.requestDisallowInterceptTouchEvent(false)
        autoScroll?.stop()
        autoScroll = null
        week.active = null
        translationX = 0f
        translationY = 0f
        translationZ = 0f
        invalidate()
        if (was == Resizing) {
            // As it is stored again, until the screen is told otherwise.
            week.place(this)
            describe()
            if (!cancelled && savableDuration != block.durationMinutes) host.onBlockResize(block, savableDuration)
        } else if (!cancelled && (savableDay != day || savableStart != block.startMinutes)) {
            host.onBlockMove(block, savableDay, savableStart)
        }
    }

    fun cancelGesture() = finish(cancelled = true)

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    // --- Accessibility --------------------------------------------------------------

    private fun resizeBy(minutes: Int): Boolean {
        val duration = TimeSnapper.clampDuration(block.startMinutes, block.durationMinutes + minutes)
        if (duration == block.durationMinutes) return false
        return host.overlapPolicy(day, block.id).canPlace(block.startMinutes, duration) && host.onBlockResize(block, duration)
    }

    private fun moveBy(days: Int, minutes: Int): Boolean {
        val to = day + days
        val start = TimeSnapper.clampStart(block.startMinutes + minutes, block.durationMinutes)
        if (to < 0 || to > 6 || (days == 0 && start == block.startMinutes)) return false
        return host.overlapPolicy(to, block.id).canPlace(start, block.durationMinutes) && host.onBlockMove(block, to, start)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isClickable = true
        info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Open actions"))
        info.addAction(AccessibilityAction(RenameAction, "Rename"))
        info.addAction(AccessibilityAction(DeleteAction, "Delete"))
        info.addAction(AccessibilityAction(PreviousDayAction, "Move to the day before"))
        info.addAction(AccessibilityAction(NextDayAction, "Move to the day after"))
        info.addAction(AccessibilityAction(EarlierAction, "Move earlier 15 minutes"))
        info.addAction(AccessibilityAction(LaterAction, "Move later 15 minutes"))
        info.addAction(AccessibilityAction(ShortenAction, "Shorten 15 minutes"))
        info.addAction(AccessibilityAction(LengthenAction, "Lengthen 15 minutes"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_CLICK -> {
            host.onBlockTap(block)
            true
        }
        RenameAction -> {
            host.onBlockRename(block)
            true
        }
        DeleteAction -> {
            host.onBlockDelete(block)
            true
        }
        PreviousDayAction -> moveBy(-1, 0)
        NextDayAction -> moveBy(1, 0)
        EarlierAction -> moveBy(0, -WeekSnapMinutes)
        LaterAction -> moveBy(0, WeekSnapMinutes)
        ShortenAction -> resizeBy(-WeekSnapMinutes)
        LengthenAction -> resizeBy(WeekSnapMinutes)
        else -> super.performAccessibilityAction(action, arguments)
    }
}

// The week's seven days beneath the heading: a weekday over its date, each above its
// column, on the bare paper: the week is not drawn behind them. A tap opens the day. Like the
// week's page, a week's dates are a row of their own that travels with it when the week
// changes, the next week's row beside it.
internal class WeekDays(context: Context, private val host: WeekHost, private val columns: WeekColumns) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val namePaint = context.textPaint(12f, 600)
    private val datePaint = context.textPaint(20f, 600)
    private val nameLine = Math.round(context.sp(15f))
    private val dateLine = Math.round(context.sp(24f))
    private var pressColour = host.palette.PrimaryText
    private var row = Row(context)
    private var other = Row(context)
    private var paired = false

    // Where the heading ends and the days begin.
    var topPx = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    // The bottom edge, which the week scrolls beneath and its first hour opens against.
    val solidBottom: Int get() = topPx + Math.round(3f * density) + nameLine + dateLine + Math.round(11f * density)

    init {
        other.visibility = GONE
        addView(row)
        addView(other)
    }

    // The week on show: its Monday, today's place in it or -1, and how its days are named
    // and spoken.
    fun show(monday: LocalDate, today: Int, locale: Locale, spoken: DateTimeFormatter) = row.bind(monday, today, locale, spoken)

    fun openOther(monday: LocalDate, today: Int, locale: Locale, spoken: DateTimeFormatter) {
        paired = true
        other.visibility = VISIBLE
        other.bind(monday, today, locale, spoken)
    }

    fun swap() {
        val held = row
        row = other
        other = held
    }

    fun slide(selected: Float, leaving: Float) {
        row.translationX = selected
        other.translationX = leaving
    }

    fun closeOther() {
        other.visibility = GONE
        paired = false
        slide(0f, 0f)
    }

    // Each step redraws the days in its colours; only a flip of light and dark changes the ink
    // their ripples are made in.
    fun paletteChanged() {
        val ink = host.palette.PrimaryText
        row.repaint(ink)
        other.repaint(ink)
        pressColour = ink
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (!paired) return super.drawChild(canvas, child, drawingTime)
        canvas.save()
        canvas.clipRect((columns.gutter - columns.gap).toFloat(), 0f, width.toFloat(), height.toFloat())
        val more = super.drawChild(canvas, child, drawingTime)
        canvas.restore()
        return more
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), solidBottom)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        columns.fit(right - left)
        row.layout(0, 0, right - left, bottom - top)
        other.layout(0, 0, right - left, bottom - top)
    }

    // One week's seven dates.
    private inner class Row(context: Context) : ViewGroup(context) {
        private val heads = Array(7) { Head(context, it) }
        private var monday: LocalDate? = null
        private var today = -1

        init {
            for (head in heads) addView(head)
        }

        fun bind(monday: LocalDate, today: Int, locale: Locale, spoken: DateTimeFormatter) {
            if (monday == this.monday && today == this.today) return
            this.monday = monday
            this.today = today
            for (day in 0..6) {
                val date = monday.plusDays(day.toLong())
                heads[day].bind(
                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), date.dayOfMonth.toString(), date.format(spoken), day == today
                )
            }
        }

        fun repaint(ink: Int) {
            for (head in heads) {
                if (ink != pressColour) head.rippleOver(ink, 8f * density)
                head.invalidate()
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val y = topPx + Math.round(3f * density)
            for (day in 0..6) heads[day].layout(columns.left(day), y, columns.left(day) + columns.column, y + nameLine + dateLine)
        }
    }

    // One day's weekday and date, which open it.
    private inner class Head(context: Context, private val day: Int) : View(context) {
        private var name: TextBlock? = null
        private var date: TextBlock? = null
        private var today = false

        init {
            isClickable = true
            // Its own ripple shows the focus, as the heading's does.
            defaultFocusHighlightEnabled = false
            rippleOver(pressColour, 8f * density)
            setOnClickListener { host.onDayOpen(day) }
        }

        fun bind(name: String, date: String, spoken: String, today: Boolean) {
            this.today = today
            this.name = textBlock(name, namePaint, 0f, Unbounded)
            this.date = textBlock(date, datePaint, 0f, Unbounded)
            contentDescription = if (today) "Open today, $spoken" else "Open $spoken"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val colours = host.palette
            name?.let {
                it.draw(
                    canvas, Math.round((width - it.width) / 2f).toFloat(), Math.round((nameLine - it.height) / 2f).toFloat(),
                    if (today) colours.Delete else colours.MutedText
                )
            }
            date?.let {
                it.draw(
                    canvas, Math.round((width - it.width) / 2f).toFloat(), nameLine + Math.round((dateLine - it.height) / 2f).toFloat(),
                    if (today) colours.Delete else colours.TimeText
                )
            }
        }

        override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    }
}
