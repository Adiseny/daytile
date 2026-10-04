package com.privateplanner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ScrollView
import com.privateplanner.domain.OverlapPolicy
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeSnapper
import com.privateplanner.postNotificationsGranted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal const val HeadingDate = 0
internal const val FullDate = 1
internal const val MonthTitle = 2
private val DatePatterns = arrayOf("EEEE, d MMMM", "EEEE, d MMMM yyyy", "MMMM yyyy")
private val DateFormatters = arrayOfNulls<DateTimeFormatter>(3)

// Each pattern is parsed once per locale and shared, including with the launch warm-up: a
// formatter cannot change and carries its locale, so whichever thread stores one, any can use it.
internal fun dateFormatter(kind: Int, locale: Locale): DateTimeFormatter =
    DateFormatters[kind]?.takeIf { it.locale == locale }
        ?: DateTimeFormatter.ofPattern(DatePatterns[kind], locale).also { DateFormatters[kind] = it }

// Launch's main-thread work that can be done ahead, on the warm-up thread: the palettes,
// the grid labels, and the locale's day and month names (the first format loads them).
// Whatever is not ready in time is done where it was.
internal fun Context.warmUpInterface() {
    val now = TimeSnapper.localNowMillis()
    displayedPaletteForMinute(TimeSnapper.minuteOfDay(now))
    prepareGridLabels()
    val locale = Locale.getDefault()
    val today = TimeSnapper.dateOf(now)
    today.format(dateFormatter(HeadingDate, locale))
    prepareDateSheet(locale, today)
}

// A single translucent tint spans the status bar and heading. It never becomes
// opaque, so scrolling tiles remain visible behind the system icons too.
private const val HeaderTintAlpha = 0.68f
private const val HeaderFadeHeight = 56f
private const val ScrimSteps = 32
private const val DaySwipeThreshold = 72f
private const val SnackbarMillis = 4_000

