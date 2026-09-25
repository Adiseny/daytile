package com.privateplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val MonthTitlePattern = "MMMM yyyy"
private val WeekdayLabels = ConcurrentHashMap<Locale, List<String>>()

private fun weekdayLabels(locale: Locale): List<String> = WeekdayLabels.getOrPut(locale) {
    DayOfWeek.entries.map { day -> day.getDisplayName(java.time.format.TextStyle.NARROW, locale) }
}

// The bell from res/drawable/ic_bell.xml (still the notification icon), built from the
// same path data in code so opening the date sheet parses no XML and the slashed variant
// needs no drawable. Stroke-only, as the XML is: its transparent fill draws nothing.
private fun bell(slashed: Boolean): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    val ink = SolidColor(Color.White)
    addPath(
        addPathNodes("M12,3.2C8.9,3.2 6.8,5.6 6.8,9v3.6L5.2,15.4h13.6l-1.6,-2.8V9c0,-3.4 -2.1,-5.8 -5.2,-5.8z"),
        stroke = ink,
        strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    )
    addPath(addPathNodes("M10.1,18.1a1.9,1.9 0 0 0 3.8,0"), stroke = ink, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round)
    if (slashed) {
        addPath(addPathNodes("M4.6,4.6L19.4,19.4"), stroke = ink, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round)
    }
}.build()

private val BellOn = bell(slashed = false)
private val BellOff = bell(slashed = true)

// Called on the launch warm-up thread: builds the bells (with this file's other
// constants) and loads the month and weekday text the date sheet shows.
internal fun prepareDateSheet(locale: Locale, date: LocalDate) {
    date.format(dateFormatter(MonthTitlePattern, locale))
    weekdayLabels(locale)
}

private val SheetShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
private val SheetButtonShape = RoundedCornerShape(8.dp)
// bodyLarge in bold, fixed rather than copied on every keystroke.
private val TitleInputStyle = TextStyle(
    fontFamily = DaytileFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 16.sp,
    lineHeight = 22.sp
)

// Material's placeholder colour (onSurfaceVariant) for each polarity.
private val PlaceholderOnLight = Color(0xFF49454F)
private val PlaceholderOnDark = Color(0xFFCAC4D0)
private val TitleFieldPadding = 16.dp
// The input and the placeholder each sit in a line at least 24dp tall, 16dp from either side.
private val TitleSlot = Modifier
    .heightIn(min = 24.dp)
    .wrapContentHeight()
    .padding(horizontal = TitleFieldPadding)

// Material's filled TextField as this sheet used it, container and indicator hidden: at
// least 280 by 56, with the input and the placeholder each centred in its height.
@Composable
private fun TitleField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    errorText: String?,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier
) {
    val colours = PlannerColours
    val textStyle = remember(colours.PrimaryText) { TitleInputStyle.merge(TextStyle(color = colours.PrimaryText)) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .then(if (errorText != null) Modifier.semantics { error(errorText) } else Modifier)
            .defaultMinSize(minWidth = 280.dp, minHeight = 56.dp),
        textStyle = textStyle,
        cursorBrush = SolidColor(if (errorText != null) colours.Delete else colours.PrimaryText),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        decorationBox = { innerTextField ->
            Layout(
                content = {
                    if (value.text.isEmpty()) {
                        Box(TitleSlot) {
                            Text(
                                text = "What's happening?",
                                color = if (colours.LightBackground) PlaceholderOnLight else PlaceholderOnDark
                            )
                        }
                    }
                    Box(TitleSlot, propagateMinConstraints = true) { innerTextField() }
                }
            ) { measurables, constraints ->
                val verticalPadding = (TitleFieldPadding * 2).roundToPx()
                val slot = constraints.copy(minHeight = 0).offset(vertical = -verticalPadding)
                val input = measurables.last().measure(slot)
                val placeholder = if (measurables.size > 1) measurables[0].measure(slot.copy(minWidth = 0)) else null
                val width = constraints.constrainWidth(maxOf(input.width, placeholder?.width ?: 0))
                val height = constraints.constrainHeight(verticalPadding + maxOf(input.height, placeholder?.height ?: 0))
                layout(width, height) {
                    placeholder?.placeRelative(0, Alignment.CenterVertically.align(placeholder.height, height))
                    input.placeRelative(0, Alignment.CenterVertically.align(input.height, height))
                }
            }
        }
    )
}

