package com.privateplanner.ui

import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun BlockInputSheet(
    title: String,
    buttonLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    selectAll: Boolean = false,
    errorText: String? = null
) {
    PlannerSheetSurface(onDismiss = onDismiss) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var value by remember(title, selectAll) {
            mutableStateOf(
                TextFieldValue(
                    text = title,
                    selection = if (selectAll) TextRange(0, title.length) else TextRange(title.length)
                )
            )
        }
        val canSubmit = value.text.isNotBlank()

        fun submit() {
            if (canSubmit) {
                onSubmit(value.text)
            }
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
            .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Cancel" }
                ) {
                    Text(
                        text = "\u00D7",
                        fontFamily = DaytileFontFamily,
                        fontSize = 24.sp,
                        color = PlannerColors.MutedText
                    )
                }

                TextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text("What's happening?") },
                    isError = errorText != null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                        cursorColor = PlannerColors.PrimaryText
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = { submit() },
                    enabled = canSubmit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PlannerColors.AddButton,
                        contentColor = PlannerColors.Sheet,
                        disabledContainerColor = PlannerColors.AddButtonDisabled,
                        disabledContentColor = PlannerColors.MutedText
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = buttonLabel,
                        color = if (canSubmit) PlannerColors.Sheet else PlannerColors.MutedText
                    )
                }
            }

            if (errorText != null) {
                Text(
                    text = errorText,
                    color = PlannerColors.Delete,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 58.dp, top = 2.dp, end = 8.dp)
                )
            }
        }
    }
}

@Composable
fun BlockActionSheet(
    block: PlannerBlock,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    PlannerSheetSurface(onDismiss = onDismiss) {
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
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRename)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .semantics {
                        contentDescription = "Rename ${block.title}"
                    }
            ) {
                Text(
                    text = block.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${TimeFormatter.range(block.startMinutes, block.durationMinutes)} \u00B7 ${TimeFormatter.duration(block.durationMinutes)}",
                    color = PlannerColors.MutedText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            TextButton(
                onClick = onDelete,
                modifier = Modifier
                    .height(48.dp)
                    .semantics {
                        contentDescription = "Delete ${block.title}"
                    }
            ) {
                Text(
                    text = "Delete",
                    color = PlannerColors.Delete,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun DateJumpSheet(
    selectedDate: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    PlannerSheetSurface(onDismiss = onDismiss) {
        var visibleMonth by remember(selectedDate) { mutableStateOf(YearMonth.from(selectedDate)) }
        val today = LocalDate.now()
        val locale = LocalLocale.current.platformLocale
        val titleFormatter = remember(locale) {
            DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        }

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
                    text = visibleMonth.atDay(1).format(titleFormatter),
                    style = MaterialTheme.typography.titleMedium,
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
                onSelect = onSelect
            )

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                TextButton(onClick = { onSelect(today) }) {
                    Text("Today", color = PlannerColors.PrimaryText)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = PlannerColors.MutedText)
                }
            }
        }
    }
}

@Composable
private fun PlannerSheetSurface(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    // While a sheet is open the screen is dimmed by the scrim, so drive the system bars to the same
    // dimmed shade (with light icons) for as long as it's shown, and restore the time-of-day style
    // on dismiss. Without this the status bar kept its pre-dim background shade while everything
    // below it darkened, leaving the top strip looking like a different colour.
    val view = LocalView.current
    val paperArgb = PlannerColors.Paper.toArgb()
    val dimmedArgb = PlannerColors.Scrim.compositeOver(PlannerColors.Paper).toArgb()
    val lightBackground = PlannerColors.Paper.luminance() > 0.5f
    DisposableEffect(view, dimmedArgb, lightBackground) {
        val activity = view.context.findComponentActivity()
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(dimmedArgb),
            navigationBarStyle = SystemBarStyle.dark(dimmedArgb)
        )
        onDispose {
            val restored = if (lightBackground) {
                SystemBarStyle.light(paperArgb, PaperBackgroundDarkArgb)
            } else {
                SystemBarStyle.dark(paperArgb)
            }
            activity?.enableEdgeToEdge(
                statusBarStyle = restored,
                navigationBarStyle = restored
            )
        }
    }

    val keyboardBottomOffset = keyboardBottomOffset()
    val density = LocalDensity.current
    val contentBottomPadding = with(density) {
        val imeBottom = WindowInsets.ime.getBottom(this)
        if (imeBottom > 0) {
            8.dp
        } else {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlannerColors.Scrim)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onDismiss
            )
    ) {
        Surface(
            color = PlannerColors.Sheet,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = keyboardBottomOffset)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Box(modifier = Modifier.padding(bottom = contentBottomPadding)) {
                content()
            }
        }
    }
}

@Composable
private fun keyboardBottomOffset(): androidx.compose.ui.unit.Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    val resources = LocalResources.current
    val view = LocalView.current
    val visibleFrame = remember { Rect() }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    if (imeBottomPx <= 0) return 0.dp

    view.getWindowVisibleDisplayFrame(visibleFrame)
    val fullWindowHeightPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
    } else {
        @Suppress("DEPRECATION")
        resources.displayMetrics.heightPixels
    }
    val alreadyResized = view.height <= fullWindowHeightPx - imeBottomPx / 2
    if (alreadyResized) return 0.dp

    val visibleFrameOverlap = (view.rootView.height - visibleFrame.bottom).coerceAtLeast(0)
    val keyboardOverlapPx = if (visibleFrameOverlap > 0) {
        minOf(imeBottomPx, visibleFrameOverlap)
    } else {
        imeBottomPx
    }
    return with(density) { keyboardOverlapPx.toDp() }
}

@Composable
private fun MonthChevron(
    text: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Text(
            text = text,
            color = PlannerColors.PrimaryText,
            fontFamily = DaytileFontFamily,
            fontSize = 28.sp
        )
    }
}

@Composable
private fun WeekdayRow() {
    val locale = LocalLocale.current.platformLocale
    val labels = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
    ).map { it.getDisplayName(java.time.format.TextStyle.NARROW, locale) }

    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                color = PlannerColors.MutedText,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
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
    onSelect: (LocalDate) -> Unit
) {
    val firstDay = visibleMonth.atDay(1)
    val leadingBlanks = firstDay.dayOfWeek.value - 1

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(6) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val cellIndex = row * 7 + column
                    val day = cellIndex - leadingBlanks + 1
                    if (day in 1..visibleMonth.lengthOfMonth()) {
                        val date = visibleMonth.atDay(day)
                        DateCell(
                            date = date,
                            isSelected = date == selectedDate,
                            isToday = date == today,
                            onSelect = onSelect,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(
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
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = CircleShape
    val background = if (isSelected) PlannerColors.PrimaryText else Color.Transparent
    val textColor = if (isSelected) PlannerColors.Sheet else PlannerColors.PrimaryText
    val borderModifier = if (isToday && !isSelected) {
        Modifier.border(1.dp, PlannerColors.HourLine, shape)
    } else {
        Modifier
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(48.dp)
            .padding(3.dp)
            .clip(shape)
            .then(borderModifier)
            .background(background)
            .clickable { onSelect(date) }
            .semantics {
                contentDescription = date.toString()
            }
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
