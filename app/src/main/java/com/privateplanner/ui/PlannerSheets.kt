package com.privateplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privateplanner.domain.MaxTitleLength
import com.privateplanner.domain.PlannerBlock
import com.privateplanner.domain.TimeFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val SheetShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
private val SheetButtonShape = RoundedCornerShape(8.dp)

@Composable
internal fun BlockInputSheet(
    title: String,
    buttonLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    errorText: String?
) {
    PlannerSheetSurface(
        accessibilityTitle = "$buttonLabel block",
        onDismiss = onDismiss
    ) {
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
        val canSubmit = value.text.isNotBlank() && value.text.length <= MaxTitleLength

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
                TextField(
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
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                        cursorColor = PlannerColours.PrimaryText
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )

                Button(
                    onClick = { submit() },
                    enabled = canSubmit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PlannerColours.PrimaryText,
                        contentColor = PlannerColours.Sheet,
                        disabledContainerColor = PlannerColours.AddButtonDisabled,
                        disabledContentColor = PlannerColours.MutedText
                    ),
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
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun BlockActionSheet(
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
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${TimeFormatter.range(block.startMinutes, block.durationMinutes)} \u00B7 ${TimeFormatter.duration(block.durationMinutes)}",
                    color = PlannerColours.MutedText,
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
                    color = PlannerColours.Delete,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
internal fun DateJumpSheet(
    selectedDate: LocalDate,
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
                    text = visibleMonth.format(titleFormatter),
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
                    Text("Today")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = PlannerColours.MutedText)
                }
            }
        }
    }
}

@Composable
private fun PlannerSheetSurface(
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
        modifier = Modifier.fillMaxSize()
    ) {
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
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
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
}

@Composable
private fun MonthChevron(
    text: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        fontFamily = DaytileFontFamily,
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
    val labels = remember(locale) {
        DayOfWeek.entries.map { day ->
            day.getDisplayName(java.time.format.TextStyle.NARROW, locale)
        }
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { label ->
            Text(
                text = label,
                color = PlannerColours.MutedText,
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
    val leadingBlanks = visibleMonth.atDay(1).dayOfWeek.value - 1
    val daysInMonth = visibleMonth.lengthOfMonth()
    val rowCount = (leadingBlanks + daysInMonth + 6) / 7

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rowCount) { row ->
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
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .height(48.dp)
            .clickable { onSelect(date) }
            .semantics {
                contentDescription = date.toString()
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
