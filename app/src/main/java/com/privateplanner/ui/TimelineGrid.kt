package com.privateplanner.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.privateplanner.domain.TimeFormatter
import com.privateplanner.domain.TimeSnapper
import kotlin.math.abs
import kotlin.math.roundToInt

private val CurrentTimeLabelShape = RoundedCornerShape(7.dp)
private val HourLabels = List(24) { hour -> TimeFormatter.time(hour * 60) }
private val HalfHourLabels = List(24) { hour -> TimeFormatter.time(hour * 60 + 30) }
private val HourLabelStyle = TextStyle(
    fontFamily = DaytileFontFamily,
    fontSize = 16.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.SemiBold
)
private val HalfHourLabelStyle = TextStyle(
    fontFamily = DaytileFontFamily,
    fontSize = 12.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.SemiBold
)
// The 48 label layouts, measured once. Layout depends only on text, style, density, font
// scale and direction, so layouts measured on the launch warm-up thread with the
// window's values draw exactly as ones measured in composition.
private class GridLabels(measurer: TextMeasurer, val density: Density, val direction: LayoutDirection) {
    val hours = HourLabels.map { measurer.measure(text = it, style = HourLabelStyle, maxLines = 1) }
    val halfHours = HalfHourLabels.map { measurer.measure(text = it, style = HalfHourLabelStyle, maxLines = 1) }

    fun fits(density: Density, direction: LayoutDirection): Boolean =
        this.density.density == density.density &&
            this.density.fontScale == density.fontScale &&
            this.direction == direction
}

@Volatile
private var preparedGridLabels: GridLabels? = null

// Runs on the launch warm-up thread, so the first frame draws the labels instead of
// laying them out on the main thread. Composition falls back to measuring if the
// window's density or direction differ.
internal fun Context.prepareGridLabels() {
    val density = Density(this)
    preparedGridLabels = GridLabels(
        TextMeasurer(createFontFamilyResolver(this), density, LayoutDirection.Ltr, cacheSize = 0),
        density,
        LayoutDirection.Ltr
    )
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

// On today the clock is read only by this derived state, inside the draw pass: a tick
// recomposes nothing, and redraws the grid only when a label has to give way.
@Composable
internal fun TimelineGrid(showsNow: Boolean) {
    val minute = LocalCurrentMinuteOfDay.current
    val hiddenLabel = remember(showsNow, minute) {
        derivedStateOf { if (showsNow) hiddenGridLabelMinutes(minute.intValue) else null }
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val labels = remember(density, direction, fontFamilyResolver) {
        preparedGridLabels?.takeIf { it.fits(density, direction) }
            ?: GridLabels(TextMeasurer(fontFamilyResolver, density, direction, cacheSize = 0), density, direction)
    }
    val hourLayouts = labels.hours
    val halfHourLayouts = labels.halfHours
    // Read in the draw pass, so a palette step redraws the grid without rebuilding its
    // cached paths.
    val palette = rememberUpdatedState(PlannerColours)
    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val hourHeight = HourHeight.toPx()
                val gutter = TimelineGutter.toPx()
                val fiveMinuteTickLength = FiveMinuteTickLength.toPx()
                val quarterTickLength = QuarterHourTickLength.toPx()
                val strokePx = GridStrokeWidth.toPx()
                val labelWidthPx = (TimelineGutter - 8.dp).toPx()
                val hourLabelHeightPx = HourLabelHeight.toPx()
                val halfHourLabelHeightPx = HalfHourLabelHeight.toPx()
                val hourPath = Path()
                val halfHourPath = Path()
                val fiveMinutePath = Path()
                val quarterPath = Path()

                fun Path.addHorizontalLine(y: Float, endX: Float) {
                    moveTo(gutter, y)
                    lineTo(endX, y)
                }

                for (hour in 0..24) {
                    val hourY = (hour * hourHeight).roundToInt().toFloat()
                    hourPath.addHorizontalLine(hourY, size.width)
                    if (hour < 24) {
                        for (tick in 1 until 12) {
                            val tickY = (hourY + hourHeight * tick / 12f)
                                .roundToInt()
                                .toFloat()
                            when {
                                tick == 6 -> halfHourPath.addHorizontalLine(tickY, size.width)
                                tick % 3 == 0 -> quarterPath.addHorizontalLine(
                                    tickY,
                                    gutter + quarterTickLength
                                )
                                else -> fiveMinutePath.addHorizontalLine(
                                    tickY,
                                    gutter + fiveMinuteTickLength
                                )
                            }
                        }
                    }
                }

                val stroke = Stroke(width = strokePx)
                onDrawBehind {
                    // Clock ticks change label visibility, not the cached grid paths.
                    val hidden = hiddenLabel.value
                    val colours = palette.value
                    drawPath(hourPath, colours.HourLine, style = stroke)
                    drawPath(halfHourPath, colours.HalfHourLine, style = stroke)
                    drawPath(quarterPath, colours.QuarterTick, style = stroke)
                    drawPath(fiveMinutePath, colours.QuarterTick.copy(alpha = FiveMinuteTickAlpha), style = stroke)
                    drawTimelineLabels(
                        layouts = hourLayouts,
                        colour = colours.TimeText,
                        labelWidthPx = labelWidthPx,
                        labelHeightPx = hourLabelHeightPx,
                        hiddenHour = hidden?.takeIf { it % 60 == 0 }?.div(60)
                    ) { hour ->
                        (hourHeight * hour - hourLabelHeightPx / 2f).coerceAtLeast(0f)
                    }
                    drawTimelineLabels(
                        layouts = halfHourLayouts,
                        colour = colours.MutedText,
                        labelWidthPx = labelWidthPx,
                        labelHeightPx = halfHourLabelHeightPx,
                        hiddenHour = hidden?.takeIf { it % 60 == 30 }?.div(60)
                    ) { hour ->
                        hourHeight * hour + hourHeight / 2f - halfHourLabelHeightPx / 2f
                    }
                }
            }
    )
}

