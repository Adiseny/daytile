package com.privateplanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.privateplanner.R
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.isBlankTitle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.function.Consumer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Called on the launch warm-up thread: loads the month and weekday text the date sheet
// shows, which the platform then holds for the locale.
internal fun prepareDateSheet(locale: Locale, date: LocalDate) {
    date.format(dateFormatter(MonthTitle, locale))
    DayOfWeek.MONDAY.getDisplayName(TextStyle.NARROW, locale)
}

private fun weighted(height: Int = WRAP_CONTENT) = Flow.Cell(0, height, 1f)

// Material's placeholder colour (onSurfaceVariant) for each polarity.
private const val PlaceholderOnLight = 0xFF49454F.toInt()
private const val PlaceholderOnDark = 0xFFCAC4D0.toInt()

// The platform's text field, kept to plain text: no spell-check underlines, and a selection
// toolbar that does not offer to share the title with another app.
private class TitleInput(context: Context) : EditText(context), ActionMode.Callback {
    init {
        // The platform offers the device's text actions (Read aloud, where there is one) only
        // to a field it can find again by its id.
        id = android.R.id.edit
        customSelectionActionModeCallback = this
    }

    // Read out with the field.
    var problem: String? = null
        set(value) {
            if (field == value) return
            field = value
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.removeItem(android.R.id.shareText)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun isSuggestionsEnabled() = false

    override fun onTextContextMenuItem(id: Int) =
        super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.error = problem
        info.isContentInvalid = problem != null
    }
}

// Title entry for a new block or a rename: the field beside its button, any problem beneath.
// The field is at least 56dp tall, one line of 16sp in bold, 16dp from either side, over a
// placeholder in regular. Its cursor, selection and handles take the ink as the accent of the
// palette's polarity (styles.xml), whose theme also gives the selection toolbar that polarity.
internal class InputSheet(
    context: Context,
    private val colours: PlannerPalette,
    title: String,
    buttonLabel: String,
    private val onSubmit: Consumer<String>
) : Flow(context, vertical = true) {
    private val theme = if (colours.LightBackground) R.style.Theme_Daytile_Day else R.style.Theme_Daytile_Night
    private val input = TitleInput(ContextThemeWrapper(context, theme))
    private val label = Label(context, LabelLarge, 0, lineBox = true)
    private val button = PlannerButton(context, colours.Sheet, 24f, pillHeight = 48f)
    private val error = Label(context, BodyMedium, colours.Delete)
    private val keyboard = context.getSystemService(InputMethodManager::class.java)

    val text: String get() = input.text.toString()

    init {
        setPadding(context.px(14f), context.px(10f), context.px(14f), context.px(16f))
        input.apply {
            background = null
            gravity = Gravity.TOP
            includeFontPadding = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_PX, context.sp(16f))
            // The line sits centred in a slot at least 24dp tall between the paddings, an odd
            // pixel going above it.
            val line = paint.fontMetricsInt.run { descent - ascent }
            val slack = (context.px(24f) - line).coerceAtLeast(0)
            val above = (slack + 1) / 2
            val padding = context.px(16f)
            setPadding(padding, padding + above, padding, padding + slack - above)
            minimumHeight = context.px(56f)
            setTextColor(colours.PrimaryText)
            setHintTextColor(if (colours.LightBackground) PlaceholderOnLight else PlaceholderOnDark)
            hint = "What's happening?"
            // Sentences, corrections, Done.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
            // Never splits a surrogate pair at the limit.
            filters = arrayOf(InputFilter.LengthFilter(MaxTitleLength))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    typeface = if (text.isNullOrEmpty()) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
                    submittable(!text.isBlankTitle())
                }
            })
            // Done on the keyboard arrives once, with no event; Enter on a physical
            // keyboard arrives as the key goes down and again as it comes up.
            setOnEditorActionListener { _, _, event ->
                if (event == null || event.action == KeyEvent.ACTION_DOWN) submit()
                true
            }
        }
        label.text = buttonLabel
        button.pill.addView(label)
        button.setOnClickListener { submit() }
        addView(
            Flow(context, centred = true).apply {
                addView(input, weighted())
                addView(button, Cell(WRAP_CONTENT, context.px(48f)).apply { leftMargin = context.px(10f) })
            },
            Cell(MATCH_PARENT)
        )
        error.setPadding(context.px(16f), context.px(2f), context.px(8f), 0)
        error.visibility = GONE
        addView(error)
        input.setText(title)
        submittable(!title.isBlankTitle())
    }

    private fun submit() {
        val title = text
        if (!title.isBlankTitle()) onSubmit.accept(title)
    }

    private fun submittable(yes: Boolean) {
        button.isEnabled = yes
        button.container = if (yes) colours.PrimaryText else colours.AddButtonDisabled
        label.colour = if (yes) colours.Sheet else colours.MutedText
    }

    // A title to rename opens selected, so typing replaces it; one carried over a change
    // of palette keeps its caret at the end.
    fun open(typed: String?) {
        if (typed == null) input.setSelectAllOnFocus(true) else {
            input.setText(typed)
            input.setSelection(input.length())
        }
        input.requestFocus()
        // Posted: the keyboard only answers the field once its focus has been reported.
        input.post { keyboard.showSoftInput(input, 0) }
    }

    fun setError(message: String?) {
        error.text = message.orEmpty()
        error.visibility = if (message == null) GONE else VISIBLE
        input.problem = message
        // Material's error cursor; before Android 10 it keeps the ink.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            input.textCursorDrawable?.mutate()?.setTint(if (message != null) colours.Delete else colours.PrimaryText)
        }
    }

    // Nothing else hides the keyboard when its field goes with the sheet.
    override fun onDetachedFromWindow() {
        keyboard.hideSoftInputFromWindow(windowToken, 0)
        super.onDetachedFromWindow()
    }
}

