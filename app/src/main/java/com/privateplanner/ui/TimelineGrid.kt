package com.privateplanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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

@Composable
internal fun TimelineGrid(hiddenLabelMinutes: Int?) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val hourLayouts = remember(textMeasurer) {
        HourLabels.map { label ->
            textMeasurer.measure(text = label, style = HourLabelStyle, maxLines = 1)
        }
    }
    val halfHourLayouts = remember(textMeasurer) {
        HalfHourLabels.map { label ->
            textMeasurer.measure(text = label, style = HalfHourLabelStyle, maxLines = 1)
        }
    }
    val hourLine = PlannerColours.HourLine
    val halfHourLine = PlannerColours.HalfHourLine
    val quarterTick = PlannerColours.QuarterTick
    val hourColour = PlannerColours.TimeText
    val halfHourColour = PlannerColours.MutedText
    val hiddenHour = hiddenLabelMinutes?.takeIf { it % 60 == 0 }?.div(60)
    val hiddenHalfHour = hiddenLabelMinutes?.takeIf { it % 60 == 30 }?.div(60)
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

                val fiveMinTickColour = quarterTick.copy(alpha = FiveMinuteTickAlpha)
                val stroke = Stroke(width = strokePx)
                onDrawBehind {
                    drawPath(hourPath, hourLine, style = stroke)
                    drawPath(halfHourPath, halfHourLine, style = stroke)
                    drawPath(quarterPath, quarterTick, style = stroke)
                    drawPath(fiveMinutePath, fiveMinTickColour, style = stroke)
                    drawTimelineLabels(
                        layouts = hourLayouts,
                        colour = hourColour,
                        labelWidthPx = labelWidthPx,
                        labelHeightPx = hourLabelHeightPx,
                        hiddenHour = hiddenHour
                    ) { hour ->
                        (hourHeight * hour - hourLabelHeightPx / 2f).coerceAtLeast(0f)
                    }
                    drawTimelineLabels(
                        layouts = halfHourLayouts,
                        colour = halfHourColour,
                        labelWidthPx = labelWidthPx,
                        labelHeightPx = halfHourLabelHeightPx,
                        hiddenHour = hiddenHalfHour
                    ) { hour ->
                        hourHeight * hour + hourHeight / 2f - halfHourLabelHeightPx / 2f
                    }
                }
            }
    )
}

@Composable
internal fun CurrentTimeIndicator(minutes: Int) {
    val timeText = remember(minutes) { TimeFormatter.time(minutes) }
    val indicatorColour = PlannerColours.Delete
    val y = HourHeight * (minutes / 60f)
    val labelTop = (y - HourLabelHeight / 2).coerceAtLeast(0.dp)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(3f)
    ) {
        val yPx = y.toPx().roundToInt().toFloat()
        val gutter = TimelineGutter.toPx()
        drawLine(
            color = indicatorColour,
            start = Offset(gutter, yPx),
            end = Offset(size.width, yPx),
            strokeWidth = 2.dp.toPx()
        )
        drawCircle(
            color = indicatorColour,
            radius = 4.dp.toPx(),
            center = Offset(gutter, yPx)
        )
    }

    Text(
        text = timeText,
        color = indicatorColour,
        fontFamily = DaytileFontFamily,
        fontSize = 13.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .offset(x = 6.dp, y = labelTop)
            .width(58.dp)
            .height(HourLabelHeight)
            .zIndex(4f)
            .background(PlannerColours.Sheet, CurrentTimeLabelShape)
            .border(1.dp, indicatorColour.copy(alpha = 0.35f), CurrentTimeLabelShape)
            .semantics {
                contentDescription = "Current time, $timeText"
            }
            .wrapContentSize(Alignment.Center)
    )
}

private inline fun DrawScope.drawTimelineLabels(
    layouts: List<androidx.compose.ui.text.TextLayoutResult>,
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
