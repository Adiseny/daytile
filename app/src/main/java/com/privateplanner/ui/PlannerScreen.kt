package com.privateplanner.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import com.privateplanner.domain.canShift
import com.privateplanner.domain.isBlankTitle
import com.privateplanner.postNotificationsGranted
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Collections
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
// The week's heading: the month by itself, as a name standing alone, and with its year.
private const val MonthName = 3
private const val MonthAndYear = 4
private val DatePatterns = arrayOf("EEEE, d MMMM", "EEEE, d MMMM yyyy", "MMMM yyyy", "LLLL", "LLLL yyyy")
private val DateFormatters = arrayOfNulls<DateTimeFormatter>(5)

// Each pattern is parsed once per locale and shared, including with the launch warm-up: a
// formatter cannot change and carries its locale, so whichever thread stores one, any can use it.
internal fun dateFormatter(kind: Int, locale: Locale): DateTimeFormatter =
    DateFormatters[kind]?.takeIf { it.locale == locale }
        ?: DateTimeFormatter.ofPattern(DatePatterns[kind], locale).also { DateFormatters[kind] = it }

// Launch's main-thread work that can be done ahead, on the warm-up thread, in the order
// the main thread comes to it: the palette as the window is styled, the locale's day and
// month names (the first format loads them) as the heading is set, the grid labels at the
// first draw, and the date sheet's names when it is first opened. Whatever is not ready in
// time is done where it was.
internal fun Context.warmUpInterface() {
    val now = TimeSnapper.localNowMillis()
    displayedPaletteForMinute(TimeSnapper.minuteOfDay(now))
    val locale = Locale.getDefault()
    val today = TimeSnapper.dateOf(now)
    today.format(dateFormatter(HeadingDate, locale))
    prepareGridLabels()
    prepareDateSheet(locale, today)
}

// A single translucent tint spans the status bar and heading. It never becomes
// opaque, so scrolling tiles remain visible behind the system icons too.
private const val HeaderTintAlpha = 0.68f
private const val HeaderFadeHeight = 56f
private const val ScrimSteps = 32
private const val DaySwipeThreshold = 72f

// A change of day. Lengths in dp, times in milliseconds: the landing once the finger has
// lifted, shorter for what little may be left of it; what counts as a flick; and how far
// the heading leans after the finger before it changes.
private const val LandingMillis = 240f
private const val ShortestLandingMillis = 90f
private const val FlickSpeed = 0.54f
private const val FlickTravel = 19f
private const val HeadingLean = 10f
private const val SnackbarMillis = 4_000

// Between the day and the week. Fingers that close by this share of their first distance
// bring the week all the way in, and ones that open by this share take it all the way
// out; let go half way or further and it carries on, short of that it returns. The day
// falls back as the week comes forward, each by this share of its size.
private const val PinchInSpan = 0.42f
private const val PinchOutSpan = 0.6f
private const val ZoomDayShrink = 0.26f
private const val ZoomWeekGrow = 0.2f