// The planner: the scrolling day under its heading, with at most one snackbar and one
// sheet above. It draws what the view model holds, and nothing beneath it: the window's
// background is the paper.
internal class PlannerScreen(
    context: Context,
    val viewModel: PlannerViewModel,
    // Told when leaving with back should be the planner's rather than the system's.
    private val onBackEnabled: (Boolean) -> Unit = {},
    private val haptics: (Int) -> Unit = {}
) : FrameLayout(context), TimelineHost {
    private val density = resources.displayMetrics.density
    private val hourPx = HourHeight * density
    private val locale: Locale = resources.configuration.locales[0]

    // Before the views below, which read it as they are built.
    override var palette: PlannerPalette = displayedPaletteForMinute(TimeSnapper.minuteOfDay(TimeSnapper.localNowMillis()))
        private set

    private val scroll = TimelineScroll(context)
    private val day = DayView(context, this)
    private val header = Header(context)
    private val snackbars = SnackbarHost(context)
    private var scrim: View? = null
    private var sheetFrame: FrameLayout? = null
    private var sheetContent: View? = null
    private var shownSheet: PlannerSheet? = null

    private var shownDate: LocalDate? = null
    private var today: LocalDate = TimeSnapper.dateOf(TimeSnapper.localNowMillis())

    private var statusTop = 0
    private var navigationBottom = 0
    private var imeBottom = 0
    private var imeAnimating = false
    // Android 15 and later: the height of a navigation bar with buttons, which the screen
    // backs with the bar's colour as earlier versions' own bar did. Zero with gestures.
    private var navigationButtons = 0
    private val navigationPaint = Paint()

    // Where the timeline opens, taken once. Until the first layout applies it, it also
    // stands in for the scroll position, so a dense day builds only the tiles it will show.
    private var scrollTarget: Int? = viewModel.takeScrollTarget()
    private var scrollRemainder = 0f

    init {
        scroll.addView(day)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(snackbars, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) followKeyboard()
    }

    // --- Lifecycle: the view model is followed, and the clock runs, only while visible --

    fun start() {
        tick()
        viewModel.onChange = { render() }
        render()
    }

    fun stop() {
        viewModel.onChange = null
        removeCallbacks(clock)
        day.cancelGesture()
    }

    private val clock = Runnable { tick() }

    private fun tick() {
        removeCallbacks(clock)
        val now = TimeSnapper.localNowMillis()
        val minute = TimeSnapper.minuteOfDay(now)
        val date = TimeSnapper.dateOf(now)
        day.minute = minute
        if (date != today) {
            today = date
            showDate()
        }
        val step = displayedPaletteForMinute(minute)
        if (step !== palette) {
            val flipped = step.LightBackground != palette.LightBackground
            palette = step
            paletteChanged(flipped)
        }
        postDelayed(clock, (TimeSnapper.MillisPerMinute - Math.floorMod(now, TimeSnapper.MillisPerMinute)).coerceAtLeast(250L))
    }

    // Every five-minute step redraws the day in its colours. A step within a ramp moves a
    // colour by less than one part in 255, so an open sheet keeps the colours it opened with
    // rather than being taken down under the keyboard; only the flip between light and dark
    // builds it again, around what it held.
    private fun paletteChanged(flipped: Boolean) {
        day.paletteChanged()
        header.invalidate()
        snackbars.paletteChanged()
        if (flipped && sheetContent != null) {
            val typed = (sheetContent as? InputSheet)?.text
            val month = (sheetContent as? DateSheet)?.visibleMonth
            closeSheet()
            showSheet(typed, month)
        }
        applyBars()
    }

    private fun applyBars() {
        (context as? Activity)?.applyPlannerSystemBars(palette, dimmed = shownSheet != null)
        if (navigationButtons != 0) invalidate()
    }

    // --- State ----------------------------------------------------------------------

    // Nothing here changes the view model, so a change is never met half way through one.
    private fun render() {
        if (shownDate != viewModel.selectedDate) showDate()
        day.setBlocks(viewModel.blocks)
        showSheet(null, null)
        snackbars.show(viewModel.snackbar)
        onBackEnabled(backEnabled)
    }

    private fun showDate() {
        val date = viewModel.selectedDate
        shownDate = date
        val formatter = dateFormatter(HeadingDate, locale)
        // Relative days show their date underneath; other years (rare) add the year.
        val relative = when (date) {
            today.minusDays(1) -> "Yesterday"
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> null
        }
        when {
            relative != null -> header.show(relative, date.format(formatter))
            date.year == today.year -> header.show(date.format(formatter), null)
            else -> header.show(date.format(dateFormatter(FullDate, locale)), null)
        }
        day.showsNow = date == today
        onBackEnabled(backEnabled)
    }

    // Back closes a sheet, then a message, then returns to today; after that it leaves.
    val backEnabled: Boolean
        get() = viewModel.sheet != null || viewModel.snackbar != null || viewModel.selectedDate != today

    fun handleBack() {
        val snackbar = viewModel.snackbar
        when {
            viewModel.sheet != null -> viewModel.dismissSheet()
            snackbar != null -> viewModel.clearSnackbar(snackbar.id)
            else -> viewModel.returnToToday()
        }
    }

    private fun deleteBlock(id: Long) {
        haptic(HapticFeedbackConstants.LONG_PRESS)
        viewModel.deleteBlock(id)
    }

    // --- Sheets ---------------------------------------------------------------------

    private fun block(id: Long): PlannerBlock? = viewModel.blocks.firstOrNull { it.id == id }

    // A rename or actions sheet exists only while its block does.
    private fun showSheet(typed: String?, month: java.time.YearMonth?) {
        val sheet = viewModel.sheet?.takeIf {
            when (it) {
                is PlannerSheet.RenameBlock -> block(it.blockId) != null
                is PlannerSheet.BlockActions -> block(it.blockId) != null
                else -> true
            }
        }
        if (sheet !== shownSheet || (sheet != null && sheetContent == null)) {
            closeSheet()
            if (sheet != null) openSheet(sheet, typed, month)
        }
        when (val content = sheetContent) {
            is InputSheet -> content.setError(viewModel.sheetError)
            is ActionsSheet -> block((sheet as PlannerSheet.BlockActions).blockId)?.let { content.bind(it) }
            is DateSheet -> content.setReminders(viewModel.remindersOn)
        }
    }

    private fun openSheet(sheet: PlannerSheet, typed: String?, month: java.time.YearMonth?) {
        val title: String
        val content: View = when (sheet) {
            is PlannerSheet.CreateBlock -> {
                title = "Add block"
                InputSheet(context, palette, "", "Add") { viewModel.createBlock(it) }
            }
            is PlannerSheet.RenameBlock -> {
                title = "Rename block"
                InputSheet(context, palette, block(sheet.blockId)!!.title, "Rename") { viewModel.renameBlock(it) }
            }
            is PlannerSheet.BlockActions -> {
                title = "Block actions"
                ActionsSheet(context, palette, { viewModel.openRename(sheet.blockId) }, { deleteBlock(sheet.blockId) })
            }
            // PlannerSheet.DateJump, the one that is left: naming it would compile in an
            // exception for a fifth kind that does not exist.
            else -> {
                title = "Choose date"
                DateSheet(context, palette, viewModel.selectedDate, today, month, { toggleReminders(it) }) { viewModel.jumpTo(it) }
            }
        }
        // Scrim and sheet go straight into the screen, above everything added before them.
        scrim = View(context).apply {
            setBackgroundColor(palette.Scrim)
            contentDescription = "Dismiss $title"
            setOnClickListener { viewModel.dismissSheet() }
            this@PlannerScreen.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        sheetFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(palette.Sheet)
                val radius = context.dp(18f)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            // Touches on the sheet never reach the dismissing scrim behind.
            setOnTouchListener { _, _ -> true }
            contentDescription = title
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) accessibilityPaneTitle = title
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            this@PlannerScreen.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        sheetContent = content
        shownSheet = sheet
        placeSheet()
        setBehindSheet(true)
        (content as? InputSheet)?.open(typed)
        applyBars()
    }

    private fun closeSheet() {
        if (sheetContent == null) return
        removeView(sheetFrame)
        removeView(scrim)
        sheetFrame = null
        scrim = null
        sheetContent = null
        shownSheet = null
        setBehindSheet(false)
        applyBars()
    }

    // What lies behind an open sheet is not offered to accessibility services.
    private fun setBehindSheet(behind: Boolean) {
        val importance = if (behind) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        scroll.importantForAccessibility = importance
        header.importantForAccessibility = importance
        snackbars.importantForAccessibility = importance
    }

    // Asked through the platform itself; the activity hears the answer.
    private fun toggleReminders(on: Boolean) {
        val activity = context as? Activity
        if (!on || activity == null || activity.postNotificationsGranted()) {
            viewModel.setRemindersOn(on)
        } else {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    // --- Insets ---------------------------------------------------------------------

    private fun applyInsets(insets: WindowInsets) {
        val keyboard: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top
            navigationBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            @Suppress("DEPRECATION")
            statusTop = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            navigationBottom = insets.stableInsetBottom
            @Suppress("DEPRECATION")
            keyboard = insets.systemWindowInsetBottom.takeIf { it > navigationBottom } ?: 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val buttons = insets.getInsets(WindowInsets.Type.tappableElement()).bottom
            if (buttons != navigationButtons) {
                navigationButtons = buttons
                invalidate()
            }
        }
        header.statusTop = statusTop
        snackbars.setPadding(context.px(16f), context.px(16f), context.px(16f), context.px(16f) + navigationBottom)
        if (!imeAnimating) {
            imeBottom = keyboard
            placeSheet()
        }
    }

    // The sheet rises with the keyboard, frame by frame.
    private fun followKeyboard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) imeAnimating = true
            }

            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                if (imeAnimating) {
                    imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                    placeSheet()
                }
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                imeAnimating = false
                rootWindowInsets?.let { applyInsets(it) }
            }
        })
    }

    // Above the keyboard while there is one, above the navigation bar otherwise.
    private fun placeSheet() {
        val frame = sheetFrame ?: return
        frame.translationY = -imeBottom.toFloat()
        frame.setPadding(0, 0, 0, if (imeBottom > 0) context.px(8f) else navigationBottom)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (navigationButtons == 0) return
        navigationPaint.color = navigationBarColour(palette, dimmed = shownSheet != null)
        canvas.drawRect(0f, (height - navigationButtons).toFloat(), width.toFloat(), height.toFloat(), navigationPaint)
    }

    // --- Day swipe: a horizontal drag that began as one, 72dp or more, changes day ----

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDecided = false
    private var swiping = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (shownSheet != null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = event.x
                swipeDownY = event.y
                swipeDecided = false
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> if (!swipeDecided) {
                val dx = event.x - swipeDownX
                val dy = event.y - swipeDownY
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (dx * dx + dy * dy > slop * slop) {
                    swipeDecided = true
                    swiping = abs(dx) > abs(dy)
                }
            }
        }
        return swiping
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swiping) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - swipeDownX
            if (abs(dx) >= DaySwipeThreshold * density) {
                viewModel.shiftDay(if (dx > 0f) -1 else 1)
                haptic(HapticFeedbackConstants.SEGMENT_TICK)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swiping = false
        return true
    }

    // --- What the day and its blocks ask of the screen ------------------------------

    override val scrollPx: Int
        get() = scrollTarget?.let { initialScroll(it, resources.displayMetrics.heightPixels) } ?: scroll.scrollY

    override val viewportHeightPx: Int get() = scroll.height

    override val headerHeightPx: Int get() = header.contentHeight

    override val visibleBottomPx: Int get() = scroll.height - navigationBottom

    private fun initialScroll(target: Int, height: Int): Int = initialTimelineScroll(
        target, height, hourPx, TimelineTopClearance * density, viewModel.selectedDate == today
    )

    // Whole pixels now, the fraction carried to the next frame.
    override fun scrollTimelineBy(delta: Float): Boolean {
        scrollRemainder += delta
        val whole = scrollRemainder.toInt()
        if (whole == 0) return false
        scrollRemainder -= whole
        val before = scroll.scrollY
        scroll.scrollBy(0, whole)
        return scroll.scrollY != before
    }

    override fun haptic(constant: Int) {
        haptics(constant)
        performHapticFeedback(when {
            constant == HapticFeedbackConstants.SEGMENT_TICK && Build.VERSION.SDK_INT < 34 -> HapticFeedbackConstants.CLOCK_TICK
            constant == HapticFeedbackConstants.TEXT_HANDLE_MOVE && Build.VERSION.SDK_INT < 27 -> HapticFeedbackConstants.CLOCK_TICK
            else -> constant
        })
    }

    // One policy per block and set of blocks: a drag asks many times.
    private var policyBlocks: List<PlannerBlock>? = null
    private var policyBlockId = Long.MIN_VALUE
    private var policy: OverlapPolicy? = null

    override fun overlapPolicy(blockId: Long): OverlapPolicy {
        val current = viewModel.blocks
        if (current !== policyBlocks || blockId != policyBlockId) {
            policyBlocks = current
            policyBlockId = blockId
            policy = OverlapPolicy.from(current, blockId)
        }
        return policy!!
    }

    override fun onEmptyTimeTap(minutes: Int) = viewModel.openCreate(minutes)

    override fun onBlockTap(id: Long) = viewModel.openActions(id)

    override fun onBlockRename(id: Long) = viewModel.openRename(id)

    override fun onBlockDelete(id: Long) = deleteBlock(id)

    override fun onBlockMove(id: Long, startMinutes: Int): Boolean = viewModel.moveBlock(id, startMinutes)

    override fun onBlockResize(id: Long, durationMinutes: Int): Boolean = viewModel.resizeBlock(id, durationMinutes)

    // The day, scrolling. It opens with the current time a third of the way down, placed in
    // its first layout so that no frame shows midnight first.
    private inner class TimelineScroll(context: Context) : ScrollView(context) {
        init {
            isVerticalScrollBarEnabled = false
            // Restored with the activity, as a scroll position should be.
            id = android.R.id.list
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val target = scrollTarget ?: return
            if (height == 0) return
            scrollTarget = null
            scrollTo(0, initialScroll(target, height))
            day.scrolled()
        }

        override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
            super.onScrollChanged(left, top, oldLeft, oldTop)
            day.scrolled()
        }
    }

    // The date heading: the day, with its date beneath when the day is named, over a tint of
    // paper that fades out below. Tapping the text opens the date sheet; the rest lets
    // touches through to the day.
    private inner class Header(context: Context) : View(context) {
        private val titlePaint = context.textPaint(PlannerType.headlineMedium.size, PlannerType.headlineMedium.weight)
        private val subtitlePaint = context.textPaint(PlannerType.bodySmall.size, PlannerType.bodySmall.weight)
        private val glaze = Paint()
        private var glazePaper = 0
        private var title = ""
        private var subtitle: String? = null
        private var titleBlock: TextBlock? = null
        private var subtitleBlock: TextBlock? = null
        private val button = Rect()
        // In its colour from the start: setting it during the first draw would ask for a second.
        private var pressColour = palette.PrimaryText
        private val press = ripple(pressColour, roundedRect(-1, context.dp(10f))).also { it.callback = this }

        // Status bar, text and the padding around it: the heading's solid part.
        var contentHeight = 0
            private set

        var statusTop = 0
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        init {
            isClickable = true
            setOnClickListener { viewModel.openDateJump() }
        }

        fun show(title: String, subtitle: String?) {
            this.title = title
            this.subtitle = subtitle
            contentDescription = "Jump date, $title"
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val textWidth = width - context.px(16f) * 2
            val first = textBlock(title, titlePaint, 0f, textWidth, maxLines = 1, ellipsis = true)
            val second = subtitle?.let { textBlock(it, subtitlePaint, 0f, textWidth, maxLines = 1, ellipsis = true) }
            titleBlock = first
            subtitleBlock = second
            val buttonWidth = max(first.width, second?.width ?: 0) + context.px(16f) * 2
            val buttonHeight = first.height + (second?.height ?: 0) + context.px(4f) * 2
            val left = Math.round((width - buttonWidth) / 2f)
            val top = statusTop + context.px(4f)
            button.set(left, top, left + buttonWidth, top + buttonHeight)
            press.bounds = button
            contentHeight = button.bottom + context.px(14f)
            setMeasuredDimension(width, contentHeight + context.px(HeaderFadeHeight))
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            glazePaper = 0
        }

        override fun onDraw(canvas: Canvas) {
            val colours = palette
            if (glazePaper != colours.Paper) {
                glazePaper = colours.Paper
                // Smootherstep meets the constant tint and the transparent timeline with zero
                // slope.
                glaze.shader = LinearGradient(
                    0f, height - context.dp(HeaderFadeHeight), 0f, height.toFloat(),
                    IntArray(ScrimSteps + 1) { index ->
                        val t = index.toFloat() / ScrimSteps
                        val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
                        withAlpha(colours.Paper, HeaderTintAlpha * (1f - eased))
                    },
                    FloatArray(ScrimSteps + 1) { it.toFloat() / ScrimSteps },
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glaze)
            val first = titleBlock ?: return
            val inner = button.width() - context.px(16f) * 2
            val textTop = button.top + context.px(4f)
            first.draw(
                canvas, (button.left + context.px(16f) + Math.round((inner - first.width) / 2f)).toFloat(), textTop.toFloat(),
                colours.PrimaryText
            )
            subtitleBlock?.let {
                it.draw(
                    canvas, (button.left + context.px(16f) + Math.round((inner - it.width) / 2f)).toFloat(),
                    (textTop + first.height).toFloat(), colours.PrimaryText
                )
            }
            if (pressColour != colours.PrimaryText) {
                pressColour = colours.PrimaryText
                press.setColor(rippleColour(pressColour))
            }
            press.draw(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !button.contains(event.x.toInt(), event.y.toInt())) return false
            return super.onTouchEvent(event)
        }

        override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean = who === press || super.verifyDrawable(who)

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            press.state = drawableState
        }

        override fun drawableHotspotChanged(x: Float, y: Float) {
            super.drawableHotspotChanged(x, y)
            press.setHotspot(x, y)
        }

        override fun jumpDrawablesToCurrentState() {
            super.jumpDrawablesToCurrentState()
            press.jumpToCurrentState()
        }

        // Its place on screen is the text's, not the whole tinted band's.
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            val origin = IntArray(2)
            getLocationOnScreen(origin)
            info.setBoundsInScreen(Rect(button).apply { offset(origin[0], origin[1]) })
        }
    }

    // One message at a time above the navigation bar: 4s on screen (or what accessibility
    // services ask for), sprung in and out, and gone the moment Undo is tapped while the
    // restore runs. One on its way out stays until it has faded.
    private inner class SnackbarHost(context: Context) : FrameLayout(context) {
        private var current: PlannerSnackbar? = null
        private var shown: PlannerSnackbar? = null
        private var shownView: SnackbarView? = null
        private var undoneId = Long.MIN_VALUE
        private val timeout = Runnable { shown?.let { viewModel.clearSnackbar(it.id) } }

        override fun onDetachedFromWindow() {
            removeCallbacks(timeout)
            super.onDetachedFromWindow()
        }

        fun show(snackbar: PlannerSnackbar?) {
            current = snackbar
            val next = snackbar?.takeIf { it.id != undoneId }
            if (next === shown) return
            removeCallbacks(timeout)
            shownView?.leave()
            shown = next
            shownView = next?.let { item ->
                SnackbarView(context, item).also {
                    addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    it.enter()
                }
            }
            if (next == null) return
            val accessibility = context.getSystemService(AccessibilityManager::class.java)
            val millis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                accessibility.getRecommendedTimeoutMillis(
                    SnackbarMillis,
                    AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT or
                        if (next is PlannerSnackbar.Deleted) AccessibilityManager.FLAG_CONTENT_CONTROLS else 0
                )
            } else {
                SnackbarMillis
            }
            postDelayed(timeout, millis.toLong())
        }

        fun paletteChanged() {
            for (index in 0 until childCount) getChildAt(index).invalidate()
        }

        private inner class SnackbarView(context: Context, private val item: PlannerSnackbar) :
            Flow(context, centred = true), Choreographer.FrameCallback {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val message = Label(
                context, TextSpec(14f, 18f, 600), 0, maxLines = 1, ellipsis = true, lineBox = true
            )
            private val undo = if (item is PlannerSnackbar.Deleted) {
                Label(context, TextSpec(14f, 18f, 600), 0, maxLines = 1, gravity = Gravity.CENTER_VERTICAL, lineBox = true)
            } else {
                null
            }
            private var entering = true
            private var startNanos = 0L
            private var fromAlpha = 0f
            private var fromScale = 0.8f

            init {
                setWillNotDraw(false)
                setPadding(context.px(16f), context.px(12f), context.px(14f), context.px(12f))
                message.text = if (item is PlannerSnackbar.Message) item.message else "Deleted"
                addView(message, Cell(0, LayoutParams.WRAP_CONTENT, 1f))
                if (undo != null) {
                    undo.text = "Undo"
                    undo.setPadding(context.px(12f), 0, context.px(12f), 0)
                    undo.setOnClickListener {
                        undoneId = item.id
                        viewModel.undoDelete(item.id)
                        show(current)
                    }
                    addView(undo, Cell(LayoutParams.WRAP_CONTENT, context.px(48f)).apply { leftMargin = context.px(12f) })
                }
                alpha = 0f
                scaleX = 0.8f
                scaleY = 0.8f
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) accessibilityPaneTitle = "Alert"
            }

            override fun onDraw(canvas: Canvas) {
                val colours = palette
                message.colour = colours.PrimaryText
                val radius = context.dp(18f)
                paint.style = Paint.Style.FILL
                paint.color = colours.Sheet
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
                val stroke = min(ceil(density), ceil(min(width, height) / 2f))
                val half = stroke / 2
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.color = withAlpha(colours.HourLine, 0.72f)
                canvas.drawRoundRect(half, half, width - half, height - half, radius - half, radius - half, paint)
                if (undo != null && undo.background == null) {
                    undo.colour = colours.Delete
                    undo.background = roundedRect(withAlpha(colours.Delete, 0.10f), context.dp(11f))
                    undo.rippleOver(colours.PrimaryText, context.dp(11f))
                }
            }

            override fun invalidate() {
                undo?.background = null
                super.invalidate()
            }

            fun enter() = spring(true)

            override fun onDetachedFromWindow() {
                Choreographer.getInstance().removeFrameCallback(this)
                super.onDetachedFromWindow()
            }

            fun leave() {
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
                spring(false)
            }

            private fun spring(entering: Boolean) {
                this.entering = entering
                fromAlpha = alpha
                fromScale = scaleX
                startNanos = 0L
                Choreographer.getInstance().removeFrameCallback(this)
                Choreographer.getInstance().postFrameCallback(this)
            }

            // Material's snackbar springs: a critically damped fade and a barely
            // underdamped scale between 0.8 and 1.
            override fun doFrame(frameNanos: Long) {
                if (startNanos == 0L) startNanos = frameNanos
                val seconds = (frameNanos - startNanos) / 1e9f
                val fade = sprung(seconds, 3800f, 1f)
                val grow = sprung(seconds, 1400f, 0.9f)
                val targetAlpha = if (entering) 1f else 0f
                val targetScale = if (entering) 1f else 0.8f
                alpha = targetAlpha + (fromAlpha - targetAlpha) * fade
                val scale = targetScale + (fromScale - targetScale) * grow
                scaleX = scale
                scaleY = scale
                if (abs(fade) > 0.01f || abs(grow) > 0.01f) {
                    Choreographer.getInstance().postFrameCallback(this)
                    return
                }
                alpha = targetAlpha
                scaleX = targetScale
                scaleY = targetScale
                if (!entering) (parent as? FrameLayout)?.removeView(this)
            }

            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.isDismissable = true
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
            }

            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                if (action != AccessibilityNodeInfo.ACTION_DISMISS) return super.performAccessibilityAction(action, arguments)
                viewModel.clearSnackbar(item.id)
                return true
            }
        }
    }
}

// How far a spring released at rest one unit from its target still is from it.
private fun sprung(seconds: Float, stiffness: Float, dampingRatio: Float): Float {
    val natural = sqrt(stiffness)
    if (dampingRatio >= 1f) return (1f + natural * seconds) * exp(-natural * seconds)
    val damped = natural * sqrt(1f - dampingRatio * dampingRatio)
    return exp(-dampingRatio * natural * seconds) *
        (cos(damped * seconds) + dampingRatio * natural / damped * sin(damped * seconds))
}