@Composable
internal fun BoxScope.BlockInputSheet(
    title: String,
    buttonLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    errorText: String?
) {
    // Held out here, so a keystroke recomposes only the sheet's content below and the
    // field and button colours are built once rather than on every keystroke.
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember(title) {
        mutableStateOf(
            TextFieldValue(
                text = title,
                selection = TextRange(0, title.length)
            )
        )
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    PlannerSheetSurface(
        accessibilityTitle = "$buttonLabel block",
        onDismiss = onDismiss
    ) {
        val canSubmit = value.text.isNotBlank() && value.text.length <= MaxTitleLength

        fun submit() {
            if (canSubmit) {
                onSubmit(value.text)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TitleField(
                    value = value,
                    onValueChange = { incoming ->
                        value = if (incoming.text.length <= MaxTitleLength) {
                            incoming
                        } else {
                            val cappedLength = if (
                                incoming.text[MaxTitleLength - 1].isHighSurrogate()
                            ) {
                                MaxTitleLength - 1
                            } else {
                                MaxTitleLength
                            }
                            val capped = incoming.text.take(cappedLength)
                            TextFieldValue(
                                text = capped,
                                selection = TextRange(capped.length)
                            )
                        }
                    },
                    errorText = errorText,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )

                PlannerButton(
                    onClick = { submit() },
                    enabled = canSubmit,
                    containerColor = if (canSubmit) PlannerColours.PrimaryText else PlannerColours.AddButtonDisabled,
                    contentColor = if (canSubmit) PlannerColours.Sheet else PlannerColours.MutedText,
                    contentPadding = ButtonPadding,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .height(48.dp)
                ) {
                    Text(
                        text = buttonLabel
                    )
                }
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    color = PlannerColours.Delete,
                    style = PlannerType.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.BlockActionSheet(
    block: PlannerBlock,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    PlannerSheetSurface(
        accessibilityTitle = "Block actions",
        onDismiss = onDismiss
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clip(SheetButtonShape)
                    .clickable(onClick = onRename)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics {
                        contentDescription = "Rename ${block.title}"
                    }
            ) {
                Text(
                    text = block.title,
                    style = PlannerType.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${TimeFormatter.range(block.startMinutes, block.durationMinutes)} \u00B7 ${TimeFormatter.duration(block.durationMinutes)}",
                    color = PlannerColours.MutedText,
                    style = PlannerType.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            PlannerButton(
                onClick = onDelete,
                containerColor = Color.Transparent,
                contentColor = PlannerColours.PrimaryText,
                contentPadding = TextButtonPadding,
                modifier = Modifier
                    .height(48.dp)
                    .semantics {
                        contentDescription = "Delete ${block.title}"
                    }
            ) {
                Text(
                    text = "Delete",
                    color = PlannerColours.Delete,
                    style = PlannerType.labelLarge
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.DateJumpSheet(
    selectedDate: LocalDate,
    remindersOn: Boolean,
    onToggleReminders: (Boolean) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    PlannerSheetSurface(
        accessibilityTitle = "Choose date",
        onDismiss = onDismiss
    ) {
        var visibleMonth by remember(selectedDate) { mutableStateOf(YearMonth.from(selectedDate)) }
        val today = LocalCurrentDate.current
        val locale = LocalLocale.current.platformLocale
        val titleFormatter = remember(locale) { dateFormatter(MonthTitlePattern, locale) }
        val spokenFormatter = remember(locale) { dateFormatter(FullDatePattern, locale) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MonthChevron(
                    text = "\u2039",
                    contentDescription = "Previous month",
                    onClick = { visibleMonth = visibleMonth.minusMonths(1) }
                )
                Text(
                    text = visibleMonth.format(titleFormatter),
                    style = PlannerType.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                MonthChevron(
                    text = "\u203A",
                    contentDescription = "Next month",
                    onClick = { visibleMonth = visibleMonth.plusMonths(1) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            WeekdayRow()
            MonthGrid(
                visibleMonth = visibleMonth,
                selectedDate = selectedDate,
                today = today,
                spokenFormatter = spokenFormatter,
                onSelect = onSelect
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                val reminderColour =
                    if (remindersOn) PlannerColours.PrimaryText else PlannerColours.MutedText
                PlannerButton(
                    onClick = { onToggleReminders(!remindersOn) },
                    containerColor = Color.Transparent,
                    contentColor = PlannerColours.PrimaryText,
                    // Sits the glyph in the same column as the month chevron above it.
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp),
                    modifier = Modifier.semantics {
                        contentDescription = "Reminders"
                        stateDescription = if (remindersOn) "On" else "Off"
                    }
                ) {
                    val bellTint = remember(reminderColour) { ColorFilter.tint(reminderColour) }
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .paint(
                                rememberVectorPainter(if (remindersOn) BellOn else BellOff),
                                colorFilter = bellTint,
                                contentScale = ContentScale.Fit
                            )
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(text = "Reminders", color = reminderColour)
                }

                Spacer(modifier = Modifier.weight(1f))

                PlannerButton(
                    onClick = { onSelect(today) },
                    containerColor = Color.Transparent,
                    contentColor = PlannerColours.PrimaryText,
                    contentPadding = TextButtonPadding
                ) {
                    Text("Today")
                }
            }
        }
    }
}

// Scrim and sheet go straight into the screen's root box, above everything drawn
// before them, instead of into a full-screen box of their own.
@Composable
private fun BoxScope.PlannerSheetSurface(
    accessibilityTitle: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlannerColours.Scrim)
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onDismiss
            )
            .semantics {
                contentDescription = "Dismiss $accessibilityTitle"
            }
    )

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .imePadding()
            .fillMaxWidth()
            .background(PlannerColours.Sheet, SheetShape)
            // Any pointer node makes the sheet the hit target, so taps on it never
            // reach the dismissing scrim behind.
            .pointerInput(Unit) {}
            .semantics {
                contentDescription = accessibilityTitle
                paneTitle = accessibilityTitle
                isTraversalGroup = true
            }
            .then(
                if (imeVisible) {
                    Modifier.padding(bottom = 8.dp)
                } else {
                    Modifier.navigationBarsPadding()
                }
            )
    ) {
        content()
    }
}

@Composable
private fun MonthChevron(
    text: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        fontSize = 28.sp,
        modifier = Modifier
            .size(48.dp)
            .clip(SheetButtonShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .wrapContentSize(Alignment.Center)
    )
}

@Composable
private fun WeekdayRow() {
    val locale = LocalLocale.current.platformLocale
    val labels = remember(locale) { weekdayLabels(locale) }

    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                color = PlannerColours.MutedText,
                textAlign = TextAlign.Center,
                style = PlannerType.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MonthGrid(
    visibleMonth: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    spokenFormatter: DateTimeFormatter,
    onSelect: (LocalDate) -> Unit
) {
    val leadingBlanks = visibleMonth.atDay(1).dayOfWeek.value - 1
    val daysInMonth = visibleMonth.lengthOfMonth()

    // Always lay out the six rows a month can need, so the sheet keeps one
    // height and the calendar does not jump as you page through months.
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(6) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val cellIndex = row * 7 + column
                    val day = cellIndex - leadingBlanks + 1
                    if (day in 1..daysInMonth) {
                        val date = visibleMonth.atDay(day)
                        DateCell(
                            date = date,
                            isSelected = date == selectedDate,
                            isToday = date == today,
                            spokenFormatter = spokenFormatter,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    spokenFormatter: DateTimeFormatter,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier
) {
    val shape = CircleShape
    val textColour = if (isSelected) PlannerColours.Sheet else PlannerColours.PrimaryText
    val borderModifier = if (isToday && !isSelected) {
        Modifier.border(1.dp, PlannerColours.HourLine, shape)
    } else {
        Modifier
    }

    Text(
        text = date.dayOfMonth.toString(),
        color = textColour,
        style = PlannerType.bodyMedium,
        modifier = modifier
            .height(48.dp)
            .clickable { onSelect(date) }
            // Spoken as a date, not as ISO digits, with the ring and fill as states.
            .semantics {
                val spoken = date.format(spokenFormatter)
                contentDescription = if (isToday) "Today, $spoken" else spoken
                selected = isSelected
            }
            .padding(3.dp)
            .clip(shape)
            .then(borderModifier)
            .then(
                if (isSelected) {
                    Modifier.background(PlannerColours.PrimaryText)
                } else {
                    Modifier
                }
            )
            .wrapContentSize(Alignment.Center)
    )
}
