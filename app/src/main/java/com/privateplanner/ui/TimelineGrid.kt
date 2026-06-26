package com.privateplanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlin.math.roundToInt

@Composable
internal fun TimelineGrid(modifier: Modifier = Modifier) {
    val hourLine = PlannerColors.HourLine
    val halfHourLine = PlannerColors.HalfHourLine
    val quarterTick = PlannerColors.QuarterTick
    Box(
        modifier = modifier.drawWithCache {
            val hourHeight = HourHeight.toPx()
            val gutter = TimelineGutter.toPx()
            val fiveMinuteTickLength = 10.dp.toPx()
            val quarterTickLength = 18.dp.toPx()
            val strokePx = 1.dp.toPx()
            val hourPath = Path()
            val halfHourPath = Path()
            val fiveMinutePath = Path()
            val quarterPath = Path()

            for (hour in 0..24) {
                val y = (hour * hourHeight).roundToInt().toFloat()
                hourPath.line(gutter, y, size.width, y)
                if (hour < 24) {
                    for (tick in 1 until 12) {
                        if (tick % 3 != 0) {
                            val tickY = (y + hourHeight * tick / 12f).roundToInt().toFloat()
                            fiveMinutePath.line(gutter, tickY, gutter + fiveMinuteTickLength, tickY)
                        }
                    }
                    for (quarter in 1..3) {
                        val tickY = (y + hourHeight * quarter / 4f).roundToInt().toFloat()
                        if (quarter == 2) {
                            halfHourPath.line(gutter, tickY, size.width, tickY)
                        } else {
                            quarterPath.line(gutter, tickY, gutter + quarterTickLength, tickY)
                        }
                    }
                }
            }

            val fiveMinTickColor = quarterTick.copy(alpha = 0.72f)
            val stroke = Stroke(width = strokePx)
            onDrawBehind {
                drawPath(hourPath, hourLine, style = stroke)
                drawPath(halfHourPath, halfHourLine, style = stroke)
                drawPath(quarterPath, quarterTick, style = stroke)
                drawPath(fiveMinutePath, fiveMinTickColor, style = stroke)
            }
        }
    )
}

internal fun Path.line(startX: Float, startY: Float, endX: Float, endY: Float) {
    moveTo(startX, startY)
    lineTo(endX, endY)
}

@Composable
internal fun CurrentTimeIndicator(minutes: Int) {
    val timeText = remember(minutes) { TimeFormatter.time(minutes) }
    val indicatorColor = PlannerColors.Delete
    val y = HourHeight * (minutes / 60f)
    val labelTop = (y - HourLabelHeight / 2).coerceAtLeast(0.dp)
    val labelShape = RoundedCornerShape(7.dp)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(3f)
    ) {
        val yPx = (minutes / 60f * HourHeight.toPx()).roundToInt().toFloat()
        val gutter = TimelineGutter.toPx()
        drawLine(
            color = indicatorColor,
            start = Offset(gutter, yPx),
            end = Offset(size.width, yPx),
            strokeWidth = 2.dp.toPx()
        )
        drawCircle(
            color = indicatorColor,
            radius = 4.dp.toPx(),
            center = Offset(gutter, yPx)
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(x = 6.dp, y = labelTop)
            .width(58.dp)
            .height(HourLabelHeight)
            .zIndex(4f)
            .clip(labelShape)
            .background(PlannerColors.Sheet)
            .border(1.dp, indicatorColor.copy(alpha = 0.35f), labelShape)
            .semantics {
                contentDescription = "Current time, $timeText"
            }
    ) {
        androidx.compose.material3.Text(
            text = timeText,
            color = indicatorColor,
            fontFamily = DaytileFontFamily,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun TimeLabels(modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 48)
    val hourLabels = remember { List(24) { hour -> TimeFormatter.time(hour * 60) } }
    val halfHourLabels = remember { List(24) { hour -> TimeFormatter.time(hour * 60 + 30) } }
    val hourStyle = remember {
        TextStyle(
            fontFamily = DaytileFontFamily,
            fontSize = 16.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    val halfHourStyle = remember {
        TextStyle(
            fontFamily = DaytileFontFamily,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    val hourLayouts = remember(textMeasurer, hourLabels, hourStyle) {
        hourLabels.map { label ->
            textMeasurer.measure(
                text = label,
                style = hourStyle,
                maxLines = 1
            )
        }
    }
    val halfHourLayouts = remember(textMeasurer, halfHourLabels, halfHourStyle) {
        halfHourLabels.map { label ->
            textMeasurer.measure(
                text = label,
                style = halfHourStyle,
                maxLines = 1
            )
        }
    }
    val hourColor = PlannerColors.TimeText
    val halfHourColor = PlannerColors.MutedText.copy(alpha = 0.9f)
    val labelWidth = TimelineGutter - 8.dp

    Canvas(modifier = modifier) {
        val labelWidthPx = labelWidth.toPx()
        val hourHeightPx = HourHeight.toPx()
        val hourLabelHeightPx = HourLabelHeight.toPx()
        val halfHourLabelHeightPx = HalfHourLabelHeight.toPx()
        drawTimelineLabels(
            layouts = hourLayouts,
            color = hourColor,
            labelWidthPx = labelWidthPx,
            labelHeightPx = hourLabelHeightPx
        ) { hour ->
            (hourHeightPx * hour - hourLabelHeightPx / 2f).coerceAtLeast(0f)
        }
        drawTimelineLabels(
            layouts = halfHourLayouts,
            color = halfHourColor,
            labelWidthPx = labelWidthPx,
            labelHeightPx = halfHourLabelHeightPx
        ) { hour ->
            hourHeightPx * hour + hourHeightPx / 2f - halfHourLabelHeightPx / 2f
        }
    }
}

private inline fun DrawScope.drawTimelineLabels(
    layouts: List<androidx.compose.ui.text.TextLayoutResult>,
    color: Color,
    labelWidthPx: Float,
    labelHeightPx: Float,
    topForHour: (Int) -> Float
) {
    layouts.forEachIndexed { hour, layout ->
        drawText(
            textLayoutResult = layout,
            color = color,
            topLeft = Offset(
                x = labelWidthPx - layout.size.width,
                y = topForHour(hour) + (labelHeightPx - layout.size.height) / 2f
            )
        )
    }
}
