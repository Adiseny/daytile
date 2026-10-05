package com.privateplanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.privateplanner.domain.BlockLayout
import com.privateplanner.domain.MovePlacement
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.domain.blockBackgroundArgb
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val ActiveTileAlpha = 0.70f
private const val IdleTileAlpha = 0.86f
private const val BlackWhiteContrastSwitchLuminance = 0.17912878f
private const val DurationVisibleMinWidthDp = 112f
private const val DurationTitleRemainderMinDp = 56f
private const val DurationMaxReserveFraction = 0.34f

internal fun compositedTileBackground(background: Int, paper: Int, active: Boolean): Int =
    compositeOver(withAlpha(background, if (active) ActiveTileAlpha else IdleTileAlpha), paper)

internal fun tileInkFor(background: Int, paper: Int, active: Boolean): Int =
    if (luminance(compositedTileBackground(background, paper, active)) >= BlackWhiteContrastSwitchLuminance) {
        0xFF000000.toInt()
    } else {
        0xFFFFFFFF.toInt()
    }

internal fun durationReserveDp(
    tileWidthDp: Float,
    durationText: String,
    compact: Boolean,
    durationFontSizeSp: Float,
    fontScale: Float
): Float {
    if (tileWidthDp < DurationVisibleMinWidthDp) return 0f
    val estimatedTextWidthDp = durationText.length * durationFontSizeSp * fontScale * 0.58f
    val reserveDp = estimatedTextWidthDp + if (compact) 10f else 12f
    val maxReserveDp = tileWidthDp * DurationMaxReserveFraction
    return if (
        reserveDp <= maxReserveDp &&
        tileWidthDp - reserveDp >= DurationTitleRemainderMinDp
    ) {
        reserveDp
    } else {
        0f
    }
}

// How far a long tile's title slides down to stay just below the heading. Lengths in dp
// except where named px.
internal fun titleFollowOffsetPx(
    density: Float,
    scrollPx: Int,
    blockTop: Float,
    visualHeight: Float,
    headerBottomPx: Int
): Int {
    if (headerBottomPx <= 0) return 0
    val desiredTitleTop = scrollPx + headerBottomPx + 6f * density - TimelineTopClearance * density - blockTop * density
    val maxOffset = max(visualHeight - 56f, 0f) * density
    return Math.round((desiredTitleTop - 8f * density).coerceAtLeast(0f).coerceAtMost(maxOffset))
}

// Tiles draw one at a time on the main thread, each setting what it needs, so they share one paint.
private val TilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val TextPaints = HashMap<Long, TextPaint>()

// Tiles share a text paint per size and weight; each sets its ink as it draws.
private fun Context.tilePaint(size: Float, weight: Int): TextPaint =
    TextPaints.getOrPut((sp(size) * 64f).toLong() * 1000 + weight) { textPaint(size, weight) }

// What a block offers accessibility services beyond a tap. A service names a custom action
// by its id alone, and these lie where an app's own ids do, apart from every platform action.
internal const val RenameAction = 0x7f000001
internal const val DeleteAction = 0x7f000002
internal const val EarlierAction = 0x7f000003
internal const val LaterAction = 0x7f000004
internal const val ShortenAction = 0x7f000005
internal const val LengthenAction = 0x7f000006

private const val Idle = 0
private const val Pending = 1
private const val Moving = 2
private const val Resizing = 3
private const val Done = 4