// A block's title and time, which rename it when tapped, beside Delete.
internal class ActionsSheet(
    context: Context,
    colours: PlannerPalette,
    onRename: OnClickListener,
    onDelete: OnClickListener
) : Flow(context, centred = true) {
    private val title = Label(context, TitleMedium, colours.PrimaryText, maxLines = 1, ellipsis = true)
    private val time = Label(context, BodyMedium, colours.MutedText, maxLines = 1, ellipsis = true)
    private val rename = Flow(context, vertical = true)
    private val delete = PlannerButton(context, colours.PrimaryText, 12f, pillHeight = 48f)

    init {
        setPadding(context.px(14f), context.px(12f), context.px(10f), context.px(12f))
        rename.apply {
            minimumHeight = context.px(56f)
            setPadding(context.px(8f), context.px(6f), context.px(8f), context.px(6f))
            rippleOver(colours.PrimaryText, context.dp(8f))
            setOnClickListener(onRename)
            addView(title)
            time.setPadding(0, context.px(2f), 0, 0)
            addView(time)
        }
        addView(rename, weighted())
        delete.pill.addView(Label(context, LabelLarge, colours.Delete).apply { text = "Delete" })
        delete.setOnClickListener(onDelete)
        addView(delete, Cell(WRAP_CONTENT, context.px(48f)))
    }

    fun bind(block: PlannerBlock) {
        title.text = block.title
        time.text = "${TimeFormatter.range(block.startMinutes, block.durationMinutes)} \u00B7 ${TimeFormatter.duration(block.durationMinutes)}"
        rename.contentDescription = "Rename ${block.title}"
        delete.contentDescription = "Delete ${block.title}"
    }
}

// The bell of res/drawable/ic_bell.xml (the notification icon), stroked from the same path
// in its 24-unit viewport, with a slash through it while reminders are off.
private class Bell(context: Context) : View(context) {
    private val path = Path().apply {
        moveTo(12f, 3.2f)
        cubicTo(8.9f, 3.2f, 6.8f, 5.6f, 6.8f, 9f)
        rLineTo(0f, 3.6f)
        lineTo(5.2f, 15.4f)
        rLineTo(13.6f, 0f)
        rLineTo(-1.6f, -2.8f)
        lineTo(17.2f, 9f)
        rCubicTo(0f, -3.4f, -2.1f, -5.8f, -5.2f, -5.8f)
        close()
        // The clapper: the lower half of a circle of radius 1.9 about (12, 18.1).
        arcTo(10.1f, 16.2f, 13.9f, 20f, 180f, -180f, true)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    var colour = 0
    var slashed = false

    override fun onDraw(canvas: Canvas) {
        val scale = min(width, height) / 24f
        canvas.scale(scale, scale)
        paint.color = colour
        canvas.drawPath(path, paint)
        if (slashed) canvas.drawLine(4.6f, 4.6f, 19.4f, 19.4f, paint)
    }
}

// One day of the month: its number, in a filled pill when selected and a ringed one today.
private class DayCell(context: Context, private val colours: PlannerPalette) : View(context) {
    private val textPaint = context.textPaint(BodyMedium)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var number: TextBlock? = null
    private var today = false

    init {
        foreground = ripple(colours.PrimaryText, ColorDrawable(-1))
    }

    fun bind(day: Int, selected: Boolean, today: Boolean, spoken: String) {
        number = textBlock(day.toString(), textPaint, 0f, Unbounded)
        isSelected = selected
        this.today = today
        // Spoken as a date, not as digits, with the ring and fill as states.
        contentDescription = if (today) "Today, $spoken" else spoken
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = context.px(3f).toFloat()
        val radius = min(width - 2 * inset, height - 2 * inset) / 2f
        if (isSelected) {
            paint.style = Paint.Style.FILL
            paint.color = colours.PrimaryText
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, paint)
        } else if (today) {
            val stroke = ceil(context.dp(1f))
            val half = stroke / 2
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = colours.HourLine
            canvas.drawRoundRect(
                inset + half, inset + half, width - inset - half, height - inset - half,
                max(radius - half, 0f), max(radius - half, 0f), paint
            )
        }
        val text = number ?: return
        text.draw(
            canvas,
            inset + Math.round((width - 2 * inset - text.width) / 2f),
            inset + Math.round((height - 2 * inset - text.height) / 2f),
            if (isSelected) colours.Sheet else colours.PrimaryText
        )
    }
}