// One node: the full-width start of its chain draws the line and dot in the day's own
// x coordinates, and the rest narrows to the label, with no day-tall canvas beside it.
@Composable
internal fun CurrentTimeIndicator() {
    val minutes = LocalCurrentMinuteOfDay.current.intValue
    val timeText = remember(minutes) { TimeFormatter.time(minutes) }
    val indicatorColour = PlannerColours.Delete
    val y = HourHeight * (minutes / 60f)
    val labelTop = (y - HourLabelHeight / 2).coerceAtLeast(0.dp)

    Text(
        text = timeText,
        color = indicatorColour,
        fontSize = 13.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .offset(y = labelTop)
            .fillMaxWidth()
            .height(HourLabelHeight)
            .zIndex(4f)
            .drawBehind {
                // Placement is whole pixels, so this is the day's rounded y exactly.
                val lineY = y.toPx().roundToInt().toFloat() - labelTop.roundToPx()
                val gutter = TimelineGutter.toPx()
                drawLine(
                    color = indicatorColour,
                    start = Offset(gutter, lineY),
                    end = Offset(size.width, lineY),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = indicatorColour,
                    radius = 4.dp.toPx(),
                    center = Offset(gutter, lineY)
                )
            }
            // Release the full-width constraint before measuring the gutter badge.
            .wrapContentWidth(Alignment.Start)
            .padding(start = 6.dp)
            .width(58.dp)
            .background(PlannerColours.Sheet, CurrentTimeLabelShape)
            .border(1.dp, indicatorColour.copy(alpha = 0.35f), CurrentTimeLabelShape)
            .semantics {
                contentDescription = "Current time, $timeText"
            }
            .wrapContentSize(Alignment.Center)
    )
}

private inline fun DrawScope.drawTimelineLabels(
    layouts: List<TextLayoutResult>,
    colour: Color,
    labelWidthPx: Float,
    labelHeightPx: Float,
    hiddenHour: Int?,
    topForHour: (Int) -> Float
) {
    for (hour in layouts.indices) {
        if (hour == hiddenHour) continue
        val layout = layouts[hour]
        drawText(
            textLayoutResult = layout,
            color = colour,
            topLeft = Offset(
                x = labelWidthPx - layout.size.width,
                y = topForHour(hour) + (labelHeightPx - layout.size.height) / 2f
            )
        )
    }
}