// The planner: the scrolling day under its heading, with at most one snackbar and one
// sheet above. It draws what the view model holds, and nothing beneath it: the window's
// background is the paper. Open so that a test can hear its haptics.
internal open class PlannerScreen(
    context: Context,
    val viewModel: PlannerViewModel,
    // Run when `backEnabled` may have changed.
    private val onBackChanged: Runnable = Runnable {}
) : FrameLayout(context), TimelineHost, WeekHost, Choreographer.FrameCallback {
    private val density = resources.displayMetrics.density
    private val hourPx = HourHeight * density
    private val locale: Locale = resources.configuration.locales[0]

    // Before the views below, which read it as they are built.
    final override var palette: PlannerPalette = displayedPaletteForMinute(TimeSnapper.minuteOfDay(TimeSnapper.localNowMillis()))
        private set

    private val scroll = TimelineScroll(context)
    private val day = DayView(context, this)
    private val weekScroll = WeekScroll(context)
    private val week = WeekView(context, this)
    private val weekDays = WeekDays(
        if (resources.configuration.fontScale <= WeekLargestText) context else
            context.createConfigurationContext(Configuration(resources.configuration).apply { fontScale = WeekLargestText }),
        this, week.columns
    )
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
        weekScroll.addView(week)
        weekScroll.visibility = INVISIBLE
        weekDays.visibility = INVISIBLE
        addView(weekScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(weekDays, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(snackbars, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        followKeyboard()
        // Nothing is acted on while another app draws over the planner.
        filterTouchesWhenObscured = true
        applyBars()
    }

    // --- Lifecycle: the view model is followed, and the clock runs, only while visible --

    fun start() {
        tick()
        viewModel.onChange = Runnable { render() }
        render()
    }

    fun stop() {
        viewModel.onChange = null
        removeCallbacks(clock)
        day.cancelGesture()
        week.cancelGesture()
        swiping = false
        closeSlide()
        // A zoom on its way ends where it was heading.
        pinching = false
        pinchSpan = 0f
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        zoom = if (viewModel.week) 1f else 0f
        placeZoom()
    }

    private val clock = Runnable { tick() }

    private fun tick() {
        removeCallbacks(clock)
        val now = TimeSnapper.localNowMillis()
        val minute = TimeSnapper.minuteOfDay(now)
        val date = TimeSnapper.dateOf(now)
        day.minute = minute
        week.minute = minute
        if (date != today) {
            closeSlide()
            today = date
            showDate()
            week.today = todayIn(viewModel.selectedDate)
            showWeekDays()
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
        week.paletteChanged()
        weekDays.paletteChanged()
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
        val date = viewModel.selectedDate
        val from = shownDate
        if (viewModel.week != modeWeek) {
            // Between the day and the week nothing slides: the one gives way to the other.
            swiping = false
            closeSlide()
            day.cancelGesture()
            day.putDownAll()
            week.cancelGesture()
            modeWeek = viewModel.week
            shownDate = date
            if (modeWeek) {
                weekAtFirstHour = true
                weekScroll.requestLayout()
            }
            if (!pinching) {
                zoomAroundDay(viewModel.weekHome)
                settleZoom(if (modeWeek) 1f else 0f)
            }
            onBackChanged.run()
        } else if (from != date) {
            if (modeWeek && from != null && from.weekStart() == date.weekStart()) {
                // Another day of the week on show: nothing moves.
                shownDate = date
                onBackChanged.run()
            } else if (slideDirection != 0 && !slideWon && date == slideDate) {
                // The swipe under way was heading here, and its blocks are in place already.
                winSlide(true)
            } else {
                // A day or week chosen any other way slides in by itself, where there is motion at all.
                swiping = false
                closeSlide()
                if (from != null && width > 0 && ValueAnimator.areAnimatorsEnabled()) {
                    openSlide(date, if (date > from) 1 else -1)
                    winSlide(false)
                    land(1f)
                } else {
                    showDate()
                }
            }
        }
        if (modeWeek) {
            val monday = date.weekStart()
            for (index in 0..6) week.setDay(index, viewModel.cachedBlocks(monday.plusDays(index.toLong())) ?: Collections.emptyList())
            week.today = todayIn(date)
            showWeekDays()
        }
        // Behind the week the day is left as it is until it is next on its way in: a change
        // of week builds no tiles nobody sees.
        if (!modeWeek) day.setBlocks(viewModel.blocks)
        showSheet(null, null)
        snackbars.show(viewModel.snackbar)
        onBackChanged.run()
    }

    private fun showDate() {
        val date = viewModel.selectedDate
        shownDate = date
        showHeading(date)
        onBackChanged.run()
    }

    // Today's place in a day's week, Monday 0, or -1 where today is in another week.
    private fun todayIn(date: LocalDate): Int {
        val days = today.toEpochDay() - date.weekStart().toEpochDay()
        return if (days in 0..6) days.toInt() else -1
    }

    private fun weekLists(date: LocalDate): Array<List<PlannerBlock>> {
        val monday = date.weekStart()
        return Array(7) { viewModel.cachedBlocks(monday.plusDays(it.toLong())) ?: Collections.emptyList() }
    }

    // The week's heading is its month alone: its dates stand over its columns, and travel
    // with them. A week that runs from one month into the next names both, and one outside
    // this year gives the year.
    private fun showWeekHeading(date: LocalDate) {
        val monday = date.weekStart()
        val sunday = monday.plusDays(6)
        val name = dateFormatter(MonthName, locale)
        val dated = dateFormatter(MonthAndYear, locale)
        val last = sunday.format(if (sunday.year == today.year) name else dated)
        header.show(
            when {
                monday.month == sunday.month -> last
                monday.year == sunday.year -> monday.format(name) + " \u2013 " + last
                else -> monday.format(dated) + " \u2013 " + sunday.format(dated)
            },
            null
        )
    }

    // The dates of the selected day's week, over its columns.
    private fun showWeekDays() {
        val date = viewModel.selectedDate
        weekDays.show(date.weekStart(), todayIn(date), locale, dateFormatter(FullDate, locale))
    }

    // The day's name and, on today, the current time: what stays put while blocks change places.
    private fun showHeading(date: LocalDate) {
        if (weekHeading) return showWeekHeading(date)
        day.showsNow = date == today
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
    }

    // Back closes a sheet, then a message, then returns to today; after that it leaves.
    val backEnabled: Boolean
        get() = viewModel.sheet != null || viewModel.snackbar != null || day.hasLifted || viewModel.week ||
            viewModel.selectedDate != today

    fun handleBack() {
        val snackbar = viewModel.snackbar
        when {
            viewModel.sheet != null -> viewModel.dismissSheet()
            snackbar != null -> viewModel.clearSnackbar(snackbar.id)
            day.hasLifted -> day.putDownAll()
            viewModel.week -> viewModel.showWeek(false)
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
            it.kind == PlannerSheet.Create || it.kind == PlannerSheet.Date || block(it.value) != null
        }
        if (sheet !== shownSheet || (sheet != null && sheetContent == null)) {
            val row = sheetContent as? InputSheet
            if (row != null && sheet != null && sheet.kind == PlannerSheet.Create && shownSheet?.kind == PlannerSheet.Create) {
                // The next block of a row: the sheet and its keyboard stay, emptied, and the
                // day comes up so that the block just added, and where the next will start,
                // show above them.
                shownSheet = sheet
                row.clear()
                val over = TimelineTopClearance * density + sheet.value / 60f * hourPx - scroll.scrollY -
                    (sheetFrame!!.top + sheetFrame!!.translationY - context.px(RowClearance))
                if (!modeWeek && over > 0f) scroll.smoothScrollBy(0, Math.round(over))
            } else {
                closeSheet()
                if (sheet != null) openSheet(sheet, typed, month)
            }
        }
        when (val content = sheetContent) {
            is InputSheet -> content.setError(viewModel.sheetError)
            is ActionsSheet -> block(sheet!!.value)?.let { content.bind(it) }
            is DateSheet -> content.setReminders(viewModel.remindersOn)
        }
    }

    private fun openSheet(sheet: PlannerSheet, typed: String?, month: java.time.YearMonth?) {
        val title: String
        val content: View = when (sheet.kind) {
            PlannerSheet.Create -> {
                title = "Add block"
                InputSheet(context, palette, "", "Add", { viewModel.createBlock(it) }) {
                    // The keyboard's key adds and stays for the next; on nothing, it is done.
                    if (it.isBlankTitle()) viewModel.dismissSheet() else viewModel.createBlock(it, again = true)
                }
            }
            PlannerSheet.Rename -> {
                title = "Rename block"
                InputSheet(context, palette, block(sheet.value)!!.title, "Rename", { viewModel.renameBlock(it) })
            }
            PlannerSheet.Actions -> {
                title = "Block actions"
                ActionsSheet(context, palette, { viewModel.openRename(sheet.value) }, { deleteBlock(sheet.value) })
            }
            else -> {
                title = "Choose date"
                DateSheet(context, palette, viewModel.selectedDate, today, month, { toggleReminders(!viewModel.remindersOn) }) {
                    viewModel.jumpTo(it)
                }
            }
        }
        // Scrim and sheet go straight into the screen, above everything added before them.
        scrim = View(context).apply {
            setBackgroundColor(palette.Scrim)
            contentDescription = "Dismiss $title"
            setOnClickListener { viewModel.dismissSheet() }
            // For touch and for accessibility services; from a keyboard, back dismisses.
            isFocusable = false
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
        weekScroll.importantForAccessibility = importance
        weekDays.importantForAccessibility = importance
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

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        applyInsets(insets)
        return insets
    }

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
        placeWeek()
        val edge = context.px(16f)
        snackbars.setPadding(edge, edge, edge, edge + navigationBottom)
        if (!imeAnimating) {
            imeBottom = keyboard
            placeSheet()
        }
    }

    // The sheet rises with the keyboard, frame by frame, where the platform reports its frames.
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

    // --- Day swipe: a horizontal drag that began as one carries the day's blocks with it
    // and the next day's in beside them. Let go 72dp or more along, or flick, and the day
    // changes; short of that, or flicking back, it returns.

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDecided = false
    private var swiping = false
    private var swipeX = 0f
    private var swipeTime = 0L
    private var swipeSpeed = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (shownSheet != null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A touch finds the day at rest: a landing still on its way ends here.
                closeSlide()
                swipeDownX = event.x
                swipeDownY = event.y
                swipeDecided = false
                swiping = false
            }
            // Two fingers are never a change of day, and when the first lifts the one left
            // is not to be measured from where the first came down.
            MotionEvent.ACTION_POINTER_DOWN -> swipeDecided = true
            MotionEvent.ACTION_MOVE -> if (!swipeDecided) {
                val dx = event.x - swipeDownX
                val dy = event.y - swipeDownY
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (dx * dx + dy * dy > slop * slop) {
                    swipeDecided = true
                    swiping = abs(dx) > abs(dy)
                    if (swiping) {
                        swipeX = event.x
                        swipeTime = event.eventTime
                        swipeSpeed = 0f
                        dragTo(dx)
                    }
                }
            }
        }
        return swiping
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!swiping) return false
        val dx = event.x - swipeDownX
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_MOVE) {
            val millis = event.eventTime - swipeTime
            if (millis > 0) {
                swipeSpeed = 0.6f * swipeSpeed + 0.4f * (event.x - swipeX) / millis
                swipeX = event.x
                swipeTime = event.eventTime
            }
            dragTo(dx)
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swiping = false
            // A finger that stopped before it lifted has no speed left.
            release(dx, if (event.eventTime - swipeTime > 90) 0f else swipeSpeed, action == MotionEvent.ACTION_CANCEL)
        }
        return true
    }

    // --- A change of day under way: two days side by side, the one arriving `slideDirection`
    // (+1 later, -1 earlier) of the one leaving, `slideAt` of the way there, 0 to 1.

    private var slideDirection = 0
    private var slideDate: LocalDate? = null
    private var slideAt = 0f
    // The day has changed: the arriving blocks are the selected day's and the rest is landing.
    private var slideWon = false
    private var headingNext = false
    private var landFrom = 0f
    private var landTo = 0f
    private var landMillis = 0f
    private var landNanos = 0L

    // What blocks cross between one day and the next, or one week and the next: the screen
    // less the hours' margin.
    private val travel: Float get() = width - if (modeWeek) week.columns.gutter.toFloat() else TimelineGutter * density

    private fun openSlide(date: LocalDate, direction: Int) {
        slideDirection = direction
        slideDate = date
        slideAt = 0f
        slideWon = false
        headingNext = false
        if (modeWeek) {
            week.openOther(weekLists(date), todayIn(date))
            weekDays.openOther(date.weekStart(), todayIn(date), locale, dateFormatter(FullDate, locale))
        } else {
            day.openOther(viewModel.cachedBlocks(date) ?: Collections.emptyList())
        }
        placeSlide()
    }

    private fun winSlide(finger: Boolean) {
        slideWon = true
        shownDate = viewModel.selectedDate
        if (modeWeek) {
            week.swapPages()
            weekDays.swap()
        } else {
            day.swapDays()
        }
        aimHeading(finger)
    }

    // Ends a change of day where it stands: the selected day is at rest and alone.
    private fun closeSlide() {
        if (slideDirection == 0) return
        slideDirection = 0
        Choreographer.getInstance().removeFrameCallback(this)
        header.lean = 0f
        showHeading(viewModel.selectedDate)
        if (modeWeek) {
            week.closeOther()
            weekDays.closeOther()
        } else {
            day.closeOther()
        }
    }

    private fun placeSlide() {
        val across = slideDirection * travel
        val leaving = -across * slideAt
        val arriving = across * (1f - slideAt)
        val selected = if (slideWon) arriving else leaving
        val other = if (slideWon) leaving else arriving
        if (modeWeek) {
            week.slide(selected, other)
            weekDays.slide(selected, other)
        } else {
            day.slide(selected, other)
        }
        header.lean = if (headingNext) 0f else {
            -slideDirection * HeadingLean * density * min(slideAt * travel / (DaySwipeThreshold * density), 1f)
        }
    }

    // The heading names the next day once the day has changed, or from the point where
    // letting go would change it; short of that, this day, leaning after the finger. The
    // current time's line goes with the name. A finger that brings the change about, by
    // crossing that point or by a flick short of it, feels the tick a change of day has
    // always had.
    private fun aimHeading(finger: Boolean) {
        val next = slideWon || slideAt * travel >= DaySwipeThreshold * density
        if (next == headingNext) return
        headingNext = next
        showHeading(if (next) slideDate!! else viewModel.selectedDate)
        if (next && finger) haptic(HapticFeedbackConstants.SEGMENT_TICK)
    }

    private fun dragTo(dx: Float) {
        val direction = if (dx < 0f) 1 else if (dx > 0f) -1 else slideDirection
        if (direction == 0) return
        if (slideDirection != direction) {
            closeSlide()
            openSlide(viewModel.selectedDate.plusDays(direction * if (modeWeek) 7L else 1L), direction)
        }
        slideAt = min(abs(dx) / travel, 1f)
        aimHeading(true)
        placeSlide()
    }

    private fun release(dx: Float, speed: Float, cancelled: Boolean) {
        val direction = slideDirection
        if (direction == 0) return
        val fast = abs(speed) > FlickSpeed * density
        val onward = fast && (speed < 0f) == (direction > 0)
        val stick = !cancelled && (!fast || onward) &&
            (abs(dx) >= DaySwipeThreshold * density || (onward && abs(dx) > FlickTravel * density))
        // The screen finds the swipe heading to the new day and keeps its blocks. Back, pressed
        // meanwhile, may have changed the day to it already.
        if (stick && !slideWon) {
            if (modeWeek) viewModel.shiftWeek(direction.toLong()) else viewModel.shiftDay(direction.toLong())
        }
        if (slideDirection != 0) land(if (slideWon) 1f else 0f)
    }

    // The rest of the way, or back; at once where motion is switched off.
    private fun land(to: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) return closeSlide()
        landFrom = slideAt
        landTo = to
        landMillis = max(ShortestLandingMillis, LandingMillis * abs(to - slideAt))
        landNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // Quick away and slowing into place.
    override fun doFrame(frameTimeNanos: Long) {
        val left = 1f - min(max(frameTimeNanos - landNanos, 0L) / 1e6f / landMillis, 1f)
        slideAt = landTo + (landFrom - landTo) * left * left * left
        aimHeading(false)
        placeSlide()
        if (left == 0f) closeSlide() else Choreographer.getInstance().postFrameCallback(this)
    }

    // --- Between the day and the week: two fingers closing on the day bring in the week
    // that holds it, and two opening on the week bring back the day beneath them. The one
    // fades and falls back as the other comes forward, as far as the fingers have gone.

    // The mode the screen has taken up, and whether the heading is the week's yet.
    private var modeWeek = false
    private var weekHeading = false
    // 0 the day, 1 the week, and between them while a pinch or its landing is under way.
    private var zoom = 0f
    private var zoomFrom = 0f
    private var zoomTo = 0f
    private var zoomMillis = 0f
    private var zoomNanos = 0L
    // The fingers' distance as the second one touched, or 0 with fewer than two down.
    private var pinchSpan = 0f
    private var pinchFromWeek = false
    private var pinching = false
    // The week opens at its first hour, placed in the layout after it is next shown.
    private var weekAtFirstHour = false

    private fun span(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    // Every touch passes here first, whoever has claimed the gesture: a pinch is told by
    // the distance between two fingers, which no child is placed to see.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (pinching) {
            if (action == MotionEvent.ACTION_MOVE && pinchSpan > 0f && event.pointerCount >= 2) {
                val ratio = span(event) / pinchSpan
                zoom = if (pinchFromWeek) {
                    1f - ((ratio - 1f) / PinchOutSpan).coerceAtLeast(0f).coerceAtMost(1f)
                } else {
                    ((1f - ratio) / PinchInSpan).coerceAtLeast(0f).coerceAtMost(1f)
                }
                placeZoom()
            } else if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_POINTER_DOWN && pinchSpan > 0f) {
                // A finger has lifted: the rest of the way, or back.
                pinchSpan = 0f
                settleZoom(if (action == MotionEvent.ACTION_CANCEL) (if (pinchFromWeek) 1f else 0f) else if (zoom >= 0.5f) 1f else 0f)
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pinching = false
            return true
        }
        if (action == MotionEvent.ACTION_DOWN) day.manyFingers = false
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            pinchSpan = 0f
            // Two fingers are a pinch or, held still on two of the day's blocks, the lifting of
            // both: no part of it is a tap, and in the week a hold in waiting ends.
            day.manyFingers = true
            if (event.pointerCount == 2 && shownSheet == null && (zoom == 0f || zoom == 1f) && day.activeBlockId == 0L && week.active == null) {
                week.cancelGesture()
                pinchSpan = span(event)
            }
        } else if (action == MotionEvent.ACTION_MOVE && pinchSpan > 0f && event.pointerCount == 2) {
            val change = span(event) - pinchSpan
            // Closing on the day, or opening on the week.
            if (abs(change) > 2 * ViewConfiguration.get(context).scaledTouchSlop && (change < 0f) != modeWeek &&
                day.activeBlockId == 0L && week.active == null
            ) {
                startPinch(event)
                return true
            }
        } else if (action != MotionEvent.ACTION_MOVE) {
            pinchSpan = 0f
        }
        return super.dispatchTouchEvent(event)
    }

    private fun startPinch(event: MotionEvent) {
        // Whoever had the gesture is told it is over; from here it is the screen's.
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        swiping = false
        closeSlide()
        pinching = true
        pinchFromWeek = modeWeek
        if (modeWeek) {
            // Opening out, back into the day the week was come into from, as it was left.
            viewModel.selectDay(viewModel.weekHome)
            day.setBlocks(viewModel.blocks)
            day.showsNow = viewModel.selectedDate == today
        } else {
            viewModel.showWeek(true)
        }
        zoomAroundDay(viewModel.weekHome)
    }

    // What the two fall back from and come forward to. The day goes into its own column of
    // the week and comes back out of it, about its middle; the week, with its dates, about
    // where the dates begin, so that they stay beneath the heading.
    private fun zoomAroundDay(date: LocalDate) {
        val x = week.columns.left(date.dayOfWeek.value - 1) + week.columns.column / 2f
        scroll.pivotX = x
        scroll.pivotY = height * 0.42f
        weekScroll.pivotX = x
        weekScroll.pivotY = weekDays.topPx.toFloat()
        weekDays.pivotX = x
        weekDays.pivotY = weekScroll.pivotY
    }

    // The day and the week as far between them as `zoom` says: the day falls back and
    // fades out over the first half, and the week comes forward out of the paper over the
    // second, its dates with it. The two are never on show together, so neither is read
    // through the other, and whichever is on show is where a pinch let go ends. Only moves
    // and fades what is drawn already; the heading changes between them.
    private fun placeZoom() {
        val z = zoom
        val away = (z * 2f).coerceAtMost(1f)
        val near = (z * 2f - 1f).coerceAtLeast(0f)
        scroll.visibility = if (away < 1f) VISIBLE else INVISIBLE
        weekScroll.visibility = if (near > 0f) VISIBLE else INVISIBLE
        weekDays.visibility = weekScroll.visibility
        scroll.alpha = 1f - away
        scroll.scaleX = 1f - ZoomDayShrink * away
        scroll.scaleY = scroll.scaleX
        val grown = 1f + ZoomWeekGrow * (1f - near)
        weekScroll.alpha = near
        weekScroll.scaleX = grown
        weekScroll.scaleY = grown
        weekDays.alpha = near
        weekDays.scaleX = grown
        weekDays.scaleY = grown
        val heading = z >= 0.5f
        if (heading != weekHeading) {
            weekHeading = heading
            header.week = heading
            showHeading(viewModel.selectedDate)
        }
    }

    // The rest of the way to the day or the week; at once where motion is switched off.
    private fun settleZoom(to: Float) {
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        if (zoom == to || width == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            zoom = to
            placeZoom()
            zoomSettled()
            return
        }
        zoomFrom = zoom
        zoomTo = to
        zoomMillis = max(ShortestLandingMillis, LandingMillis * abs(to - zoom))
        zoomNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(zoomFrame)
    }

    // Quick away and slowing into place, as a change of day lands.
    private val zoomFrame: Choreographer.FrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val left = 1f - min(max(frameTimeNanos - zoomNanos, 0L) / 1e6f / zoomMillis, 1f)
        zoom = zoomTo + (zoomFrom - zoomTo) * left * left * left
        placeZoom()
        if (left == 0f) zoomSettled() else again()
    }

    private fun again() {
        Choreographer.getInstance().postFrameCallback(zoomFrame)
    }

    // A pinch let go short of the week, or one that opened a day out of it, ends on the day.
    private fun zoomSettled() {
        if (zoom == 0f && viewModel.week) viewModel.showWeek(false)
    }

    // The week stands beneath the heading as the heading is in its week form, whichever
    // form it has at the moment.
    private fun placeWeek() {
        weekDays.topPx = header.weekContentHeight
        week.setFrame(weekDays.solidBottom, navigationBottom, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        placeWeek()
    }

    // --- What the day, the week and their blocks ask of the screen. To the week a day is
    // its place in the week on show, Monday 0.

    override val scrollPx: Int
        get() = scrollTarget?.let { initialScroll(it, resources.displayMetrics.heightPixels) } ?: scroll.scrollY

    override val weekScrollPx: Int get() = weekScroll.scrollY

    override val viewportHeightPx: Int get() = scroll.height

    // A block is carried in whichever of the two is on show.
    override val headerHeightPx: Int get() = if (modeWeek) weekDays.solidBottom else header.contentHeight

    override val headerFadeBottomPx: Int get() = header.measuredHeight

    final override var lastScrollMillis = 0L
        private set

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
        val scrolled = if (modeWeek) weekScroll else scroll
        val before = scrolled.scrollY
        scrolled.scrollBy(0, whole)
        return scrolled.scrollY != before
    }

    override fun haptic(constant: Int) {
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

    private fun policy(blocks: List<PlannerBlock>, blockId: Long): OverlapPolicy {
        if (blocks !== policyBlocks || blockId != policyBlockId) {
            policyBlocks = blocks
            policyBlockId = blockId
            policy = OverlapPolicy.from(blocks, blockId)
        }
        return policy!!
    }

    override fun overlapPolicy(blockId: Long): OverlapPolicy = policy(viewModel.blocks, blockId)

    override fun overlapPolicy(day: Int, blockId: Long): OverlapPolicy =
        policy(viewModel.cachedBlocks(weekDate(day)) ?: Collections.emptyList(), blockId)

    override fun onEmptyTimeTap(minutes: Int) = viewModel.openCreate(minutes)

    // Only for a block of the selected day: one arriving or leaving is not to be acted on.
    override fun onBlockTap(id: Long) {
        if (block(id) != null) viewModel.openActions(id)
    }

    override fun onBlockRename(id: Long) {
        if (block(id) != null) viewModel.openRename(id)
    }

    override fun onBlockDelete(id: Long) = deleteBlock(id)

    override fun onBlockMove(id: Long, startMinutes: Int): Boolean = viewModel.moveBlock(id, startMinutes)

    override fun onBlockResize(id: Long, durationMinutes: Int): Boolean = viewModel.resizeBlock(id, durationMinutes)

    override fun canShift(ids: LongArray, deltaMinutes: Int): Boolean = canShift(viewModel.blocks, ids, deltaMinutes)

    override fun onBlocksShift(ids: LongArray, deltaMinutes: Int) = viewModel.shiftBlocks(ids, deltaMinutes)

    // Fingers that have lifted blocks are not a pinch, however they move from here.
    override fun onLiftedChanged() {
        pinchSpan = 0f
        onBackChanged.run()
    }

    override fun onShowWeek() = viewModel.showWeek(true)

    private fun weekDate(day: Int): LocalDate = viewModel.selectedDate.weekStart().plusDays(day.toLong())

    override fun dayName(day: Int): String = DayOfWeek.of(day + 1).getDisplayName(TextStyle.FULL, locale)

    override fun onDayOpen(day: Int) = viewModel.openDay(weekDate(day))

    override fun onShowDay() = viewModel.showWeek(false)

    // A sheet is about the selected day, which the day touched in the week becomes.
    override fun onEmptyTimeTap(day: Int, minutes: Int) {
        viewModel.selectDay(weekDate(day))
        viewModel.openCreate(minutes)
    }

    override fun onBlockTap(block: PlannerBlock) {
        viewModel.selectDay(block.date)
        viewModel.openActions(block.id)
    }

    override fun onBlockRename(block: PlannerBlock) {
        viewModel.selectDay(block.date)
        viewModel.openRename(block.id)
    }

    override fun onBlockDelete(block: PlannerBlock) {
        viewModel.selectDay(block.date)
        deleteBlock(block.id)
    }

    override fun onBlockMove(block: PlannerBlock, day: Int, startMinutes: Int): Boolean =
        viewModel.moveBlock(block, weekDate(day), startMinutes)

    override fun onBlockResize(block: PlannerBlock, durationMinutes: Int): Boolean {
        viewModel.resizeBlock(block, durationMinutes)
        return true
    }

    // The week, scrolling. It opens with its first hour at the top, placed in the layout
    // after it is shown.
    private inner class WeekScroll(context: Context) : ScrollView(context) {
        init {
            isVerticalScrollBarEnabled = false
            defaultFocusHighlightEnabled = false
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (!weekAtFirstHour || height == 0) return
            weekAtFirstHour = false
            scrollTo(0, Math.round(WeekFirstHour * week.hourPx))
        }

        // The week is drawn from beneath its dates down and nowhere above: the heading and
        // the dates stand on the bare paper, at rest and while the week comes and goes. The
        // margin reaches half a label higher, for the label of the hour it opens at.
        override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
            val top = scrollY.toFloat()
            val edge = top + weekDays.solidBottom
            val margin = (week.columns.gutter - week.columns.gap).toFloat()
            canvas.save()
            canvas.clipOutRect(margin, top, width.toFloat(), edge)
            canvas.clipOutRect(0f, top, margin, edge - context.dp(7f))
            val more = super.drawChild(canvas, child, drawingTime)
            canvas.restore()
            return more
        }
    }

    // The day, scrolling. It opens with the current time a third of the way down, placed in
    // its first layout so that no frame shows midnight first.
    private inner class TimelineScroll(context: Context) : ScrollView(context) {
        init {
            isVerticalScrollBarEnabled = false
            // Restored with the activity, as a scroll position should be.
            id = android.R.id.list
            // Keys take the window out of touch mode, where the platform shades whatever
            // holds the focus: left to that, the whole day once a sheet typed into from a
            // keyboard has closed.
            defaultFocusHighlightEnabled = false
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
            lastScrollMillis = SystemClock.uptimeMillis()
            day.scrolled()
        }
    }

    // The date heading: the day, with its date beneath when the day is named, over a tint of
    // paper that fades out below. Tapping the text opens the date sheet; the rest lets
    // touches through to the day.
    private inner class Header(context: Context) : View(context) {
        private val titlePaint = context.textPaint(HeadlineMedium)
        private val subtitlePaint = context.textPaint(BodySmall)
        private val glaze = Paint()
        private var glazePaper = 0
        private var title = ""
        private var subtitle: String? = null
        private var titleBlock: TextBlock? = null
        private var subtitleBlock: TextBlock? = null
        private var textWidth = -1
        private val button = Rect()
        // In its colour from the start: setting it during the first draw would ask for a second.
        private var pressColour = palette.PrimaryText
        private val press = ripple(pressColour, roundedRect(-1, context.dp(10f))).also { it.callback = this }

        // Status bar, text and the padding around it: the heading's solid part.
        var contentHeight = 0
            private set

        // Over the week the heading is its text and no more, on the bare paper: the week's
        // days stand beneath it and the week is drawn beneath them. The bottom of that form
        // is kept whichever form the heading has, for the week to stand beneath.
        var week = false
            set(value) {
                if (field == value) return
                field = value
                glazePaper = 0
                requestLayout()
                invalidate()
            }
        var weekContentHeight = 0
            private set

        // How far the name leans after a finger that is carrying the day away.
        var lean = 0f
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        var statusTop = 0
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        init {
            isClickable = true
            // Its own ripple shows the focus, in the button's shape; the platform's
            // highlight would shade the whole band.
            defaultFocusHighlightEnabled = false
            setOnClickListener { viewModel.openDateJump() }
        }

        fun show(title: String, subtitle: String?) {
            if (this.title == title && this.subtitle == subtitle) return
            this.title = title
            this.subtitle = subtitle
            textWidth = -1
            contentDescription = "Jump date, $title"
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val available = width - context.px(16f) * 2
            if (textWidth != available) {
                textWidth = available
                titleBlock = textBlock(title, titlePaint, 0f, available, maxLines = 1, ellipsis = true)
                subtitleBlock = subtitle?.let { textBlock(it, subtitlePaint, 0f, available, maxLines = 1, ellipsis = true) }
            }
            val first = titleBlock!!
            val second = subtitleBlock
            val buttonWidth = max(first.width, second?.width ?: 0) + context.px(16f) * 2
            val buttonHeight = first.height + (second?.height ?: 0) + context.px(4f) * 2
            val left = Math.round((width - buttonWidth) / 2f)
            val top = statusTop + context.px(4f)
            button.set(left, top, left + buttonWidth, top + buttonHeight)
            press.bounds = button
            weekContentHeight = top + first.height + context.px(4f) * 2 + context.px(6f)
            contentHeight = if (week) weekContentHeight else button.bottom + context.px(14f)
            setMeasuredDimension(width, if (week) contentHeight else contentHeight + context.px(HeaderFadeHeight))
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            glazePaper = 0
            // Blocks kept through a change of day rest their pinned titles below the new fade.
            day.scrolled()
        }

        override fun onDraw(canvas: Canvas) {
            val colours = palette
            // Over the week there is no tint and no fade: nothing is drawn behind the heading.
            if (!week) {
                if (glazePaper != colours.Paper) {
                    glazePaper = colours.Paper
                    // Smootherstep meets the constant tint and the transparent timeline with zero slope.
                    glaze.shader = LinearGradient(
                        0f, height - context.dp(HeaderFadeHeight), 0f, height.toFloat(),
                        IntArray(ScrimSteps + 1) { index ->
                            val t = index.toFloat() / ScrimSteps
                            val eased = t * t * t * (t * (t * 6f - 15f) + 10f)
                            withAlpha(colours.Paper, HeaderTintAlpha * (1f - eased))
                        },
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glaze)
            }
            val first = titleBlock ?: return
            val inner = button.width() - context.px(16f) * 2
            val textTop = button.top + context.px(4f)
            first.draw(
                canvas, button.left + context.px(16f) + Math.round((inner - first.width) / 2f) + lean, textTop.toFloat(),
                colours.PrimaryText
            )
            subtitleBlock?.let {
                it.draw(
                    canvas, button.left + context.px(16f) + Math.round((inner - it.width) / 2f) + lean,
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
                        if (next.deletedBlock != null) AccessibilityManager.FLAG_CONTENT_CONTROLS else 0
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
                context, MessageText, 0, maxLines = 1, ellipsis = true, lineBox = true
            )
            private val undo = if (item.deletedBlock != null) {
                Label(context, MessageText, 0, maxLines = 1, gravity = Gravity.CENTER_VERTICAL, lineBox = true)
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
                message.text = item.message
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