// A month to pick a day from, always six rows tall so the sheet keeps one height as the
// months are paged, with the reminders switch and a way back to today beneath.
internal class DateSheet(
    context: Context,
    private val colours: PlannerPalette,
    private val selectedDate: LocalDate,
    private val today: LocalDate,
    visibleMonth: YearMonth?,
    onToggleReminders: OnClickListener,
    private val onSelect: Consumer<LocalDate>
) : Flow(context, vertical = true) {
    private val locale = resources.configuration.locales[0]
    private val monthTitle = Label(
        context, TitleMedium, colours.PrimaryText, gravity = Gravity.CENTER_HORIZONTAL, centred = true
    )
    private val cells = List(42) { DayCell(context, colours) }
    private val bell = Bell(context)
    private val remindersLabel = Label(context, LabelLarge, 0, lineBox = true).apply { text = "Reminders" }
    private val reminders = PlannerButton(context, colours.PrimaryText, 15f)

    var visibleMonth: YearMonth = visibleMonth ?: YearMonth.from(selectedDate)
        private set

    init {
        setPadding(context.px(18f), context.px(16f), context.px(18f), context.px(16f))
        addView(
            Flow(context, centred = true).apply {
                addView(chevron("\u2039", "Previous month", -1), Cell(context.px(48f), context.px(48f)))
                addView(monthTitle, weighted())
                addView(chevron("\u203A", "Next month", 1), Cell(context.px(48f), context.px(48f)))
            },
            Cell(MATCH_PARENT)
        )
        addView(
            Flow(context).apply {
                for (day in DayOfWeek.values()) {
                    val label = Label(
                        context, BodyMedium, colours.MutedText, gravity = Gravity.CENTER_HORIZONTAL, centred = true
                    )
                    label.text = day.getDisplayName(TextStyle.NARROW, locale)
                    addView(label, weighted())
                }
            },
            Cell(MATCH_PARENT).apply { topMargin = context.px(10f) }
        )
        for (row in 0 until 6) {
            addView(
                Flow(context).apply {
                    for (column in 0 until 7) addView(cells[row * 7 + column], weighted(context.px(48f)))
                },
                Cell(MATCH_PARENT)
            )
        }
        reminders.pill.apply {
            addView(bell, Cell(context.px(18f), context.px(18f)))
            addView(remindersLabel, Cell().apply { leftMargin = context.px(7f) })
        }
        reminders.contentDescription = "Reminders"
        reminders.setOnClickListener(onToggleReminders)
        val todayButton = PlannerButton(context, colours.PrimaryText, 12f)
        todayButton.pill.addView(Label(context, LabelLarge, colours.PrimaryText, lineBox = true).apply { text = "Today" })
        todayButton.setOnClickListener { onSelect.accept(today) }
        addView(
            Flow(context, centred = true).apply {
                addView(reminders)
                addView(View(context), weighted(0))
                addView(todayButton)
            },
            Cell(MATCH_PARENT).apply { topMargin = context.px(8f) }
        )
        showMonth()
    }

    private fun chevron(glyph: String, description: String, months: Long): View =
        Label(context, ChevronText, colours.PrimaryText, gravity = Gravity.CENTER, lineBox = true).apply {
            text = glyph
            contentDescription = description
            rippleOver(colours.PrimaryText, context.dp(8f))
            setOnClickListener {
                visibleMonth = visibleMonth.plusMonths(months)
                showMonth()
            }
        }

    private fun showMonth() {
        monthTitle.text = visibleMonth.format(dateFormatter(MonthTitle, locale))
        val spoken = dateFormatter(FullDate, locale)
        val leadingBlanks = visibleMonth.atDay(1).dayOfWeek.value - 1
        val daysInMonth = visibleMonth.lengthOfMonth()
        for (index in cells.indices) {
            val cell = cells[index]
            val day = index - leadingBlanks + 1
            if (day in 1..daysInMonth) {
                val date = visibleMonth.atDay(day)
                cell.visibility = VISIBLE
                cell.bind(day, date == selectedDate, date == today, date.format(spoken))
                cell.setOnClickListener { onSelect.accept(date) }
            } else {
                cell.visibility = INVISIBLE
            }
        }
    }

    fun setReminders(on: Boolean) {
        val colour = if (on) colours.PrimaryText else colours.MutedText
        if (bell.colour == colour && bell.slashed == !on) return
        bell.colour = colour
        bell.slashed = !on
        bell.invalidate()
        remindersLabel.colour = colour
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) reminders.stateDescription = if (on) "On" else "Off"
    }
}