// One block: the touch target, at least 48dp tall, with the visual tile drawn inside it.
// A tap opens its actions, a hold moves it, and its lower handle or centre lane resizes it.
internal class TimeBlockView(
    context: Context,
    private val host: TimelineHost,
    private val day: DayView,
    block: PlannerBlock,
    columns: BlockLayout
) : View(context) {
    private val density = resources.displayMetrics.density
    private val hourPx = HourHeight * density

    var block = block
        private set
    var columns = columns
        private set

    private var previewStartMinutes = NoPreviewMinutes
    private var previewDurationMinutes = NoPreviewMinutes
    private var moveActive = false
    private var resizeActive = false

    private val displayedStartMinutes: Int
        get() = if (previewStartMinutes != NoPreviewMinutes) previewStartMinutes else block.startMinutes

    val displayedDurationMinutes: Int
        get() = if (previewDurationMinutes != NoPreviewMinutes) previewDurationMinutes else block.durationMinutes

    // The visual tile within the touch target: its width and height in dp, which choose
    // the text, and its place in pixels.
    private var tileWidth = 0f
    private var visualHeight = 0f
    private var visualOffsetPx = 0
    private var visualHeightPx = 0

    private var contentStale = true
    private var title: TextBlock? = null
    private var meta: TextBlock? = null
    private var metaStart = NoPreviewMinutes
    private var duration: TextBlock? = null
    private var titleX = 0
    private var titleY = 0
    private var metaY = 0
    private var durationX = 0
    private var durationY = 0
    private var inkBackground = 0
    private var ink = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentChanged()
    }

    fun bind(block: PlannerBlock, columns: BlockLayout) {
        if (this.block == block && this.columns == columns) return
        val renamed = this.block.title != block.title
        val moved = this.block.startMinutes != block.startMinutes ||
            this.block.durationMinutes != block.durationMinutes || this.columns != columns
        this.block = block
        this.columns = columns
        if (moved) day.place(this)
        contentChanged(layout = renamed)
    }

    fun setVisual(tileWidth: Float, visualHeight: Float, visualOffsetPx: Int, visualHeightPx: Int) {
        if (this.tileWidth == tileWidth && this.visualHeight == visualHeight &&
            this.visualOffsetPx == visualOffsetPx && this.visualHeightPx == visualHeightPx
        ) return
        this.tileWidth = tileWidth
        this.visualHeight = visualHeight
        this.visualOffsetPx = visualOffsetPx
        this.visualHeightPx = visualHeightPx
        contentChanged()
    }

    fun paletteChanged() = invalidate()

    // Only a long tile's pinned title follows the scroll.
    private var followOffset = 0

    fun scrolled() {
        if (visualHeight < LongTitlePinMinHeight) return
        val offset = titleFollowOffsetPx(density, host.scrollPx, heightForMinutes(displayedStartMinutes), visualHeight, host.headerHeightPx)
        if (offset != followOffset) {
            followOffset = offset
            invalidate()
        }
    }

    private fun contentChanged(layout: Boolean = true) {
        contentStale = contentStale || layout
        contentDescription = "${block.title}, " +
            "${TimeFormatter.range(block.startMinutes, displayedDurationMinutes, " to ")}, " +
            "${TimeFormatter.duration(displayedDurationMinutes)}. Actions: Rename, Delete."
        invalidate()
    }

    private fun px(dp: Float): Int = Math.round(dp * density)

    private fun layOutContent() {
        contentStale = false
        val height = visualHeight
        val compact = height < 48f
        val durationSize = when {
            height < 32f -> 11f
            height < 64f -> 12f
            height < 128f -> 13f
            else -> 14f
        }
        val durationText = TimeFormatter.duration(displayedDurationMinutes)
        val reserve = durationReserveDp(tileWidth, durationText, compact, durationSize, resources.configuration.fontScale)
        val endPadding = px(if (reserve > 0f) reserve else 8f)
        if (compact) {
            val start = px(if (height < 32f) 9f else 10f)
            val text = textBlock(
                block.title,
                context.tilePaint(if (height < 32f) 10f else 11f, 500),
                context.sp(if (height < 32f) 11f else 13f),
                width - start - endPadding,
                maxLines = 1,
                ellipsis = true,
                maxHeight = visualHeightPx,
                fill = true
            )
            title = text
            titleX = start
            titleY = Math.round((visualHeightPx - text.height) / 2f)
            meta = null
        } else {
            val small = height < 64f
            val start = px(12f)
            val top = px(if (small) 5f else 8f)
            val columnWidth = width - start - endPadding
            val columnHeight = visualHeightPx - top - px(if (small) 4f else 8f)
            val text = textBlock(
                block.title,
                context.tilePaint(if (small) 12f else 14f, 600),
                context.sp(if (small) 14f else 18f),
                columnWidth,
                maxLines = if (height < 80f) 1 else 2,
                ellipsis = true,
                maxHeight = columnHeight
            )
            title = text
            titleX = start
            titleY = top
            metaY = top + text.height
            meta = timeText(columnWidth, max(columnHeight - text.height, 0))
        }
        duration = if (reserve > 0f) {
            val reservePx = px(reserve)
            textBlock(
                durationText,
                context.tilePaint(durationSize, if (height >= 64f) 700 else 600),
                context.sp(durationSize + 2f),
                reservePx - px(if (compact) 5f else 8f),
                maxLines = 1,
                fill = true
            ).also {
                durationX = width - reservePx
                durationY = Math.round((visualHeightPx - it.height) / 2f)
            }
        } else {
            null
        }
    }

    private fun timeText(width: Int, height: Int): TextBlock {
        metaStart = displayedStartMinutes
        val small = visualHeight < 64f
        return textBlock(
            TimeFormatter.range(metaStart, displayedDurationMinutes),
            context.tilePaint(if (small) 10f else 12f, 400),
            context.sp(if (small) 12f else 16f),
            width,
            maxLines = 1,
            ellipsis = true,
            maxHeight = height,
            fill = true
        )
    }

    // In one pass and in this order: fill, text, resize handle, border. The border is a
    // whole-pixel stroke inset by half its width, with correspondingly smaller corners.
    override fun onDraw(canvas: Canvas) {
        if (contentStale) layOutContent()
        // Moving changes only the time label. Keep the title and duration layouts.
        if (metaStart != displayedStartMinutes) meta = meta?.let { timeText(it.width, it.height) }
        val active = moveActive || resizeActive
        val background = blockBackgroundArgb(block.startMinutes, columns.columnIndex).toInt()
        val composite = compositedTileBackground(background, host.palette.Paper, active)
        if (inkBackground != composite) {
            inkBackground = composite
            ink = tileInkFor(background, host.palette.Paper, active)
        }
        val w = width.toFloat()
        val h = visualHeightPx.toFloat()
        canvas.save()
        canvas.translate(0f, visualOffsetPx.toFloat())
        val radius = min((if (displayedDurationMinutes <= QuickResizeMaxDurationMinutes) 13f else 16f) * density, min(w, h) / 2f)
        TilePaint.style = Paint.Style.FILL
        TilePaint.color = withAlpha(background, if (active) ActiveTileAlpha else IdleTileAlpha)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, TilePaint)

        val follow = if (meta != null && visualHeight >= LongTitlePinMinHeight) {
            titleFollowOffsetPx(density, host.scrollPx, heightForMinutes(displayedStartMinutes), visualHeight, host.headerHeightPx)
        } else {
            0
        }
        followOffset = follow
        title?.draw(canvas, titleX.toFloat(), (titleY + follow).toFloat(), ink)
        meta?.draw(canvas, titleX.toFloat(), (metaY + follow).toFloat(), ink)
        duration?.draw(canvas, durationX.toFloat(), durationY.toFloat(), ink)

        val handleWidth = ResizeHandleWidth * density
        val handleHeight = ResizeHandleHeight * density
        val handleLeft = (w - handleWidth) / 2f
        val handleTop = h - ResizeHandleBottomPadding * density - handleHeight
        TilePaint.color = withAlpha(ink, 0.18f)
        canvas.drawRoundRect(
            handleLeft, handleTop, handleLeft + handleWidth, handleTop + handleHeight, handleHeight / 2f, handleHeight / 2f, TilePaint
        )

        val stroke = min(ceil((if (active) 1.5f else 1f) * density), ceil(min(w, h) / 2))
        val half = stroke / 2
        TilePaint.style = Paint.Style.STROKE
        TilePaint.strokeWidth = stroke
        TilePaint.color = withAlpha(ink, 0.30f)
        canvas.drawRoundRect(half, half, w - half, h - half, max(radius - half, 0f), max(radius - half, 0f), TilePaint)
        canvas.restore()
    }

    // --- Gestures -------------------------------------------------------------------

    private var phase = Idle
    private var initial = block
    private var downYInVisual = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var preHoldX = 0f
    private var preHoldY = 0f
    private var startsInResizeZone = false
    private var startsOnResizeHandle = false
    private var holdStillThresholdSquared = 0f
    private var quickResizeDragThreshold = 0f
    private var touchSlop = 0f
    private var initialScroll = 0
    private var initialPointerViewportY = 0f
    private var totalDy = 0f
    private var lastSnapped = 0
    private var lastSavable = 0
    private var hasDraggedAfterHold = false
    private var autoScroll: EdgeAutoScroll? = null
    private val hold = Runnable { held() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event)
            // A second finger abandons whatever was under way, saving nothing.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> finish(cancelled = true)
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                when (phase) {
                    Pending -> waited(dx, dy)
                    Moving -> {
                        totalDy += dy
                        if (!hasDraggedAfterHold && abs(totalDy) > touchSlop) hasDraggedAfterHold = true
                        moveTo()
                    }
                    Resizing -> {
                        totalDy += dy
                        resizeTo()
                    }
                }
                autoScroll?.update()
            }
            MotionEvent.ACTION_UP -> if (phase == Pending) {
                finish(cancelled = true)
                host.onBlockTap(block.id)
            } else {
                finish(cancelled = false)
            }
        }
        return true
    }

    private fun begin(event: MotionEvent) {
        initial = block
        downYInVisual = event.y - visualOffsetPx
        lastRawX = event.rawX
        lastRawY = event.rawY
        preHoldX = 0f
        preHoldY = 0f
        val heightPx = initial.durationMinutes / 60f * hourPx
        startsInResizeZone = TimelineGeometry.isInResizeZone(
            x = event.x,
            yInVisual = downYInVisual,
            blockWidthPx = width.toFloat(),
            visualHeightPx = heightPx,
            durationMinutes = initial.durationMinutes,
            laneFraction = ResizeLaneFraction,
            minimumTouchTargetPx = MinimumTouchTarget * density,
            quickResizeMaxDurationMinutes = QuickResizeMaxDurationMinutes
        )
        startsOnResizeHandle = TimelineGeometry.isInResizeHandle(
            x = event.x,
            yInVisual = downYInVisual,
            blockWidthPx = width.toFloat(),
            visualHeightPx = heightPx,
            handleHitWidthPx = ResizeHandleLongPressHitWidth * density,
            handleHitHeightPx = ResizeHandleLongPressHitHeight * density,
            handleBottomPaddingPx = ResizeHandleBottomPadding * density
        )
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val holdStill = max(touchSlop * 1.25f, HoldStillTolerance * density)
        holdStillThresholdSquared = holdStill * holdStill
        quickResizeDragThreshold = max(touchSlop, QuickResizeDragThreshold * density)
        phase = Pending
        // A drag down the resize lane is this block's, not the scrolling day's.
        if (startsInResizeZone) parent.requestDisallowInterceptTouchEvent(true)
        postDelayed(hold, BlockMoveHoldMillis)
    }

    // Before the hold: a vertical drag in the resize lane resizes at once; any other
    // movement past the tolerance leaves the gesture to scrolling or the day swipe.
    private fun waited(dx: Float, dy: Float) {
        preHoldX += dx
        preHoldY += dy
        val usefulResizeDrag = preHoldY > 0f || initial.durationMinutes > TimeSnapper.MinimumDurationMinutes
        val potentialQuickResize = startsInResizeZone && abs(preHoldY) > abs(preHoldX) && usefulResizeDrag
        if (potentialQuickResize) {
            if (abs(preHoldY) >= quickResizeDragThreshold || abs(preHoldX) >= quickResizeDragThreshold) startResize()
        } else if (preHoldX * preHoldX + preHoldY * preHoldY > holdStillThresholdSquared) {
            finish(cancelled = true)
        }
    }

    private fun held() {
        if (phase != Pending) return
        if (preHoldX * preHoldX + preHoldY * preHoldY > holdStillThresholdSquared) return finish(cancelled = true)
        parent.requestDisallowInterceptTouchEvent(true)
        host.haptic(HapticFeedbackConstants.LONG_PRESS)
        if (startsOnResizeHandle) startResize() else startMove()
    }

    private fun startGesture() {
        removeCallbacks(hold)
        initialScroll = host.scrollPx
        initialPointerViewportY =
            TimelineTopClearance * density + initial.startMinutes / 60f * hourPx - initialScroll + downYInVisual
        totalDy = preHoldY
        day.activeBlockId = block.id
        translationZ = 2f
    }

    private fun startMove() {
        startGesture()
        phase = Moving
        moveActive = true
        lastSnapped = initial.startMinutes
        lastSavable = initial.startMinutes
        hasDraggedAfterHold = false
        invalidate()
        moveTo()
        autoScroll = EdgeAutoScroll(host, density, this)
    }

    private fun startResize() {
        startGesture()
        phase = Resizing
        resizeActive = true
        lastSnapped = initial.durationMinutes
        lastSavable = initial.durationMinutes
        invalidate()
        resizeTo()
        autoScroll = EdgeAutoScroll(host, density, this)
    }

    // What the edge scrolling asks of the gesture: where the finger is in the viewport,
    // whether it may scroll yet (a move waits for the first drag after the hold), and to
    // follow the day once it has scrolled.
    val pointerViewportY: Float get() = initialPointerViewportY + totalDy

    val scrollsAtEdges: Boolean get() = phase == Resizing || hasDraggedAfterHold

    fun edgeScrolled() = if (phase == Moving) moveTo() else resizeTo()

    // Every snapped step ticks unless the spot is unusable; only a savable one may be dropped on.
    private fun ticks(placement: MovePlacement): Boolean {
        if (placement != MovePlacement.Invalid) host.haptic(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        return placement == MovePlacement.Savable
    }

    private fun moveTo() {
        val effectiveDy = totalDy + (host.scrollPx - initialScroll)
        val snapped = TimeSnapper.clampStart(
            initial.startMinutes + TimeSnapper.deltaMinutesFromY(effectiveDy, hourPx),
            initial.durationMinutes
        )
        translationY = (snapped - initial.startMinutes) / 60f * hourPx
        if (snapped == lastSnapped) return
        lastSnapped = snapped
        previewStartMinutes = snapped
        invalidate()
        if (ticks(host.overlapPolicy(block.id).placement(snapped, initial.durationMinutes))) lastSavable = snapped
    }

    private fun resizeTo() {
        val effectiveDy = totalDy + (host.scrollPx - initialScroll)
        val snapped = TimeSnapper.clampDuration(
            initial.startMinutes,
            initial.durationMinutes + TimeSnapper.deltaMinutesFromY(effectiveDy, hourPx)
        )
        if (snapped == lastSnapped) return
        lastSnapped = snapped
        previewDurationMinutes = snapped
        day.place(this)
        contentChanged()
        if (ticks(host.overlapPolicy(block.id).placement(initial.startMinutes, snapped))) lastSavable = snapped
    }

    private fun finish(cancelled: Boolean) {
        removeCallbacks(hold)
        val was = phase
        phase = if (was == Idle) Idle else Done
        if (was != Idle) parent?.requestDisallowInterceptTouchEvent(false)
        if (was != Moving && was != Resizing) return
        autoScroll?.stop()
        autoScroll = null
        if (!cancelled) {
            if (was == Moving && lastSavable != initial.startMinutes) host.onBlockMove(block.id, lastSavable)
            if (was == Resizing && lastSavable != initial.durationMinutes) host.onBlockResize(block.id, lastSavable)
        }
        moveActive = false
        resizeActive = false
        previewStartMinutes = NoPreviewMinutes
        previewDurationMinutes = NoPreviewMinutes
        translationY = 0f
        translationZ = 0f
        day.activeBlockId = 0
        day.place(this)
        contentChanged(layout = false)
    }

    fun cancelGesture() = finish(cancelled = true)

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    // --- Accessibility --------------------------------------------------------------

    private fun moveBy(deltaMinutes: Int): Boolean {
        val target = TimeSnapper.clampStart(block.startMinutes + deltaMinutes, block.durationMinutes)
        if (target == block.startMinutes) return false
        return host.overlapPolicy(block.id).canPlace(target, block.durationMinutes) && host.onBlockMove(block.id, target)
    }

    private fun resizeBy(deltaMinutes: Int): Boolean {
        val target = TimeSnapper.clampDuration(block.startMinutes, block.durationMinutes + deltaMinutes)
        if (target == block.durationMinutes) return false
        return host.overlapPolicy(block.id).canPlace(block.startMinutes, target) && host.onBlockResize(block.id, target)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isClickable = true
        info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Open actions"))
        info.addAction(AccessibilityAction(RenameAction, "Rename"))
        info.addAction(AccessibilityAction(DeleteAction, "Delete"))
        info.addAction(AccessibilityAction(EarlierAction, "Move earlier 5 minutes"))
        info.addAction(AccessibilityAction(LaterAction, "Move later 5 minutes"))
        info.addAction(AccessibilityAction(ShortenAction, "Shorten 5 minutes"))
        info.addAction(AccessibilityAction(LengthenAction, "Lengthen 5 minutes"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_CLICK -> {
            host.onBlockTap(block.id)
            true
        }
        RenameAction -> {
            host.onBlockRename(block.id)
            true
        }
        DeleteAction -> {
            host.onBlockDelete(block.id)
            true
        }
        EarlierAction -> moveBy(-TimeSnapper.SnapMinutes)
        LaterAction -> moveBy(TimeSnapper.SnapMinutes)
        ShortenAction -> resizeBy(-TimeSnapper.SnapMinutes)
        LengthenAction -> resizeBy(TimeSnapper.SnapMinutes)
        else -> super.performAccessibilityAction(action, arguments)
    }
}
